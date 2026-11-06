package com.flowgate.gateway.admission;

import com.flowgate.contract.*;
import com.flowgate.gateway.policy.EffectivePolicyResolver;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 同版路由元数据冻结、五级顺序及稳定参数摘要的实际契约。 */
class PolicyResolverTest {
    Snapshot snapshot(long version,int capacity) {
        var scopes=List.of("CALLER","ROUTE","API","SERVICE","GLOBAL");
        var policies=new ArrayList<Map<String,Object>>(); var bindings=new ArrayList<Map<String,Object>>();
        for(int i=0;i<scopes.size();i++) {
            String scope=scopes.get(i); long id=i+1;
            policies.add(Map.of("id",id,"code",scope,"type","RATE_LIMIT","scope",scope,"enabled",true,
                    "executionStatus",PolicyRules.ENFORCED,"parametersJson","{\"refillPerSecond\":1,\"capacity\":"+capacity+"}"));
            bindings.add(Map.of("policyId",id,"targetType",scope,"targetId",scope.equals("GLOBAL")?0:scope.equals("SERVICE")?10:scope.equals("API")?20:30,"targetRouteId","slow"));
        }
        return SnapshotCodec.seal(new Snapshot(1,version,"",List.of(Map.of("id",10,"enabled",true)),
                List.of(Map.of("id",20,"serviceId",10,"method","GET","publicPath","/api/slow","enabled",true)),
                List.of(new RouteSpec("slow","GET","/api/slow","/demo/slow","http://127.0.0.1:8085",false,"demo:read",0,version)),policies,bindings));
    }
    @Test void freezesFiveLevelsAndDoesNotResetRevisionOnUnrelatedPublish() {
        var v1=snapshot(1,2); PolicyRules.validate(v1);
        var old=EffectivePolicyResolver.freeze(v1,v1.routes().get(0));
        assertEquals(List.of("GLOBAL","SERVICE","API","ROUTE","CALLER"),old.stream().map(r->r.scope()).toList());
        assertEquals("slow",old.get(3).target()); assertEquals("30",old.get(4).target());
        var v2=snapshot(2,2); var next=EffectivePolicyResolver.freeze(v2,v2.routes().get(0));
        assertEquals(old.stream().map(r->r.key()).toList(),next.stream().map(r->r.key()).toList());
        var v3=snapshot(3,3); var changed=EffectivePolicyResolver.freeze(v3,v3.routes().get(0));
        assertNotEquals(old.get(0).key(),changed.get(0).key()); assertEquals(2,old.get(0).capacity());
    }
}
