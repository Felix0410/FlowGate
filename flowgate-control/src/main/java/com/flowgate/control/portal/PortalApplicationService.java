package com.flowgate.control.portal;

import com.flowgate.control.security.ApiFailure;
import com.flowgate.control.service.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import static com.flowgate.control.portal.PortalStore.*;
import static com.flowgate.control.service.BusinessInputs.*;

/** 自助应用与权益。应用锁→权益槽→唯一免费来源→不可变Grant→订阅/余额。 */
@Service
public class PortalApplicationService {
    private final PortalStore db;private final PortalOwnershipService owner;private final DeveloperApplicationService applications;private final AppCredentialService credentials;private final MarketCatalogService market;private final V12Readiness readiness;
    public PortalApplicationService(PortalStore db,PortalOwnershipService owner,DeveloperApplicationService applications,AppCredentialService credentials,MarketCatalogService market,V12Readiness readiness){this.db=db;this.owner=owner;this.applications=applications;this.credentials=credentials;this.market=market;this.readiness=readiness;}
    public Object apps(long user,long after,int limit){page(after,limit);return db.rows("SELECT a.id,a.developer_id,a.code,a.name,a.status FROM fg_application a JOIN fg_developer_member m ON m.developer_id=a.developer_id WHERE m.principal_id=? AND m.role='OWNER' AND m.status='ACTIVE' AND a.id>? ORDER BY a.id LIMIT ?",user,after,limit);}
    @Transactional public Object create(long user,Map<String,Object> b){fields(b,"developerId","code","name");long developer=number(b.get("developerId"),Long.MAX_VALUE,"工作区");owner.developer(user,developer);return applications.saveApp(null,Map.of("developerId",developer,"code",b.get("code"),"name",b.get("name")),user);}
    public Object credential(long user,long app,String action){
        owner.app(user,app);
        if(action.equals("status"))return credentials.summary(app);
        if(action.equals("revoke")){credentials.revoke(app,user);return Map.of("status","REVOKED");}
        // 1. Scope只能来自本人已经获得权益的当前可用API，服务端生成，忽略任意客户端Scope。
        var snapshot=readiness.current();readiness.requireNodes(snapshot.version(),snapshot.checksum());var scopes=new TreeSet<String>();
        for(var row:db.rows("SELECT api_id FROM fg_api_subscription WHERE application_id=? AND status='ACTIVE' AND effective_at<=UTC_TIMESTAMP(3) AND expires_at>UTC_TIMESTAMP(3)",app))for(var route:readiness.api(n(row,"api_id")))scopes.add(route.requiredScope());
        if(scopes.isEmpty())throw new ApiFailure(409,"NO_ACTIVE_API_ENTITLEMENT","请先领取当前API免费权益");
        return credentials.issue(app,List.copyOf(scopes),action.equals("rotate"),user);
    }
    public static String period(Instant now){return DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC).format(now);}
    public static void validPeriod(String period){require(period!=null&&period.matches("[0-9]{4}(0[1-9]|1[0-2])"),"VALIDATION_ERROR","月份须为YYYYMM");}
    @Transactional public Object claim(long user,Map<String,Object> b){
        fields(b,"applicationId","skuId");long app=number(b.get("applicationId"),Long.MAX_VALUE,"应用"),sku=number(b.get("skuId"),Long.MAX_VALUE,"报价");owner.app(user,app);
        // 2. 先锁应用，随后在同事务再次核验OWNER及启用状态，竞争请求不可换池。
        var application=db.need("SELECT * FROM fg_application WHERE id=? FOR UPDATE",app);owner.lockAppOwner(user,app);var developer=db.need("SELECT status FROM fg_developer WHERE id=? FOR SHARE",application.get("developer_id"));
        require("ACTIVE".equals(application.get("status"))&&"ACTIVE".equals(developer.get("status")),"APP_DISABLED","应用或工作区已停用");
        var offer=market.claimOffer(sku);long api=n(offer,"api_id"),units=n(offer,"quota_units");Instant now=db.nowUtc();String period=period(now);Instant start=now.atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant(),end=start.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        // 不接触旧订阅/余额，旧V11本月订阅存在时也不允许利用新领取覆盖其语义。
        if(db.one("SELECT id FROM fg_subscription WHERE application_id=? AND effective_at<? AND expires_at>? LIMIT 1",app,java.sql.Timestamp.from(end),java.sql.Timestamp.from(start))!=null)throw new ApiFailure(409,"LEGACY_SUBSCRIPTION_CONFLICT","应用当月已有旧版订阅，请使用独立应用领取");
        if(db.one("SELECT id FROM fg_portal_free_claim WHERE principal_id=? AND api_id=? FOR SHARE",user,api)!=null)throw new ApiFailure(409,"FREE_ALREADY_CLAIMED","你已领取过该API的免费权益，每个用户终身仅限一次");
        db.update("INSERT INTO fg_api_entitlement_slot(application_id,api_id,period_yyyymm) VALUES(?,?,?) ON DUPLICATE KEY UPDATE application_id=application_id",app,api,period);
        var slot=db.need("SELECT state FROM fg_api_entitlement_slot WHERE application_id=? AND api_id=? AND period_yyyymm=? FOR UPDATE",app,api,period);if(!"AVAILABLE".equals(slot.get("state")))throw new ApiFailure(409,"BASE_ENTITLEMENT_CONFLICT","应用在此API当月已有或已预占基础权益");
        long claim;
        try{claim=db.insert("INSERT INTO fg_portal_free_claim(principal_id,api_id,application_id,sku_id,period_yyyymm) VALUES(?,?,?,?,?)",user,api,app,sku,period);}catch(DuplicateKeyException duplicate){throw new ApiFailure(409,"FREE_ALREADY_CLAIMED","你已领取过该API的免费权益");}
        // 3. 唯一来源、槽、订阅、余额在同一数据库提交；客户端没有直接设置余额入口。
        long grant=db.insert("INSERT INTO fg_entitlement_grant(source_type,source_id,application_id,api_id,period_yyyymm,units) VALUES('FREE_TRIAL',?,?,?,?,?)",claim,app,api,period,units);
        db.update("UPDATE fg_api_entitlement_slot SET state='GRANTED',holder_type='FREE_GRANT',holder_id=?,version=version+1 WHERE application_id=? AND api_id=? AND period_yyyymm=?",grant,app,api,period);
        long subscription=db.insert("INSERT INTO fg_api_subscription(application_id,api_id,period_yyyymm,effective_at,expires_at,grant_id,units_snapshot) VALUES(?,?,?,?,?,?,?)",app,api,period,java.sql.Timestamp.from(start),java.sql.Timestamp.from(end),grant,units);
        db.update("INSERT INTO fg_api_quota_balance(application_id,api_id,period_yyyymm,subscription_id,total_units,remaining_units) VALUES(?,?,?,?,?,?)",app,api,period,subscription,units,units);
        return Map.of("entitlementId",grant,"applicationId",app,"apiId",api,"periodUtc",period,"totalUnits",units,"remainingUnits",units);
    }
    public Object subscriptions(long user,long app,long after,int limit){owner.app(user,app);page(after,limit);return db.rows("SELECT s.*,a.method,a.public_path FROM fg_api_subscription s JOIN fg_api a ON a.id=s.api_id WHERE s.application_id=? AND s.id>? ORDER BY s.id LIMIT ?",app,after,limit);}
    public Object quota(long user,long app,long api,String period){owner.app(user,app);validPeriod(period);var row=db.one("SELECT total_units,remaining_units,period_yyyymm,api_id,version FROM fg_api_quota_balance WHERE application_id=? AND api_id=? AND period_yyyymm=?",app,api,period);if(row==null)throw new ApiFailure(404,"NO_ENTITLEMENT","该应用在此API本月尚未开通权益");return row;}
    public Object ledger(long user,long app,long api,String period,long after,int limit){owner.app(user,app);validPeriod(period);page(after,limit);return db.rows("SELECT id,charge_id,api_id,route_id,period_yyyymm,units,remaining_units_after,created_at FROM fg_api_quota_ledger WHERE application_id=? AND api_id=? AND period_yyyymm=? AND id>? ORDER BY id LIMIT ?",app,api,period,after,limit);}
    public Object usage(long user,long app,long api,String period){owner.app(user,app);validPeriod(period);var pending=db.need("SELECT COUNT(*) AS pending FROM fg_api_quota_ledger l LEFT JOIN fg_api_consumed_event c ON c.charge_id=l.charge_id WHERE l.application_id=? AND l.api_id=? AND l.period_yyyymm=? AND c.charge_id IS NULL",app,api,period);return Map.of("pending",n(pending,"pending"),"source","RABBITMQ_DELAYED","daily",db.rows("SELECT day_utc,authorized_units,grant_count FROM fg_api_usage_daily WHERE application_id=? AND api_id=? AND DATE_FORMAT(day_utc,'%Y%m')=? ORDER BY day_utc",app,api,period),"message","日报仅含已消费事件；空列表表示尚无统计，不代表零使用量");}
}
