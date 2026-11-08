package com.flowgate.gateway.protection;

import com.flowgate.contract.SnapshotCodec;
import com.flowgate.gateway.protection.ProtectionPolicyResolver.Plan;
import java.util.*;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServiceUnavailableException;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

/** 原生Gateway异常的JSON边界和固定内部降级处理；已提交响应绝不二次写入。 */
@RestController
@Order(-2)
public class ProtectionResponses implements WebExceptionHandler {
    private final CircuitBreakerConfigRegistry registry;
    public ProtectionResponses(CircuitBreakerConfigRegistry registry) { this.registry=registry; }
    @Override public Mono<Void> handle(ServerWebExchange exchange,Throwable error) {
        if(exchange.getResponse().isCommitted()) return Mono.error(error);
        if(error instanceof com.flowgate.gateway.quota.QuotaDenied denied)return denied.write(exchange);
        if(error instanceof ResponseStatusException status && status.getStatusCode().value()==504) {
            return write(exchange,504,"UPSTREAM_TIMEOUT","上游响应超时","TIMEOUT","timeout",false);
        }
        if(error instanceof ServiceUnavailableException && plan(exchange)!=null && !plan(exchange).breakerName().isEmpty()) {
            return write(exchange,503,"CIRCUIT_OPEN","熔断器暂不允许请求","CIRCUIT",null,false);
        }
        if(error instanceof CircuitBreakerStatusCodeException status) {
            // 1. 官方statusCodes先记录失败再抛异常；保留上游HTTP状态并标明来源，不伪装为超时。
            return write(exchange,status.getStatusCode().value(),"UPSTREAM_ERROR","下游返回故障状态","CIRCUIT",null,false);
        }
        return Mono.error(error);
    }
    @RequestMapping("/__flowgate/fallback")
    public Mono<Void> fallback(ServerWebExchange exchange) {
        // 2. 仅官方CB内部转发持有异常属性；直接请求与非GET一律拒绝，不能凭Header伪造。
        Plan plan=plan(exchange);
        if(exchange.getAttribute(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR)==null || plan==null || !"STATIC_JSON".equals(plan.fallbackMode())
                || exchange.getRequest().getMethod()!=HttpMethod.GET) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND); return exchange.getResponse().setComplete();
        }
        Object failure=exchange.getAttribute(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        if(failure instanceof com.flowgate.gateway.quota.QuotaDenied denied)return denied.write(exchange);
        if(failure instanceof ResponseStatusException status && status.getStatusCode().value()==504) {
            Route route=exchange.getAttribute(GATEWAY_ROUTE_ATTR);if(route!=null) registry.event(route.getId(),"TIMEOUT","timeout");
        }
        return write(exchange,503,"DEGRADED","当前只读服务暂不可用","FALLBACK","degraded",true);
    }
    private Plan plan(ServerWebExchange exchange) {
        Route route=exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return route==null?null:(Plan)route.getMetadata().get(ProtectionPolicyResolver.METADATA);
    }
    private Mono<Void> write(ServerWebExchange exchange,int status,String code,String message,String kind,String outcome,boolean degraded) {
        // 3. 清理上游长度/编码Header，防止用上游Content-Length截断网关错误JSON。
        if(exchange.getResponse().isCommitted()) return Mono.empty();
        Route route=exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        if(outcome!=null && route!=null) registry.event(route.getId(),kind,outcome);
        var headers=exchange.getResponse().getHeaders(); headers.remove(HttpHeaders.CONTENT_LENGTH);headers.remove(HttpHeaders.CONTENT_ENCODING);headers.remove(HttpHeaders.TRANSFER_ENCODING);
        headers.setContentType(MediaType.APPLICATION_JSON);exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        var body=new LinkedHashMap<String,Object>();body.put("code",code);body.put("message",message);
        body.put("requestId",exchange.getAttributeOrDefault(com.flowgate.gateway.filter.RequestIdWebFilter.REQUEST_ID,
                Objects.toString(exchange.getResponse().getHeaders().getFirst("X-Request-Id"),"")));
        body.put("source",degraded?"GATEWAY_FALLBACK":code.equals("UPSTREAM_ERROR")?"DOWNSTREAM":"GATEWAY");
        if(degraded) body.put("degraded",true);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(SnapshotCodec.JSON.writeValueAsBytes(body))));
    }
}
