package com.flowgate.control.async;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.MessageListenerContainer;
import com.flowgate.control.security.ApiFailure;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 包装异常分类与毒分区seek边界，实际断DB自动恢复另有Kafka集成验证。 */
class V11ConsumerRecoveryTest {
 @Test void unwrapsDatabaseFailuresButNeverRetriesProtocolConflictsAsDatabase(){assertTrue(UsageConsumerErrorHandler.isTransient(new RuntimeException(new DataAccessResourceFailureException("offline"))));assertTrue(UsageConsumerErrorHandler.isTransient(new RuntimeException(new java.sql.SQLTransientConnectionException())));assertFalse(UsageConsumerErrorHandler.isTransient(new RuntimeException(new ApiFailure(409,"EVENT_CONFLICT","冲突"))));assertFalse(UsageConsumerErrorHandler.isTransient(new IllegalArgumentException("invalid")));}
 @Test void poisonOnlyPausesItsPartitionAndSeeksEveryUnprocessedPartition(){
  var diagnostics=new AsyncDiagnostics();var handler=new UsageConsumerErrorHandler(diagnostics);Consumer<?,?> consumer=mock(Consumer.class);var container=mock(MessageListenerContainer.class);
  handler.handleRemaining(new IllegalArgumentException("invalid"),List.of(new ConsumerRecord<>("usage",1,7,"k","v"),new ConsumerRecord<>("usage",2,9,"k","v")),consumer,container);
  verify(consumer).seek(new TopicPartition("usage",1),7);verify(consumer).seek(new TopicPartition("usage",2),9);verify(container).pausePartition(new TopicPartition("usage",1));verify(container,never()).pausePartition(new TopicPartition("usage",2));assertFalse(handler.isAckAfterHandle());assertEquals(1L,diagnostics.snapshot().get("poisonPause"));assertEquals(0L,diagnostics.snapshot().get("transientRetry"));
 }
}
