package com.flowgate.gateway.filter;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** 核对入口标识覆盖、下游响应冲突及每次请求的独立性。 */
class RequestIdWebFilterTest {
    @Test
    void replacesUntrustedHeadersAndPreservesIdAtCommit() {
        RequestIdWebFilter filter = new RequestIdWebFilter();
        var first = MockServerWebExchange.from(MockServerHttpRequest.get("/unknown")
                .header(RequestIdWebFilter.HEADER, "forged", "duplicate"));
        String[] forwarded = new String[1];
        filter.filter(first, exchange -> {
            var ids = exchange.getRequest().getHeaders().get(RequestIdWebFilter.HEADER);
            assertEquals(1, ids.size());
            forwarded[0] = ids.get(0);
            assertDoesNotThrow(() -> UUID.fromString(forwarded[0]));
            exchange.getResponse().getHeaders().set(RequestIdWebFilter.HEADER, "downstream");
            return exchange.getResponse().setComplete();
        }).block();
        assertEquals(forwarded[0], first.getResponse().getHeaders().getFirst(RequestIdWebFilter.HEADER));
        var second = MockServerWebExchange.from(MockServerHttpRequest.get("/api/demo/fast/123"));
        filter.filter(second, exchange -> Mono.empty()).block();
        assertNotEquals(forwarded[0], second.getResponse().getHeaders().getFirst(RequestIdWebFilter.HEADER));
    }
}
