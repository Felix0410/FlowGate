package com.flowgate.control.async;

import java.time.*;
import java.util.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;

/** 有界真实Rabbit主/Retry/Poison查询；5秒缓存与采样时间，不参与Gateway授权热路径。 */
@Component
public class AsyncBrokerProbe {
    private final boolean enabled;private final ObjectProvider<RabbitTemplate> rabbit;private final LegacyTransportFence fence;
    private Instant checked=Instant.EPOCH;private Map<String,Object> cached=Map.of("state","UNKNOWN");
    public AsyncBrokerProbe(@Value("${flowgate.async.enabled:false}")boolean enabled,@Qualifier("legacyUsageRabbitTemplate")ObjectProvider<RabbitTemplate> rabbit,LegacyTransportFence fence){this.enabled=enabled;this.rabbit=rabbit;this.fence=fence;}
    public boolean enabled(){return enabled;}
    public synchronized Map<String,Object> status(){
        if(!enabled)return Map.of("state","ASYNC_DISABLED","checkedAt",Instant.now(),"transport","RABBIT");
        if(Duration.between(checked,Instant.now()).getSeconds()>=5){
            try{
                var template=rabbit.getIfAvailable();if(template==null)throw new IllegalStateException();
                cached=template.execute(channel->{
                    // 1. 被动确认真实存在的拓扑；读取实际ready消息和消费者，不把TCP连接视为业务ready。
                    channel.exchangeDeclarePassive(LegacyUsageRabbitConfiguration.EXCHANGE);var queues=new ArrayList<Map<String,Object>>();
                    for(String name:List.of(LegacyUsageRabbitConfiguration.QUEUE,LegacyUsageRabbitConfiguration.RETRY,LegacyUsageRabbitConfiguration.POISON)){
                        var info=channel.queueDeclarePassive(name);queues.add(Map.of("name",name,"readyMessages",info.getMessageCount(),"consumers",info.getConsumerCount()));
                    }
                    return Map.<String,Object>of("state","BROKER_AVAILABLE","queues",queues,"transport","RABBIT","quorumPolicy","quorum/at-least-once DLX；实际类型与参数由部署检查验证");
                });
            }catch(RuntimeException unavailable){cached=Map.of("state","BROKER_UNAVAILABLE","transport","RABBIT");}
            checked=Instant.now();
        }
        // 2. FROZEN与UNKNOWN显式展示；Broker在线不代表日模型追平。
        var result=new LinkedHashMap<>(cached);result.put("checkedAt",checked);result.put("fence",fence.status());return result;
    }
}
