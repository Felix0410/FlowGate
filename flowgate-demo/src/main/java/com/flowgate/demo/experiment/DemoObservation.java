package com.flowgate.demo.experiment;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** 仅显式开启的本机实验观测：计数真实进入 Demo 的请求，默认不注册、不暴露。 */
@RestController
@ConditionalOnProperty(name="flowgate.demo.observation",havingValue="true")
public class DemoObservation extends OncePerRequestFilter {
    private final AtomicLong fast=new AtomicLong(),slow=new AtomicLong(),echo=new AtomicLong();
    private final AtomicInteger inFlight=new AtomicInteger(),peak=new AtomicInteger();
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String path=request.getRequestURI();
        AtomicLong counter=path.startsWith("/demo/fast/")?fast:path.equals("/demo/slow")?slow:path.equals("/demo/echo")?echo:null;
        if(counter==null) { chain.doFilter(request,response); return; }
        // 1. 只记录实际到达下游的业务调用，不以网关接受数替代。
        counter.incrementAndGet(); int active=inFlight.incrementAndGet(); peak.accumulateAndGet(active,Math::max);
        // 2. MVC 正常、异常都归还在途计数，观测本身不改变业务响应。
        try { chain.doFilter(request,response); } finally { inFlight.decrementAndGet(); }
    }
    @GetMapping("/demo/observation") public Map<String,Object> observation() {
        return Map.of("fast",fast.get(),"slow",slow.get(),"echo",echo.get(),"inFlight",inFlight.get(),"peak",peak.get());
    }
}
