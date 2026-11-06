package com.flowgate.gateway.policy;

import com.flowgate.contract.*;
import com.flowgate.gateway.admission.LocalConcurrencyLimiter;
import java.util.*;

/** 构建 Route 时冻结同版本规则；请求不读取可变全局 candidate/applied。 */
public final class EffectivePolicyResolver {
    public static final String METADATA = "flowgatePolicies";
    private static final List<String> ORDER=List.of("GLOBAL","SERVICE","API","ROUTE","CALLER");
    private EffectivePolicyResolver() {}
    public record Rule(String policyId,String code,String type,String scope,String target,String revision,
            int capacity,int refillPerSecond,int maxConcurrent,LocalConcurrencyLimiter.Pool pool) {
        public String key() { return "fg:rl:"+policyId+":"+revision+":"+scope+":"+target; }
    }
    /** 稳定参数摘要使无关发布不清空配额；Caller 目标为内部 principalId。 */
    public static List<Rule> freeze(Snapshot snapshot, RouteSpec route) {
        // 1. API 与服务由同版 method/publicPath 定位，绝不把数据库数字 ID 当 routeId。
        var api=snapshot.apis().stream().filter(a -> route.method().equals(a.get("method")) && route.publicPath().equals(a.get("publicPath"))).findFirst().orElse(Map.of());
        var result=new ArrayList<Rule>();
        for(var policy:snapshot.policies()) {
            if(!PolicyRules.enforced(policy)) continue;
            for(var binding:snapshot.bindings()) {
                if(PolicyRules.id(binding,"policyId")!=PolicyRules.id(policy,"id")) continue;
                String scope=(String)policy.get("scope"), target=String.valueOf(binding.get("targetId"));
                boolean matches=switch(scope) {
                    case "GLOBAL" -> true;
                    case "SERVICE" -> api.containsKey("serviceId") && target.equals(String.valueOf(api.get("serviceId")));
                    case "API" -> api.containsKey("id") && target.equals(String.valueOf(api.get("id")));
                    case "ROUTE" -> route.routeId().equals(binding.get("targetRouteId"));
                    case "CALLER" -> !route.isPublic();
                    default -> false;
                };
                if(!matches) continue;
                // 2. 规则与许可对象随 Route 保留，旧请求始终归还自己获取的旧对象。
                if(scope.equals("GLOBAL")) target="global";
                if(scope.equals("ROUTE")) target=route.routeId();
                String type=(String)policy.get("type");
                var parameters=PolicyRules.parameters(type,(String)policy.get("parametersJson"));
                String revision=SnapshotCodec.digest(type+":"+scope+":"+SnapshotCodec.JSON.writeValueAsString(SnapshotCodec.canonical(parameters))).substring(0,32);
                String id=String.valueOf(policy.get("id"));
                int max=type.equals("CONCURRENCY") ? parameters.get("maxConcurrent").intValue() : 0;
                var pool=max>0 ? LocalConcurrencyLimiter.pool(id+":"+revision+":"+scope+":"+target,max) : null;
                result.add(new Rule(id,(String)policy.get("code"),type,scope,target,revision,
                        max==0 ? parameters.get("capacity").intValue():0,max==0 ? parameters.get("refillPerSecond").intValue():0,max,pool));
            }
        }
        // 3. 固定分级顺序；每一维独立扣减，不承诺多 Key 原子回滚。
        result.sort(Comparator.comparingInt(r -> ORDER.indexOf(r.scope())));
        return List.copyOf(result);
    }
}
