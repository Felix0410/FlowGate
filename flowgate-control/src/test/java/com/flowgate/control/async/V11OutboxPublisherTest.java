package com.flowgate.control.async;
import com.flowgate.control.entity.OutboxEventEntry;
import com.flowgate.control.mapper.OutboxEventEntryMapper;
import com.flowgate.control.service.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 真实TransactionTemplate边界与异步Future：COMMIT先于发送，只有Broker ACK才标记SENT。 */
class V11OutboxPublisherTest {
 @Test void commitBeforeSendAndLateOwnerCannotClaimSuccess(){
  var mapper=mock(OutboxEventEntryMapper.class);var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  boolean[] committed={false};doAnswer(i->{committed[0]=true;return null;}).when(manager).commit(any());
  var row=new OutboxEventEntry();row.id=1L;row.attemptCount=0;row.eventId="00000000-0000-0000-0000-000000000001";row.payloadJson=new V11UsageEventTest().event;
  when(mapper.claimable(20)).thenReturn(List.of(row));
  KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);var ack=new CompletableFuture<SendResult<String,String>>();
  when(kafka.send(anyString(),anyString(),anyString())).thenAnswer(i->{assertTrue(committed[0]);return ack;});
  var diagnostics=new AsyncDiagnostics();var publisher=new OutboxPublisher(new OutboxClaimService(mapper,manager),kafka,"test",diagnostics);
  publisher.publish();verify(mapper,never()).sent(anyLong(),anyString());assertEquals(1,publisher.inflight());
  ack.complete(null);verify(mapper).sent(1,row.leaseOwner);assertEquals(0,publisher.inflight());assertEquals(1L,diagnostics.snapshot().get("staleOwner"));
 }
 @Test void failedFutureQueuesRetryWithoutSent(){
  var claims=mock(OutboxClaimService.class);var row=new OutboxEventEntry();row.id=1L;row.eventId="00000000-0000-0000-0000-000000000001";row.payloadJson=new V11UsageEventTest().event;
  when(claims.claim(20)).thenReturn(List.of(row));KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
  when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));
  var publisher=new OutboxPublisher(claims,kafka,"test",new AsyncDiagnostics());publisher.publish();verify(claims).failed(row,"BROKER_UNAVAILABLE");verify(claims,never()).sent(any());assertEquals(0,publisher.inflight());
 }
}
