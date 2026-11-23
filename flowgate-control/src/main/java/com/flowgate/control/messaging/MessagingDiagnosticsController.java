package com.flowgate.control.messaging;

import com.flowgate.control.portal.PortalStore;
import java.util.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

/** 四条消息链的只读事实；Broker Ready与业务Consumed分别呈现，不从队列空推断业务成功。 */
@RestController
public class MessagingDiagnosticsController {
    private final PortalStore db;
    private final ObjectProvider<RabbitTemplate> api,legacy;
    public MessagingDiagnosticsController(PortalStore db,
        @Qualifier("apiUsageRabbitTemplate") ObjectProvider<RabbitTemplate> api,
        @Qualifier("legacyUsageRabbitTemplate") ObjectProvider<RabbitTemplate> legacy) {
        this.db=db;this.api=api;this.legacy=legacy;
    }
    @GetMapping("/admin/messaging/diagnostics")
    public Object diagnostics() {
        var chains=new ArrayList<Map<String,Object>>();
        String[][] definitions={
            {"V11用量","fg_outbox_event","fg_consumed_event","flowgate.usage.legacy.aggregate","flowgate.usage.legacy.retry","flowgate.usage.legacy.poison"},
            {"V12 API用量","fg_api_usage_outbox","fg_api_consumed_event","flowgate.usage.v12.aggregate","flowgate.usage.v12.retry","flowgate.usage.v12.poison"},
            {"付款权益","fg_business_outbox","fg_business_event_consumed","flowgate.commerce.v12.orders","flowgate.commerce.v12.retry","flowgate.commerce.v12.poison"},
            {"促销意向","fg_promo_outbox","fg_promo_event_consumed","flowgate.commerce.v12.promo.intents","flowgate.commerce.v12.promo.retry","flowgate.commerce.v12.promo.poison"}
        };
        for(String[] d:definitions) {
            var row=new LinkedHashMap<String,Object>();row.put("chain",d[0]);
            try {
                // 1. 表名仅来自固定源码；缺表/OFF旧环境不执行不存在业务表的聚合。
                long installed=PortalStore.n(db.need("SELECT COUNT(*) amount FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN (?,?)",d[1],d[2]),"amount");
                if(installed!=2){row.put("state","NOT_READY");chains.add(row);continue;}
                row.put("outbox",db.rows("SELECT status,COUNT(*) amount FROM "+d[1]+" GROUP BY status"));
                row.put("consumed",db.need("SELECT COUNT(*) amount FROM "+d[2]).get("amount"));
                if(d[1].equals("fg_promo_outbox"))row.put("retryExhausted",db.need("SELECT COUNT(*) amount FROM fg_promo_outbox o LEFT JOIN fg_promo_event_consumed c ON c.event_id=o.event_id WHERE o.status='FAILED' AND o.attempt_count>=8 AND c.event_id IS NULL").get("amount"));
                row.put("state","OBSERVED");
            }catch(org.springframework.dao.DataAccessException unavailable){row.put("state","UNKNOWN");}
            try {
                // 2. 被动声明只读现存队列，不偷偷建立拓扑；无消费者/未知连接与业务统计分开。
                var template=api.getIfAvailable();if(template==null)template=legacy.getIfAvailable();
                if(template==null)throw new IllegalStateException();
                row.put("broker",template.execute(channel->{var main=channel.queueDeclarePassive(d[3]);var retry=channel.queueDeclarePassive(d[4]);var poison=channel.queueDeclarePassive(d[5]);return Map.of("ready",main.getMessageCount(),"consumers",main.getConsumerCount(),"retry",retry.getMessageCount(),"poison",poison.getMessageCount(),"state","OBSERVED");}));
            }catch(RuntimeException unavailable){row.put("broker",Map.of("state","UNKNOWN"));}
            chains.add(row);
        }
        // 3. 这是手动有界诊断快照，不将跨查询的不同瞬间包装为完整RR对账或最终验收。
        return Map.of("chains",chains,"sampledAtUtc",java.time.Instant.now(),"note","队列Ready不含未ACK；SENT不等于Consumed，诊断不代替业务对账");
    }
}
