package com.flowgate.control.commerce;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** 与V12用量共享连接，交换机与quorum队列完全独立；OFF不声明、不消费。 */
@Configuration
@ConditionalOnProperty(name={"FLOWGATE_V12_ASYNC_ENABLED","FLOWGATE_V12_COMMERCE_ENABLED","FLOWGATE_V12_COMMERCE_ASYNC_ENABLED"},havingValue="true")
public class CommerceRabbitConfiguration {
    public static final String EXCHANGE="flowgate.commerce.v12",QUEUE="flowgate.commerce.v12.orders",RETRY="flowgate.commerce.v12.retry",POISON="flowgate.commerce.v12.poison";
    @Bean Declarables commerceTopology(){
        // 1. 业务队列毒消息进入持久隔离队列；可重试依赖故障投递到5秒TTL重试队列。
        return new Declarables(new DirectExchange(EXCHANGE,true,false),QueueBuilder.durable(QUEUE).quorum().withArgument("x-dead-letter-strategy","at-least-once").withArgument("x-overflow","reject-publish").deadLetterExchange(EXCHANGE).deadLetterRoutingKey("poison").build(),QueueBuilder.durable(RETRY).quorum().withArgument("x-dead-letter-strategy","at-least-once").withArgument("x-overflow","reject-publish").ttl(5000).deadLetterExchange(EXCHANGE).deadLetterRoutingKey("payment").build(),QueueBuilder.durable(POISON).quorum().build(),new Binding(QUEUE,Binding.DestinationType.QUEUE,EXCHANGE,"payment",null),new Binding(RETRY,Binding.DestinationType.QUEUE,EXCHANGE,"retry",null),new Binding(POISON,Binding.DestinationType.QUEUE,EXCHANGE,"poison",null));
    }
}
