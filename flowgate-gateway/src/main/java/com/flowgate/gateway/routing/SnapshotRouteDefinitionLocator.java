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
    public SnapshotRouteDefinitionLocator(ActiveSnapshotStore store) { this.store = store; }
    @Override public Flux<RouteDefinition> getRouteDefinitions() {
        return Flux.defer(() -> {
            var snapshot = store.candidate();
            if (snapshot == null) return Flux.empty();
            return Flux.fromIterable(snapshot.routes()).map(spec -> {
                RouteDefinition route = new RouteDefinition();
                route.setId(spec.routeId()); route.setUri(URI.create(spec.upstreamUri())); route.setOrder(spec.priority());
                route.setPredicates(List.of(new PredicateDefinition("Path=" + spec.publicPath()), new PredicateDefinition("Method=" + spec.method())));
                route.setFilters(List.of(new FilterDefinition("SetPath=" + spec.upstreamPath())));
                route.setMetadata(Map.of(SPEC, spec, "configVersion", spec.configVersion()));
                return route;
            });
        });
    }
}
