package com.flowgate.control.async;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.*;
/** 首版保守毒消息策略：回退尚未处理偏移并暂停问题分区，不依赖不可确认的DLT。 */
@Configuration
@ConditionalOnProperty(prefix="flowgate.async",name="enabled",havingValue="true")
public class UsageKafkaConfiguration {
 @Bean ConcurrentKafkaListenerContainerFactory<String,String> usageKafkaFactory(ConsumerFactory<String,String> consumers,AsyncDiagnostics diagnostics){
  var factory=new ConcurrentKafkaListenerContainerFactory<String,String>();factory.setConsumerFactory(consumers);
  factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
  factory.getContainerProperties().setSyncCommits(true);factory.getContainerProperties().setMissingTopicsFatal(false);
  factory.setCommonErrorHandler(new CommonErrorHandler(){
   @Override public boolean seeksAfterHandling(){return true;}
   @Override public boolean isAckAfterHandle(){return false;}
   @Override public void handleRemaining(Exception error,List<ConsumerRecord<?,?>> records,Consumer<?,?> consumer,MessageListenerContainer container){
    // 1. 同一次poll尚未处理的所有分区回退到各自第一条，禁止吞掉后续记录。
    var offsets=new HashMap<TopicPartition,Long>();for(var record:records)offsets.merge(new TopicPartition(record.topic(),record.partition()),record.offset(),Math::min);
    offsets.forEach(consumer::seek);
    // 2. 只暂停问题分区；DB/协议恢复并诊断后由负责人重启消费者继续原offset。
    if(!records.isEmpty()){var first=records.get(0);container.pausePartition(new TopicPartition(first.topic(),first.partition()));diagnostics.count("pausedPartition");}
    // 3. 不确认原offset，不发送未经ACK保证的DLT，也不输出原始payload/异常消息。
    diagnostics.count("conflict");
   }
  });
  return factory;
 }
}
