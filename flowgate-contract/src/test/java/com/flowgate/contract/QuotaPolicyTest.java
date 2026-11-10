package com.flowgate.contract;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** 配额标记、同路由唯一和旧快照NONE，与原令牌桶模型完全分开。 */
class QuotaPolicyTest {
 Snapshot sample(boolean publicApi,boolean duplicate){
  var route=new RouteSpec("metered","GET","/api/private/{id}","/demo/fast/{id}","http://127.0.0.1:8085",publicApi,"demo:read",0,1);
  var policy=Map.<String,Object>of("id",1,"code","quota","type","USAGE_QUOTA","scope","ROUTE","enabled",true,"executionStatus",PolicyRules.QUOTA,"parametersJson","{\"unitsPerCall\":1}");
  var binding=Map.<String,Object>of("id",1,"policyId",1,"targetType","ROUTE","targetId",3,"targetRouteId","metered");
  var policies=new ArrayList<Map<String,Object>>();policies.add(policy);var bindings=new ArrayList<Map<String,Object>>();bindings.add(binding);
  if(duplicate){var p=new HashMap<>(policy);p.put("id",2);p.put("scope","API");policies.add(p);bindings.add(Map.of("id",2,"policyId",2,"targetType","API","targetId",2));}
  return SnapshotCodec.seal(new Snapshot(1,1,"",List.of(Map.of("id",1,"enabled",true)),List.of(Map.of("id",2,"serviceId",1,"method","GET","publicPath","/api/private/{id}","enabled",true,"isPublic",publicApi)),List.of(route),policies,bindings));
 }
 @Test void independentPlanHasTrustedVersionAndUnits(){var s=sample(false,false);PolicyRules.validate(s);var p=QuotaPolicy.freeze(s,s.routes().get(0));assertTrue(p.metered());assertEquals(2,p.apiId());assertEquals(s.checksum(),p.snapshotChecksum());assertFalse(PolicyRules.enforced(s.policies().get(0)));}
 @Test void publicAndDuplicateEffectiveQuotaAreRejected(){assertThrows(IllegalArgumentException.class,()->PolicyRules.validate(sample(true,false)));assertThrows(IllegalArgumentException.class,()->PolicyRules.validate(sample(false,true)));assertThrows(IllegalArgumentException.class,()->PolicyRules.parameters("USAGE_QUOTA","{\"unitsPerCall\":101}"));}
 @Test void legacySnapshotWithoutQuotaStaysNone(){var s=sample(false,false);var old=SnapshotCodec.seal(new Snapshot(1,1,"",s.services(),s.apis(),s.routes(),List.of(),List.of()));assertEquals(QuotaPolicy.NONE,QuotaPolicy.freeze(old,old.routes().get(0)));SnapshotCodec.verify(old);}
 @Test void v12RequiresNewSchemaAndNeverSharesLegacyPool(){
  var old=sample(false,false);var p=new HashMap<>(old.policies().get(0));p.put("type","USAGE_QUOTA_V12");p.put("executionStatus",PolicyRules.QUOTA_V12);
  var invalid=SnapshotCodec.seal(new Snapshot(1,1,"",old.services(),old.apis(),old.routes(),List.of(p),old.bindings()));
  assertThrows(IllegalArgumentException.class,()->PolicyRules.validate(invalid));
  var v12=SnapshotCodec.seal(new Snapshot(2,1,"",old.services(),old.apis(),old.routes(),List.of(p),old.bindings()));
  SnapshotCodec.verify(v12);PolicyRules.validate(v12);assertEquals(QuotaPolicy.Mode.V12_APP_API,QuotaPolicy.freeze(v12,v12.routes().get(0)).mode());assertEquals(QuotaPolicy.Mode.V11_APP,QuotaPolicy.freeze(old,old.routes().get(0)).mode());
  var mixed=new ArrayList<Map<String,Object>>(old.policies());p.put("id",2);mixed.add(p);var bindings=new ArrayList<Map<String,Object>>(old.bindings());bindings.add(Map.of("id",2,"policyId",2,"targetType","ROUTE","targetId",3,"targetRouteId","metered"));
  var duplicate=SnapshotCodec.seal(new Snapshot(2,1,"",old.services(),old.apis(),old.routes(),mixed,bindings));assertThrows(IllegalArgumentException.class,()->PolicyRules.validate(duplicate));
 }
}