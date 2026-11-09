package com.flowgate.control.async;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.*;
/** 消费者错误策略薄配置：数据库自动退避重试，永久毒消息暂停且不ACK。 */
@Configuration
@ConditionalOnProperty(prefix="flowgate.async",name="enabled",havingValue="true")
public class UsageKafkaConfiguration {
 @Bean ConcurrentKafkaListenerContainerFactory<String,String> usageKafkaFactory(ConsumerFactory<String,String> consumers,AsyncDiagnostics diagnostics){
  var factory=new ConcurrentKafkaListenerContainerFactory<String,String>();factory.setConsumerFactory(consumers);
  factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
  factory.getContainerProperties().setSyncCommits(true);factory.getContainerProperties().setMissingTopicsFatal(false);
  factory.setCommonErrorHandler(new UsageConsumerErrorHandler(diagnostics));
  return factory;
 }
}
