package com.flowgate.control.promotion;

import com.flowgate.control.portal.PortalStore;
import com.flowgate.control.security.ApiFailure;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.flowgate.control.portal.PortalStore.*;

/** B订单事务中的最小来源扩展。调用方已持APP→Slot→Order，库存永远最后锁。 */
@Service
public class PromotionOrderStockService {
    private final PortalStore db;
    public PromotionOrderStockService(PortalStore db){this.db=db;}
    private boolean promo(Map<String,Object> order){return "PROMOTION".equals(order.get("source"));}
    private Map<String,Object> intent(Map<String,Object> order,String status){var row=db.need("SELECT * FROM fg_promo_purchase_intent WHERE id=? FOR UPDATE",order.get("promo_intent_id"));if(n(row,"order_id")!=n(order,"id")||!status.equals(row.get("status")))throw conflict();return row;}
    public void paid(Map<String,Object> order){if(!promo(order))return;var i=intent(order,"ORDER_READY");if(db.update("UPDATE fg_promo_stock SET reserved=reserved-1,sold=sold+1,version=version+1 WHERE promotion_id=? AND reserved>=1",i.get("promotion_id"))!=1)throw conflict();db.update("UPDATE fg_promo_purchase_intent SET status='PAID',version=version+1 WHERE id=?",i.get("id"));}
    public void closed(Map<String,Object> order){if(!promo(order))return;var i=intent(order,"ORDER_READY");if(db.update("UPDATE fg_promo_stock SET reserved=reserved-1,version=version+1 WHERE promotion_id=? AND reserved>=1",i.get("promotion_id"))!=1)throw conflict();db.update("UPDATE fg_promo_purchase_intent SET status='CLOSED',version=version+1 WHERE id=?",i.get("id"));}
    public void lockRefund(Map<String,Object> order){if(promo(order))intent(order,"PAID");}
    public void refunded(Map<String,Object> order){if(!promo(order))return;if(db.update("UPDATE fg_promo_purchase_intent SET status='REFUNDED',version=version+1 WHERE id=? AND status='PAID' AND order_id=?",order.get("promo_intent_id"),order.get("id"))!=1)throw conflict();/* 累计sold不返售；不改Ledger、Grant或历史余额。 */}
    private ApiFailure conflict(){return new ApiFailure(409,"PROMOTION_STOCK_CONFLICT","活动受理或库存状态不一致，请由运营检查");}
}
