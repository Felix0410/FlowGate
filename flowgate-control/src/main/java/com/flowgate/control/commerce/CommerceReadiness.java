package com.flowgate.control.commerce;

import com.flowgate.control.portal.*;
import com.flowgate.control.security.ApiFailure;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.stereotype.Service;

/** 关掉时不查B表。新交易需真实schema、Broker队列和当前活跃业务消费者；旧单仍可恢复。 */
@Service
public class CommerceReadiness {
    private final PortalStore db;private final boolean enabled,async,prerequisites;private final ObjectProvider<RabbitTemplate> rabbit;private final ObjectProvider<RabbitListenerEndpointRegistry> registry;
    public CommerceReadiness(PortalStore db,@Value("${FLOWGATE_V12_COMMERCE_ENABLED:false}")boolean enabled,@Value("${FLOWGATE_V12_COMMERCE_ASYNC_ENABLED:false}")boolean async,@Value("${FLOWGATE_PORTAL_ENABLED:false}")boolean portal,@Value("${FLOWGATE_V12_QUOTA_ENABLED:false}")boolean quota,@Value("${FLOWGATE_V12_ASYNC_ENABLED:false}")boolean usage,@Qualifier("apiUsageRabbitTemplate")ObjectProvider<RabbitTemplate> rabbit,ObjectProvider<RabbitListenerEndpointRegistry> registry){this.db=db;this.enabled=enabled;this.async=async;this.prerequisites=portal&&quota&&usage;this.rabbit=rabbit;this.registry=registry;}
    public Map<String,Object> schema(){
        if(!enabled)return Map.of("status","DISABLED","message","普通订单默认关闭；不查询B业务表。");
        if(!prerequisites)return Map.of("status","PREREQUISITE_NOT_READY","message","请先在新隔离环境启用A Portal、API权益与Rabbit用量链。");
        try{long count=PortalStore.n(db.need("SELECT COUNT(*) AS amount FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('fg_order','fg_payment_record','fg_business_outbox','fg_business_event_consumed','fg_order_transition','fg_simulated_refund')"),"amount");return Map.of("status",count==6?"READY":"SCHEMA_NOT_READY","message",count==6?"六张B表已安装":"请在新隔离库显式安装B六表；不自动迁移用户旧库");}catch(org.springframework.dao.DataAccessException unavailable){return Map.of("status","UNKNOWN","message","数据库就绪事实暂不可知");}
    }
    public Map<String,Object> status(){var schema=schema();if(!"READY".equals(schema.get("status")))return schema;if(!async)return Map.of("status","ASYNC_DISABLED","message","Commerce异步默认关闭，不接受新订单。");
        try{var template=rabbit.getIfAvailable();if(template==null)return Map.of("status","UNKNOWN","message","Rabbit连接未配置");template.execute(channel->{channel.exchangeDeclarePassive(CommerceRabbitConfiguration.EXCHANGE);channel.queueDeclarePassive(CommerceRabbitConfiguration.QUEUE);channel.queueDeclarePassive(CommerceRabbitConfiguration.RETRY);channel.queueDeclarePassive(CommerceRabbitConfiguration.POISON);return true;});
            var workers=registry.getIfAvailable();boolean active=workers!=null&&workers.getListenerContainers().stream().anyMatch(c->c instanceof SimpleMessageListenerContainer simple&&Arrays.asList(simple.getQueueNames()).contains(CommerceRabbitConfiguration.QUEUE)&&simple.isRunning()&&simple.getActiveConsumerCount()>0);
            return Map.of("status",active?"READY":"WORKER_NOT_READY","message",active?"Broker队列与本实例活跃业务Worker已确认；交易仍以MySQL为准":"业务Worker尚未活跃，不接受新订单");
        }catch(RuntimeException unavailable){return Map.of("status","BROKER_NOT_READY","message","业务Rabbit不可用；已付款原订单由Outbox保留，恢复后可继续开通");}}
    public void requireSchema(){require(schema());}
    public void requireReady(){require(status());}
    private void require(Map<String,Object> state){if(!"READY".equals(state.get("status")))throw new ApiFailure(503,state.get("status").toString(),state.get("message").toString());}
}
