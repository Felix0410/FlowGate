package com.flowgate.contract;
import java.util.*;
/** 配额独立解析，不进入令牌桶的capacity/refill参数模型；计划随同版Route冻结。 */
public final class QuotaPolicy {
 public static final String METADATA="flowgateQuota", FEATURE="USAGE_QUOTA_V1", FEATURE_V12="USAGE_QUOTA_V12";
 private QuotaPolicy(){}
 public enum Mode { NONE, V11_APP, V12_APP_API }
 public record Plan(boolean metered,String routeId,long apiId,long policyId,int units,long appliedVersion,String snapshotChecksum,Mode mode){
  public Plan(boolean metered,String routeId,long apiId,long policyId,int units,long appliedVersion,String snapshotChecksum){this(metered,routeId,apiId,policyId,units,appliedVersion,snapshotChecksum,metered?Mode.V11_APP:Mode.NONE);}
 }
 public static final Plan NONE=new Plan(false,"",0,0,0,0,"");
 public static Plan freeze(Snapshot snapshot,RouteSpec route){
  var api=snapshot.apis().stream().filter(a->route.method().equals(a.get("method"))&&route.publicPath().equals(a.get("publicPath"))).findFirst().orElse(Map.of());
  Plan result=NONE;
  // 1. 同版的API与ROUTE绑定统一解析，重叠匹配也只能有一条计量策略。
  for(var p:snapshot.policies()){
   if(!Boolean.TRUE.equals(p.get("enabled"))||!Set.of(PolicyRules.QUOTA,PolicyRules.QUOTA_V12).contains(p.get("executionStatus")))continue;
   for(var b:snapshot.bindings()){
    if(PolicyRules.id(b,"policyId")!=PolicyRules.id(p,"id"))continue;
    boolean matches="API".equals(b.get("targetType"))&&api.containsKey("id")&&PolicyRules.id(api,"id")==PolicyRules.id(b,"targetId")
      ||"ROUTE".equals(b.get("targetType"))&&route.routeId().equals(b.get("targetRouteId"));
    if(!matches)continue;
    Rules.require(!route.isPublic()&&!api.isEmpty()&&Boolean.TRUE.equals(api.get("enabled"))&&Boolean.FALSE.equals(api.get("isPublic")),"计量只允许启用的私有API/路由");
    Rules.require(!result.metered(),"同一路由至多一条实际配额策略");
    result=new Plan(true,route.routeId(),PolicyRules.id(api,"id"),PolicyRules.id(p,"id"),PolicyRules.parameters((String)p.get("type"),(String)p.get("parametersJson")).get("unitsPerCall").intValue(),snapshot.version(),snapshot.checksum(),PolicyRules.QUOTA_V12.equals(p.get("executionStatus"))?Mode.V12_APP_API:Mode.V11_APP);
    Rules.require(result.mode()!=Mode.V12_APP_API || snapshot.schemaVersion()==2,"V12计量必须使用新协议以拒绝旧网关");
   }
  }
  return result;
 }
}
