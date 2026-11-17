package com.flowgate.control.messaging;

import com.flowgate.control.security.ApiFailure;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;

/** V12共享传输边界：持久确认、有限重试、COMMIT后ACK。没有业务表或旧Kafka依赖。 */
public final class RabbitDelivery {
    public static final String RETRY_HEADER="x-flowgate-retry-count";
    public static final int MAX_RETRIES=12;
    private RabbitDelivery(){}
    public static void confirmed(RabbitTemplate rabbit,String exchange,String route,String payload,String eventId,int retries)throws Exception{
        var props=new MessageProperties();props.setContentType(MessageProperties.CONTENT_TYPE_JSON);props.setContentEncoding("UTF-8");props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);props.setMessageId(eventId);props.setHeader(RETRY_HEADER,retries);
        var correlation=new CorrelationData(java.util.UUID.randomUUID().toString());rabbit.send(exchange,route,new Message(payload.getBytes(StandardCharsets.UTF_8),props),correlation);
        var confirmation=correlation.getFuture().get(5,TimeUnit.SECONDS);if(!confirmation.ack()||correlation.getReturned()!=null)throw new IllegalStateException("RABBIT_PUBLISH_NOT_CONFIRMED");
    }
    public static int retries(Message message){
        Object raw=message.getMessageProperties().getHeaders().get(RETRY_HEADER);long count=0;
        if(raw!=null){if(!(raw instanceof Integer||raw instanceof Long||raw instanceof Short||raw instanceof Byte))throw new IllegalArgumentException("INVALID_RETRY_HEADER");count=((Number)raw).longValue();if(count<0||count>MAX_RETRIES)throw new IllegalArgumentException("INVALID_RETRY_HEADER");}
        // 1. Rabbit压缩同队列x-death记录；不只信客户端头，取Broker累计死亡次数的更大值。
        var deaths=message.getMessageProperties().getXDeathHeader();if(deaths!=null)for(var death:deaths){Object value=death.get("count");if(value instanceof Number number)count=Math.max(count,number.longValue());}
        return (int)Math.min(MAX_RETRIES,count);
    }
    public static void receive(Message message,Channel channel,RabbitTemplate rabbit,String exchange,java.util.function.Consumer<String> process)throws Exception{
        long tag=message.getMessageProperties().getDeliveryTag();int retries;
        try{retries=retries(message);if(message.getBody().length>4096)throw new IllegalArgumentException();}catch(IllegalArgumentException invalid){channel.basicReject(tag,false);return;}
        String payload=new String(message.getBody(),StandardCharsets.UTF_8);
        try{
            process.accept(payload);channel.basicAck(tag,false);
        }catch(IllegalArgumentException permanent){channel.basicReject(tag,false);}
        catch(ApiFailure failure){if(failure.status<500)channel.basicReject(tag,false);else retry(message,channel,rabbit,exchange,payload,retries);}
        catch(org.springframework.dao.NonTransientDataAccessException permanent){
            // 2. 资源连接失败可以恢复；约束/SQL错误等确定性失败保留Poison，不能无限回炉。
            if(permanent instanceof org.springframework.dao.DataAccessResourceFailureException)retry(message,channel,rabbit,exchange,payload,retries);else channel.basicReject(tag,false);
        }catch(RuntimeException transientOrUnknown){retry(message,channel,rabbit,exchange,payload,retries);}
    }
    private static void retry(Message message,Channel channel,RabbitTemplate rabbit,String exchange,String payload,int retries)throws Exception{
        long tag=message.getMessageProperties().getDeliveryTag();if(retries>=MAX_RETRIES){org.slf4j.LoggerFactory.getLogger(RabbitDelivery.class).warn("V12_MESSAGE_RETRY_EXHAUSTED：消息保留在Poison等待运营检查");channel.basicReject(tag,false);return;}
        // 3. 即使未知Runtime也最多12次；副本confirm且无return才ACK原消息，失败保留原件。
        confirmed(rabbit,exchange,"retry",payload,message.getMessageProperties().getMessageId(),retries+1);channel.basicAck(tag,false);
    }
}
