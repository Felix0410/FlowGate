package com.flowgate.control.async;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** FROZEN不启动消费者；已运行时DB暂不可用保留消费容器，让错误走持久Retry。 */
@Component
@ConditionalOnProperty(prefix="flowgate.async",name="enabled",havingValue="true")
public class LegacyListenerLifecycle {
    private final LegacyTransportFence fence;private final ObjectProvider<RabbitListenerEndpointRegistry> registry;
    public LegacyListenerLifecycle(LegacyTransportFence fence,ObjectProvider<RabbitListenerEndpointRegistry> registry){this.fence=fence;this.registry=registry;}
    @Scheduled(fixedDelay=5000) public void reconcile(){
        var workers=registry.getIfAvailable();var container=workers==null?null:workers.getListenerContainer("legacyUsage");if(container==null)return;
        String mode=String.valueOf(fence.status().get("mode"));
        if("RABBIT".equals(mode)&&!container.isRunning())container.start();
        else if(("FROZEN".equals(mode)||"KAFKA".equals(mode))&&container.isRunning())container.stop();
    }
}
