package com.flowgate.control.service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.flowgate.contract.*;
import com.flowgate.control.entity.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.security.ApiFailure;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 权威准入：余额、唯一流水、Outbox同事务。Grant即消耗，下游失败不退款。 */
@Service
public class QuotaChargeService {
 private final ApplicationEntryMapper apps;private final DeveloperEntryMapper developers;private final PrincipalMapper principals;private final AppPrincipalEntryMapper links;private final AppCredentialStateMapper states;private final SubscriptionEntryMapper subscriptions;private final ApiPlanEntryMapper plans;
 private final QuotaBalanceEntryMapper balances;private final QuotaLedgerEntryMapper ledger;private final OutboxEventEntryMapper outbox;private final ConfigReleaseMapper releases;private final QuotaActivationService activation;private final TransactionTemplate tx;
 public QuotaChargeService(ApplicationEntryMapper apps,DeveloperEntryMapper developers,PrincipalMapper principals,AppPrincipalEntryMapper links,AppCredentialStateMapper states,SubscriptionEntryMapper subscriptions,ApiPlanEntryMapper plans,QuotaBalanceEntryMapper balances,QuotaLedgerEntryMapper ledger,OutboxEventEntryMapper outbox,ConfigReleaseMapper releases,QuotaActivationService activation,PlatformTransactionManager manager){this.apps=apps;this.developers=developers;this.principals=principals;this.links=links;this.states=states;this.subscriptions=subscriptions;this.plans=plans;this.balances=balances;this.ledger=ledger;this.outbox=outbox;this.releases=releases;this.activation=activation;this.tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(4);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
 private record Context(ApplicationEntry app,RouteSpec route,long policy,String fingerprint){}
 private ApiFailure denied(String code,String text){return new ApiFailure(403,code,text);}
 private Context context(QuotaCharge q){
  var release=releases.selectOne(new QueryWrapper<ConfigRelease>().eq("version",q.appliedVersion()));
  if(release==null||!release.sha256.equals(q.snapshotChecksum()))throw new ApiFailure(409,"CONFIG_VERSION_MISMATCH","历史配置版本或校验和不匹配");
  var snapshot=SnapshotCodec.JSON.readValue(release.snapshotJson,Snapshot.class);SnapshotCodec.verify(snapshot);
  var route=snapshot.routes().stream().filter(r->r.routeId().equals(q.routeId())).findFirst().orElseThrow(()->new ApiFailure(409,"CONFIG_VERSION_MISMATCH","历史路由不存在"));
  var plan=QuotaPolicy.freeze(snapshot,route);
  if(!plan.metered()||plan.apiId()!=q.apiId()||plan.units()!=q.units())throw new ApiFailure(409,"CONFIG_VERSION_MISMATCH","历史计量策略/API/单位不匹配");
  var link=links.selectById(q.principalId());if(link==null)throw denied("APP_TOKEN_REVOKED","应用主体关联无效");
  // 1. 先应用共享锁再查客户/身份，和停用、轮换的应用独占锁形成一致授权边界。
  var app=apps.shareById(link.applicationId);if(app==null||!"ACTIVE".equals(app.status))throw denied("APP_DISABLED","应用已停用");
  var developer=developers.shareById(app.developerId);if(developer==null||!"ACTIVE".equals(developer.status))throw denied("DEVELOPER_DISABLED","客户已停用");
  var principal=principals.shareById(q.principalId());var state=states.shareById(q.principalId());
  if(principal==null||!"APP_CALLER".equals(principal.principalType)||!"APP_CALLER".equals(principal.role)||!Boolean.TRUE.equals(principal.enabled)||state==null||!Boolean.TRUE.equals(state.enabled)||state.generation!=q.credentialGeneration())throw denied("APP_TOKEN_REVOKED","应用凭证已撤销或代次过期");
  var scopes=SnapshotCodec.JSON.readValue(principal.scopesJson,List.class);if(!scopes.contains(route.requiredScope()))throw denied("INSUFFICIENT_SCOPE","应用未获API所需Scope");
  String fingerprint=SnapshotCodec.digest(SnapshotCodec.JSON.writeValueAsString(List.of(app.id,q.principalId(),q.credentialGeneration(),q.apiId(),q.routeId(),plan.policyId(),q.units(),q.appliedVersion(),q.snapshotChecksum())));
  return new Context(app,route,plan.policyId(),fingerprint);
 }
 private Map<String,Object> response(QuotaLedgerEntry row,boolean duplicate){return Map.of("chargeId",row.chargeId,"chargedUnits",row.units,"periodUtc",row.periodYyyymm,"remainingUnits",row.remainingUnitsAfter,"duplicate",duplicate);}
 private Map<String,Object> duplicate(QuotaLedgerEntry old,Context c){if(!old.requestFingerprint.equals(c.fingerprint))throw new ApiFailure(409,"IDEMPOTENCY_CONFLICT","同chargeId的可信业务参数不同");return response(old,true);}
 public Map<String,Object> charge(QuotaCharge q){
  if(!activation.enabled())throw new ApiFailure(503,"QUOTA_UNAVAILABLE","配额链路未启用");
  try{return tx.execute(status->grant(q));}
  catch(DuplicateKeyException collision){
   // 2. 唯一键竞争必须先回滚整个尝试，再用独立事务读取已提交授权，不能在回滚事务内继续。
   return tx.execute(status->{var c=context(q);var old=ledger.current(q.chargeId());if(old==null)throw new ApiFailure(503,"QUOTA_UNAVAILABLE","并发授权状态未确定");return duplicate(old,c);});
  }
 }
 private Map<String,Object> grant(QuotaCharge q){
  var c=context(q);var old=ledger.find(q.chargeId());if(old!=null)return duplicate(old,c);
  // 3. 只有首次授予才查当前月份订阅；跨月重试返回原账本月份，不要求新月有效订阅。
  var now=apps.nowUtc();String period=DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC).format(now);
  var candidates=subscriptions.selectList(new QueryWrapper<SubscriptionEntry>().eq("application_id",c.app.id).eq("status","ACTIVE").le("effective_at",now).gt("expires_at",now).last("FOR SHARE"));
  if(candidates.size()!=1)throw denied("NO_ACTIVE_SUBSCRIPTION","没有唯一当前有效订阅");var sub=candidates.get(0);var plan=plans.shareById(sub.planId);
  if(plan==null||!"ACTIVE".equals(plan.status))throw denied("NO_ACTIVE_SUBSCRIPTION","套餐已停用");
  var begin=SubscriptionSchedule.monthStart(now);var end=begin.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
  if(sub.effectiveAt.isBefore(begin)||sub.expiresAt.isAfter(end))throw denied("NO_ACTIVE_SUBSCRIPTION","历史跨月订阅需人工处理，不能静默截断");
  balances.initialize(c.app.id,period,sub.id,plan.id,sub.planVersionSnapshot,plan.monthlyUnits);var balance=balances.locked(c.app.id,period);
  // 持余额行锁后的当前读还可识别同应用并发重复，额度恰好耗尽时也不误返回429。
  old=ledger.current(q.chargeId());if(old!=null)return duplicate(old,c);
  if(balance.remainingUnits<q.units())throw new ApiFailure(429,"QUOTA_EXHAUSTED","本月准入单位已耗尽");
  var row=new QuotaLedgerEntry();row.chargeId=q.chargeId();row.requestFingerprint=c.fingerprint;row.applicationId=c.app.id;row.principalId=q.principalId();row.credentialGeneration=q.credentialGeneration();row.apiId=q.apiId();row.routeId=q.routeId();row.appliedVersion=q.appliedVersion();row.policyId=c.policy;row.periodYyyymm=period;row.units=q.units();row.remainingUnitsAfter=balance.remainingUnits-q.units();ledger.insert(row);
  if(balances.deduct(balance.id,q.units())!=1)throw new ApiFailure(429,"QUOTA_EXHAUSTED","本月准入单位已耗尽");
  var event=new OutboxEventEntry();event.eventId=UUID.randomUUID().toString();event.eventType="USAGE_GRANTED";event.status="NEW";event.attemptCount=0;
  event.payloadJson=SnapshotCodec.JSON.writeValueAsString(Map.of("eventId",event.eventId,"chargeId",q.chargeId(),"applicationId",c.app.id,"apiId",q.apiId(),"units",q.units(),"periodUtc",period,"eventTime",now.toString(),"version",1));outbox.insert(event);
  return response(row,false);
 }
 public Object balance(long application,String period){BusinessInputs.id(application);validPeriod(period);return balances.selectList(new QueryWrapper<QuotaBalanceEntry>().eq("application_id",application).eq("period_yyyymm",period));}
 public Object history(long application,String period,long after,int limit){BusinessInputs.id(application);validPeriod(period);BusinessInputs.page(after,limit);
  return ledger.selectList(new QueryWrapper<QuotaLedgerEntry>().eq("application_id",application).eq("period_yyyymm",period).gt("id",after).orderByAsc("id").last("LIMIT "+limit)).stream().map(row->Map.of("id",row.id,"chargeId",row.chargeId,"applicationId",row.applicationId,"apiId",row.apiId,"routeId",row.routeId,"periodUtc",row.periodYyyymm,"units",row.units,"remainingUnitsAfter",row.remainingUnitsAfter,"appliedVersion",row.appliedVersion,"createdAt",row.createdAt)).toList();
 }
 private void validPeriod(String period){BusinessInputs.require(period!=null&&period.matches("[0-9]{4}(0[1-9]|1[0-2])"),"VALIDATION_ERROR","月份格式须YYYYMM");}
}
