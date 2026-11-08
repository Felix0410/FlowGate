import com.flowgate.control.FlowGateControlApplication;
import com.flowgate.control.service.*;
import com.flowgate.control.async.*;
import com.flowgate.control.security.ApiFailure;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.time.*;
/** C4真库运营、同日多App游标与封账对账；仅修改本probe独占库夹具。 */
public class V11AsyncOperationsProbe {
 public static void main(String[]args){
  var boot=new SpringApplication(FlowGateControlApplication.class);boot.setWebApplicationType(WebApplicationType.NONE);
  try(var ctx=boot.run("--flowgate.async.enabled=false")){
   var jdbc=ctx.getBean(JdbcTemplate.class);var ops=ctx.getBean(AsyncOperationsService.class);var aggregate=ctx.getBean(UsageAggregationService.class);
   var payloads=jdbc.queryForList("SELECT payload_json FROM fg_outbox_event ORDER BY id",String.class);var day=UsageGrantedEvent.parse(payloads.get(0)).eventTime().atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate();
   for(var payload:payloads){var e=UsageGrantedEvent.parse(payload);var changed=new UsageGrantedEvent(1,e.eventId(),e.chargeId(),e.applicationId(),e.apiId(),e.periodUtc(),e.units(),day.atTime(23,59,59).toInstant(ZoneOffset.UTC));jdbc.update("UPDATE fg_outbox_event SET payload_json=? WHERE event_id=?",changed.canonical(),e.eventId());}
   var pending=(Map<?,?>)ops.query(true,day.toString(),day.toString(),null,null,null,1);var rows=(List<Map<String,Object>>)pending.get("rows");if(rows.size()!=1||!rows.get(0).get("status").equals("PENDING")||pending.get("nextCursor")==null)throw new AssertionError("PENDING或游标");
   var second=(Map<?,?>)ops.query(true,day.toString(),day.toString(),null,null,(String)pending.get("nextCursor"),1);if(((List<?>)second.get("rows")).size()!=1||second.get("nextCursor")!=null)throw new AssertionError("同日漏页");
   for(String payload:jdbc.queryForList("SELECT payload_json FROM fg_outbox_event",String.class))aggregate.process(payload);
   var match=(Map<?,?>)ops.query(true,day.toString(),day.toString(),null,null,null,20);if(((List<Map<String,Object>>)match.get("rows")).stream().anyMatch(r->!r.get("status").equals("MATCH")))throw new AssertionError("未MATCH");
   var usage=(Map<?,?>)ops.query(false,day.toString(),day.toString(),null,null,null,1);if(usage.get("nextCursor")==null)throw new AssertionError("Usage复合游标");
   jdbc.update("UPDATE fg_usage_daily SET authorized_units=authorized_units+1 LIMIT 1");var mismatch=(Map<?,?>)ops.query(true,day.toString(),day.toString(),null,null,null,20);if(((List<Map<String,Object>>)mismatch.get("rows")).stream().noneMatch(r->r.get("status").equals("MISMATCH")))throw new AssertionError("未诊断差异");
   long id=jdbc.queryForObject("SELECT MIN(id) FROM fg_outbox_event",Long.class);jdbc.update("UPDATE fg_outbox_event SET status='FAILED' WHERE id=?",id);ops.retry(id,1);try{ops.retry(id,1);throw new AssertionError("重复重试应冲突");}catch(ApiFailure expected){if(expected.status!=409)throw expected;}
   if(jdbc.queryForObject("SELECT COUNT(*) FROM fg_audit_log WHERE action='RETRY_ASYNC_EVENT'",Long.class)!=1)throw new AssertionError("重试审计");
   String json=com.flowgate.contract.SnapshotCodec.JSON.writeValueAsString(ops.events(null,0,20));if(json.contains("payloadJson")||json.contains("payload_json"))throw new AssertionError("泄露payload");
   ops.summary();try{ops.query(true,LocalDate.now(ZoneOffset.UTC).toString(),LocalDate.now(ZoneOffset.UTC).toString(),null,null,null,20);throw new AssertionError("今日未封账");}catch(IllegalArgumentException expected){}
   System.out.println("{\"passed\":true,\"sameDayCompositePagination\":true,\"reconciliation\":[\"PENDING\",\"MATCH\",\"MISMATCH\"],\"failedRetryAuditedOnce\":true,\"payloadRedacted\":true}");
  }
 }
}
