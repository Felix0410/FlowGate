package com.flowgate.control.service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.flowgate.control.async.*;
import com.flowgate.control.entity.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.security.ApiFailure;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
/** 先核验本地权威事件，再同事务去重/汇总；返回到Listener时已经真正COMMIT。 */
@Service
public class UsageAggregationService {
 private final OutboxEventEntryMapper outbox;private final QuotaLedgerEntryMapper ledger;private final ConsumedEventMapper consumed;private final UsageDailyMapper daily;private final TransactionTemplate tx;
 public UsageAggregationService(OutboxEventEntryMapper outbox,QuotaLedgerEntryMapper ledger,ConsumedEventMapper consumed,UsageDailyMapper daily,PlatformTransactionManager manager){this.outbox=outbox;this.ledger=ledger;this.consumed=consumed;this.daily=daily;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(4);}
 private ApiFailure conflict(String code){return new ApiFailure(409,code,"事件与本地权威记录不一致，已停止该分区等待诊断");}
 private void validate(UsageGrantedEvent e){
  // 1. Kafka不是可信数字来源；完整八字段必须与本地Outbox一致，并交叉校验Ledger。
  var source=outbox.selectOne(new QueryWrapper<OutboxEventEntry>().eq("event_id",e.eventId()));
  if(source==null||!"USAGE_GRANTED".equals(source.eventType)||!e.equals(UsageGrantedEvent.parse(source.payloadJson)))throw conflict("EVENT_CONFLICT");
  var grant=ledger.find(e.chargeId());
  if(grant==null||grant.applicationId!=e.applicationId()||grant.apiId!=e.apiId()||grant.units!=e.units()||!grant.periodYyyymm.equals(e.periodUtc()))throw conflict("EVENT_CONFLICT");
 }
 private String duplicate(UsageGrantedEvent e,ConsumedEventEntry old){
  if(!old.eventId.equals(e.eventId()))throw conflict("CONSUME_DUPLICATE_CHARGE");
  if(!old.chargeId.equals(e.chargeId())||!old.payloadSha256.equals(e.fingerprint()))throw conflict("EVENT_CONFLICT");
  return "DUPLICATE";
 }
 public String process(String payload){
  var event=UsageGrantedEvent.parse(payload);
  try{return tx.execute(s->{
   validate(event);var old=consumed.existing(event.eventId(),event.chargeId());if(old!=null)return duplicate(event,old);
   // 2. 唯一标记和日增量同事务；日归属只取已核验的Outbox.eventTime UTC日期。
   var entry=new ConsumedEventEntry();entry.eventId=event.eventId();entry.chargeId=event.chargeId();entry.payloadSha256=event.fingerprint();consumed.insert(entry);
   daily.add(event.day(),event.applicationId(),event.apiId(),event.units());return "CONSUMED";
  });}catch(DuplicateKeyException collision){
   // 3. 唯一竞争先完整回滚，再用新事务读已提交标记；失败不能留下孤立消费记录。
   return tx.execute(s->{validate(event);var old=consumed.existing(event.eventId(),event.chargeId());if(old==null)throw conflict("EVENT_CONFLICT");return duplicate(event,old);});
  }
 }
}
