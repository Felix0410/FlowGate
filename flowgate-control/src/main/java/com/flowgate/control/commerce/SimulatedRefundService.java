package com.flowgate.control.commerce;

import com.flowgate.control.portal.*;
import com.flowgate.control.security.ApiFailure;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.flowgate.control.portal.PortalStore.*;

/** 只模拟退款：持APP→slot→order→订阅/余额锁，和Gateway相同APP锁串行；不改Ledger。 */
@Service
public class SimulatedRefundService {
    private final com.flowgate.control.promotion.PromotionOrderStockService promoStock;private final PortalStore db;private final OrderService orders;private final TransactionTemplate tx;
    public SimulatedRefundService(PortalStore db,OrderService orders,PlatformTransactionManager manager,com.flowgate.control.promotion.PromotionOrderStockService promoStock){this.promoStock=promoStock;this.db=db;this.orders=orders;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(8);}
    public Object refund(long user,String orderNo){var seed=orders.get(user,orderNo);return tx.execute(status->{
        // 1. 非锁定快照只定位，真正资格必须在订单锁后重读；已退款幂等返回原收据。
        var order=orders.lock(seed);String before=s(order,"status");long id=n(order,"id");if("REFUNDED".equals(before))return orders.summary(order);promoStock.lockRefund(order);
        if("FULFILLED".equals(before)){
            var sub=db.need("SELECT * FROM fg_api_subscription WHERE application_id=? AND api_id=? AND period_yyyymm=? FOR UPDATE",order.get("application_id"),order.get("api_id"),order.get("period_yyyymm"));
            var balance=db.need("SELECT * FROM fg_api_quota_balance WHERE subscription_id=? FOR UPDATE",sub.get("id"));
            if(!orders.canRefund(order)||n(balance,"remaining_units")!=n(balance,"total_units"))throw new ApiFailure(409,"USED_ENTITLEMENT_NON_REFUNDABLE","该权益已授权过调用，不能模拟退款；已扣额度不回滚");
            // 2. 已Grant的BASE槽永不回售，历史Grant/余额与Ledger保持原样，仅暂停订阅。
            if(db.one("SELECT application_id FROM fg_api_entitlement_slot WHERE application_id=? AND api_id=? AND period_yyyymm=? AND state='GRANTED' AND holder_type='ORDER' AND holder_id=?",order.get("application_id"),order.get("api_id"),order.get("period_yyyymm"),id)==null)throw new ApiFailure(409,"ORDER_SLOT_CONFLICT","原订单权益槽不一致");
            db.update("UPDATE fg_api_subscription SET status='SUSPENDED' WHERE id=?",sub.get("id"));
        }else if(Set.of("PAID_PENDING_FULFILLMENT","FULFILLMENT_RETRY","FULFILLMENT_REVIEW").contains(before)){
            if(db.one("SELECT id FROM fg_entitlement_grant WHERE source_type='PAID_ORDER' AND source_id=?",id)!=null)throw new ApiFailure(409,"ORDER_GRANT_CONFLICT","原单已有权益来源，需运营检查");
            long slotVersion=orders.held(order);
            if(db.update("UPDATE fg_api_entitlement_slot SET state='AVAILABLE',holder_type=NULL,holder_id=NULL,version=version+1 WHERE application_id=? AND api_id=? AND period_yyyymm=? AND state='HELD' AND holder_type='ORDER' AND holder_id=? AND version=?",order.get("application_id"),order.get("api_id"),order.get("period_yyyymm"),id,slotVersion)!=1)throw new ApiFailure(409,"ORDER_SLOT_CONFLICT","释放原订单权益槽失败");
        }else throw new ApiFailure(409,"ORDER_NOT_REFUNDABLE","只有已模拟付款的未用权益可退款；未付款请取消订单");
        // 3. 唯一模拟退款、终态和追加审计同提交；晚到Worker只可记录忽略终态。
        var payment=db.need("SELECT amount_cents FROM fg_payment_record WHERE order_id=?",id);if(n(payment,"amount_cents")!=n(order,"price_cents"))throw new ApiFailure(409,"PAYMENT_CONFLICT","收据金额不一致");
        db.insert("INSERT INTO fg_simulated_refund(order_id,refund_ref,amount_cents,reason_code) VALUES(?,?,?,?)",id,UUID.randomUUID().toString(),order.get("price_cents"),"SIMULATED_UNUSED_REFUND");
        promoStock.refunded(order);
        db.update("UPDATE fg_order SET status='REFUNDED',version=version+1 WHERE id=? AND status=?",id,before);orders.transition(id,before,"REFUNDED","PORTAL",user,"SIMULATED_UNUSED_REFUND");return orders.summary(db.need("SELECT * FROM fg_order WHERE id=?",id));
    });}
}
