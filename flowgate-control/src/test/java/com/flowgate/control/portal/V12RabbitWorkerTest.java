package com.flowgate.control.portal;

import com.flowgate.control.security.ApiFailure;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 消息边界测试：事务失败不可ACK；重试副本必须确认后才释放原消息。 */
class V12RabbitWorkerTest {
    private Message message(){var props=new MessageProperties();props.setDeliveryTag(7);props.setMessageId("event-1");return new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),props);}
    @Test void commitPrecedesManualAck()throws Exception{var usage=mock(ApiUsageService.class);var channel=mock(Channel.class);var worker=new ApiUsageWorker(usage,mock(RabbitTemplate.class));worker.receive(message(),channel);var order=inOrder(usage,channel);order.verify(usage).process("{}");order.verify(channel).basicAck(7,false);}
    @Test void poisonGoesToDeadLetterWithoutSuccessAck()throws Exception{var usage=mock(ApiUsageService.class);var channel=mock(Channel.class);when(usage.process(anyString())).thenThrow(new ApiFailure(409,"V12_EVENT_CONFLICT","冲突"));new ApiUsageWorker(usage,mock(RabbitTemplate.class)).receive(message(),channel);verify(channel).basicReject(7,false);verify(channel,never()).basicAck(anyLong(),anyBoolean());}
    @Test void dependencyFailureRequiresConfirmedRetryCopyBeforeAck()throws Exception{var usage=mock(ApiUsageService.class);var channel=mock(Channel.class);var rabbit=mock(RabbitTemplate.class);when(usage.process(anyString())).thenThrow(new DataAccessResourceFailureException("unavailable"));doAnswer(call->{CorrelationData correlation=call.getArgument(3);correlation.getFuture().complete(new CorrelationData.Confirm(true,null));return null;}).when(rabbit).send(anyString(),eq("retry"),any(Message.class),any(CorrelationData.class));new ApiUsageWorker(usage,rabbit).receive(message(),channel);var order=inOrder(rabbit,channel);order.verify(rabbit).send(anyString(),eq("retry"),any(Message.class),any(CorrelationData.class));order.verify(channel).basicAck(7,false);}
    @Test void retryPublishNackNeverAcknowledgesOriginal()throws Exception{var usage=mock(ApiUsageService.class);var channel=mock(Channel.class);var rabbit=mock(RabbitTemplate.class);when(usage.process(anyString())).thenThrow(new DataAccessResourceFailureException("unavailable"));doAnswer(call->{CorrelationData correlation=call.getArgument(3);correlation.getFuture().complete(new CorrelationData.Confirm(false,"not-confirmed"));return null;}).when(rabbit).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));assertThrows(IllegalStateException.class,()->new ApiUsageWorker(usage,rabbit).receive(message(),channel));verify(channel,never()).basicAck(anyLong(),anyBoolean());}
}
