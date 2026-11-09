package com.flowgate.control.service;
import com.flowgate.control.async.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.security.ApiFailure;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
/** 安全运营查询和ADMIN失败重排；对账只读RR快照，不修改任何权威余额。 */
@Service
public class AsyncOperationsService {
 private final long started=System.nanoTime();private final int replayGraceSeconds;
 private final LedgerIntegrityMapper integrity;
 private final AsyncUsageReadMapper reads;private final ApplicationEntryMapper clock;private final AuditService audit;private final TransactionTemplate snapshot,write;private final AsyncBrokerProbe broker;private final AsyncDiagnostics diagnostics;
 public AsyncOperationsService(AsyncUsageReadMapper reads,ApplicationEntryMapper clock,AuditService audit,PlatformTransactionManager manager,AsyncBrokerProbe broker,AsyncDiagnostics diagnostics,LedgerIntegrityMapper integrity,@org.springframework.beans.factory.annotation.Value("${flowgate.async.replay-grace-seconds:300}")int grace){if(grace<1||grace>86400)throw new IllegalArgumentException("补投等待期须1..86400秒，生产建议至少300秒");replayGraceSeconds=grace;this.integrity=integrity;this.reads=reads;this.clock=clock;this.audit=audit;this.broker=broker;this.diagnostics=diagnostics;snapshot=new TransactionTemplate(manager);snapshot.setReadOnly(true);snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);snapshot.setTimeout(8);write=new TransactionTemplate(manager);}
 private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
 public Object query(boolean reconcile,String from,String to,Long app,Long api,String cursor,int limit){
  BusinessInputs.page(0,limit);if(app!=null)BusinessInputs.id(app);if(api!=null)BusinessInputs.id(api);var position=UsageCursor.parse(cursor);
  return snapshot.execute(s->{
   // 1. 所有表同一只读RR快照；日期以DB UTC为准，封账日不接受今天或未来。
   Instant asOf=clock.nowUtc();LocalDate today=asOf.atZone(ZoneOffset.UTC).toLocalDate(),end,start;
   try{end=to==null?today.minusDays(reconcile?1:0):LocalDate.parse(to);start=from==null?end.minusDays(6):LocalDate.parse(from);}catch(RuntimeException invalid){throw new IllegalArgumentException("日期须YYYY-MM-DD");}
   if(start.isAfter(end)||java.time.temporal.ChronoUnit.DAYS.between(start,end)>30||end.isAfter(reconcile?today.minusDays(1):today))throw new IllegalArgumentException("最多31天；对账仅接受已关闭UTC日");
   // 缺失事件时只能按写入时间窗口（含相邻UTC日）做候选诊断，绝不据此归属日报。
   var warnings=reconcile?integrity.scan(start,end,start.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC),end.plusDays(2).atStartOfDay().toInstant(ZoneOffset.UTC),app,api,101):List.<Map<String,Object>>of();
   var rows=reconcile?reads.reconciliation(start,end,app,api,position==null?null:position.day(),position==null?null:position.applicationId(),position==null?null:position.apiId(),limit+1):reads.usage(start,end,app,api,position==null?null:position.day(),position==null?null:position.applicationId(),position==null?null:position.apiId(),limit+1);
   // 2. 完整主键稳定分页，多读一条判断是否确有下一页。
   boolean more=rows.size()>limit;var page=new ArrayList<>(rows.subList(0,Math.min(rows.size(),limit)));String next=null;
   if(more){var last=page.get(page.size()-1);next=new UsageCursor(last.get("dayUtc").toString(),number(last,"applicationId"),number(last,"apiId")).encode();}
   // 3. 未消费优先PENDING；齐全但统计不一致才MISMATCH，绝不按Kafka lag推导正确。
   if(reconcile)for(var row:page){long expected=number(row,"expectedCount"),consumed=number(row,"consumedCount");row.put("outboxBacklog",Math.max(0,expected-consumed));row.put("status",consumed<expected?"PENDING":consumed==expected&&number(row,"expectedUnits")==number(row,"consumedUnits")&&expected==number(row,"aggregatedCount")&&number(row,"expectedUnits")==number(row,"aggregatedUnits")?"MATCH":"MISMATCH");}
   var result=new LinkedHashMap<String,Object>();result.put("rows",page);result.put("nextCursor",next);result.put("asOfUtc",asOf);result.put("asyncEnabled",broker.enabled());result.put("statisticMeaning","授权单位与次数，异步读模型可能延迟，不是业务成功量");
   if(reconcile){
    boolean gap=!warnings.isEmpty();if(gap)page.forEach(row->row.put("status","INTEGRITY_GAP"));
    result.put("integrityStatus",gap?"INTEGRITY_GAP":"NO_GAP_IN_SCAN");result.put("integrityWarnings",warnings.subList(0,Math.min(100,warnings.size())));result.put("integrityTruncated",warnings.size()>100);
    result.put("integrityScan",Map.of("fromUtc",start.minusDays(1).toString(),"untilUtcExclusive",end.plusDays(2).toString(),"note","缺事件日期未知；写入时间仅作候选筛选，包含相邻UTC日，不作为日报归属。单次最多100个异常样本。"));
   }
   return result;
  });
 }
 public Object events(String status,long after,int limit){BusinessInputs.page(after,limit);if(status!=null&&!Set.of("NEW","LEASED","SENT","FAILED").contains(status))throw new IllegalArgumentException("状态非法");var rows=reads.events(status,after,limit,replayGraceSeconds);if(!replayWarm())rows.forEach(row->row.put("replayEligible",false));return rows;}
 public Object summary(){var result=new LinkedHashMap<>(reads.summary());result.put("overdueUnconsumedSent",reads.overdueSent(replayGraceSeconds));result.put("sentReplayScanLimit",1000);result.put("replayGraceSeconds",replayGraceSeconds);result.put("replayGuardReady",replayWarm());result.put("asyncEnabled",broker.enabled());result.put("broker",broker.status());result.put("processCounters",diagnostics.snapshot());result.put("poisonPolicy","数据库故障自动退避重试；永久毒消息暂停问题分区且不提交offset，不使用未确认DLT");return result;}
 public Object retry(long id,long actor){BusinessInputs.id(id);return write.execute(s->{if(reads.retry(id)!=1)throw new ApiFailure(409,"EVENT_STATE_CONFLICT","仅FAILED可重排，可能已被其他管理员处理");audit.record(actor,"RETRY_ASYNC_EVENT","outbox",Long.toString(id),null,"保留原eventId重新排队，不修改权威Ledger或余额");return Map.of("status","NEW");});}
 /** 新ACK写入next_attempt_at作为SENT等待期基点；启动冷却还保护旧版本时间含义不同的存量行。 */
 private boolean replayWarm(){return java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)>=replayGraceSeconds;}
 public Object replay(long id,long actor){
  BusinessInputs.id(id);
  // 1. 不允许刚重启即补投旧SENT；节点与事件都必须度过完整等待期。
  if(!replayWarm())throw new ApiFailure(409,"REPLAY_GRACE_NOT_ELAPSED","补投保护等待期尚未结束，请稍后重新读取");
  return write.execute(s->{
   // 2. 状态/未消费/数据库时间门槛在一条UPDATE中CAS复验，双管理员只能一人成功。
   if(reads.replay(id,replayGraceSeconds)!=1)throw new ApiFailure(409,"EVENT_REPLAY_CONFLICT","仅超过等待期且未消费的SENT事件可补投");
   // 3. 与审计同事务，保留原payload/eventId，不访问或修改Quota余额及Ledger。
   audit.record(actor,"REPLAY_SENT_EVENT","outbox",Long.toString(id),null,"超过等待期的SENT未消费原事件补投，不产生新授权");return Map.of("status","NEW");
  });
 }
}
