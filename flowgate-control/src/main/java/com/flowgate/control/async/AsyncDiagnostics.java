package com.flowgate.control.async;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
/** 固定枚举的进程计数，不使用事件/应用身份作指标标签；重启后计数归零。 */
@Component
public class AsyncDiagnostics {
 private volatile long publisherCheck; private volatile boolean publisherHealthy;
 @org.springframework.beans.factory.annotation.Value("${flowgate.async.poll-ms:1000}") private long pollMs=1000;
 public void publisherTick(boolean healthy){publisherHealthy=healthy;publisherCheck=System.nanoTime();}
 /** 调度完成时间仅说明当前实例实际轮询，不代表SENT已消费或日报已追平。 */
 public Map<String,Object> publisherStatus(){long at=publisherCheck;boolean fresh=at!=0&&System.nanoTime()-at>=0&&java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at)<=Math.max(5000,pollMs*3);return Map.of("state",!fresh?"UNKNOWN":publisherHealthy?"READY":"BLOCKED","reason",!fresh?"尚无新鲜Worker完成记录":publisherHealthy?"当前实例Worker最近已完成轮询":"Worker最近轮询失败，请检查数据库");}
 private final Map<String,AtomicLong> counts=new LinkedHashMap<>();
 public AsyncDiagnostics(){for(String key:List.of("sent","sendFailed","staleOwner","consumed","duplicate","conflict","pausedPartition","workerUnavailable","transientRetry","poisonPause"))counts.put(key,new AtomicLong());}
 public void count(String outcome){counts.get(outcome).incrementAndGet();}
 public Map<String,Long> snapshot(){var result=new LinkedHashMap<String,Long>();counts.forEach((k,v)->result.put(k,v.get()));return result;}
}
