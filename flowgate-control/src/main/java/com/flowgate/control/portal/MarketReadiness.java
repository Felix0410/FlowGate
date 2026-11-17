package com.flowgate.control.portal;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.flowgate.control.security.ApiFailure;

/** 只查INFORMATION_SCHEMA；旧V11无V12表时也能安全读取新入口。 */
@Service
public class MarketReadiness {
    private final PortalStore db;private final boolean enabled;
    public MarketReadiness(PortalStore db,@Value("${FLOWGATE_PORTAL_ENABLED:false}")boolean enabled){this.db=db;this.enabled=enabled;}
    public Map<String,Object> status(){if(!enabled)return Map.of("status","DISABLED","message","Portal默认关闭；请参考隔离环境指南，不迁移用户旧数据库。");try{long count=PortalStore.n(db.need("SELECT COUNT(*) AS amount FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('fg_portal_user','fg_developer_member','fg_api_category','fg_api_listing','fg_api_sku','fg_portal_free_claim','fg_api_entitlement_slot','fg_entitlement_grant','fg_api_subscription','fg_api_quota_balance','fg_api_quota_ledger','fg_api_usage_outbox','fg_api_consumed_event','fg_api_usage_daily')"),"amount");return Map.of("status",count==14?"READY":"SCHEMA_NOT_READY","message",count==14?"市场结构已安装；上架/领取仍核验真实路由能力。":"隔离库未安装完整V12-A十四表，请先查看指南。");}catch(org.springframework.dao.DataAccessException unavailable){return Map.of("status","UNKNOWN","message","数据库就绪状态暂不可知");}}
    public void require(){var state=status();if(!"READY".equals(state.get("status")))throw new ApiFailure(503,state.get("status").toString(),state.get("message").toString());}
}
