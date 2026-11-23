package com.flowgate.control.promotion;

import com.flowgate.control.messaging.RabbitDelivery;
import com.flowgate.control.portal.PortalStore;
import com.rabbitmq.client.Channel;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

/** 每轮两条5秒确认，低于30秒租约；创建订单真正COMMIT后才ACK。 */
@Component
@ConditionalOnProperty(name={"FLOWGATE_V12_PROMOTION_ENABLED","FLOWGATE_V12_ASYNC_ENABLED","FLOWGATE_V12_COMMERCE_ASYNC_ENABLED"},havingValue="true")
public class PromotionIntentWorker {
    private final PromotionService promotions;private final PromotionReadiness readiness;private final RabbitTemplate rabbit;private final AtomicBoolean busy=new AtomicBoolean();
    public PromotionIntentWorker(PromotionService promotions,PromotionReadiness readiness,@Qualifier("apiUsageRabbitTemplate")RabbitTemplate rabbit){this.promotions=promotions;this.readiness=readiness;this.rabbit=rabbit;}
    @Scheduled(fixedDelay=1000) public void publish(){if(!readiness.available()||!busy.compareAndSet(false,true))return;try{for(var row:promotions.claimOutbox()){
        try{var event=PromotionIntentEvent.parse(PortalStore.s(row,"payload_json"));if(!event.eventId().equals(row.get("event_id"))||event.intentId()!=PortalStore.n(row,"intent_id"))throw new IllegalArgumentException();RabbitDelivery.confirmed(rabbit,PromotionIntentRabbitConfiguration.EXCHANGE,"promo-intent",event.canonical(),event.eventId(),0);promotions.published(row);}
        catch(IllegalArgumentException permanent){promotions.failed(row,true);}catch(Exception unavailable){promotions.failed(row,false);if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();}
    }}catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("PROMO_OUTBOX_PENDING");}finally{busy.set(false);}}
    @RabbitListener(queues=PromotionIntentRabbitConfiguration.QUEUE,containerFactory="apiUsageRabbitFactory") public void receive(Message message,Channel channel)throws Exception{RabbitDelivery.receive(message,channel,rabbit,PromotionIntentRabbitConfiguration.EXCHANGE,"promo-retry",promotions::process);}
}
