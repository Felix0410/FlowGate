package com.flowgate.gateway.protection;

import com.flowgate.contract.*;
import java.util.*;

/** 配置构建时解析同版保护规则；历史PENDING不执行，热路径不访问控制面。 */
public final class ProtectionPolicyResolver {
    public static final String METADATA="flowgateProtection";
    private ProtectionPolicyResolver() {}
    public record Plan(int timeoutMs,String breakerName,String revision,String fallbackMode,String parametersJson) {}
    public static Plan freeze(Snapshot snapshot,RouteSpec route) {
        // 1. 超时按SERVICE/API/ROUTE取最小；CB只精确匹配稳定routeId。
        var api=snapshot.apis().stream().filter(a->route.method().equals(a.get("method")) && route.publicPath().equals(a.get("publicPath"))).findFirst().orElse(Map.of());
        int timeout=0; String name="",revision="",fallback="NONE",json="";
        for(var p:snapshot.policies()) {
            if(!Boolean.TRUE.equals(p.get("enabled")) || !PolicyRules.PROTECTED.equals(p.get("executionStatus"))) continue;
            for(var b:snapshot.bindings()) {
                if(PolicyRules.id(b,"policyId")!=PolicyRules.id(p,"id")) continue;
                boolean matches=switch((String)b.get("targetType")) {
                    case "SERVICE"->api.containsKey("serviceId") && String.valueOf(api.get("serviceId")).equals(String.valueOf(b.get("targetId")));
                    case "API"->api.containsKey("id") && String.valueOf(api.get("id")).equals(String.valueOf(b.get("targetId")));
                    case "ROUTE"->route.routeId().equals(b.get("targetRouteId"));
                    default->false;
                };
                if(!matches) continue;
                var params=PolicyRules.parameters((String)p.get("type"),(String)p.get("parametersJson"));
                if("TIMEOUT".equals(p.get("type"))) {
                    int value=params.get("timeoutMs").intValue(); timeout=timeout==0?value:Math.min(timeout,value);
                } else if("CIRCUIT_BREAKER".equals(p.get("type"))) {
                    // 2. 显式NONE与旧四参数产生相同摘要，无关发布不清空熔断状态。
                    var normalized=SnapshotCodec.JSON.valueToTree(params);
                    ((tools.jackson.databind.node.ObjectNode)normalized).put("fallbackMode",params.has("fallbackMode")?params.get("fallbackMode").stringValue():"NONE");
                    json=SnapshotCodec.JSON.writeValueAsString(SnapshotCodec.canonical(normalized));
                    revision=SnapshotCodec.digest(json).substring(0,32);
                    name="fg-cb-"+p.get("id")+"-"+revision+"-"+route.routeId();
                    fallback=params.has("fallbackMode")?params.get("fallbackMode").stringValue():"NONE";
                }
            }
        }
        // 3. Plan随Route冻结，旧请求持旧参数/名称，新版本不从全局指针替换。
        return new Plan(timeout,name,revision,fallback,json);
    }
}
