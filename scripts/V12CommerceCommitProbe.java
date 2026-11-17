import com.flowgate.control.FlowGateControlApplication;
import com.flowgate.control.commerce.*;
import com.flowgate.control.portal.PortalStore;
import com.rabbitmq.client.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** 只由隔离脚本运行：真实Worker事务COMMIT后故意丢失ACK，观察Broker重投和幂等。 */
public class V12CommerceCommitProbe {
    public static void main(String[] args)throws Exception{
        String payload=new BufferedReader(new InputStreamReader(System.in)).readLine();var event=CommerceEvent.parse(payload);
        var app=new SpringApplication(FlowGateControlApplication.class);app.setWebApplicationType(WebApplicationType.SERVLET);app.setDefaultProperties(Map.of("logging.level.root","ERROR","spring.main.banner-mode","off"));
        try(var context=app.run()){
            var factory=new ConnectionFactory();factory.setHost(System.getenv("FLOWGATE_RABBITMQ_HOST"));factory.setPort(Integer.parseInt(System.getenv("FLOWGATE_RABBITMQ_PORT")));factory.setUsername(System.getenv("FLOWGATE_RABBITMQ_USERNAME"));factory.setPassword(System.getenv("FLOWGATE_RABBITMQ_PASSWORD"));factory.setVirtualHost("/flowgate-v12");
            var db=context.getBean(PortalStore.class);var sourceOrder=db.need("SELECT api_id FROM fg_order WHERE id=?",event.orderId());
            long readyEnd=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);boolean ready=false;
            while(System.nanoTime()<readyEnd){try{context.getBean(com.flowgate.control.portal.V12Readiness.class).api(PortalStore.n(sourceOrder,"api_id"));ready=true;break;}catch(com.flowgate.control.security.ApiFailure blocked){Thread.sleep(500);}}if(!ready)throw new AssertionError("TRUSTED_GATEWAYS_NOT_READY");
            var worker=new CommerceWorker(context.getBean(CommerceService.class),context.getBean("apiUsageRabbitTemplate",RabbitTemplate.class));
            try(var connection=factory.newConnection()){
                Channel channel=connection.createChannel();String queue="flowgate.commerce.ackprobe."+UUID.randomUUID();channel.queueDeclare(queue,true,false,false,Map.of("x-queue-type","quorum"));channel.confirmSelect();channel.basicPublish("",queue,new AMQP.BasicProperties.Builder().deliveryMode(2).messageId(event.eventId()).build(),payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));channel.waitForConfirmsOrDie(5000);
                var first=get(channel,queue);Channel real=channel;
                Channel lostAck=(Channel)Proxy.newProxyInstance(Channel.class.getClassLoader(),new Class[]{Channel.class},(proxy,method,values)->{
                    if(method.getName().equals("basicAck")){real.close();throw new IOException("INJECTED_ACK_LOSS_AFTER_COMMIT");}
                    try{return method.invoke(real,values);}catch(InvocationTargetException failure){throw failure.getCause();}
                });
                boolean injected=false;try{worker.receive(message(first),lostAck);}catch(IOException expected){injected=expected.getMessage().equals("INJECTED_ACK_LOSS_AFTER_COMMIT");}if(!injected)throw new AssertionError("ACK_WINDOW_NOT_REACHED");
                var order=db.need("SELECT status FROM fg_order WHERE id=?",event.orderId());if(!"FULFILLED".equals(order.get("status")))throw new AssertionError("DB_NOT_COMMITTED_BEFORE_ACK_LOSS");
                try(var replay=connection.createChannel()){var second=get(replay,queue);if(!second.getEnvelope().isRedeliver())throw new AssertionError("NOT_BROKER_REDELIVERY");worker.receive(message(second),replay);if(replay.basicGet(queue,false)!=null)throw new AssertionError("ACK_NOT_APPLIED");}
                System.out.println("COMMIT_ACK_REDELIVERY_PASS");
            }
        }
    }
    static GetResponse get(Channel channel,String queue)throws Exception{long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){var result=channel.basicGet(queue,false);if(result!=null)return result;Thread.sleep(100);}throw new AssertionError("PROBE_MESSAGE_TIMEOUT");}
    static Message message(GetResponse delivery){var props=new MessageProperties();props.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());props.setMessageId(delivery.getProps().getMessageId());return new Message(delivery.getBody(),props);}
}
