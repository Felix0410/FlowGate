package com.flowgate.control.promotion;

import com.flowgate.control.portal.PortalStore;
import com.flowgate.control.commerce.CommerceReadiness;
import com.flowgate.control.security.ApiFailure;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** OFF与缺表只返回明确未就绪；C不向已有库自动迁移。 */
@Service
public class PromotionReadiness {
    private final PortalStore db;private final CommerceReadiness commerce;private final boolean enabled;
    public PromotionReadiness(PortalStore db,CommerceReadiness commerce,@Value("${FLOWGATE_V12_PROMOTION_ENABLED:false}")boolean enabled){this.db=db;this.commerce=commerce;this.enabled=enabled;}
    public Map<String,Object> status(){
        if(!enabled)return Map.of("status","DISABLED","message","限时活动尚未开启，普通订单和免费领取照常可用。");
        try{
            long count=PortalStore.n(db.need("SELECT COUNT(*) amount FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('fg_promotion','fg_promo_stock','fg_promo_purchase_intent','fg_promo_outbox','fg_promo_event_consumed')"),"amount");
            long check=PortalStore.n(db.need("SELECT COUNT(*) amount FROM information_schema.TABLE_CONSTRAINTS WHERE table_schema=DATABASE() AND table_name='fg_order' AND constraint_name='ck_fg_order_source_c'"),"amount");
            if(count!=5||check!=1)return Map.of("status","SCHEMA_NOT_READY","message","请先对授权的新隔离库安装C五表和订单兼容约束。");
            if(!"READY".equals(commerce.schema().get("status")))return Map.of("status","PREREQUISITE_NOT_READY","message","先完成A/B权益和普通订单配置。");
            return Map.of("status","READY","message","活动结构就绪，发布和抢购仍实时核验。");
        }catch(org.springframework.dao.DataAccessException unavailable){return Map.of("status","UNKNOWN","message","数据库状态暂不可知");}
    }
    public boolean available(){return "READY".equals(status().get("status"));}
    public void require(){var state=status();if(!"READY".equals(state.get("status")))throw new ApiFailure(503,state.get("status").toString(),state.get("message").toString());}
}
