package com.flowgate.gateway.quota;

import com.flowgate.contract.*;
import com.flowgate.gateway.protection.*;
import com.flowgate.gateway.security.CallerAuthFilter;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.*;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.filter.factory.*;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

/** 使用官方Gateway CB工厂组合真实过滤器，验证短路、错误采样和非计量隔离。 */
class V11QuotaFilterTest {
    final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    final AtomicInteger hops=new AtomicInteger(),downstream=new AtomicInteger();
    final CircuitBreakerRegistry breakers=CircuitBreakerRegistry.ofDefaults();
    final Resilience4JConfigurationProperties properties=new Resilience4JConfigurationProperties();
    final ProtectionPolicyResolver.Plan protection=new ProtectionPolicyResolver.Plan(0,"quota-test","r","STATIC_JSON",
        "{\"failureRateThreshold\":50,\"slidingWindowSize\":4,\"minimumNumberOfCalls\":1,\"waitDurationMs\":30000}");
    MockServerWebExchange exchange(boolean metered) {
        var e=MockServerWebExchange.from(MockServerHttpRequest.get("/private"));
        var route=Route.async().id("private").uri("http://127.0.0.1:1").asyncPredicate(x->Mono.just(true))
            .metadata(QuotaPolicy.METADATA,metered?new QuotaPolicy.Plan(true,"private",1,1,1,2,"a".repeat(64)):QuotaPolicy.NONE)
            .metadata(ProtectionPolicyResolver.METADATA,protection).build();
        e.getAttributes().put(GATEWAY_ROUTE_ATTR,route);e.getAttributes().put(CallerAuthFilter.PRINCIPAL_ID,1L);e.getAttributes().put(CallerAuthFilter.GENERATION,1L);
        return e;
    }
    QuotaAdmissionGlobalFilter quota() {
        return new QuotaAdmissionGlobalFilter(WebClient.builder().exchangeFunction(request->{hops.incrementAndGet();return Mono.just(
            ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).header("Content-Type","application/json").body("{\"code\":\"QUOTA_EXHAUSTED\"}").build());}).build(),metrics);
    }
    @Test void unmeteredRouteNeverCallsControlAndDeniedQuotaNeverForwards() {
        var quota=quota();quota.filter(exchange(false),e->{downstream.incrementAndGet();return Mono.empty();}).block();
        assertEquals(0,hops.get());assertEquals(1,downstream.get());
        var error=assertThrows(QuotaDenied.class,()->quota.filter(exchange(true),e->{downstream.incrementAndGet();return Mono.empty();}).block());
        assertEquals(429,error.status);assertEquals(1,hops.get());assertEquals(1,downstream.get());
    }
    @Test void officialCircuitWrapsQuotaOpenSkipsHopAndQuotaDenialDoesNotCountOrDegrade() {
        // 1. 与RouteDefinitionRouteLocator一致：Route CB order=1，Quota全局order=10。
        properties.setDisableTimeLimiter(true);
        var factory=new ReactiveResilience4JCircuitBreakerFactory(breakers,TimeLimiterRegistry.ofDefaults(),null,properties);
        var configRegistry=new CircuitBreakerConfigRegistry(factory,metrics);configRegistry.register(protection,"private");
        var responses=new ProtectionResponses(configRegistry);
        var beans=new StaticListableBeanFactory();
        beans.addBean("dispatcher",new DispatcherHandler(){@Override public Mono<Void> handle(org.springframework.web.server.ServerWebExchange e){return responses.fallback(e);}});
        var official=new SpringCloudCircuitBreakerResilience4JFilterFactory(factory,beans.getBeanProvider(DispatcherHandler.class));
        var cfg=new SpringCloudCircuitBreakerFilterFactory.Config().setName(protection.breakerName()).setFallbackUri("forward:/__flowgate/fallback");
        var quota=quota();
        var filters=new ArrayList<GatewayFilter>();
        filters.add(new OrderedGatewayFilter(official.apply(cfg),1));filters.add(new OrderedGatewayFilter(quota::filter,quota.getOrder()));
        org.springframework.core.annotation.AnnotationAwareOrderComparator.sort(filters);
        assertEquals(1,((org.springframework.core.Ordered)filters.get(0)).getOrder());
        // 2. 官方CB真实运行：Quota429透出，且不变为DEGRADED，不进入失败样本。
        var e=exchange(true);run(filters,0,e).block();assertEquals(429,e.getResponse().getStatusCode().value());
        assertTrue(e.getResponse().getBodyAsString().block().contains("QUOTA_EXHAUSTED"));assertEquals(0,downstream.get());
        var breaker=breakers.find(protection.breakerName()).orElseThrow();
        assertEquals(0,breaker.getMetrics().getNumberOfFailedCalls());assertEquals(0,breaker.getMetrics().getNumberOfBufferedCalls());
        // 3. 强制真实官方OPEN，验证内层Quota和下游均不执行。
        breaker.transitionToOpenState();var open=exchange(true);run(filters,0,open).block();
        assertEquals(503,open.getResponse().getStatusCode().value());assertEquals(1,hops.get());assertEquals(0,downstream.get());
    }
    @Test void cancelledHopsReleaseAllLocalSlotsAndOverflowNeverForwards() {
        var quota=new QuotaAdmissionGlobalFilter(WebClient.builder().exchangeFunction(request->Mono.never()).build(),metrics);
        var pending=new ArrayList<reactor.core.Disposable>();
        try {
            for(int i=0;i<64;i++)pending.add(quota.filter(exchange(true),e->{downstream.incrementAndGet();return Mono.empty();}).subscribe());
            var error=assertThrows(QuotaDenied.class,()->quota.filter(exchange(true),e->Mono.empty()).block());assertEquals(503,error.status);
        } finally {pending.forEach(reactor.core.Disposable::dispose);}
        var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var next=quota.filter(exchange(true),e->{downstream.incrementAndGet();return Mono.empty();}).subscribe(null,error::set);
        next.dispose();assertNull(error.get());assertEquals(0,downstream.get());
    }
    @Test void applicationAndLegacyCallerNamespacesCannotCrossRoutes() {
        var redis=org.mockito.Mockito.mock(org.springframework.data.redis.core.ReactiveStringRedisTemplate.class);
        org.springframework.data.redis.core.ReactiveValueOperations<String,String> values=org.mockito.Mockito.mock(org.springframework.data.redis.core.ReactiveValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(values);
        String token="fgapp_"+"a".repeat(43);
        org.mockito.Mockito.when(values.get("fg:auth:app:"+SnapshotCodec.digest(token))).thenReturn(Mono.just(SnapshotCodec.JSON.writeValueAsString(new AppCredentialSession(1,"APP_CALLER",List.of("demo:read"),2))));
        var auth=new CallerAuthFilter(redis);auth.setQuotaSupport(quota());
        for(boolean metered:List.of(true,false)) {
            var e=MockServerWebExchange.from(MockServerHttpRequest.get("/private").header("Authorization","Bearer "+token).header("X-Request-Id","test"));
            var route=Route.async().id("private").uri("http://127.0.0.1:1").asyncPredicate(x->Mono.just(true))
                .metadata(com.flowgate.gateway.routing.SnapshotRouteDefinitionLocator.SPEC,new RouteSpec("private","GET","/api/private","/demo/fast","http://127.0.0.1:1",false,"demo:read",0,2))
                .metadata(QuotaPolicy.METADATA,metered?new QuotaPolicy.Plan(true,"private",1,1,1,2,"a".repeat(64)):QuotaPolicy.NONE).build();
            e.getAttributes().put(GATEWAY_ROUTE_ATTR,route);
            auth.filter(e,forwarded->{assertNull(forwarded.getRequest().getHeaders().getFirst("Authorization"));assertEquals(2L,(Long)forwarded.getAttribute(CallerAuthFilter.GENERATION));downstream.incrementAndGet();return Mono.empty();}).block();
            if(!metered)assertEquals(401,e.getResponse().getStatusCode().value());
        }
        assertEquals(1,downstream.get());org.mockito.Mockito.verify(values,org.mockito.Mockito.times(1)).get(org.mockito.Mockito.anyString());
    }
    Mono<Void> run(List<GatewayFilter> filters,int i,org.springframework.web.server.ServerWebExchange e){return Mono.defer(()->i==filters.size()?Mono.fromRunnable(downstream::incrementAndGet):filters.get(i).filter(e,next->run(filters,i+1,next)));}
}
