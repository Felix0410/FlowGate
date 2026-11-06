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
}
