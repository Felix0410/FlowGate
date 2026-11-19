package com.flowgate.control.async;

import com.flowgate.control.service.UsageAggregationService;
import com.flowgate.control.messaging.RabbitDelivery;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 复用旧账本与日模型；聚合事务真正提交后才ACK，毒消息与重试均由持久拓扑处理。 */
@Component
@ConditionalOnProperty(prefix="flowgate.async",name="enabled",havingValue="true")
public class LegacyUsageRabbitListener {
    private final UsageAggregationService aggregation;private final RabbitTemplate rabbit;private final LegacyTransportFence fence;private final AsyncDiagnostics diagnostics;
    public LegacyUsageRabbitListener(UsageAggregationService aggregation,@Qualifier("legacyUsageRabbitTemplate")RabbitTemplate rabbit,LegacyTransportFence fence,AsyncDiagnostics diagnostics){this.aggregation=aggregation;this.rabbit=rabbit;this.fence=fence;this.diagnostics=diagnostics;}
    @RabbitListener(id="legacyUsage",queues=LegacyUsageRabbitConfiguration.QUEUE,containerFactory="legacyUsageRabbitFactory",autoStartup="false")
    public void receive(Message message,Channel channel)throws Exception{
        // 1. 容器只在RABBIT模式启动；已活跃时数据库短断走持久TTL重试，不在源队列热重投。
        RabbitDelivery.receive(message,channel,rabbit,LegacyUsageRabbitConfiguration.EXCHANGE,payload->{
            if(!fence.allows("RABBIT"))throw new org.springframework.dao.DataAccessResourceFailureException("LEGACY_FENCE_UNAVAILABLE");
            String outcome=aggregation.process(payload);diagnostics.count("CONSUMED".equals(outcome)?"consumed":"duplicate");
        });
    }
}
