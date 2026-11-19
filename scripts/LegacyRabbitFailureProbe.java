import com.flowgate.control.FlowGateControlApplication;
import com.flowgate.control.async.*;
import com.flowgate.control.service.UsageAggregationService;
import com.flowgate.control.messaging.RabbitDelivery;
import com.rabbitmq.client.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

/** 仅新owner隔离故障探针：真实NACK/Return与DB COMMIT后关闭真实Channel造成Broker redelivery。 */
public class LegacyRabbitFailureProbe {
    public static void main(String[] args){try{run(args);System.exit(0);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
    private static void run(String[] args)throws Exception{
        var input=new BufferedReader(new InputStreamReader(System.in));
        var factory=new ConnectionFactory();factory.setHost(System.getenv("FLOWGATE_RABBITMQ_HOST"));factory.setPort(Integer.parseInt(System.getenv("FLOWGATE_RABBITMQ_PORT")));factory.setUsername(System.getenv("FLOWGATE_RABBITMQ_USERNAME"));factory.setPassword(System.getenv("FLOWGATE_RABBITMQ_PASSWORD"));factory.setVirtualHost("/flowgate-v12");factory.setConnectionTimeout(2000);
        try(var connection=factory.newConnection()){
            if(args[0].equals("confirm")){
                var spring=new CachingConnectionFactory(factory);spring.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);spring.setPublisherReturns(true);var rabbit=new RabbitTemplate(spring);rabbit.setMandatory(true);String queue="flowgate.m6.nack."+UUID.randomUUID();
                try(var channel=connection.createChannel()){
                    channel.queueDeclare(queue,true,false,false,Map.of("x-queue-type","quorum","x-max-length",1,"x-overflow","reject-publish"));
                    RabbitDelivery.confirmed(rabbit,"",queue,"{}","first",0);boolean nack=false,returned=false;
                    for(int attempt=0;attempt<20&&!nack;attempt++){try{RabbitDelivery.confirmed(rabbit,"",queue,"{}","overflow-"+attempt,0);}catch(IllegalStateException expected){nack=true;}}
                    try{RabbitDelivery.confirmed(rabbit,"","flowgate.missing."+UUID.randomUUID(),"{}","return",0);}catch(IllegalStateException expected){returned=true;}
                    if(!nack||!returned)throw new AssertionError("REAL_NACK_OR_RETURN_NOT_OBSERVED");channel.queueDelete(queue);System.out.println("REAL_NACK_RETURN_PASS");
                }finally{rabbit.destroy();spring.destroy();}return;
            }
            String payload=input.readLine();var event=UsageGrantedEvent.parse(payload);var app=new SpringApplication(FlowGateControlApplication.class);app.setDefaultProperties(Map.of("logging.level.root","ERROR","spring.main.banner-mode","off"));
            try(var context=app.run()){
                var aggregation=context.getBean(UsageAggregationService.class);var fence=context.getBean(LegacyTransportFence.class);
                var rabbit=new RabbitTemplate(new CachingConnectionFactory(factory));var worker=new LegacyUsageRabbitListener(aggregation,rabbit,fence,new AsyncDiagnostics());
                Channel channel=connection.createChannel();String queue="flowgate.legacy.ackprobe."+UUID.randomUUID();channel.queueDeclare(queue,true,false,false,Map.of("x-queue-type","quorum"));channel.confirmSelect();channel.basicPublish("",queue,new AMQP.BasicProperties.Builder().deliveryMode(2).messageId(event.eventId()).build(),payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));channel.waitForConfirmsOrDie(5000);
                var first=get(channel,queue);Channel real=channel;
                Channel lostAck=(Channel)Proxy.newProxyInstance(Channel.class.getClassLoader(),new Class[]{Channel.class},(proxy,method,values)->{
                    if(method.getName().equals("basicAck")){real.close();throw new IOException("INJECTED_ACK_LOSS_AFTER_COMMIT");}
                    try{return method.invoke(real,values);}catch(InvocationTargetException failure){throw failure.getCause();}
                });
                boolean injected=false;try{worker.receive(message(first),lostAck);}catch(IOException expected){injected=expected.getMessage().equals("INJECTED_ACK_LOSS_AFTER_COMMIT");}if(!injected)throw new AssertionError("ACK_WINDOW_NOT_REACHED");
                var jdbc=context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);if(jdbc.queryForObject("SELECT COUNT(*) FROM fg_consumed_event WHERE event_id=?",Integer.class,event.eventId())!=1)throw new AssertionError("DB_NOT_COMMITTED");
                try(var replay=connection.createChannel()){var second=get(replay,queue);if(!second.getEnvelope().isRedeliver())throw new AssertionError("NOT_BROKER_REDELIVERY");worker.receive(message(second),replay);if(replay.basicGet(queue,false)!=null)throw new AssertionError("ACK_NOT_APPLIED");replay.queueDelete(queue);}
                System.out.println("LEGACY_COMMIT_ACK_REDELIVERY_PASS");
            }
        }
    }
    static GetResponse get(Channel channel,String queue)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){var result=channel.basicGet(queue,false);if(result!=null)return result;Thread.sleep(100);}throw new AssertionError("PROBE_MESSAGE_TIMEOUT");}
    static Message message(GetResponse delivery){var props=new MessageProperties();props.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());props.setMessageId(delivery.getProps().getMessageId());return new Message(delivery.getBody(),props);}
}
