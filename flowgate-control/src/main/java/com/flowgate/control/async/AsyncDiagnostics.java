package com.flowgate.control.async;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
/** 固定枚举的进程计数，不使用事件/应用身份作指标标签；重启后计数归零。 */
@Component
public class AsyncDiagnostics {
 private final Map<String,AtomicLong> counts=new LinkedHashMap<>();
 public AsyncDiagnostics(){for(String key:List.of("sent","sendFailed","staleOwner","consumed","duplicate","conflict","pausedPartition","workerUnavailable"))counts.put(key,new AtomicLong());}
 public void count(String outcome){counts.get(outcome).incrementAndGet();}
 public Map<String,Long> snapshot(){var result=new LinkedHashMap<String,Long>();counts.forEach((k,v)->result.put(k,v.get()));return result;}
}
