import com.flowgate.control.FlowGateControlApplication;
import com.flowgate.control.service.*;
import com.flowgate.control.async.UsageGrantedEvent;
import com.flowgate.contract.SnapshotCodec;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
/** C3真实MySQL：并发去重、聚合故障回滚、事件UTC日与Ledger插入日分离。 */
public class V11UsageAggregationProbe {
 public static void main(String[]args)throws Exception{
  var boot=new SpringApplication(FlowGateControlApplication.class);boot.setWebApplicationType(WebApplicationType.NONE);
  try(var ctx=boot.run("--flowgate.async.enabled=false")){
   var service=ctx.getBean(UsageAggregationService.class);var jdbc=ctx.getBean(JdbcTemplate.class);var payloads=jdbc.queryForList("SELECT payload_json FROM fg_outbox_event ORDER BY id",String.class);String payload=payloads.get(0);
   var pool=Executors.newFixedThreadPool(100);var start=new CountDownLatch(1);var results=new ArrayList<Future<String>>();
   try{for(int i=0;i<100;i++)results.add(pool.submit(()->{start.await();return service.process(payload);}));start.countDown();int fresh=0;for(var f:results)if(f.get().equals("CONSUMED"))fresh++;if(fresh!=1)throw new AssertionError("重复加总");}finally{pool.shutdownNow();}
   if(jdbc.queryForObject("SELECT SUM(grant_count) FROM fg_usage_daily",Long.class)!=1)throw new AssertionError("汇总次数");
   var event=UsageGrantedEvent.parse(payload);
   try{service.process(new UsageGrantedEvent(1,event.eventId(),event.chargeId(),event.applicationId(),event.apiId(),event.periodUtc(),2,event.eventTime()).canonical());throw new AssertionError("内容冲突被吞");}catch(com.flowgate.control.security.ApiFailure expected){}
   // 1. 标记已插入但汇总SQL失败时，整个事务回滚，修复后可重放。
   jdbc.execute("RENAME TABLE fg_usage_daily TO fg_usage_daily_fault");boolean rejected=false;
   try{service.process(payloads.get(1));}catch(org.springframework.dao.DataAccessException expected){rejected=true;}finally{jdbc.execute("RENAME TABLE fg_usage_daily_fault TO fg_usage_daily");}
   if(!rejected||jdbc.queryForObject("SELECT COUNT(*) FROM fg_consumed_event",Long.class)!=1)throw new AssertionError("孤立消费标记");service.process(payloads.get(1));
   // 2. 单独受控夹具把权威插入日置次日；合法Outbox事件时间仍按上日汇总。
   var boundary=UsageGrantedEvent.parse(payloads.get(2));var day=boundary.eventTime().atZone(java.time.ZoneOffset.UTC).withDayOfMonth(1).toLocalDate();
   var changed=new UsageGrantedEvent(1,boundary.eventId(),boundary.chargeId(),boundary.applicationId(),boundary.apiId(),boundary.periodUtc(),boundary.units(),day.atTime(23,59,59,999000000).toInstant(java.time.ZoneOffset.UTC));
   jdbc.update("UPDATE fg_outbox_event SET payload_json=? WHERE event_id=?",changed.canonical(),changed.eventId());jdbc.update("UPDATE fg_quota_ledger SET created_at=? WHERE charge_id=?",java.sql.Timestamp.from(day.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)),changed.chargeId());
   service.process(changed.canonical());if(jdbc.queryForObject("SELECT grant_count FROM fg_usage_daily WHERE day_utc=? AND application_id=? AND api_id=?",Long.class,day,changed.applicationId(),changed.apiId())!=1)throw new AssertionError("日归属错用Ledger");
   // 3. 同charge新eventId必须诊断冲突，不能第二次加总。
   String another=UUID.randomUUID().toString();String fork=changed.canonical().replace(changed.eventId(),another);
   jdbc.update("INSERT INTO fg_outbox_event(event_id,event_type,payload_json,status) VALUES(?,'USAGE_GRANTED',?,'NEW')",another,fork);
   try{service.process(fork);throw new AssertionError("重复charge被吞");}catch(com.flowgate.control.security.ApiFailure expected){if(!expected.code.equals("CONSUME_DUPLICATE_CHARGE"))throw expected;}
   if(jdbc.queryForObject("SELECT COUNT(*) FROM fg_consumed_event",Long.class)!=3)throw new AssertionError("重复标记增加");
   System.out.println("{\"passed\":true,\"duplicateConcurrency\":100,\"newAggregates\":1,\"rollbackWithoutOrphan\":true,\"dayFromOutbox\":true,\"chargeConflictDenied\":true}");
  }
 }
}
