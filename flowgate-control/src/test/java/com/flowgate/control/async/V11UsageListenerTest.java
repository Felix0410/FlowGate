package com.flowgate.control.async;
import com.flowgate.control.listener.UsageEventListener;
import com.flowgate.control.service.UsageAggregationService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
/** 提交服务返回与offset确认顺序；任何事务异常均不确认。 */
class V11UsageListenerTest {
 private LegacyTransportFence fence(){var fence=mock(LegacyTransportFence.class);when(fence.allows("KAFKA")).thenReturn(true);return fence;}
 @Test void acknowledgesOnlyAfterCommittedServiceReturns(){var service=mock(UsageAggregationService.class);var ack=mock(Acknowledgment.class);when(service.process("event")).thenReturn("CONSUMED");new UsageEventListener(service,new AsyncDiagnostics(),fence()).receive("event",ack);var order=inOrder(service,ack);order.verify(service).process("event");order.verify(ack).acknowledge();}
 @Test void neverAcknowledgesFailedTransaction(){var service=mock(UsageAggregationService.class);var ack=mock(Acknowledgment.class);when(service.process(anyString())).thenThrow(new IllegalStateException());assertThrows(IllegalStateException.class,()->new UsageEventListener(service,new AsyncDiagnostics(),fence()).receive("invalid",ack));verifyNoInteractions(ack);}
}
