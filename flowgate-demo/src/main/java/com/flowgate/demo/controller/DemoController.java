package com.flowgate.demo.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 用最小业务响应展示路径、参数、JSON、Header 和状态码的真实代理结果。 */
@RestController
@RequestMapping("/demo")
public class DemoController {
    /** 快速查询只回显业务输入；直连时请求标识可为空。 */
    @GetMapping("/fast/{id}")
    public Map<String, Object> fast(@PathVariable String id,
            @RequestParam(defaultValue = "") String tag,
            @RequestHeader(value = "X-Request-Id", defaultValue = "") String requestId) {
        return Map.of("id", id, "tag", tag, "requestId", requestId);
    }

    /** 仅接受 JSON 对象，框架负责非法 JSON 的 400；成功返回 201 和接收到的头。 */
    @PostMapping(value = "/echo", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> echo(@RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Demo-Header", defaultValue = "") String demoHeader,
            @RequestHeader(value = "X-Request-Id", defaultValue = "") String requestId) {
        return Map.of("body", body, "demoHeader", demoHeader, "requestId", requestId);
    }

    /** 在 MVC 线程内模拟 0～2000ms 延时；越界返回 400，中断保留标记并返回 503。 */
    @GetMapping("/slow")
    public Map<String, Object> slow(@RequestParam(defaultValue = "100") long delayMs,
            @RequestHeader(value = "X-Request-Id", defaultValue = "") String requestId) {
        // 1. 先校验范围，防止调用方占用无界等待时间；非数值由 MVC 返回 400。
        if (delayMs < 0 || delayMs > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delayMs 必须在 0～2000 之间");
        }
        // 2. 仅在下游 Servlet 工作线程等待，不在网关事件循环执行阻塞操作。
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "演示等待被中断", exception);
        }
        // 3. 回显实际采用的延时参数和入口标识，供端到端核对。
        return Map.of("delayMs", delayMs, "requestId", requestId);
    }
}
