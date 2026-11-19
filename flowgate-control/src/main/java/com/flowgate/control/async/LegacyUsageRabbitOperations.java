package com.flowgate.control.async;

import com.flowgate.control.mapper.*;
import com.flowgate.control.entity.OutboxEventEntry;
import com.flowgate.control.security.ApiFailure;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

/** ADMIN恢复前核验源事件与Ledger；从不生成新事件或改写余额。调用方已建立写事务。 */
@Service
public class LegacyUsageRabbitOperations {
    private final OutboxEventEntryMapper outbox;private final QuotaLedgerEntryMapper ledger;private final LegacyTransportFence fence;
    public LegacyUsageRabbitOperations(OutboxEventEntryMapper outbox,QuotaLedgerEntryMapper ledger,LegacyTransportFence fence){this.outbox=outbox;this.ledger=ledger;this.fence=fence;}
    public String validate(long id){
        // 1. 与切换工具相同的DB行锁保护epoch；切换必须离线，不能与恢复混跑。
        if(!fence.allows("RABBIT"))throw new ApiFailure(409,"CUTOVER_FROZEN","旧用量未处于Rabbit路由，禁止恢复");
        var row=outbox.selectOne(new QueryWrapper<OutboxEventEntry>().eq("id",id).last("FOR UPDATE"));
        if(row==null||!"USAGE_GRANTED".equals(row.eventType))throw new ApiFailure(409,"INTEGRITY_GAP","没有可核验的原始用量事件");
        try{
            var event=UsageGrantedEvent.parse(row.payloadJson);var grant=ledger.find(event.chargeId());
            if(!event.eventId().equals(row.eventId)||grant==null||grant.applicationId!=event.applicationId()||grant.apiId!=event.apiId()||grant.units!=event.units()||!grant.periodYyyymm.equals(event.periodUtc()))throw new IllegalArgumentException();
            // 2. 同charge的多事件不能被逐个恢复，先报告完整性缺口。
            long duplicates=outbox.selectCount(new QueryWrapper<OutboxEventEntry>().eq("event_type","USAGE_GRANTED").apply("JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.chargeId'))={0}",event.chargeId()));
            if(duplicates!=1)throw new IllegalArgumentException();
            return event.fingerprint();
        }catch(IllegalArgumentException invalid){throw new ApiFailure(409,"INTEGRITY_GAP","原始事件与Ledger冲突，禁止补投或重算");}
    }
}
