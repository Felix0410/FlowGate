package com.flowgate.control.async;

import com.flowgate.control.messaging.RabbitDelivery;
import com.flowgate.control.service.*;
import com.flowgate.control.entity.OutboxEventEntry;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;

/** 传输与旧权威事务边界的定向验证；真实MySQL/Rabbit另见隔离演练。 */
class LegacyRabbitContractTest {
    @Test void malformedJacksonIsPermanent(){assertThrows(IllegalArgumentException.class,()->UsageGrantedEvent.parse("{broken"));assertThrows(IllegalArgumentException.class,()->UsageGrantedEvent.parse(new V11UsageEventTest().event.replace("2026-11-30T23:59:59.999Z","invalidZ")));}
    @Test void frozenDoesNotClaim(){var claims=mock(OutboxClaimService.class);new LegacyUsageRabbitPublisher(claims,mock(RabbitTemplate.class),mock(LegacyTransportFence.class),new AsyncDiagnostics()).publish();verifyNoInteractions(claims);}
    @Test void onlyConfirmedUnreturnedPublishMarksSent(){
        var claims=mock(OutboxClaimService.class);var fence=mock(LegacyTransportFence.class);when(fence.allows("RABBIT")).thenReturn(true);
        var row=new OutboxEventEntry();row.eventId="00000000-0000-0000-0000-000000000001";row.payloadJson=new V11UsageEventTest().event;
        when(claims.claim(2)).thenReturn(List.of(row));var rabbit=mock(RabbitTemplate.class);
        doAnswer(i->{CorrelationData ack=i.getArgument(3);ack.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        new LegacyUsageRabbitPublisher(claims,rabbit,fence,new AsyncDiagnostics()).publish();var order=inOrder(rabbit,claims);order.verify(claims).claim(2);order.verify(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));order.verify(claims).sent(row);
    }
    @Test void mandatoryReturnDoesNotMarkSent(){
        var claims=mock(OutboxClaimService.class);var fence=mock(LegacyTransportFence.class);when(fence.allows("RABBIT")).thenReturn(true);
        var row=new OutboxEventEntry();row.eventId="00000000-0000-0000-0000-000000000001";row.payloadJson=new V11UsageEventTest().event;when(claims.claim(2)).thenReturn(List.of(row));var rabbit=mock(RabbitTemplate.class);
        doAnswer(i->{CorrelationData ack=i.getArgument(3);ack.setReturned(new ReturnedMessage(i.getArgument(2),312,"NO_ROUTE","test","granted"));ack.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        new LegacyUsageRabbitPublisher(claims,rabbit,fence,new AsyncDiagnostics()).publish();verify(claims,never()).sent(any());verify(claims).failed(row,"BROKER_UNAVAILABLE");
    }
    @Test void listenerCommitsBeforeAckAndPoisonDoesNotAck()throws Exception{
        var service=mock(UsageAggregationService.class);var fence=mock(LegacyTransportFence.class);when(fence.allows("RABBIT")).thenReturn(true);when(service.process("event")).thenReturn("CONSUMED");var channel=mock(Channel.class);var props=new MessageProperties();props.setDeliveryTag(3);
        var listener=new LegacyUsageRabbitListener(service,mock(RabbitTemplate.class),fence,new AsyncDiagnostics());listener.receive(new Message("event".getBytes(),props),channel);var order=inOrder(service,channel);order.verify(service).process("event");order.verify(channel).basicAck(3,false);
        when(service.process("bad")).thenThrow(new IllegalArgumentException());listener.receive(new Message("bad".getBytes(),props),channel);verify(channel).basicReject(3,false);
    }
}
