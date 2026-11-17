package com.flowgate.control.portal;

import com.flowgate.control.security.ApiFailure;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

/** Outbox租约+Rabbit publisher confirm/return；消费DB提交后手动ACK，至少一次投递。 */
@Component
@ConditionalOnProperty(name="FLOWGATE_V12_ASYNC_ENABLED",havingValue="true")
public class ApiUsageWorker {
    private final ApiUsageService usage;private final RabbitTemplate rabbit;private final AtomicBoolean busy=new AtomicBoolean();
    public ApiUsageWorker(ApiUsageService usage,RabbitTemplate apiUsageRabbitTemplate){this.usage=usage;this.rabbit=apiUsageRabbitTemplate;}
    private void confirmed(String payload,String key,String eventId)throws Exception{com.flowgate.control.messaging.RabbitDelivery.confirmed(rabbit,ApiUsageRabbitConfiguration.EXCHANGE,key,payload,eventId,0);}
    @Scheduled(fixedDelay=1000) public void publish(){if(!busy.compareAndSet(false,true))return;try{
        // 1. 每轮最多2，顺序确认且每次限时5秒（总处理时间低于30秒租约）；未收到Broker确认或发生return都不能标SENT。
        for(var row:usage.claim(2)){try{var event=ApiUsageGrantedEvent.parse(PortalStore.s(row,"payload_json"));if(!event.eventId().equals(row.get("event_id")))throw new IllegalArgumentException();confirmed(event.canonical(),"granted",event.eventId());usage.sent(row);}catch(IllegalArgumentException permanent){usage.failed(row,"INVALID_OUTBOX_PERMANENT");}catch(Exception unavailable){usage.failed(row,"RABBIT_PUBLISH_UNAVAILABLE");if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();}}
    }catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("V12 RabbitMQ投递暂不可用，保留原事件等待租约重试");}finally{busy.set(false);}}
    @RabbitListener(queues=ApiUsageRabbitConfiguration.QUEUE,containerFactory="apiUsageRabbitFactory")
    public void receive(Message message,Channel channel)throws Exception{
        com.flowgate.control.messaging.RabbitDelivery.receive(message,channel,rabbit,ApiUsageRabbitConfiguration.EXCHANGE,usage::process);
    }
}
