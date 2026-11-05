package com.flowgate.gateway;

import com.flowgate.contract.*;
import com.flowgate.gateway.security.CallerAuthFilter;
import com.flowgate.gateway.routing.SnapshotRouteDefinitionLocator;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.data.redis.core.*;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/** 已匹配旧路由的请求不能误用新版 Scope；下游不应收到入口的 opaque 凭证。 */
class CallerRouteVersionTest {
    @Test void authenticatesMatchedRouteAndRemovesCredential() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String,String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        String token = "a".repeat(43);
        when(values.get("fg:auth:caller:" + SnapshotCodec.digest(token))).thenReturn(Mono.just(SnapshotCodec.JSON.writeValueAsString(new Session(1,"CALLER","CALLER",List.of("old:read")))));
        var spec = new RouteSpec("fast","GET","/api/fast/{id}","/demo/fast/{id}","http://127.0.0.1:8085",false,"old:read",0,1);
        var old = Route.async().id("fast").uri(spec.upstreamUri()).asyncPredicate(e -> Mono.just(true)).metadata(SnapshotRouteDefinitionLocator.SPEC,spec).build();
        var request = MockServerWebExchange.from(MockServerHttpRequest.get("/api/fast/1").header("Authorization","Bearer " + token));
        request.getAttributes().put(GATEWAY_ROUTE_ATTR,old);
        boolean[] forwarded={false};
        new CallerAuthFilter(redis).filter(request, exchange -> {
            forwarded[0]=true;
            assertNull(exchange.getRequest().getHeaders().getFirst("Authorization"));
            assertSame(old,exchange.getAttribute(GATEWAY_ROUTE_ATTR));
            return Mono.empty();
        }).block();
        assertTrue(forwarded[0]);
    }
}
