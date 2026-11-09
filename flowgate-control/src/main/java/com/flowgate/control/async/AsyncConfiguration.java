package com.flowgate.control.async;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
/** 异步关闭时不建立Topic、不调度Worker，也不启动消费者。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="flowgate.async",name="enabled",havingValue="true")
public class AsyncConfiguration {
 @Bean NewTopic usageTopic(@Value("${flowgate.async.topic}")String topic){return new NewTopic(topic,3,(short)1);}
}
