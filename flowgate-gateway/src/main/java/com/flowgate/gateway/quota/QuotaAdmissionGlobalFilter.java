package com.flowgate.gateway.quota;
import com.flowgate.contract.*;
import com.flowgate.gateway.security.CallerAuthFilter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/** 只对同版计量Route进行有界非阻塞预准入；旧Route不请求Control。真实CB顺序另有实测。 */
@Component
public class QuotaAdmissionGlobalFilter implements GlobalFilter,Ordered,QuotaSupport {
 private final WebClient client;private final MeterRegistry metrics;private final AtomicInteger inflight=new AtomicInteger();
 @org.springframework.beans.factory.annotation.Autowired
 public QuotaAdmissionGlobalFilter(@Value("${flowgate.control-uri}")String uri,@Value("${flowgate.internal-key}")String key,MeterRegistry metrics){this(WebClient.builder().baseUrl(uri).defaultHeader("X-FlowGate-Internal-Key",key).codecs(c->c.defaultCodecs().maxInMemorySize(16384)).build(),metrics);Rules.require(key.length()>=32,"内部密钥至少32字符");}
 public QuotaAdmissionGlobalFilter(WebClient client,MeterRegistry metrics){this.client=client;this.metrics=metrics;}
 @Override public int getOrder(){return 10;}
 @Override public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain){
  return Mono.defer(()->{
   Route route=exchange.getAttribute(GATEWAY_ROUTE_ATTR);Object metadata=route==null?null:route.getMetadata().get(QuotaPolicy.METADATA);
   if(!(metadata instanceof QuotaPolicy.Plan plan)||!plan.metered())return chain.filter(exchange);
   if(inflight.incrementAndGet()>64){inflight.decrementAndGet();return Mono.error(new QuotaDenied(503,"QUOTA_UNAVAILABLE","配额服务本机在途已满"));}
   long began=System.nanoTime();
   // 1. UUID只生成一次，内部通信重试复用；身份/代次/策略只取服务器属性与Route计划。
   Long principal=exchange.getAttribute(CallerAuthFilter.PRINCIPAL_ID),generation=exchange.getAttribute(CallerAuthFilter.GENERATION);
   if(principal==null||generation==null){inflight.decrementAndGet();return Mono.error(new QuotaDenied(401,"INVALID_APP_CREDENTIAL","缺少可信应用身份"));}
   var charge=new QuotaCharge(UUID.randomUUID().toString(),principal,generation,plan.apiId(),plan.routeId(),plan.appliedVersion(),plan.snapshotChecksum(),plan.units());
   // 2. 只重试内部网络通信一次，不重试已转发的业务POST；总时间与在途数量均有上限。
   return Mono.defer(()->client.post().uri(plan.mode()==QuotaPolicy.Mode.V12_APP_API?"/internal/quota/charge-v12":"/internal/quota/charge").bodyValue(charge).exchangeToMono(response->response.bodyToMono(tools.jackson.databind.JsonNode.class).switchIfEmpty(Mono.error(new QuotaDenied(503,"QUOTA_UNAVAILABLE","配额响应为空"))).flatMap(body->{
    int status=response.statusCode().value();
    if(status==200){
     if(!body.has("chargeId")||!charge.chargeId().equals(body.get("chargeId").stringValue())||!body.has("chargedUnits")||!body.get("chargedUnits").isIntegralNumber()||body.get("chargedUnits").longValue()!=plan.units()||!body.has("remainingUnits")||!body.get("remainingUnits").isIntegralNumber()||body.get("remainingUnits").longValue()<0||!body.has("duplicate")||!body.get("duplicate").isBoolean()||!body.has("periodUtc")||!body.get("periodUtc").stringValue().matches("[0-9]{6}"))return Mono.error(new QuotaDenied(503,"QUOTA_UNAVAILABLE","配额响应未通过验证"));
     return Mono.just(body.has("duplicate")&&body.get("duplicate").booleanValue()?"DUPLICATE":"GRANTED");
    }
    String code=body.has("code")?body.get("code").stringValue():"QUOTA_UNAVAILABLE";
    if(status==429&&"QUOTA_EXHAUSTED".equals(code))return Mono.error(new QuotaDenied(429,code,"本月准入单位已耗尽"));
    if(status==403&&Set.of("APP_DISABLED","DEVELOPER_DISABLED","NO_ACTIVE_SUBSCRIPTION","NO_ACTIVE_API_ENTITLEMENT","INSUFFICIENT_SCOPE","APP_TOKEN_REVOKED").contains(code))return Mono.error(new QuotaDenied(403,code,"应用、凭证、订阅或授权范围不可用"));
    return Mono.error(new QuotaDenied(503,"QUOTA_UNAVAILABLE","配额服务未授权，依赖或配置不可用"));
   })).timeout(Duration.ofSeconds(2)))
    .retryWhen(Retry.max(1).filter(error->error instanceof WebClientRequestException||error instanceof java.util.concurrent.TimeoutException))
    .timeout(Duration.ofSeconds(5)).onErrorMap(error->error instanceof QuotaDenied?error:new QuotaDenied(503,"QUOTA_UNAVAILABLE","配额服务连接失败或授权结果不确定"))
    .doOnNext(outcome->metrics.counter("flowgate.quota.admission","routeId",plan.routeId(),"outcome",outcome).increment())
    .doOnError(error->metrics.counter("flowgate.quota.admission","routeId",plan.routeId(),"outcome",error instanceof QuotaDenied denied?denied.code:"QUOTA_UNAVAILABLE").increment())
    // 3. Grant以后才代理；无论下游结果/取消都不退款，但原并发许可和本机hop槽必释放。
    .doFinally(signal->{inflight.decrementAndGet();metrics.timer("flowgate.quota.hop","routeId",plan.routeId()).record(System.nanoTime()-began,TimeUnit.NANOSECONDS);})
    .flatMap(ignored->chain.filter(exchange));
  });
 }
}
