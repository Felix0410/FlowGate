package com.flowgate.demo.experiment;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 仅显式本地实验开启；固定故障枚举与2秒上限，不提供任意异常/地址/脚本输入。 */
@RestController
@ConditionalOnProperty(name="flowgate.demo.fault-enabled",havingValue="true")
public class DemoFaultController {
    private final AtomicLong reached=new AtomicLong();
    @GetMapping("/demo/fault")
    public ResponseEntity<?> fault(@RequestParam(defaultValue="ok") String mode,
            @RequestHeader(value="X-Request-Id",defaultValue="") String requestId) throws InterruptedException {
        // 1. 参数枚举先校验，不能通过请求创建任意等待时间。
        if(!java.util.Set.of("ok","500","503","slow").contains(mode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"mode只允许ok/500/503/slow");
        long count=reached.incrementAndGet();
        // 2. 仅独立MVC下游的工作线程等待，网关Netty不阻塞。
        if(mode.equals("slow")) Thread.sleep(2000);
        int status=mode.equals("500")?500:mode.equals("503")?503:200;
        // 3. 实际到达数用于证明OPEN短路不访问下游，不返回伪造的成功业务对象。
        return ResponseEntity.status(status).body(Map.of("source","DEMO","mode",mode,"reached",count,"requestId",requestId));
    }
    @GetMapping("/demo/fault-count") public Map<String,Long> count() { return Map.of("reached",reached.get()); }
}
