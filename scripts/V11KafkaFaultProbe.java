import com.flowgate.control.FlowGateControlApplication;
import com.flowgate.control.service.*;
import com.flowgate.control.async.*;
import com.flowgate.control.entity.OutboxEventEntry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
/** 仅验收子进程：在真实Broker ACK/DB COMMIT后halt，生产代码不引入故障开关。 */
public class V11KafkaFaultProbe {
 public static void main(String[]args)throws Exception{
  String mode=args[0],topic="flowgate.usage.v1",group="flowgate-usage-aggregate-v1",bootstrap=System.getenv("FLOWGATE_KAFKA_BOOTSTRAP_SERVERS");
  if(mode.equals("topic-size")){
   try(var admin=Admin.create(Map.of("bootstrap.servers",bootstrap))){var partitions=admin.describeTopics(List.of(topic)).allTopicNames().get(10,TimeUnit.SECONDS).get(topic).partitions();var request=new HashMap<TopicPartition,org.apache.kafka.clients.admin.OffsetSpec>();for(var p:partitions)request.put(new TopicPartition(topic,p.partition()),org.apache.kafka.clients.admin.OffsetSpec.latest());long count=0;for(var info:admin.listOffsets(request).all().get(10,TimeUnit.SECONDS).values())count+=info.offset();System.out.println("{\"records\":"+count+"}");}return;
  }
  if(mode.equals("offset")){
   try(var admin=Admin.create(Map.of("bootstrap.servers",bootstrap))){var offsets=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS);var pos=offsets.get(new TopicPartition(topic,2));System.out.println("{\"offset\":"+(pos==null?-1:pos.offset())+"}");}return;
  }
  var boot=new SpringApplication(FlowGateControlApplication.class);boot.setWebApplicationType(WebApplicationType.NONE);
  try(var ctx=boot.run("--flowgate.async.enabled=false")){
   var claims=ctx.getBean(OutboxClaimService.class);var jdbc=ctx.getBean(JdbcTemplate.class);KafkaTemplate<String,String> kafka=(KafkaTemplate)ctx.getBean(KafkaTemplate.class);
   switch(mode){
    case "send-and-halt", "send-and-mark" -> {
     var row=claims.claim(1).get(0);var event=UsageGrantedEvent.parse(row.payloadJson);
     kafka.send(topic,Long.toString(event.applicationId()),event.canonical()).get(15,TimeUnit.SECONDS);
     if(mode.equals("send-and-halt"))Runtime.getRuntime().halt(23); // Broker已确认，但尚未调用claims.sent。
     if(claims.sent(row)!=1)throw new AssertionError("未标记SENT");System.out.println("{\"sent\":1}");
    }
    case "consume-and-halt" -> {
     var props=new HashMap<String,Object>();props.put("bootstrap.servers",bootstrap);props.put("group.id",group);props.put("enable.auto.commit",false);props.put("auto.offset.reset","earliest");props.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");props.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");props.put("session.timeout.ms",6000);props.put("heartbeat.interval.ms",2000);
     try(var consumer=new KafkaConsumer<String,String>(props)){
      consumer.subscribe(List.of(topic));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
      while(System.nanoTime()<end)for(var record:consumer.poll(Duration.ofMillis(300))){ctx.getBean(UsageAggregationService.class).process(record.value());Runtime.getRuntime().halt(24);} // DB已提交，不commit offset。
      throw new AssertionError("未取得Kafka消息");
     }
    }
    case "lease100" -> {
     var ids=ConcurrentHashMap.<Long>newKeySet();var rows=new ConcurrentLinkedQueue<OutboxEventEntry>();var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
     try{var tasks=new ArrayList<Future<?>>();for(int n=0;n<2;n++)tasks.add(pool.submit(()->{try{barrier.await();for(;;){var batch=claims.claim(20);if(batch.isEmpty())return;for(var row:batch){if(!ids.add(row.id))throw new AssertionError("有效租约重叠");rows.add(row);}}}catch(Exception e){throw new RuntimeException(e);}}));for(var task:tasks)task.get();}finally{pool.shutdownNow();}
     if(ids.size()!=100)throw new AssertionError("应领取100条，实际"+ids.size());var old=rows.iterator().next();jdbc.update("UPDATE fg_outbox_event SET lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",old.id);var fresh=claims.claim(1).get(0);if(!fresh.id.equals(old.id)||claims.sent(old)!=0)throw new AssertionError("旧owner覆盖新租约");
     System.out.println("{\"leases\":100,\"workers\":2,\"staleOwnerUpdate\":0}");
    }
    case "duplicate", "poison", "conflict", "valid-partition1", "valid-partition2" -> {
     String raw=jdbc.queryForObject("SELECT payload_json FROM fg_outbox_event ORDER BY id LIMIT 1",String.class);var e=UsageGrantedEvent.parse(raw);
     if(mode.equals("duplicate")){var pending=new ArrayList<CompletableFuture<?>>();for(int i=0;i<100;i++)pending.add(kafka.send(topic,Long.toString(e.applicationId()),e.canonical()));for(var future:pending)future.get(15,TimeUnit.SECONDS);System.out.println("{\"sent\":100}");}
     else{if(mode.startsWith("valid-partition")){int part=mode.endsWith("1")?1:2;var result=kafka.send(new ProducerRecord<>(topic,part,"proof",e.canonical())).get(15,TimeUnit.SECONDS);System.out.println("{\"offset\":"+result.getRecordMetadata().offset()+",\"partition\":"+part+"}");break;}String value=mode.equals("poison")?"{\"version\":99}":new UsageGrantedEvent(1,e.eventId(),e.chargeId(),e.applicationId(),e.apiId(),e.periodUtc(),e.units()==100?99:e.units()+1,e.eventTime()).canonical();int part=mode.equals("poison")?2:1;var result=kafka.send(new ProducerRecord<>(topic,part,"fault",value)).get(15,TimeUnit.SECONDS);System.out.println("{\"offset\":"+result.getRecordMetadata().offset()+",\"partition\":"+part+"}");}
    }
    default -> throw new IllegalArgumentException("未知probe模式");
   }
  }
 }
}
