package com.flowgate.control.service;
import com.flowgate.control.entity.OutboxEventEntry;
import com.flowgate.control.mapper.OutboxEventEntryMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
/** 短事务队列领取；每轮唯一owner隔离同实例的迟到回执，绝不在事务中等待Kafka。 */
@Service
public class OutboxClaimService {
 private final OutboxEventEntryMapper mapper;private final TransactionTemplate tx;
 public OutboxClaimService(OutboxEventEntryMapper mapper,PlatformTransactionManager manager){this.mapper=mapper;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(3);}
 public List<OutboxEventEntry> claim(int requested){
  if(requested<1)return List.of();
  return tx.execute(status->{
   // 1. 达到尝试上限的崩溃租约转为显式FAILED，不永久遗留LEASED。
   mapper.expireExhausted();var rows=mapper.claimable(Math.min(20,requested));String owner=UUID.randomUUID().toString();
   // 2. SKIP LOCKED仅用于领取队列；逐行更新后立即COMMIT释放锁。
   for(var row:rows){mapper.lease(row.id,owner);row.leaseOwner=owner;row.attemptCount++;}
   // 3. 返回的是提交后的租约快照，传输发生在调用方事务之外。
   return rows;
  });
 }
 public int sent(OutboxEventEntry row){return tx.execute(s->mapper.sent(row.id,row.leaseOwner));}
 public int failed(OutboxEventEntry row,String code){if(!Set.of("BROKER_UNAVAILABLE","INVALID_EVENT").contains(code))throw new IllegalArgumentException("错误码非法");int backoff=Math.min(60,1<<Math.min(6,row.attemptCount-1));return tx.execute(s->mapper.failed(row.id,row.leaseOwner,backoff,code));}
}
