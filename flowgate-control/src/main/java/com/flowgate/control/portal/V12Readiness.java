package com.flowgate.control.portal;

import com.flowgate.contract.*;
import com.flowgate.control.service.*;
import com.flowgate.control.security.ApiFailure;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 新模式沿用完整B门禁，并增加真实V12节点能力及全部新表检查。 */
@Service
public class V12Readiness {
    private final QuotaActivationService activation;private final PortalStore db;
    private final boolean enabled;private final Set<String> traffic;
    public V12Readiness(QuotaActivationService activation,PortalStore db,@Value("${FLOWGATE_V12_QUOTA_ENABLED:false}") boolean enabled,@Value("${FLOWGATE_QUOTA_TRAFFIC_NODES:}") String nodes){this.activation=activation;this.db=db;this.enabled=enabled;this.traffic=new HashSet<>();for(var node:nodes.split(","))if(!node.isBlank())traffic.add(node.trim());}
    public void enabled(){if(!enabled||!activation.enabled())throw new ApiFailure(503,"V12_QUOTA_DISABLED","API权益计量未启用");}
    public void requireNodes(long version,String checksum){
        // 1. 旧B开关、完整名单、心跳、应用版本和校验和全部沿用，不以单个节点在线代替。
        enabled();activation.requireReady(version,checksum);
        for(String node:traffic)if(!activation.features(node).contains(QuotaPolicy.FEATURE_V12))throw new ApiFailure(409,"V12_GATEWAY_NOT_READY","接流节点尚未全部支持API权益计量");
        // 2. INFORMATION_SCHEMA只读核对，不在用户库自动迁移。
        var row=db.need("SELECT COUNT(*) AS amount FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('fg_portal_user','fg_developer_member','fg_api_category','fg_api_listing','fg_api_sku','fg_portal_free_claim','fg_api_entitlement_slot','fg_entitlement_grant','fg_api_subscription','fg_api_quota_balance','fg_api_quota_ledger','fg_api_usage_outbox','fg_api_consumed_event','fg_api_usage_daily')");
        if(PortalStore.n(row,"amount")!=14)throw new ApiFailure(409,"V12_SCHEMA_NOT_READY","隔离数据库尚未安装完整API权益结构");
    }
    public Snapshot current(){var row=db.need("SELECT r.snapshot_json FROM fg_config_release r JOIN fg_config_state s ON s.current_version=r.version WHERE s.id=1");var snapshot=SnapshotCodec.JSON.readValue(PortalStore.s(row,"snapshot_json"),Snapshot.class);SnapshotCodec.verify(snapshot);return snapshot;}
    public List<RouteSpec> api(long id){var snapshot=current();requireNodes(snapshot.version(),snapshot.checksum());var routes=snapshot.routes().stream().filter(r->{var p=QuotaPolicy.freeze(snapshot,r);return p.mode()==QuotaPolicy.Mode.V12_APP_API&&p.apiId()==id;}).toList();if(routes.isEmpty())throw new ApiFailure(409,"V12_API_NOT_READY","该API尚无已应用的私有API权益计量路由");return routes;}
}
