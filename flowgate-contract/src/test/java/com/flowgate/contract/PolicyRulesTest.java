package com.flowgate.contract;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 发布边界拒绝坏规则，同时保持历史待执行策略及校验和兼容。 */
class PolicyRulesTest {
    Map<String,Object> policy(String type,String scope,String status,String json) {
        return Map.of("id",1,"code","rule","type",type,"scope",scope,"executionStatus",status,"enabled",true,"parametersJson",json);
    }
    Snapshot snapshot(Map<String,Object> policy,List<Map<String,Object>> bindings) {
        return SnapshotCodec.seal(new Snapshot(1,1,"",List.of(),List.of(),List.of(),List.of(policy),bindings));
    }
    @Test void validatesIntegerBoundsAndExactFields() {
        for(String json:List.of("{}","{\"capacity\":0,\"refillPerSecond\":1}","{\"capacity\":1.5,\"refillPerSecond\":1}","{\"capacity\":1000001,\"refillPerSecond\":1}"))
            assertThrows(IllegalArgumentException.class,()->PolicyRules.parameters("RATE_LIMIT",json));
        assertEquals(1,PolicyRules.parameters("RATE_LIMIT","{\"capacity\":1,\"refillPerSecond\":1}").get("capacity").intValue());
    }
    @Test void oldPendingSnapshotNeverExecutesAndChecksumIsStable() {
        var old=snapshot(policy("RATE_LIMIT","ROUTE","PENDING_BLOCK_2_3","{}"),List.of(Map.of("policyId",1,"targetType","ROUTE","targetId",99)));
        PolicyRules.validate(old); assertFalse(PolicyRules.enforced(old.policies().get(0)));
        var decoded=SnapshotCodec.JSON.readValue(SnapshotCodec.JSON.writeValueAsString(old),Snapshot.class);
        SnapshotCodec.verify(decoded); assertEquals(old.checksum(),decoded.checksum());
    }
    @Test void rejectsMissingRouteMappingDuplicatesAndUnsupportedConcurrencyBinding() {
        var route=policy("RATE_LIMIT","ROUTE",PolicyRules.ENFORCED,"{\"capacity\":1,\"refillPerSecond\":1}");
        assertThrows(IllegalArgumentException.class,()->PolicyRules.validate(snapshot(route,List.of(Map.of("policyId",1,"targetType","ROUTE","targetId",1)))));
        var global=policy("RATE_LIMIT","GLOBAL",PolicyRules.ENFORCED,"{\"capacity\":1,\"refillPerSecond\":1}");
        var binding=Map.<String,Object>of("policyId",1,"targetType","GLOBAL","targetId",0);
        assertThrows(IllegalArgumentException.class,()->PolicyRules.validate(snapshot(global,List.of(binding,binding))));
        var concurrency=policy("CONCURRENCY","CALLER",PolicyRules.ENFORCED,"{\"maxConcurrent\":1}");
        PolicyRules.validate(snapshot(concurrency,List.of()));
        assertThrows(IllegalArgumentException.class,()->PolicyRules.validate(snapshot(concurrency,List.of(Map.of("policyId",1,"targetType","CALLER","targetId",1)))));
    }
    @Test void validatesBlock3RangesAndGetOnlyFallback() {
        for(String json:List.of("{\"timeoutMs\":49}","{\"timeoutMs\":10001}")) assertThrows(IllegalArgumentException.class,()->PolicyRules.parameters("TIMEOUT",json));
        String base="{\"failureRateThreshold\":50,\"slidingWindowSize\":4,\"minimumNumberOfCalls\":2,\"waitDurationMs\":1000";
        PolicyRules.parameters("CIRCUIT_BREAKER",base+"}");
        PolicyRules.parameters("CIRCUIT_BREAKER",base+",\"fallbackMode\":\"STATIC_JSON\"}");
        assertThrows(IllegalArgumentException.class,()->PolicyRules.parameters("CIRCUIT_BREAKER",base+",\"fallbackMode\":\"SCRIPT\"}"));
        var route=new RouteSpec("echo","POST","/api/echo","/demo/echo","http://127.0.0.1:8085",true,"",0,1);
        var p=policy("CIRCUIT_BREAKER","ROUTE",PolicyRules.PROTECTED,base+",\"fallbackMode\":\"STATIC_JSON\"}");
        var snap=SnapshotCodec.seal(new Snapshot(1,1,"",List.of(),List.of(),List.of(route),List.of(p),List.of(Map.of("policyId",1,"targetType","ROUTE","targetId",2,"targetRouteId","echo"))));
        assertThrows(IllegalArgumentException.class,()->PolicyRules.validate(snap));
    }

    @Test void rejectsCircuitBoundsAndUnsupportedProtectionScope() {
        var valid=Map.of("failureRateThreshold",50,"slidingWindowSize",4,"minimumNumberOfCalls",2,"waitDurationMs",1000);
        for(var invalid:List.of(Map.of("failureRateThreshold",0),Map.of("failureRateThreshold",101),Map.of("slidingWindowSize",1),
                Map.of("slidingWindowSize",101),Map.of("minimumNumberOfCalls",0),Map.of("minimumNumberOfCalls",5),Map.of("waitDurationMs",499),Map.of("waitDurationMs",60001))) {
            var params=new HashMap<>(valid);params.putAll(invalid);
            assertThrows(IllegalArgumentException.class,()->PolicyRules.parameters("CIRCUIT_BREAKER",SnapshotCodec.JSON.writeValueAsString(params)));
        }
        var p=policy("TIMEOUT","GLOBAL",PolicyRules.PROTECTED,"{\"timeoutMs\":200}");
        assertThrows(IllegalArgumentException.class,()->PolicyRules.validate(snapshot(p,List.of(Map.of("policyId",1,"targetType","GLOBAL","targetId",0)))));
    }

}
