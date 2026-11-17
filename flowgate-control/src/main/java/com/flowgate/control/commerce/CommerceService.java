package com.flowgate.control.commerce;

import com.flowgate.control.portal.*;
import com.flowgate.control.security.ApiFailure;
import com.flowgate.control.service.AuditService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.flowgate.control.portal.PortalStore.*;

/** 业务消息独立于用量消息。持订单锁核验原始收据，权益、状态、消费标记同提交。 */
@Service
public class CommerceService {
    private final long started=System.nanoTime();private final PortalStore db;private final OrderService orders;private final EntitlementGrantService grants;private final V12Readiness apiReadiness;private final AuditService audit;private final TransactionTemplate tx;private final TransactionTemplate read;
    public CommerceService(PortalStore db,OrderService orders,EntitlementGrantService grants,AuditService audit,PlatformTransactionManager manager,V12Readiness apiReadiness){this.apiReadiness=apiReadiness;this.db=db;this.orders=orders;this.grants=grants;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(8);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);read.setTimeout(4);}
    private ApiFailure conflict(){return new ApiFailure(409,"COMMERCE_EVENT_CONFLICT","业务事件与本地订单、收据或槽不一致");}
    public String process(String payload){orders.enabled();var event=CommerceEvent.parse(payload);return tx.execute(status->{
        var source=db.one("SELECT * FROM fg_business_outbox WHERE event_id=? AND aggregate_id=?",event.eventId(),event.orderId());
        if(source==null||!event.equals(CommerceEvent.parse(s(source,"payload_json")))||!event.type().equals(source.get("event_type"))||n(source,"event_version")!=1||n(source,"aggregate_revision")!=event.orderVersion())throw conflict();
        var order=orders.lock(db.need("SELECT * FROM fg_order WHERE id=?",event.orderId()));
        var receipt=db.one("SELECT * FROM fg_payment_record WHERE order_id=?",event.orderId());
        if(receipt==null||n(receipt,"amount_cents")!=n(order,"price_cents")||!"SIM".equals(receipt.get("provider")))throw conflict();
        var consumed=db.one("SELECT * FROM fg_business_event_consumed WHERE event_id=? OR aggregate_id=?",event.eventId(),event.orderId());
        if(consumed!=null){if(!event.eventId().equals(consumed.get("event_id"))||!event.fingerprint().equals(consumed.get("payload_sha256")))throw conflict();return "DUPLICATE";}
        String before=s(order,"status");
        if(Set.of("REFUNDED","CANCELLED","CLOSED_TIMEOUT").contains(before)){consumed(event,"IGNORED_TERMINAL");return "IGNORED_TERMINAL";}
        if("FULFILLED".equals(before)){var grant=db.one("SELECT id FROM fg_entitlement_grant WHERE source_type='PAID_ORDER' AND source_id=?",event.orderId());if(grant==null)throw conflict();consumed(event,"ALREADY_APPLIED");return "ALREADY_APPLIED";}if(!Set.of("PAID_PENDING_FULFILLMENT","FULFILLMENT_RETRY").contains(before))throw conflict();long slotVersion=orders.held(order);
        // 1. 已经过期的月份保留HELD与付款事实，进入人工检查；不能偷偷滚到下个月。
        boolean routeReady=true;try{apiReadiness.api(n(order,"api_id"));}catch(ApiFailure blocked){if(blocked.status>=500)throw blocked;routeReady=false;}
        if(!routeReady||!db.nowUtc().isBefore(OrderService.instant(order,"expires_at"))){db.update("UPDATE fg_order SET status='FULFILLMENT_REVIEW',version=version+1 WHERE id=?",event.orderId());orders.transition(event.orderId(),before,"FULFILLMENT_REVIEW","WORKER",null,routeReady?"PERIOD_EXPIRED_REVIEW":"API_NOT_READY_REVIEW");}
        else{
            grants.grant("PAID_ORDER",event.orderId(),n(order,"application_id"),n(order,"api_id"),s(order,"period_yyyymm"),n(order,"units_snapshot"),OrderService.instant(order,"effective_at"),OrderService.instant(order,"expires_at"));
            if(db.update("UPDATE fg_api_entitlement_slot SET state='GRANTED',version=version+1 WHERE application_id=? AND api_id=? AND period_yyyymm=? AND state='HELD' AND holder_type='ORDER' AND holder_id=? AND version=?",order.get("application_id"),order.get("api_id"),order.get("period_yyyymm"),event.orderId(),slotVersion)!=1)throw conflict();
            db.update("UPDATE fg_order SET status='FULFILLED',version=version+1 WHERE id=?",event.orderId());orders.transition(event.orderId(),before,"FULFILLED","WORKER",null,"ENTITLEMENT_GRANTED");
        }
        // 2. 唯一Grant与消费标记都受同一应用锁保护，提交之前绝不ACK。
        consumed(event,"APPLIED");return "CONSUMED";
    });}
    private void consumed(CommerceEvent event,String result){db.update("INSERT INTO fg_business_event_consumed(event_id,aggregate_id,payload_sha256,result) VALUES(?,?,?,?)",event.eventId(),event.orderId(),event.fingerprint(),result);}
    public List<Map<String,Object>> claim(int limit){orders.enabled();return tx.execute(status->{var rows=db.rows("SELECT * FROM fg_business_outbox WHERE (status IN ('NEW','FAILED') AND next_attempt_at<=UTC_TIMESTAMP(3)) OR (status='IN_FLIGHT' AND lease_until<UTC_TIMESTAMP(3)) ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",Math.min(2,Math.max(1,limit)));for(var row:rows){String owner=UUID.randomUUID().toString();db.update("UPDATE fg_business_outbox SET status='IN_FLIGHT',lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND),attempt_count=attempt_count+1 WHERE id=?",owner,row.get("id"));row.put("lease_owner",owner);}return rows;});}
    public void sent(Map<String,Object> row){db.update("UPDATE fg_business_outbox SET status='SENT',lease_owner=NULL,lease_until=NULL,last_error_code=NULL,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 300 SECOND) WHERE id=? AND status='IN_FLIGHT' AND lease_owner=?",row.get("id"),row.get("lease_owner"));}
    public void failed(Map<String,Object> row){failed(row,false);}
    public void failed(Map<String,Object> row,boolean permanent){db.update("UPDATE fg_business_outbox SET status=?,lease_owner=NULL,lease_until=NULL,last_error_code=?,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL LEAST(30,POW(2,LEAST(attempt_count,5))) SECOND) WHERE id=? AND status='IN_FLIGHT' AND lease_owner=?",permanent?"FAILED_PERMANENT":"FAILED",permanent?"INVALID_OUTBOX_PERMANENT":"RABBIT_PUBLISH_UNAVAILABLE",row.get("id"),row.get("lease_owner"));}
    public Object adminOrders(long after,int limit,String state){orders.enabled();if(state!=null&&!Set.of("PENDING_PAYMENT","PAID_PENDING_FULFILLMENT","FULFILLMENT_RETRY","FULFILLMENT_REVIEW","FULFILLED","CANCELLED","CLOSED_TIMEOUT","REFUNDED").contains(state))throw new IllegalArgumentException("INVALID_ORDER_STATUS");com.flowgate.control.service.BusinessInputs.page(after,limit);return db.rows("SELECT id,order_no,principal_id,application_id,api_id,period_yyyymm,price_cents,units_snapshot,status,expires_at,created_at FROM fg_order WHERE id>? AND (? IS NULL OR status=?) ORDER BY id LIMIT ?",after,state,state,limit);}
    public Object events(long after,int limit){orders.enabled();com.flowgate.control.service.BusinessInputs.page(after,limit);return db.rows("SELECT o.id,o.event_id,o.aggregate_id,o.status,o.attempt_count,o.last_error_code,o.next_attempt_at,c.consumed_at FROM fg_business_outbox o LEFT JOIN fg_business_event_consumed c ON c.event_id=o.event_id WHERE o.id>? ORDER BY o.id LIMIT ?",after,limit);}
    public Object replay(String id,long actor){orders.enabled();if(System.nanoTime()-started<java.util.concurrent.TimeUnit.SECONDS.toNanos(300))throw new ApiFailure(409,"REPLAY_COOLDOWN","进程启动后300秒内禁止重排");if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();return tx.execute(status->{var row=db.need("SELECT o.*,c.event_id AS consumed,(o.next_attempt_at<=UTC_TIMESTAMP(3)) AS waited FROM fg_business_outbox o LEFT JOIN fg_business_event_consumed c ON c.event_id=o.event_id WHERE o.event_id=? FOR UPDATE",id);
        if(!"SENT".equals(row.get("status"))||row.get("consumed")!=null||n(row,"waited")!=1)throw new ApiFailure(409,"REPLAY_NOT_ELIGIBLE","仅SENT超过300秒且未消费事件可重排");
        if(db.update("UPDATE fg_business_outbox SET status='NEW',next_attempt_at=UTC_TIMESTAMP(3) WHERE id=? AND status='SENT'",row.get("id"))!=1)throw conflict();audit.record(actor,"REPLAY_COMMERCE_EVENT","commerce",id,null,"原事件补投；不重复模拟付款");return Map.of("status","QUEUED","eventId",id);
    });}
    public Object adminDetail(String orderNo){orders.enabled();var row=db.need("SELECT principal_id FROM fg_order WHERE order_no=?",orderNo);return orders.detail(n(row,"principal_id"),orderNo);}
    public Object reconcile(java.time.LocalDate day){orders.enabled();return read.execute(status->{
        // 3. 首版完整性审计最多1000个对象；超过范围明确UNKNOWN，不先全表COUNT再假称有界。
        var all=db.rows("SELECT * FROM fg_order ORDER BY id LIMIT 1001");var slots=db.rows("SELECT * FROM fg_api_entitlement_slot WHERE holder_type='ORDER' ORDER BY application_id,api_id,period_yyyymm LIMIT 1001");var grants=db.rows("SELECT * FROM fg_entitlement_grant WHERE source_type='PAID_ORDER' ORDER BY id LIMIT 1001");
        if(all.size()>1000||slots.size()>1000||grants.size()>1000)return Map.of("status","UNKNOWN","reason","超过首版1000对象完整性检查范围");
        long gaps=0,pending=0,review=0;var byId=new HashMap<Long,Map<String,Object>>();for(var order:all)byId.put(n(order,"id"),order);
        for(var order:all){long id=n(order,"id");String state=s(order,"status");var payment=db.one("SELECT * FROM fg_payment_record WHERE order_id=?",id);var outbox=db.one("SELECT * FROM fg_business_outbox WHERE aggregate_id=?",id);var grant=db.one("SELECT * FROM fg_entitlement_grant WHERE source_type='PAID_ORDER' AND source_id=?",id);var refund=db.one("SELECT * FROM fg_simulated_refund WHERE order_id=?",id);
            boolean paid=OrderStateMachine.paid(state)||"REFUNDED".equals(state);
            if(paid&&(payment==null||outbox==null))gaps++;if(payment!=null&&(n(payment,"amount_cents")!=n(order,"price_cents")||!paid))gaps++;
            if("REFUNDED".equals(state)!=(refund!=null))gaps++;
            if(outbox!=null){try{var e=CommerceEvent.parse(s(outbox,"payload_json"));if(e.orderId()!=id||!e.eventId().equals(outbox.get("event_id"))||e.orderVersion()!=n(outbox,"aggregate_revision"))gaps++;var consumed=db.one("SELECT * FROM fg_business_event_consumed WHERE event_id=?",e.eventId());if(consumed!=null&&!e.fingerprint().equals(consumed.get("payload_sha256")))gaps++;}catch(IllegalArgumentException invalid){gaps++;}}
            var slot=db.one("SELECT * FROM fg_api_entitlement_slot WHERE application_id=? AND api_id=? AND period_yyyymm=?",order.get("application_id"),order.get("api_id"),order.get("period_yyyymm"));
            boolean owns=slot!=null&&"ORDER".equals(slot.get("holder_type"))&&Objects.equals(((Number)slot.get("holder_id")).longValue(),id);
            if(!Set.of("CANCELLED","CLOSED_TIMEOUT","REFUNDED").contains(state)&&(!owns||!("FULFILLED".equals(state)?"GRANTED":"HELD").equals(slot.get("state"))))gaps++;
            if("FULFILLED".equals(state)&&grant==null)gaps++;
            if(grant!=null){var sub=db.one("SELECT s.*,b.total_units,b.remaining_units FROM fg_api_subscription s LEFT JOIN fg_api_quota_balance b ON b.subscription_id=s.id WHERE s.grant_id=?",grant.get("id"));if(sub==null||sub.get("total_units")==null||n(sub,"units_snapshot")!=n(order,"units_snapshot")||n(sub,"total_units")!=n(order,"units_snapshot")||n(grant,"application_id")!=n(order,"application_id")||n(grant,"api_id")!=n(order,"api_id")||!Objects.equals(grant.get("period_yyyymm"),order.get("period_yyyymm"))||!("FULFILLED".equals(state)||"REFUNDED".equals(state)))gaps++;else if("REFUNDED".equals(state)&&(!"SUSPENDED".equals(sub.get("status"))||!owns||!"GRANTED".equals(slot.get("state"))))gaps++;}
            if(Set.of("PAID_PENDING_FULFILLMENT","FULFILLMENT_RETRY").contains(state))pending++;if("FULFILLMENT_REVIEW".equals(state))review++;
        }
        for(var slot:slots){var order=byId.get(n(slot,"holder_id"));if(order==null||n(slot,"application_id")!=n(order,"application_id")||n(slot,"api_id")!=n(order,"api_id")||!Objects.equals(slot.get("period_yyyymm"),order.get("period_yyyymm"))||Set.of("CANCELLED","CLOSED_TIMEOUT").contains(s(order,"status"))||("REFUNDED".equals(order.get("status"))&&"HELD".equals(slot.get("state"))))gaps++;}
        for(var grant:grants)if(!byId.containsKey(n(grant,"source_id")))gaps++;
        boolean current=!day.isBefore(db.nowUtc().atZone(java.time.ZoneOffset.UTC).toLocalDate());return Map.of("status",gaps>0?"INTEGRITY_GAP":review>0?"REVIEW":pending>0?"PENDING":current?"PROVISIONAL":"MATCH","dayUtc",day.toString(),"scope","全局有界订单完整性；不作为按日资金结算","orders",all.size(),"integrityGaps",gaps,"paidPending",pending,"review",review,"simulationOnly",true);
    });}
}
