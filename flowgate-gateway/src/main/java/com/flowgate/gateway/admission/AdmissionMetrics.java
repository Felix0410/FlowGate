package com.flowgate.gateway.admission;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 只使用已发布路由和固定结果标签；不记录 Caller、Token、路径或 Request ID。 */
@Component
public class AdmissionMetrics {
    private final MeterRegistry registry;
    public AdmissionMetrics(MeterRegistry registry) { this.registry=registry; }
    public void record(String route,String outcome,String type,long started) {
        registry.counter("flowgate.admission.requests","routeId",route,"outcome",outcome,"policyType",type).increment();
        registry.timer("flowgate.admission.duration","routeId",route).record(System.nanoTime()-started,TimeUnit.NANOSECONDS);
    }
}
