package com.flowgate.control.commerce;

import com.flowgate.control.portal.*;
import com.flowgate.control.security.ApiFailure;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.flowgate.control.portal.PortalStore.*;
import static com.flowgate.control.service.BusinessInputs.*;

/** 普通订单：APP→BASE槽→订单固定锁序；状态/收据/Outbox同事务，金额仅从SKU读取。 */
@Service
public class OrderService {
    private final PortalStore db;private final PortalOwnershipService owner;private final MarketCatalogService market;private final TransactionTemplate tx;private final CommerceReadiness readiness;
    public OrderService(PortalStore db,PortalOwnershipService owner,MarketCatalogService market,PlatformTransactionManager manager,CommerceReadiness readiness){this.db=db;this.owner=owner;this.market=market;this.readiness=readiness;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(8);}
    public void enabled(){readiness.requireSchema();}
    private ApiFailure conflict(String code,String message){return new ApiFailure(409,code,message);}
    public Map<String,Object> get(long user,String orderNo){enabled();var order=db.need("SELECT * FROM fg_order WHERE order_no=? AND principal_id=?",orderNo,user);owner.app(user,n(order,"application_id"));return order;}
    public Object list(long user,long after,int limit){enabled();page(after,limit);require(limit<=50,"VALIDATION_ERROR","订单每页最多50条");return db.rows("SELECT * FROM fg_order WHERE principal_id=? AND id>? ORDER BY id LIMIT ?",user,after,limit).stream().map(this::summary).toList();}
    public Object detail(long user,String orderNo){var row=get(user,orderNo);var view=summary(row);view.put("transitions",db.rows("SELECT from_status,to_status,reason_code,created_at FROM fg_order_transition WHERE order_id=? ORDER BY id",row.get("id")));view.put("payment",db.one("SELECT provider,amount_cents,currency,confirmed_at FROM fg_payment_record WHERE order_id=?",row.get("id")));view.put("refund",db.one("SELECT amount_cents,status,reason_code,confirmed_at FROM fg_simulated_refund WHERE order_id=?",row.get("id")));return view;}
    public Map<String,Object> summary(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();result.put("id",row.get("id"));result.put("orderNo",row.get("order_no"));result.put("status",row.get("status"));result.put("amountCents",row.get("price_cents"));result.put("currency",row.get("currency"));result.put("quotaUnits",row.get("units_snapshot"));result.put("periodUtc",row.get("period_yyyymm"));result.put("payDeadlineAt",row.get("pay_deadline_at"));result.put("effectiveAt",row.get("effective_at"));result.put("expiresAt",row.get("expires_at"));result.put("applicationSummary",db.need("SELECT id,name FROM fg_application WHERE id=?",row.get("application_id")));result.put("apiSummary",db.need("SELECT a.id,a.method,a.public_path,l.title FROM fg_api a JOIN fg_api_listing l ON l.api_id=a.id WHERE a.id=?",row.get("api_id")));result.put("canRefund",canRefund(row));result.put("paymentStatus",OrderStateMachine.paid(s(row,"status"))||"REFUNDED".equals(row.get("status"))?"SIMULATED_CONFIRMED":"UNPAID");return result;}
    public boolean canRefund(Map<String,Object> row){String state=s(row,"status");if(Set.of("PAID_PENDING_FULFILLMENT","FULFILLMENT_RETRY","FULFILLMENT_REVIEW").contains(state))return true;if(!"FULFILLED".equals(state))return false;return db.one("SELECT b.id FROM fg_api_quota_balance b WHERE b.application_id=? AND b.api_id=? AND b.period_yyyymm=? AND b.remaining_units=b.total_units AND NOT EXISTS(SELECT 1 FROM fg_api_quota_ledger l WHERE l.application_id=b.application_id AND l.api_id=b.api_id AND l.period_yyyymm=b.period_yyyymm)",row.get("application_id"),row.get("api_id"),row.get("period_yyyymm"))!=null;}
    public record Created(Map<String,Object> order,boolean created){}
    private Map<String,Object> retry(long user,String request,String fingerprint){var old=db.one("SELECT * FROM fg_order WHERE principal_id=? AND client_request_id=?",user,request);if(old!=null&&!fingerprint.equals(old.get("request_fingerprint")))throw conflict("IDEMPOTENCY_CONFLICT","同一请求号不可更换应用、商品或月份");return old;}
    public Created create(long user,Map<String,Object> b){enabled();fields(b,"applicationId","skuId","clientRequestId");long app=number(b.get("applicationId"),Long.MAX_VALUE,"应用"),sku=number(b.get("skuId"),Long.MAX_VALUE,"SKU");String request=text(b.get("clientRequestId"),"请求号",64);require(request.matches("[A-Za-z0-9_-]+"),"VALIDATION_ERROR","请求号仅支持字母数字横线下划线");var metadata=db.need("SELECT kind,period_policy FROM fg_api_sku WHERE id=?",sku);String fingerprint=CommerceEvent.hash(com.flowgate.contract.SnapshotCodec.JSON.writeValueAsString(List.of(user,app,sku,metadata.get("kind"),metadata.get("period_policy"))));
        owner.app(user,app);var original=retry(user,request,fingerprint);if(original!=null)return new Created(summary(original),false);readiness.requireReady();
        try{return tx.execute(status->{
            // 1. 锁定应用并再次核验OWNER；先读SKU定位槽，持槽后再共享锁重读可售商品。
            var application=db.need("SELECT * FROM fg_application WHERE id=? FOR UPDATE",app);owner.lockAppOwner(user,app);
            var old=retry(user,request,fingerprint);if(old!=null)return new Created(summary(old),false);
            var developer=db.need("SELECT status FROM fg_developer WHERE id=? FOR SHARE",application.get("developer_id"));
            if(!"ACTIVE".equals(application.get("status"))||!"ACTIVE".equals(developer.get("status")))throw conflict("APP_DISABLED","应用或工作区已停用");
            String period=PortalApplicationService.period("NEXT_MONTH".equals(metadata.get("period_policy"))?db.nowUtc().atZone(java.time.ZoneOffset.UTC).plusMonths(1).toInstant():db.nowUtc());
            long api=n(db.need("SELECT l.api_id FROM fg_api_sku s JOIN fg_api_listing l ON l.id=s.listing_id WHERE s.id=?",sku),"api_id");
            db.update("INSERT INTO fg_api_entitlement_slot(application_id,api_id,period_yyyymm) VALUES(?,?,?) ON DUPLICATE KEY UPDATE application_id=application_id",app,api,period);
            var slot=db.need("SELECT * FROM fg_api_entitlement_slot WHERE application_id=? AND api_id=? AND period_yyyymm=? FOR UPDATE",app,api,period);
            if(!"AVAILABLE".equals(slot.get("state")))throw conflict("BASE_ENTITLEMENT_CONFLICT","该应用/API/月已开通或有待付款订单，请先查看订单");
            var offer=market.paidOffer(sku);var window=OrderStateMachine.window(period,s(offer,"period_policy"),db.nowUtc());
            if(db.one("SELECT id FROM fg_subscription WHERE application_id=? AND effective_at<? AND expires_at>? LIMIT 1",app,Timestamp.from(window.end()),Timestamp.from(window.start()))!=null)throw conflict("LEGACY_SUBSCRIPTION_CONFLICT","该月已有旧版订阅，请使用独立应用");
            // 2. 快照、待付单和HELD绑定同一事务；唯一请求键跨应用竞态由DB兜底并整笔回滚。
            long id=db.insert("INSERT INTO fg_order(order_no,principal_id,developer_id,application_id,api_id,sku_id,period_yyyymm,sku_revision,price_cents,units_snapshot,client_request_id,request_fingerprint,pay_deadline_at,effective_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),user,application.get("developer_id"),app,api,sku,period,offer.get("revision"),offer.get("price_cents"),offer.get("quota_units"),request,fingerprint,Timestamp.from(window.paymentDeadline()),Timestamp.from(window.start()),Timestamp.from(window.end()));
            if(db.update("UPDATE fg_api_entitlement_slot SET state='HELD',holder_type='ORDER',holder_id=?,version=version+1 WHERE application_id=? AND api_id=? AND period_yyyymm=? AND state='AVAILABLE' AND version=?",id,app,api,period,slot.get("version"))!=1)throw conflict("SLOT_CONFLICT","权益槽已变化");
            transition(id,null,"PENDING_PAYMENT","PORTAL",user,"ORDER_CREATED");return new Created(summary(db.need("SELECT * FROM fg_order WHERE id=?",id)),true);
        });}catch(DuplicateKeyException collision){var old=retry(user,request,fingerprint);if(old==null)throw collision;return new Created(summary(old),false);}
    }
    /** 输入只能是本地已授权订单；锁顺序统一供支付、关闭与Worker使用。 */
    public Map<String,Object> lock(Map<String,Object> seed){db.need("SELECT id FROM fg_application WHERE id=? FOR UPDATE",seed.get("application_id"));db.need("SELECT * FROM fg_api_entitlement_slot WHERE application_id=? AND api_id=? AND period_yyyymm=? FOR UPDATE",seed.get("application_id"),seed.get("api_id"),seed.get("period_yyyymm"));return db.need("SELECT * FROM fg_order WHERE id=? FOR UPDATE",seed.get("id"));}
    public long held(Map<String,Object> order){var slot=db.one("SELECT version FROM fg_api_entitlement_slot WHERE application_id=? AND api_id=? AND period_yyyymm=? AND state='HELD' AND holder_type='ORDER' AND holder_id=?",order.get("application_id"),order.get("api_id"),order.get("period_yyyymm"),order.get("id"));if(slot==null)throw conflict("ORDER_SLOT_CONFLICT","订单权益槽异常，需管理员检查");return n(slot,"version");}
    public Object pay(long user,String orderNo){var seed=get(user,orderNo);long id=n(seed,"id");return tx.execute(status->{var order=lock(seed);owner.lockAppOwner(user,n(order,"application_id"));String state=s(order,"status");if(OrderStateMachine.paid(state))return summary(order);OrderStateMachine.payable(state,instant(order,"pay_deadline_at"),db.nowUtc());held(order);
        // 3. 不调用Broker、不直接授权；收据+PAYMENT_CONFIRMED只在DB提交后由独立Worker投递。
        if(db.update("UPDATE fg_order SET status='PAID_PENDING_FULFILLMENT',version=version+1 WHERE id=? AND status='PENDING_PAYMENT' AND pay_deadline_at>UTC_TIMESTAMP(3)",id)!=1)throw conflict("ORDER_NOT_PAYABLE","已超过付款截止");
        db.insert("INSERT INTO fg_payment_record(order_id,provider_ref,amount_cents) VALUES(?,?,?)",id,UUID.randomUUID().toString(),order.get("price_cents"));
        var event=new CommerceEvent(1,UUID.randomUUID().toString(),"PAYMENT_CONFIRMED",id,n(order,"version")+1,db.nowUtc().toString());
        db.insert("INSERT INTO fg_business_outbox(event_id,event_type,aggregate_id,aggregate_revision,payload_json) VALUES(?,?,?,?,?)",event.eventId(),event.type(),id,event.orderVersion(),event.canonical());transition(id,state,"PAID_PENDING_FULFILLMENT","PORTAL",user,"SIMULATED_PAYMENT");return summary(db.need("SELECT * FROM fg_order WHERE id=?",id));
    });}
    public Object cancel(long user,String orderNo){var seed=get(user,orderNo);return tx.execute(status->summary((Map<String,Object>)closeLocked(lock(seed),false,user)));}
    public Object timeout(long id){return tx.execute(status->{var seed=db.need("SELECT * FROM fg_order WHERE id=?",id);return closeLocked(lock(seed),true,null);});}
    private Object closeLocked(Map<String,Object> order,boolean timeout,Long user){String before=s(order,"status");if(Set.of("CANCELLED","CLOSED_TIMEOUT").contains(before))return order;if(!"PENDING_PAYMENT".equals(before)){if(timeout)return order;throw conflict("ORDER_NOT_CANCELLABLE","已付款订单不能取消；未使用权益可申请模拟退款");}if(timeout&&db.nowUtc().isBefore(instant(order,"pay_deadline_at")))return order;long slotVersion=held(order);String next=timeout||!db.nowUtc().isBefore(instant(order,"pay_deadline_at"))?"CLOSED_TIMEOUT":"CANCELLED";
        if(db.update("UPDATE fg_order SET status=?,version=version+1 WHERE id=? AND status='PENDING_PAYMENT' AND version=?",next,order.get("id"),order.get("version"))!=1)throw conflict("ORDER_STATE_CONFLICT","订单状态已变化");
        if(db.update("UPDATE fg_api_entitlement_slot SET state='AVAILABLE',holder_type=NULL,holder_id=NULL,version=version+1 WHERE application_id=? AND api_id=? AND period_yyyymm=? AND state='HELD' AND holder_type='ORDER' AND holder_id=? AND version=?",order.get("application_id"),order.get("api_id"),order.get("period_yyyymm"),order.get("id"),slotVersion)!=1)throw conflict("ORDER_SLOT_CONFLICT","释放权益槽失败");
        transition(n(order,"id"),before,next,timeout?"SYSTEM":"PORTAL",user,next);return db.need("SELECT * FROM fg_order WHERE id=?",order.get("id"));}
    public void transition(long id,String from,String to,String actor,Long user,String reason){db.insert("INSERT INTO fg_order_transition(order_id,from_status,to_status,actor_type,actor_id,reason_code) VALUES(?,?,?,?,?,?)",id,from,to,actor,user,reason);}
    public static Instant instant(Map<String,Object> row,String key){return ((Timestamp)row.get(key)).toInstant();}
}
