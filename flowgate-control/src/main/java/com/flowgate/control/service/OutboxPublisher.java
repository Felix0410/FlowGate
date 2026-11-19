package com.flowgate.control.service;
import com.flowgate.control.async.*;

import com.flowgate.control.service.OutboxClaimService;
import com.flowgate.control.messaging.RabbitDelivery;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 短事务租约后持久确认；维护切换必须先退出全部Control并等待租约耗尽。 */
@Component
@ConditionalOnProperty(prefix="flowgate.async",name="enabled",havingValue="true")
public class OutboxPublisher {
    private final OutboxClaimService claims;private final RabbitTemplate rabbit;private final LegacyTransportFence fence;private final AsyncDiagnostics diagnostics;
    private final AtomicBoolean busy=new AtomicBoolean();
    public OutboxPublisher(OutboxClaimService claims,@Qualifier("legacyUsageRabbitTemplate")RabbitTemplate rabbit,LegacyTransportFence fence,AsyncDiagnostics diagnostics){this.claims=claims;this.rabbit=rabbit;this.fence=fence;this.diagnostics=diagnostics;}
    @Scheduled(fixedDelayString="${flowgate.async.poll-ms:1000}") public void publish(){
        if(!fence.allows("RABBIT")||!busy.compareAndSet(false,true))return;
        try{
            // 1. 每轮最多2个5秒确认，低于30秒租约；DB领取提交后才发送。
            for(var row:claims.claim(2)){
                if(!fence.allows("RABBIT"))break;
                try{
                    var event=UsageGrantedEvent.parse(row.payloadJson);if(!event.eventId().equals(row.eventId))throw new IllegalArgumentException("INVALID_EVENT");
                    // 2. messageId和严格八字段不变；Confirm且无mandatory return才允许SENT。
                    RabbitDelivery.confirmed(rabbit,LegacyUsageRabbitConfiguration.EXCHANGE,"granted",event.canonical(),event.eventId(),0);
                    diagnostics.count(claims.sent(row)==1?"sent":"staleOwner");
                }catch(IllegalArgumentException invalid){claims.failed(row,"INVALID_EVENT");}
                catch(Exception unavailable){claims.failed(row,"BROKER_UNAVAILABLE");diagnostics.count("sendFailed");if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();}
            }
            diagnostics.publisherTick(true);
        }catch(RuntimeException unavailable){diagnostics.publisherTick(false);diagnostics.count("workerUnavailable");}
        // 3. 崩溃/回写不确定保留原事件；过期owner不能覆盖新租约，消费唯一键抗重复。
        finally{busy.set(false);}
    }
}
