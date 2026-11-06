package com.flowgate.gateway.admission;

import com.flowgate.gateway.policy.EffectivePolicyResolver;
import com.flowgate.gateway.policy.EffectivePolicyResolver.Rule;
import com.flowgate.gateway.security.CallerAuthFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/** 测试实际 Reactor 终止路径、多维短路和非阻塞本机许可，不以实现镜像断言代替行为。 */
class AdmissionTest {
    Rule rule(String type,String scope,String target,int max) {
        return new Rule(scope,scope,type,scope,target,"revision",2,1,max,max>0?new LocalConcurrencyLimiter.Pool(max):null);
    }
    MockServerWebExchange exchange(List<Rule> rules) {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/demo/slow").header("X-Request-Id","test"));
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR,Route.async().id("slow").uri("http://127.0.0.1:8085")
                .asyncPredicate(e -> Mono.just(true)).metadata(EffectivePolicyResolver.METADATA,rules).build());
        return exchange;
    }
    AdmissionGlobalFilter filter(ReactiveTokenBucketLimiter rate) {
        return new AdmissionGlobalFilter(rate,new AdmissionMetrics(new SimpleMeterRegistry()));
    }
    @Test void releasesOnCompleteErrorTimeoutAndCancellation() {
        Rule service=rule("CONCURRENCY","SERVICE","1",1),route=rule("CONCURRENCY","ROUTE","slow",1);
        var filter=filter(mock(ReactiveTokenBucketLimiter.class));
        var rules=List.of(service,route);
        filter.filter(exchange(rules),e -> Mono.empty()).block();
        assertEquals(0,service.pool().inFlight()); assertEquals(0,route.pool().inFlight());
        assertThrows(RuntimeException.class,() -> filter.filter(exchange(rules),e -> Mono.error(new IllegalStateException())).block());
        assertEquals(0,service.pool().inFlight());
        assertThrows(RuntimeException.class,() -> filter.filter(exchange(rules),e -> Mono.<Void>never().timeout(Duration.ofMillis(20))).block());
        assertEquals(0,route.pool().inFlight());
        var pending=filter.filter(exchange(rules),e -> Mono.never()).subscribe();
        assertEquals(1,service.pool().inFlight()); pending.dispose(); pending.dispose();
        assertEquals(0,service.pool().inFlight()); assertEquals(0,route.pool().inFlight());
    }
    @Test void partialFailureReleasesServiceAndDoesNotReachDownstream() {
        Rule service=rule("CONCURRENCY","SERVICE","1",2),route=rule("CONCURRENCY","ROUTE","slow",1);
        var existing=new LocalConcurrencyLimiter.Lease(); assertNull(existing.acquire(List.of(route)));
        var exchange=exchange(List.of(service,route)); var calls=new AtomicInteger();
        filter(mock(ReactiveTokenBucketLimiter.class)).filter(exchange,e -> { calls.incrementAndGet(); return Mono.empty(); }).block();
        assertEquals(503,exchange.getResponse().getStatusCode().value()); assertEquals(0,calls.get());
        assertEquals(0,service.pool().inFlight()); assertEquals(1,route.pool().inFlight());
        existing.close(); existing.close(); assertEquals(0,route.pool().inFlight());
    }
    @Test void layersShortCircuitAndDoNotRefundEarlierRateBudget() {
        Rule global=rule("RATE_LIMIT","GLOBAL","global",0),route=rule("RATE_LIMIT","ROUTE","slow",0),caller=rule("RATE_LIMIT","CALLER","123",0);
        var rate=mock(ReactiveTokenBucketLimiter.class);
        when(rate.check(global)).thenReturn(Mono.just(new ReactiveTokenBucketLimiter.Decision(true,false,0,1000)));
        when(rate.check(route)).thenReturn(Mono.just(new ReactiveTokenBucketLimiter.Decision(false,false,500,0)));
        var exchange=exchange(List.of(global,route,caller)); exchange.getAttributes().put(CallerAuthFilter.PRINCIPAL_ID,123L);
        filter(rate).filter(exchange,e -> { fail("拒绝不能进入下游"); return Mono.empty(); }).block();
        assertEquals(429,exchange.getResponse().getStatusCode().value()); assertEquals("1",exchange.getResponse().getHeaders().getFirst("Retry-After"));
        var order=inOrder(rate); order.verify(rate).check(global); order.verify(rate).check(route); verifyNoMoreInteractions(rate);
        assertTrue(exchange.getResponse().getBodyAsString().block().contains("RATE_LIMITED"));
    }
    @Test void callerBudgetDoesNotApplyToOtherCallerAndNoRulesNeedsNoRedis() {
        Rule caller=rule("RATE_LIMIT","CALLER","123",0); var rate=mock(ReactiveTokenBucketLimiter.class);
        var exchange=exchange(List.of(caller)); exchange.getAttributes().put(CallerAuthFilter.PRINCIPAL_ID,456L);
        var count=new AtomicInteger(); filter(rate).filter(exchange,e -> { count.incrementAndGet(); return Mono.empty(); }).block();
        filter(rate).filter(exchange(List.of()),e -> { count.incrementAndGet(); return Mono.empty(); }).block();
        assertEquals(2,count.get()); verifyNoInteractions(rate);
    }
    @Test void redisUnavailableIs503AndDownstream5xxDoesNotLeakLease() {
        Rule rule=rule("RATE_LIMIT","GLOBAL","global",0); var rate=mock(ReactiveTokenBucketLimiter.class);
        when(rate.check(rule)).thenReturn(Mono.just(new ReactiveTokenBucketLimiter.Decision(false,true,0,0)));
        var exchange=exchange(List.of(rule)); filter(rate).filter(exchange,e -> { fail(); return Mono.empty(); }).block();
        assertEquals(503,exchange.getResponse().getStatusCode().value());
        var concurrency=rule("CONCURRENCY","ROUTE","slow",1);
        filter(rate).filter(exchange(List.of(concurrency)),e -> { e.getResponse().setStatusCode(org.springframework.http.HttpStatus.BAD_GATEWAY); return e.getResponse().setComplete(); }).block();
        assertEquals(0,concurrency.pool().inFlight());
    }
    @Test void configPoolsReuseUnchangedRulesAndOldRequestsReleaseOldPool() {
        var old=LocalConcurrencyLimiter.pool("test:revision1",1);
        assertSame(old,LocalConcurrencyLimiter.pool("test:revision1",1));
        var next=LocalConcurrencyLimiter.pool("test:revision2",2);
        assertNotSame(old,next); assertEquals(0,old.inFlight()); assertEquals(0,next.inFlight());
    }
    @Test void oldInFlightLeaseReleasesOnlyOldRevision() {
        var oldRule=rule("CONCURRENCY","ROUTE","slow",1);
        var newRule=rule("CONCURRENCY","ROUTE","slow",2);
        var oldLease=new LocalConcurrencyLimiter.Lease(); var newLease=new LocalConcurrencyLimiter.Lease();
        assertNull(oldLease.acquire(List.of(oldRule))); assertNull(newLease.acquire(List.of(newRule)));
        oldLease.close(); oldLease.close();
        assertEquals(0,oldRule.pool().inFlight()); assertEquals(1,newRule.pool().inFlight());
        newLease.close(); assertEquals(0,newRule.pool().inFlight());
    }

}
