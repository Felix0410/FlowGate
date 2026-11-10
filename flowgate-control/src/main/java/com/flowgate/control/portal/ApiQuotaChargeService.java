package com.flowgate.control.portal;

import com.flowgate.contract.*;
import com.flowgate.control.security.ApiFailure;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.*;
import static com.flowgate.control.portal.PortalStore.*;

/** V12权威授权事务；仅扣APP/API/UTC月新池，不读写旧月余额和旧Outbox。 */
@Service
public class ApiQuotaChargeService {
    private final PortalStore db;private final V12Readiness readiness;private final TransactionTemplate tx;
    public ApiQuotaChargeService(PortalStore db,V12Readiness readiness,PlatformTransactionManager manager){this.db=db;this.readiness=readiness;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(4);}
    private ApiFailure denied(String code,String message){return new ApiFailure(403,code,message);}
    public Object charge(QuotaCharge q){readiness.enabled();return tx.execute(status->grant(q));}
    private Object grant(QuotaCharge q){
        // 1. 所有身份/规则来自数据库与不可变快照，不接受调用者指定应用或月份。
        var release=db.need("SELECT snapshot_json,sha256 FROM fg_config_release WHERE version=?",q.appliedVersion());if(!q.snapshotChecksum().equals(release.get("sha256")))throw new ApiFailure(409,"CONFIG_VERSION_MISMATCH","计量版本校验和不一致");
        var snapshot=SnapshotCodec.JSON.readValue(s(release,"snapshot_json"),Snapshot.class);SnapshotCodec.verify(snapshot);
        var route=snapshot.routes().stream().filter(r->r.routeId().equals(q.routeId())).findFirst().orElseThrow(()->new ApiFailure(409,"CONFIG_VERSION_MISMATCH","计量路由不存在"));var plan=QuotaPolicy.freeze(snapshot,route);
        if(plan.mode()!=QuotaPolicy.Mode.V12_APP_API||plan.apiId()!=q.apiId()||plan.units()!=q.units())throw new ApiFailure(409,"CONFIG_VERSION_MISMATCH","API计量模式或单位不匹配");
        var link=db.one("SELECT application_id FROM fg_app_principal WHERE principal_id=?",q.principalId());if(link==null)throw denied("APP_TOKEN_REVOKED","凭证未关联应用");long app=n(link,"application_id");
        var application=db.need("SELECT * FROM fg_application WHERE id=? FOR UPDATE",app);if(!"ACTIVE".equals(application.get("status")))throw denied("APP_DISABLED","应用已停用");
        var developer=db.need("SELECT status FROM fg_developer WHERE id=? FOR SHARE",application.get("developer_id"));if(!"ACTIVE".equals(developer.get("status")))throw denied("DEVELOPER_DISABLED","工作区已停用");
        var principal=db.need("SELECT p.principal_type,p.role,p.enabled,p.scopes_json,c.generation,c.enabled AS credential_enabled FROM fg_principal p JOIN fg_app_credential_state c ON c.principal_id=p.id WHERE p.id=? FOR SHARE",q.principalId());
        if(!"APP_CALLER".equals(principal.get("principal_type"))||!"APP_CALLER".equals(principal.get("role"))||!Boolean.TRUE.equals(principal.get("enabled"))||!Boolean.TRUE.equals(principal.get("credential_enabled"))||n(principal,"generation")!=q.credentialGeneration())throw denied("APP_TOKEN_REVOKED","凭证已撤销或过期");
        if(!SnapshotCodec.JSON.readValue(s(principal,"scopes_json"),List.class).contains(route.requiredScope()))throw denied("INSUFFICIENT_SCOPE","应用未获得此API调用范围");
        var members=db.rows("SELECT u.status,p.enabled,m.status AS member_status FROM fg_developer_member m JOIN fg_portal_user u ON u.principal_id=m.principal_id JOIN fg_principal p ON p.id=u.principal_id WHERE m.developer_id=? AND m.role='OWNER' FOR SHARE",application.get("developer_id"));
        if(!members.isEmpty()&&members.stream().noneMatch(m->"ACTIVE".equals(m.get("status"))&&"ACTIVE".equals(m.get("member_status"))&&Boolean.TRUE.equals(m.get("enabled"))))throw denied("APP_DISABLED","应用所有者已停用");
        String fingerprint=SnapshotCodec.digest(SnapshotCodec.JSON.writeValueAsString(List.of(app,q.principalId(),q.credentialGeneration(),q.apiId(),q.routeId(),plan.policyId(),q.units(),q.appliedVersion(),q.snapshotChecksum())));
        // 2. 应用锁串行同应用请求；幂等在查当前月份之前，跨月重试仍返回原月份授权。
        var old=db.one("SELECT * FROM fg_api_quota_ledger WHERE charge_id=?",q.chargeId());if(old!=null){if(!fingerprint.equals(old.get("request_fingerprint")))throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT","同一授权标识的参数发生变化");return response(old,true);}
        Instant now=db.nowUtc();String period=PortalApplicationService.period(now);
        var sub=db.one("SELECT s.id,s.units_snapshot,b.id AS balance_id,b.total_units,b.remaining_units FROM fg_api_subscription s JOIN fg_api_quota_balance b ON b.subscription_id=s.id AND b.application_id=s.application_id AND b.api_id=s.api_id AND b.period_yyyymm=s.period_yyyymm JOIN fg_entitlement_grant g ON g.id=s.grant_id AND g.application_id=s.application_id AND g.api_id=s.api_id AND g.period_yyyymm=s.period_yyyymm AND g.units=s.units_snapshot WHERE s.application_id=? AND s.api_id=? AND s.period_yyyymm=? AND s.status='ACTIVE' AND s.effective_at<=? AND s.expires_at>? FOR UPDATE",app,q.apiId(),period,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now));
        if(sub==null)throw denied("NO_ACTIVE_API_ENTITLEMENT","当前API没有有效权益");if(n(sub,"units_snapshot")!=n(sub,"total_units"))throw new ApiFailure(409,"ENTITLEMENT_INTEGRITY_GAP","冻结权益与余额不一致");
        long remaining=n(sub,"remaining_units")-q.units();if(remaining<0)throw new ApiFailure(429,"QUOTA_EXHAUSTED","该API本月额度已耗尽");
        // 3. 减余额、不可变流水、新事件同事务。429或任何插入异常全部回滚。
        db.update("UPDATE fg_api_quota_balance SET remaining_units=?,version=version+1 WHERE id=?",remaining,sub.get("balance_id"));
        db.update("INSERT INTO fg_api_quota_ledger(charge_id,request_fingerprint,application_id,principal_id,credential_generation,api_id,route_id,applied_version,policy_id,period_yyyymm,units,remaining_units_after) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",q.chargeId(),fingerprint,app,q.principalId(),q.credentialGeneration(),q.apiId(),q.routeId(),q.appliedVersion(),plan.policyId(),period,q.units(),remaining);
        var event=new ApiUsageGrantedEvent(12,"USAGE_GRANTED_V12",UUID.randomUUID().toString(),q.chargeId(),app,q.apiId(),period,q.units(),now);
        db.update("INSERT INTO fg_api_usage_outbox(event_id,charge_id,payload_json) VALUES(?,?,?)",event.eventId(),q.chargeId(),event.canonical());
        return Map.of("chargeId",q.chargeId(),"chargedUnits",q.units(),"remainingUnits",remaining,"periodUtc",period,"duplicate",false);
    }
    private Object response(Map<String,Object> row,boolean duplicate){return Map.of("chargeId",row.get("charge_id"),"chargedUnits",row.get("units"),"remainingUnits",row.get("remaining_units_after"),"periodUtc",row.get("period_yyyymm"),"duplicate",duplicate);}
}
