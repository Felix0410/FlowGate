package com.flowgate.gateway.protection;

import com.flowgate.contract.*;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实Resilience4j注册表和状态转换，验证精确版本半开门槛与错误边界。 */
class ProtectionTest {
    CircuitBreakerRegistry breakers=CircuitBreakerRegistry.ofDefaults();
    CircuitBreakerConfigRegistry registry() {
        var props=new Resilience4JConfigurationProperties();props.setDisableTimeLimiter(true);
        return new CircuitBreakerConfigRegistry(new ReactiveResilience4JCircuitBreakerFactory(breakers,TimeLimiterRegistry.ofDefaults(),null,props),new SimpleMeterRegistry());
    }
    @Test void minOneFailureOpensAndHalfOpenStillRequiresTwoSuccesses() {
        var registry=registry();
        var plan=new ProtectionPolicyResolver.Plan(0,"fg-cb-test","rev","NONE","{\"failureRateThreshold\":50,\"slidingWindowSize\":4,\"minimumNumberOfCalls\":1,\"waitDurationMs\":3000}");
        registry.register(plan,"fault");var cb=breakers.find(plan.breakerName()).orElseThrow();
        assertThrows(RuntimeException.class,()->cb.executeSupplier(()->{throw new IllegalStateException();}));
        assertEquals(CircuitBreaker.State.OPEN,cb.getState());
        cb.transitionToHalfOpenState();cb.executeSupplier(()->"ok");assertEquals(CircuitBreaker.State.HALF_OPEN,cb.getState());
        cb.executeSupplier(()->"ok");assertEquals(CircuitBreaker.State.CLOSED,cb.getState());
        cb.transitionToOpenState();cb.transitionToHalfOpenState();
        assertThrows(RuntimeException.class,()->cb.executeSupplier(()->{throw new IllegalStateException();}));
        assertEquals(CircuitBreaker.State.OPEN,cb.getState());
        registry.register(plan,"fault");assertSame(cb,breakers.find(plan.breakerName()).orElseThrow());
        var next=new ProtectionPolicyResolver.Plan(0,"fg-cb-next","next","NONE",plan.parametersJson().replace("50","75"));
        registry.register(next,"fault");
        assertEquals(CircuitBreaker.State.CLOSED,breakers.find(next.breakerName()).orElseThrow().getState());
        assertThrows(CallNotPermittedException.class,()->cb.executeSupplier(()->"old request"));
        assertEquals(CircuitBreaker.State.CLOSED,breakers.find(next.breakerName()).orElseThrow().getState());
    }
    @Test void timeoutJsonAndDirectFallbackAreSafeAndCommittedResponsesAreUntouched() {
        var responses=new ProtectionResponses(registry());
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/slow").header("X-Request-Id","test"));
        responses.handle(exchange,new ResponseStatusException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT)).block();
        assertEquals(504,exchange.getResponse().getStatusCode().value());
        assertTrue(exchange.getResponse().getBodyAsString().block().contains("UPSTREAM_TIMEOUT"));
        var direct=MockServerWebExchange.from(MockServerHttpRequest.get("/__flowgate/fallback"));responses.fallback(direct).block();
        assertEquals(404,direct.getResponse().getStatusCode().value());
        var committed=MockServerWebExchange.from(MockServerHttpRequest.get("/api/slow"));committed.getResponse().setComplete().block();
        assertThrows(ResponseStatusException.class,()->responses.handle(committed,new ResponseStatusException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT)).block());
    }
    @Test void timeoutTakesMinimumAndPendingDoesNotActivate() {
        var route=new RouteSpec("slow","GET","/api/slow","/demo/slow","http://127.0.0.1:8085",true,"",0,1);
        var policies=List.of(Map.<String,Object>of("id",1,"code","service-timeout","type","TIMEOUT","scope","SERVICE","enabled",true,"executionStatus",PolicyRules.PROTECTED,"parametersJson","{\"timeoutMs\":500}"),
                Map.<String,Object>of("id",2,"code","route-timeout","type","TIMEOUT","scope","ROUTE","enabled",true,"executionStatus",PolicyRules.PROTECTED,"parametersJson","{\"timeoutMs\":200}"));
        var bindings=List.of(Map.<String,Object>of("policyId",1,"targetType","SERVICE","targetId",10),Map.<String,Object>of("policyId",2,"targetType","ROUTE","targetId",20,"targetRouteId","slow"));
        var snapshot=SnapshotCodec.seal(new Snapshot(1,1,"",List.of(Map.of("id",10,"enabled",true)),List.of(Map.of("id",30,"serviceId",10,"method","GET","publicPath","/api/slow","enabled",true)),List.of(route),policies,bindings));
        PolicyRules.validate(snapshot);assertEquals(200,ProtectionPolicyResolver.freeze(snapshot,route).timeoutMs());
        var pending=policies.stream().map(p->{var copy=new HashMap<>(p);copy.put("executionStatus","PENDING_BLOCK3");return (Map<String,Object>)copy;}).toList();
        var pendingAll=new ArrayList<Map<String,Object>>(pending);
        pendingAll.add(Map.of("id",3,"type","CIRCUIT_BREAKER","scope","ROUTE","enabled",true,"executionStatus","PENDING_BLOCK3",
                "parametersJson","{\"failureRateThreshold\":50,\"slidingWindowSize\":4,\"minimumNumberOfCalls\":2,\"waitDurationMs\":1000}"));
        var oldBindings=new ArrayList<Map<String,Object>>(bindings);oldBindings.add(Map.of("policyId",3,"targetType","ROUTE","targetId",20,"targetRouteId","slow"));
        var old=SnapshotCodec.seal(new Snapshot(1,1,"",snapshot.services(),snapshot.apis(),snapshot.routes(),pendingAll,oldBindings));
        SnapshotCodec.verify(old);assertEquals(0,ProtectionPolicyResolver.freeze(old,route).timeoutMs());assertEquals("",ProtectionPolicyResolver.freeze(old,route).breakerName());
    }
    @Test void errorBoundaryUsesTrustedIdEvenOnOriginalExchange() {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/slow").header("X-Request-Id","forged"));
        var responses=new ProtectionResponses(registry());
        new com.flowgate.gateway.filter.RequestIdWebFilter().filter(exchange,traced->reactor.core.publisher.Mono.error(
                new ResponseStatusException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT)))
                .onErrorResume(error->responses.handle(exchange,error)).block();
        var body=SnapshotCodec.JSON.readTree(exchange.getResponse().getBodyAsString().block());
        assertEquals(exchange.getResponse().getHeaders().getFirst("X-Request-Id"),body.get("requestId").stringValue());
        assertNotEquals("forged",body.get("requestId").stringValue());
    }

}
