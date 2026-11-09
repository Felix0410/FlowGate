package com.flowgate.control.async;
import com.flowgate.control.service.*;
import com.flowgate.control.mapper.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 聚合行为空也不能隐去权威异常；完整性读取必须与日对账同一只读RR快照。 */
class V11ReconciliationIntegrityTest {
 @Test void missingEventNeverBecomesEmptySuccessAndUsesReadonlyRepeatableRead(){
  var reads=mock(AsyncUsageReadMapper.class);var integrity=mock(LedgerIntegrityMapper.class);var clock=mock(ApplicationEntryMapper.class);var audit=mock(AuditService.class);var manager=mock(PlatformTransactionManager.class);
  when(manager.getTransaction(any())).thenAnswer(i->{var definition=(TransactionDefinition)i.getArgument(0);assertTrue(definition.isReadOnly());assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,definition.getIsolationLevel());return new SimpleTransactionStatus();});
  when(clock.nowUtc()).thenReturn(Instant.parse("2026-11-09T12:00:00Z"));
  when(reads.reconciliation(any(),any(),any(),any(),any(),any(),any(),anyInt())).thenReturn(List.of());
  when(integrity.scan(any(),any(),any(),any(),any(),any(),anyInt())).thenReturn(List.of(Map.of("code","LEDGER_WITHOUT_OUTBOX","recordId",4L,"relatedCount",0)));
  var service=new AsyncOperationsService(reads,clock,audit,manager,mock(AsyncBrokerProbe.class),new AsyncDiagnostics(),integrity,300);
  var result=(Map<?,?>)service.query(true,"2026-11-08","2026-11-08",null,null,null,20);
  assertEquals("INTEGRITY_GAP",result.get("integrityStatus"));assertTrue(((List<?>)result.get("rows")).isEmpty());assertEquals(1,((List<?>)result.get("integrityWarnings")).size());verifyNoInteractions(audit);verify(manager).commit(any());
 }
}
