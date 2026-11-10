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
    private void confirmed(String payload,String key,String eventId)throws Exception{
        var props=new MessageProperties();props.setContentType(MessageProperties.CONTENT_TYPE_JSON);props.setContentEncoding("UTF-8");props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setMessageId(eventId);
        var correlation=new CorrelationData(java.util.UUID.randomUUID().toString());rabbit.send(ApiUsageRabbitConfiguration.EXCHANGE,key,new Message(payload.getBytes(StandardCharsets.UTF_8),props),correlation);
        var confirmation=correlation.getFuture().get(5,TimeUnit.SECONDS);
        if(!confirmation.ack()||correlation.getReturned()!=null)throw new IllegalStateException("RABBIT_PUBLISH_NOT_CONFIRMED");
    }
    @Scheduled(fixedDelay=1000) public void publish(){if(!busy.compareAndSet(false,true))return;try{
        // 1. 每轮最多2，顺序确认且每次限时5秒（总处理时间低于30秒租约）；未收到Broker确认或发生return都不能标SENT。
        for(var row:usage.claim(2)){try{var event=ApiUsageGrantedEvent.parse(PortalStore.s(row,"payload_json"));if(!event.eventId().equals(row.get("event_id")))throw new IllegalArgumentException();confirmed(event.canonical(),"granted",event.eventId());usage.sent(row);}catch(Exception unavailable){usage.failed(row,"RABBIT_PUBLISH_UNAVAILABLE");if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();}}
    }catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("V12 RabbitMQ投递暂不可用，保留原事件等待租约重试");}finally{busy.set(false);}}
    @RabbitListener(queues=ApiUsageRabbitConfiguration.QUEUE,containerFactory="apiUsageRabbitFactory")
    public void receive(Message message,Channel channel)throws Exception{
        long tag=message.getMessageProperties().getDeliveryTag();String payload=new String(message.getBody(),StandardCharsets.UTF_8);
        try{
            // 2. process返回即MySQL真实COMMIT；重复消息也先验证本地权威，不重复聚合。
            usage.process(payload);channel.basicAck(tag,false);
        }catch(ApiFailure|IllegalArgumentException poison){
            // 3. 永久冲突只进入持久隔离队列，不丢弃、不记录敏感payload，不阻塞其他消息。
            channel.basicReject(tag,false);
        }catch(RuntimeException dependency){
            // 4. 短暂故障使用持久TTL重试队列；新副本确认前不能ACK原消息。
            confirmed(payload,"retry",message.getMessageProperties().getMessageId());channel.basicAck(tag,false);
        }
    }
}
