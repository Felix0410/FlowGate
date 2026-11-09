package com.flowgate.control.service;
import com.flowgate.control.async.*;
import com.flowgate.control.entity.OutboxEventEntry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** 提交后有界至少一次投递；Broker确认前不写SENT，崩溃后保留原eventId重发。 */
@Component
@ConditionalOnProperty(prefix="flowgate.async",name="enabled",havingValue="true")
public class OutboxPublisher {
 private final OutboxClaimService claims;private final KafkaTemplate<String,String> kafka;private final String topic;private final AsyncDiagnostics diagnostics;
 private final AtomicInteger inflight=new AtomicInteger();private final AtomicBoolean busy=new AtomicBoolean();
 public OutboxPublisher(OutboxClaimService claims,KafkaTemplate<String,String> kafka,@Value("${flowgate.async.topic}")String topic,AsyncDiagnostics diagnostics){this.claims=claims;this.kafka=kafka;this.topic=topic;this.diagnostics=diagnostics;}
 @Scheduled(fixedDelayString="${flowgate.async.poll-ms:1000}") public void publish(){
  if(!busy.compareAndSet(false,true))return;
  try{
   // 1. 每轮最多20，整个实例最多100在途，领取事务返回后才调用传输。
   for(var row:claims.claim(Math.min(20,100-inflight.get()))){
    UsageGrantedEvent event;
    try{event=UsageGrantedEvent.parse(row.payloadJson);if(!row.eventId.equals(event.eventId()))throw new IllegalArgumentException();}
    catch(RuntimeException invalid){claims.failed(row,"INVALID_EVENT");diagnostics.count("sendFailed");continue;}
    inflight.incrementAndGet();
    try{
     // 2. 10秒通信上限；不重新生成事件ID，不能把send排队当作Broker ACK。
     kafka.send(topic,Long.toString(event.applicationId()),event.canonical()).orTimeout(10,TimeUnit.SECONDS).whenComplete((ack,error)->{
      try{if(error==null){int changed=claims.sent(row);diagnostics.count(changed==1?"sent":"staleOwner");}else fail(row);}
      catch(RuntimeException unavailable){diagnostics.count("workerUnavailable");}
      finally{inflight.decrementAndGet();}
     });
    }catch(RuntimeException unavailable){try{fail(row);}finally{inflight.decrementAndGet();}}
   }
   diagnostics.publisherTick(true);
  }catch(RuntimeException unavailable){diagnostics.publisherTick(false);diagnostics.count("workerUnavailable");}
  // 3. DB回写失败不伪装成功；租约到期后由任意Worker重领，消费幂等负责重复。
  finally{busy.set(false);}
 }
 private void fail(OutboxEventEntry row){claims.failed(row,"BROKER_UNAVAILABLE");diagnostics.count("sendFailed");}
 public int inflight(){return inflight.get();}
}
