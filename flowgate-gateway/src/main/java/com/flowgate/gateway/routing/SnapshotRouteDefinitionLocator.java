package com.flowgate.gateway.routing;

import com.flowgate.gateway.config.ActiveSnapshotStore;
import java.net.URI;
import java.util.*;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** 将可信快照翻译为官方路由定义，不实现 HTTP 转发，也不提供静态鉴权旁路。 */
@Component
public class SnapshotRouteDefinitionLocator implements RouteDefinitionLocator {
    public static final String SPEC = "flowgateSpec";
    private final ActiveSnapshotStore store;
    private final com.flowgate.gateway.protection.CircuitBreakerConfigRegistry protection;
    private final int connectTimeout;
    /** 无CB场景的轻量测试构造；生产使用下方注入注册表的构造。 */
    public SnapshotRouteDefinitionLocator(ActiveSnapshotStore store) { this(store,null,1000); }
    @org.springframework.beans.factory.annotation.Autowired
    public SnapshotRouteDefinitionLocator(ActiveSnapshotStore store,com.flowgate.gateway.protection.CircuitBreakerConfigRegistry protection,
            @org.springframework.beans.factory.annotation.Value("${flowgate.connect-timeout-ms:1000}") int connectTimeout) {
        com.flowgate.contract.Rules.require(connectTimeout>=50 && connectTimeout<=10000,"连接超时必须50..10000ms");
        this.store=store;this.protection=protection;this.connectTimeout=connectTimeout;
    }
    @Override public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.defer(() -> {
            var snapshot = store.candidate();
            if (snapshot == null) return Flux.empty();
            return Flux.fromIterable(snapshot.routes()).map(spec -> {
                RouteDefinition route = new RouteDefinition();
                route.setId(spec.routeId()); route.setUri(URI.create(spec.upstreamUri())); route.setOrder(spec.priority());
                route.setPredicates(List.of(new PredicateDefinition("Path=" + spec.publicPath()), new PredicateDefinition("Method=" + spec.method())));
                route.setFilters(List.of(new FilterDefinition("SetPath=" + spec.upstreamPath())));
                route.setMetadata(Map.of(SPEC, spec, "configVersion", spec.configVersion(),
                        com.flowgate.gateway.policy.EffectivePolicyResolver.METADATA, com.flowgate.gateway.policy.EffectivePolicyResolver.freeze(snapshot,spec)));
                var plan=com.flowgate.gateway.protection.ProtectionPolicyResolver.freeze(snapshot,spec);
                var metadata=new HashMap<String,Object>(route.getMetadata());
                metadata.put(com.flowgate.gateway.protection.ProtectionPolicyResolver.METADATA,plan);
                metadata.put(com.flowgate.contract.QuotaPolicy.METADATA,com.flowgate.contract.QuotaPolicy.freeze(snapshot,spec));
                if(plan.timeoutMs()>0) {
                    metadata.put(org.springframework.cloud.gateway.support.RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR,plan.timeoutMs());
                    metadata.put(org.springframework.cloud.gateway.support.RouteMetadataUtils.CONNECT_TIMEOUT_ATTR,connectTimeout);
                }
                route.setMetadata(metadata);
                if(!plan.breakerName().isEmpty()) {
                    if(protection==null) throw new IllegalStateException("熔断配置注册表不可用");
                    protection.register(plan,spec.routeId());
                    var cb=new FilterDefinition();cb.setName("CircuitBreaker");
                    var args=new LinkedHashMap<String,String>();args.put("name",plan.breakerName());
                    for(int i=0;i<4;i++) args.put("statusCodes["+i+"]",List.of("500","502","503","504").get(i));
                    if("STATIC_JSON".equals(plan.fallbackMode())) args.put("fallbackUri","forward:/__flowgate/fallback");
                    cb.setArgs(args);
                    // 官方CB包裹SetPath及Netty转发，顺序在真实联调验证；认证/准入负序优先执行。
                    route.setFilters(List.of(cb,new FilterDefinition("SetPath="+spec.upstreamPath())));
                }
                return route;
            });
        });
    }
}
