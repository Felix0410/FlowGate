package com.flowgate.control.promotion;

import com.flowgate.control.commerce.CommerceRabbitConfiguration;
import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** 同Commerce交换机、独立路由与三quorum队列，不能误投B付款消费者。 */
@Configuration
@ConditionalOnProperty(name={"FLOWGATE_V12_PROMOTION_ENABLED","FLOWGATE_V12_ASYNC_ENABLED","FLOWGATE_V12_COMMERCE_ASYNC_ENABLED"},havingValue="true")
public class PromotionIntentRabbitConfiguration {
    public static final String EXCHANGE=CommerceRabbitConfiguration.EXCHANGE,QUEUE="flowgate.commerce.v12.promo.intents",RETRY="flowgate.commerce.v12.promo.retry",POISON="flowgate.commerce.v12.promo.poison";
    @Bean Declarables promotionIntentTopology(){return new Declarables(new DirectExchange(EXCHANGE,true,false),QueueBuilder.durable(QUEUE).quorum().withArgument("x-dead-letter-strategy","at-least-once").withArgument("x-overflow","reject-publish").deadLetterExchange(EXCHANGE).deadLetterRoutingKey("promo-poison").build(),QueueBuilder.durable(RETRY).quorum().withArgument("x-dead-letter-strategy","at-least-once").withArgument("x-overflow","reject-publish").ttl(5000).deadLetterExchange(EXCHANGE).deadLetterRoutingKey("promo-intent").build(),QueueBuilder.durable(POISON).quorum().build(),new Binding(QUEUE,Binding.DestinationType.QUEUE,EXCHANGE,"promo-intent",null),new Binding(RETRY,Binding.DestinationType.QUEUE,EXCHANGE,"promo-retry",null),new Binding(POISON,Binding.DestinationType.QUEUE,EXCHANGE,"promo-poison",null));}
}
