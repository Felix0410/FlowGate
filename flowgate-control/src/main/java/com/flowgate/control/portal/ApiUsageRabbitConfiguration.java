package com.flowgate.control.portal;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

/** V12独立RabbitMQ交换机/持久队列，V11 Kafka配置保持不变。OFF不创建队列或监听器。 */
@Configuration
@EnableRabbit
@EnableScheduling
@ConditionalOnProperty(name="FLOWGATE_V12_ASYNC_ENABLED",havingValue="true")
public class ApiUsageRabbitConfiguration {
    public static final String EXCHANGE="flowgate.usage.v12",QUEUE="flowgate.usage.v12.aggregate",RETRY="flowgate.usage.v12.retry",POISON="flowgate.usage.v12.poison";
    @Bean CachingConnectionFactory apiUsageConnectionFactory(@Value("${FLOWGATE_RABBITMQ_HOST:127.0.0.1}")String host,@Value("${FLOWGATE_RABBITMQ_PORT:5672}")int port,@Value("${FLOWGATE_RABBITMQ_USERNAME:flowgate}")String username,@Value("${FLOWGATE_RABBITMQ_PASSWORD:}")String password){var connection=new CachingConnectionFactory(host,port);connection.setUsername(username);connection.setPassword(password);connection.setVirtualHost("/flowgate-v12");connection.setConnectionTimeout(2000);connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);connection.setPublisherReturns(true);return connection;}
    @Bean RabbitAdmin apiUsageRabbitAdmin(CachingConnectionFactory apiUsageConnectionFactory){return new RabbitAdmin(apiUsageConnectionFactory);}
    @Bean Declarables apiUsageTopology(){
        // 1. 业务队列毒消息进入持久隔离队列；可重试依赖故障投递到5秒TTL重试队列。
        return new Declarables(new DirectExchange(EXCHANGE,true,false),QueueBuilder.durable(QUEUE).quorum().withArgument("x-dead-letter-strategy","at-least-once").withArgument("x-overflow","reject-publish").deadLetterExchange(EXCHANGE).deadLetterRoutingKey("poison").build(),QueueBuilder.durable(RETRY).quorum().withArgument("x-dead-letter-strategy","at-least-once").withArgument("x-overflow","reject-publish").ttl(5000).deadLetterExchange(EXCHANGE).deadLetterRoutingKey("granted").build(),QueueBuilder.durable(POISON).quorum().build(),new Binding(QUEUE,Binding.DestinationType.QUEUE,EXCHANGE,"granted",null),new Binding(RETRY,Binding.DestinationType.QUEUE,EXCHANGE,"retry",null),new Binding(POISON,Binding.DestinationType.QUEUE,EXCHANGE,"poison",null));
    }
    @Bean RabbitTemplate apiUsageRabbitTemplate(CachingConnectionFactory apiUsageConnectionFactory){var template=new RabbitTemplate(apiUsageConnectionFactory);template.setMandatory(true);return template;}
    @Bean SimpleRabbitListenerContainerFactory apiUsageRabbitFactory(CachingConnectionFactory apiUsageConnectionFactory){var factory=new SimpleRabbitListenerContainerFactory();factory.setConnectionFactory(apiUsageConnectionFactory);factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);factory.setPrefetchCount(20);factory.setConcurrentConsumers(2);factory.setRecoveryInterval(5000L);factory.setDefaultRequeueRejected(true);factory.setMissingQueuesFatal(false);return factory;}
}
