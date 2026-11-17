package com.flowgate.control.portal;

import java.time.Instant;
import java.sql.Timestamp;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 免费和付费共用的权益写入边界；调用方必须持应用与槽锁，同事务提交。 */
@Service
public class EntitlementGrantService {
    private final PortalStore db;
    public EntitlementGrantService(PortalStore db){this.db=db;}
    @Transactional(propagation=Propagation.MANDATORY)
    public long grant(String source,long sourceId,long app,long api,String period,long units,Instant start,Instant end){
        long grant=db.insert("INSERT INTO fg_entitlement_grant(source_type,source_id,application_id,api_id,period_yyyymm,units) VALUES(?,?,?,?,?,?)",source,sourceId,app,api,period,units);
        long subscription=db.insert("INSERT INTO fg_api_subscription(application_id,api_id,period_yyyymm,effective_at,expires_at,grant_id,units_snapshot) VALUES(?,?,?,?,?,?,?)",app,api,period,Timestamp.from(start),Timestamp.from(end),grant,units);
        db.update("INSERT INTO fg_api_quota_balance(application_id,api_id,period_yyyymm,subscription_id,total_units,remaining_units) VALUES(?,?,?,?,?,?)",app,api,period,subscription,units,units);
        return grant;
    }
}
