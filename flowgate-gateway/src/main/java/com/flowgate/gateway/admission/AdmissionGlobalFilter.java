package com.flowgate.gateway.admission;

import com.flowgate.contract.*;
import com.flowgate.gateway.policy.EffectivePolicyResolver;
import com.flowgate.gateway.policy.EffectivePolicyResolver.Rule;
import com.flowgate.gateway.security.CallerAuthFilter;
import java.util.*;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/** 先身份授权，再逐层扣速率预算，最后获取本机并发；拒绝绝不进入下游。 */
@Component
public class AdmissionGlobalFilter implements GlobalFilter,Ordered {
    private final ReactiveTokenBucketLimiter rate;
    private final AdmissionMetrics metrics;
    public AdmissionGlobalFilter(ReactiveTokenBucketLimiter rate,AdmissionMetrics metrics) { this.rate=rate; this.metrics=metrics; }
    @Override public int getOrder() { return -90; }
    @Override public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain) {
        return Mono.defer(() -> {
            Route route=exchange.getAttribute(GATEWAY_ROUTE_ATTR);
            @SuppressWarnings("unchecked") List<Rule> frozen=route==null ? List.of() : (List<Rule>)route.getMetadata().getOrDefault(EffectivePolicyResolver.METADATA,List.of());
            Long caller=exchange.getAttribute(CallerAuthFilter.PRINCIPAL_ID);
            List<Rule> rules=frozen.stream().filter(r -> !r.scope().equals("CALLER") || (caller!=null && r.target().equals(caller.toString()))).toList();
            long started=System.nanoTime(); String routeId=route==null ? "unknown" : route.getId();
            return checkRate(exchange,rules,0,routeId,started).flatMap(allowed -> {
                if(!allowed) return Mono.empty();
                // 1. 非阻塞获取服务→路由；部分失败立刻归还服务许可。
                var lease=new LocalConcurrencyLimiter.Lease();
                Rule rejected=lease.acquire(rules);
                if(rejected!=null) return reject(exchange,routeId,rejected,"CONCURRENCY_LIMITED",503,0,started);
                metrics.record(routeId,"ALLOWED",rules.isEmpty()?"NONE":"ADMISSION",started);
                // 2. defer 捕获同步异常，complete/error/cancel 都归还原对象且最多一次。
                return Mono.defer(() -> chain.filter(exchange)).doFinally(signal -> lease.close());
            });
        });
    }
    private Mono<Boolean> checkRate(ServerWebExchange exchange,List<Rule> rules,int index,String route,long started) {
        if(index==rules.size()) return Mono.just(true);
        Rule rule=rules.get(index);
        if(!rule.type().equals("RATE_LIMIT")) return checkRate(exchange,rules,index+1,route,started);
        // 3. 前层已扣预算表示尝试量，后层拒绝不退还；每 Key 原子而非跨维度事务。
        return rate.check(rule).flatMap(decision -> decision.allowed()
                ? checkRate(exchange,rules,index+1,route,started)
                : reject(exchange,route,rule,decision.unavailable()?"ADMISSION_UNAVAILABLE":"RATE_LIMITED",
                        decision.unavailable()?503:429,decision.retryAfterMs(),started).thenReturn(false));
    }
    private Mono<Void> reject(ServerWebExchange exchange,String route,Rule rule,String code,int status,long retry,long started) {
        metrics.record(route,code,rule.type(),started);
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if(retry>0) exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER,Long.toString((retry+999)/1000));
        var body=new LinkedHashMap<String,Object>();
        body.put("code",code); body.put("message",switch(code) {
            case "RATE_LIMITED" -> "速率预算已耗尽";
            case "CONCURRENCY_LIMITED" -> "本机在途并发已满";
            default -> "限速依赖暂不可用";
        });
        body.put("routeId",route); body.put("policyCode",rule.code()); body.put("retryAfterMs",retry);
        body.put("requestId",exchange.getRequest().getHeaders().getFirst("X-Request-Id"));
        byte[] bytes=SnapshotCodec.JSON.writeValueAsBytes(body);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
