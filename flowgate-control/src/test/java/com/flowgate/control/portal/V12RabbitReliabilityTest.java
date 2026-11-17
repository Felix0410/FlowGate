package com.flowgate.control.portal;

import com.flowgate.control.messaging.RabbitDelivery;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class V12RabbitReliabilityTest {
    @Test void jackson3SyntaxAndWrongTypesArePermanent(){for(String bad:new String[]{"{broken","[]","null","{}","{\"version\":12.5}"}){var ex=assertThrows(IllegalArgumentException.class,()->ApiUsageGrantedEvent.parse(bad));assertEquals("INVALID_API_USAGE_EVENT",ex.getMessage());}}
    @Test void maxRetriesGoesToPoisonWithoutPublishing()throws Exception{var props=new MessageProperties();props.setDeliveryTag(5);props.setHeader(RabbitDelivery.RETRY_HEADER,12);var rabbit=mock(RabbitTemplate.class);var channel=mock(Channel.class);RabbitDelivery.receive(new Message("{}".getBytes(),props),channel,rabbit,"test",p->{throw new DataAccessResourceFailureException("offline");});verify(channel).basicReject(5,false);verifyNoInteractions(rabbit);}
    @Test void forgedRetryCounterIsRejected()throws Exception{var props=new MessageProperties();props.setDeliveryTag(3);props.setHeader(RabbitDelivery.RETRY_HEADER,-1);var channel=mock(Channel.class);var process=mock(java.util.function.Consumer.class);RabbitDelivery.receive(new Message("{}".getBytes(),props),channel,mock(RabbitTemplate.class),"test",process);verify(channel).basicReject(3,false);verifyNoInteractions(process);}
    @Test void offMarketDoesNotQueryAnyTable(){var db=mock(PortalStore.class);var readiness=new MarketReadiness(db,false);assertEquals("DISABLED",readiness.status().get("status"));verifyNoInteractions(db);}
}
