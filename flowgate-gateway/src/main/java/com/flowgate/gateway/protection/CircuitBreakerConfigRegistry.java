package com.flowgate.gateway.protection;

import com.flowgate.contract.*;
import com.flowgate.gateway.protection.ProtectionPolicyResolver.Plan;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.stereotype.Component;

/** 官方工厂的配置薄适配；刷新前注册，复用稳定名称，绝不自建熔断状态机。 */
@Component
public class CircuitBreakerConfigRegistry {
    private final ReactiveResilience4JCircuitBreakerFactory factory;
    private final MeterRegistry metrics;
    private final Map<String,String> changedAt=new ConcurrentHashMap<>();
    public CircuitBreakerConfigRegistry(ReactiveResilience4JCircuitBreakerFactory factory,MeterRegistry metrics) { this.factory=factory;this.metrics=metrics; }
    public void register(Plan plan,String route) {
        if(plan.breakerName().isEmpty()) return;
        changedAt.computeIfAbsent(plan.breakerName(),name->{
            // 1. 框架默认TimeLimiter被统一关闭；仅官方Netty路由超时计时，避免双计时与双降级。
            var p=SnapshotCodec.JSON.readTree(plan.parametersJson());
            var config=CircuitBreakerConfig.custom().failureRateThreshold(p.get("failureRateThreshold").intValue())
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED).slidingWindowSize(p.get("slidingWindowSize").intValue())
                    .minimumNumberOfCalls(Math.max(2,p.get("minimumNumberOfCalls").intValue())).waitDurationInOpenState(Duration.ofMillis(p.get("waitDurationMs").intValue()))
                    .permittedNumberOfCallsInHalfOpenState(2).automaticTransitionFromOpenToHalfOpenEnabled(true).build();
            factory.configure(builder->builder.circuitBreakerConfig(config).timeLimiterConfig(TimeLimiterConfig.ofDefaults()),name);
            var breaker=factory.getCircuitBreakerRegistry().circuitBreaker(name,config);
            // 2. 标签只含已发布routeId与固定事件，不以版本/用户/原始路径注册指标。
            breaker.getEventPublisher().onStateTransition(event->{changedAt.put(name,Instant.now().toString());event(route,"CIRCUIT","to_"+event.getStateTransition().getToState().name());});
            // 2.3.0把半开样本门槛与CLOSED最小样本耦合：固定至少2确保两次成功才关闭。
            // 首样本即失败且用户min=1、或半开任一失败时，用官方转换API立即OPEN；不维护自定义状态机。
            breaker.getEventPublisher().onError(error->{
                if(error.getThrowable() instanceof org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory.CircuitBreakerStatusCodeException)
                    event(route,"CIRCUIT","upstream_5xx");
                if(breaker.getState()==CircuitBreaker.State.HALF_OPEN || (breaker.getState()==CircuitBreaker.State.CLOSED
                        && p.get("minimumNumberOfCalls").intValue()==1 && breaker.getMetrics().getNumberOfBufferedCalls()==0)) breaker.transitionToOpenState();
            });
            breaker.getEventPublisher().onCallNotPermitted(event->event(route,"CIRCUIT",breaker.getState()==CircuitBreaker.State.OPEN?"open_rejected":"half_open_rejected"));
            breaker.getEventPublisher().onSuccess(event->{if(breaker.getState()==CircuitBreaker.State.HALF_OPEN) event(route,"CIRCUIT","half_open_probe");});
            return Instant.now().toString();
        });
    }
    public void event(String route,String kind,String outcome) {
        metrics.counter("flowgate.protection.events","routeId",route,"kind",kind,"outcome",outcome).increment();
    }
    /** 只报告最后确认应用版本所引用的实例；进程重启后状态可能从CLOSED重置。 */
    public List<Map<String,String>> states(Snapshot applied) {
        if(applied==null) return List.of();
        return applied.routes().stream().map(route->{
            var plan=ProtectionPolicyResolver.freeze(applied,route);
            if(plan.breakerName().isEmpty()) return null;
            return factory.getCircuitBreakerRegistry().find(plan.breakerName()).map(cb->Map.of("routeId",route.routeId(),
                    "revision",plan.revision(),"state",cb.getState().name(),"stateChangedAt",changedAt.getOrDefault(plan.breakerName(),"未知"))).orElse(null);
        }).filter(Objects::nonNull).limit(100).toList();
    }
}
