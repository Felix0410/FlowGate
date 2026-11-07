import com.flowgate.control.FlowGateControlApplication;
import com.flowgate.control.service.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.entity.*;
import com.flowgate.control.security.ApiFailure;
import com.flowgate.contract.*;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

/** B3受控内部实测：直接调用真实Spring事务Bean，不开放B2公网签发接口或修改当前发布版本。 */
public class V11QuotaDbProbe {
 static ConfigurableApplicationContext context(String init){var app=new SpringApplication(FlowGateControlApplication.class);app.setWebApplicationType(WebApplicationType.NONE);return app.run("--flowgate.quota-activation-enabled=true","--spring.datasource.hikari.connection-init-sql="+init);}
 static long id(Object value){return ((Number)value).longValue();}
 static Snapshot snapshot(ConfigurableApplicationContext ctx,long version){
  var releases=ctx.getBean(ConfigReleaseMapper.class);var old=releases.selectOne(new QueryWrapper<ConfigRelease>().eq("version",1));var s=SnapshotCodec.JSON.readValue(old.snapshotJson,Snapshot.class);
  var route=s.routes().stream().filter(r->!r.isPublic()).findFirst().orElseThrow();
  var api=s.apis().stream().filter(a->route.publicPath().equals(a.get("publicPath"))).findFirst().orElseThrow();
  var policy=Map.<String,Object>of("id",9001,"code","db-quota","type","USAGE_QUOTA","scope","API","enabled",true,"executionStatus",PolicyRules.QUOTA,"parametersJson","{\"unitsPerCall\":1}");
  var result=SnapshotCodec.seal(new Snapshot(1,version,"",s.services(),s.apis(),s.routes().stream().map(r->new RouteSpec(r.routeId(),r.method(),r.publicPath(),r.upstreamPath(),r.upstreamUri(),r.isPublic(),r.requiredScope(),r.priority(),version)).toList(),List.of(policy),List.of(Map.of("id",9001,"policyId",9001,"targetType","API","targetId",api.get("id")))));
  SnapshotCodec.verify(result);var release=new ConfigRelease();release.version=version;release.sha256=result.checksum();release.actorId=1L;release.snapshotJson=SnapshotCodec.JSON.writeValueAsString(result);releases.insert(release);return result;
 }
 record TestApp(long id,long principal,long generation){}
 static TestApp app(ConfigurableApplicationContext ctx,long dev,long plan,String name){
  var business=ctx.getBean(DeveloperApplicationService.class);var app=business.saveApp(null,Map.of("developerId",dev,"code",name,"name",name),1);
  var mapper=ctx.getBean(ApplicationEntryMapper.class);var now=mapper.nowUtc();var begin=SubscriptionSchedule.monthStart(now);var end=begin.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
  ctx.getBean(PlanSubscriptionService.class).subscribe(Map.of("applicationId",app.id(),"planId",plan,"effectiveAt",begin.toString(),"expiresAt",end.toString()),1);
  var credentials=ctx.getBean(AppCredentialService.class).issue(app.id(),List.of("demo:read"),false,1);
  if(!((String)credentials.get("token")).matches("fgapp_[A-Za-z0-9_-]{43}"))throw new AssertionError("Token格式");
  return new TestApp(app.id(),id(credentials.get("principalId")),id(credentials.get("generation")));
 }
 static QuotaCharge request(Snapshot s,TestApp app,String charge){var route=s.routes().stream().filter(r->!r.isPublic()).findFirst().orElseThrow();var plan=QuotaPolicy.freeze(s,route);return new QuotaCharge(charge,app.principal,app.generation,plan.apiId(),route.routeId(),s.version(),s.checksum(),1);}
 static List<Object> concurrent(java.util.function.IntFunction<Object> call)throws Exception{
  var pool=Executors.newFixedThreadPool(100);var ready=new CountDownLatch(100);var start=new CountDownLatch(1);var futures=new ArrayList<Future<Object>>();
  try{for(int i=0;i<100;i++){int n=i;futures.add(pool.submit(()->{ready.countDown();start.await();try{return call.apply(n);}catch(ApiFailure error){return error.status;}}));}ready.await();start.countDown();var result=new ArrayList<Object>();for(var future:futures)result.add(future.get(30,TimeUnit.SECONDS));return result;}finally{pool.shutdownNow();}
 }
 public static void main(String[] args)throws Exception{
  QuotaCharge duplicateRequest;long duplicateApp;String originalPeriod;
  try(var ctx=context("SET time_zone='+00:00'")){
   // 1. 只写测试独占库中的新历史夹具，不更新fg_config_state；运行中的Gateway继续旧V1。
   var business=ctx.getBean(DeveloperApplicationService.class);var developer=business.saveDeveloper(null,Map.of("code","db-probe","name","DB验证"),1);
   var plan=ctx.getBean(PlanSubscriptionService.class).savePlan(null,Map.of("code","db-five","name","额度5","monthlyUnits",5),1);
   var s=snapshot(ctx,2);var service=ctx.getBean(QuotaChargeService.class);var one=app(ctx,developer.id,plan.id,"probe-five");
   // 2. 真100线程同一余额：仅5grant、95耗尽，三张表守恒。
   var results=concurrent(i->service.charge(request(s,one,UUID.randomUUID().toString())));
   long granted=results.stream().filter(v->v instanceof Map).count();long rejected=results.stream().filter(v->Integer.valueOf(429).equals(v)).count();
   if(granted!=5||rejected!=95)throw new AssertionError("并发5额度结果="+granted+"/"+rejected);
   var ledger=ctx.getBean(QuotaLedgerEntryMapper.class);var outbox=ctx.getBean(OutboxEventEntryMapper.class);var balances=ctx.getBean(QuotaBalanceEntryMapper.class);
   if(ledger.selectCount(null)!=5||outbox.selectCount(null)!=5||balances.selectOne(new QueryWrapper<QuotaBalanceEntry>().eq("application_id",one.id)).remainingUnits!=0)throw new AssertionError("守恒失败");
   // 3. 同chargeId100并发只有一个新grant；不同合法历史上下文409，停用后重复也403。
   var two=app(ctx,developer.id,plan.id,"probe-duplicate");duplicateRequest=request(s,two,UUID.randomUUID().toString());duplicateApp=two.id;
   var q=duplicateRequest;var duplicates=concurrent(i->service.charge(q));if(duplicates.stream().anyMatch(v->!(v instanceof Map)))throw new AssertionError("重复授权失败");
   if(duplicates.stream().map(v->(Map<?,?>)v).filter(v->Boolean.FALSE.equals(v.get("duplicate"))).count()!=1||ledger.selectCount(null)!=6||outbox.selectCount(null)!=6)throw new AssertionError("重复写账");
   originalPeriod=(String)((Map<?,?>)duplicates.get(0)).get("periodUtc");
   var newer=snapshot(ctx,3);try{service.charge(request(newer,two,q.chargeId()));throw new AssertionError("上下文冲突未拒绝");}catch(ApiFailure error){if(!error.code.equals("IDEMPOTENCY_CONFLICT"))throw error;}
   business.saveApp(two.id,Map.of("status","SUSPENDED"),1);try{service.charge(q);throw new AssertionError("停用未拒绝");}catch(ApiFailure error){if(error.status!=403)throw error;}business.saveApp(two.id,Map.of("status","ACTIVE"),1);
  }
  // 新连接的DB会话时钟跳到次月，真实服务仍从原Ledger取归属，不要求新月订阅。
  long next=YearMonth.parse(originalPeriod,java.time.format.DateTimeFormatter.ofPattern("yyyyMM")).plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
  try(var ctx=context("SET time_zone='+00:00', timestamp="+next)){
   var result=ctx.getBean(QuotaChargeService.class).charge(duplicateRequest);
   if(!result.get("periodUtc").equals(originalPeriod)||!Boolean.TRUE.equals(result.get("duplicate")))throw new AssertionError("跨月重复扣量");
   if(ctx.getBean(QuotaBalanceEntryMapper.class).selectCount(new QueryWrapper<QuotaBalanceEntry>().eq("application_id",duplicateApp))!=1)throw new AssertionError("创建了第二月余额");
  }
  System.out.println("{\"passed\":true,\"differentChargeConcurrency\":100,\"grants\":5,\"exhausted\":95,\"duplicateConcurrency\":100,\"duplicateLedger\":1,\"outboxMatchesLedger\":true,\"crossMonthOriginalPeriod\":\""+originalPeriod+"\",\"disabledReplayDenied\":true}");
 }
}
