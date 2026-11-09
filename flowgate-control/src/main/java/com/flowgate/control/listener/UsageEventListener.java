package com.flowgate.control.listener;
import com.flowgate.control.async.AsyncDiagnostics;
import com.flowgate.control.service.UsageAggregationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
/** 手动ACK必须在业务事务方法返回后；返回即真实DB提交，不能在事务内部ack。 */
@Component
@ConditionalOnProperty(prefix="flowgate.async",name="enabled",havingValue="true")
public class UsageEventListener {
 private final UsageAggregationService aggregation;private final AsyncDiagnostics diagnostics;
 public UsageEventListener(UsageAggregationService aggregation,AsyncDiagnostics diagnostics){this.aggregation=aggregation;this.diagnostics=diagnostics;}
 @KafkaListener(id="flowgateUsage",groupId="${spring.kafka.consumer.group-id}",idIsGroup=false,topics="${flowgate.async.topic}",containerFactory="usageKafkaFactory")
 public void receive(String payload,Acknowledgment ack){
  String outcome=aggregation.process(payload);
  diagnostics.count(outcome.equals("CONSUMED")?"consumed":"duplicate");
  ack.acknowledge();
 }
}
