package com.flowgate.gateway.security;

import com.flowgate.contract.*;
import com.flowgate.gateway.routing.SnapshotRouteDefinitionLocator;
import java.util.Map;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/** 只验证当前匹配 Route 的 Caller 权限；Redis 故障 503，凭证无效 401，Scope 不足 403。 */
@Component
public class CallerAuthFilter implements GlobalFilter, Ordered {
    public static final String PRINCIPAL_ID="flowgateCallerPrincipalId";
    private final ReactiveStringRedisTemplate redis;
    public CallerAuthFilter(ReactiveStringRedisTemplate redis) { this.redis = redis; }
    @Override public int getOrder() { return -100; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 从已匹配路由恢复同版本权限，绝不按新全局快照另查一次。
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        Object metadata = route == null ? null : route.getMetadata().get(SnapshotRouteDefinitionLocator.SPEC);
        if (!(metadata instanceof RouteSpec spec)) return reject(exchange, 503, "NO_TRUSTED_ROUTE", "尚无可信路由");
        // 已解码的单级变量不能变成上游路径分隔符或目录穿越，Query 不参与目标路径拼接。
        for (String value : org.springframework.cloud.gateway.support.ServerWebExchangeUtils.getUriTemplateVariables(exchange).values()) {
            if (value.contains("/") || value.contains("\\") || value.contains("%") || value.equals(".") || value.equals("..")) {
                return reject(exchange, 400, "VALIDATION_ERROR", "路径变量不能包含编码分隔符或目录跳转");
            }
        }
        if (spec.isPublic()) return forward(exchange, chain);
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ") || !header.substring(7).matches("[A-Za-z0-9_-]{43}")) {
            return reject(exchange, 401, "INVALID_CALLER", "需要有效调用方凭证");
        }
        // 2. 非阻塞查询 Caller 专用摘要 key；管理会话不能用于业务 API。
        return redis.opsForValue().get("fg:auth:caller:" + SnapshotCodec.digest(header.substring(7)))
                .map(value -> {
                    try {
                        Session session = SnapshotCodec.JSON.readValue(value, Session.class);
                        if (!"CALLER".equals(session.principalType()) || !"CALLER".equals(session.role())) return 401;
                        if (!session.scopes().contains(spec.requiredScope())) return 403;
                        exchange.getAttributes().put(PRINCIPAL_ID,session.principalId());
                        return 200;
                    } catch (RuntimeException invalid) { return 401; }
                }).defaultIfEmpty(401).onErrorReturn(503)
                // 3. 准入结论与下游执行分开，避免把下游异常误归为 Redis 错误。
                .flatMap(status -> status == 200 ? forward(exchange, chain) : reject(exchange, status,
                        status == 503 ? "AUTH_UNAVAILABLE" : status == 403 ? "INSUFFICIENT_SCOPE" : "INVALID_CALLER",
                        status == 503 ? "会话服务暂不可用" : status == 403 ? "调用方 Scope 不足" : "调用方凭证无效或已到期"));
    }
    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 凭证仅用于入口授权，不把 opaque Token 泄露给演示下游。
        return chain.filter(exchange.mutate().request(request -> request.headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION))).build());
    }
    private Mono<Void> reject(ServerWebExchange exchange, int status, String code, String message) {
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = SnapshotCodec.JSON.writeValueAsBytes(Map.of("code", code, "message", message,
                "requestId", exchange.getRequest().getHeaders().getFirst("X-Request-Id")));
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
