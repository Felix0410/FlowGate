package com.flowgate.control.controller;

import com.flowgate.control.mapper.ConfigStateMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

/** 本地测试台的只读依赖探测；固定 Demo 地址，不接收任意 URL 或暴露配置秘密。 */
@RestController
public class StatusController {
    private final ConfigStateMapper state;
    private final StringRedisTemplate redis;
    private final String demo;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    public StatusController(ConfigStateMapper state, StringRedisTemplate redis,
            @Value("${FLOWGATE_DEMO_STATUS_URI:http://127.0.0.1:8085}") String demo,
            @Value("${flowgate.allowed-upstreams}") String allowed) {
        this.state = state; this.redis = redis; this.demo = demo;
        com.flowgate.contract.Rules.upstream(demo, Set.copyOf(Arrays.asList(allowed.split(","))));
    }
    @GetMapping("/status") public Object status() {
        // 1. 控制面存活不等于依赖就绪，分别实际读取数据库与 Redis。
        var status = new LinkedHashMap<String,Object>(); status.put("control", "UP");
        try { state.selectById(1); status.put("mysql", "UP"); } catch (Exception unavailable) { status.put("mysql", "依赖未就绪"); }
        try (var connection = redis.getConnectionFactory().getConnection()) {
            status.put("redis", "PONG".equals(connection.ping()) ? "UP" : "依赖未就绪");
        }
        catch (Exception unavailable) { status.put("redis", "依赖未就绪"); }
        // 2. 通过固定的可信本地 Demo 地址探测 HTTP，不从客户端获取探测目标。
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(demo + "/demo/fast/health")).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding());
            status.put("demo", response.statusCode() == 200 ? "UP" : "HTTP " + response.statusCode());
        } catch (Exception unavailable) { status.put("demo", "无法访问"); }
        // 3. 原样呈现各依赖结论，不把断联或非 200 误标为在线。
        return status;
    }
}
