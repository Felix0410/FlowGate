package com.flowgate.gateway.filter;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 在路由匹配前建立请求标识，让代理请求和未匹配请求都能被关联。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {
    public static final String HEADER = "X-Request-Id";

    /** 覆盖客户端标识，并在响应提交前再次保证返回同一标识；全程不阻塞。 */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 1. 每次入口生成独立标识，不信任客户端提供的一个或多个同名头。
        String requestId = UUID.randomUUID().toString();
        ServerWebExchange traced = exchange.mutate().request(request ->
                request.headers(headers -> headers.set(HEADER, requestId))).build();
        // 2. 提前设置响应头，框架错误处理重置响应时在提交前恢复，覆盖下游同名头。
        exchange.getResponse().getHeaders().set(HEADER, requestId);
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, requestId);
            return Mono.empty();
        });
        // 3. 继续框架的路由及异常链，不读取请求体，不使用线程本地上下文。
        return chain.filter(traced);
    }
}
