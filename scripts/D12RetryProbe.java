import com.flowgate.control.messaging.RabbitDelivery;
import com.rabbitmq.client.*;
import java.util.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
/** 真Broker/5秒TTL/12次有限重试；业务边界注入明确的瞬态错误，不伪造confirm或x-death。 */
public class D12RetryProbe {
 public static void main(String[] args){try{run(args);System.exit(0);}catch(Throwable e){e.printStackTrace();System.exit(1);}}
 private static void run(String[] args)throws Exception{
  var factory=new CachingConnectionFactory(System.getenv("FLOWGATE_RABBITMQ_HOST"),Integer.parseInt(System.getenv("FLOWGATE_RABBITMQ_PORT")));factory.setUsername(System.getenv("FLOWGATE_RABBITMQ_USERNAME"));factory.setPassword(System.getenv("FLOWGATE_RABBITMQ_PASSWORD"));factory.setVirtualHost("/flowgate-v12");factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);factory.setPublisherReturns(true);var rabbit=new RabbitTemplate(factory);rabbit.setMandatory(true);
  String ex="flowgate.d.retry."+UUID.randomUUID(),main=ex+".main",retry=ex+".retry",poison=ex+".poison";
  try(var connection=factory.createConnection();var ch=connection.createChannel(false)){
   ch.exchangeDeclare(ex,"direct",true);ch.queueDeclare(main,true,false,false,Map.of("x-queue-type","quorum","x-dead-letter-exchange",ex,"x-dead-letter-routing-key","poison"));ch.queueDeclare(retry,true,false,false,Map.of("x-queue-type","quorum","x-message-ttl",5000,"x-dead-letter-exchange",ex,"x-dead-letter-routing-key","main"));ch.queueDeclare(poison,true,false,false,Map.of("x-queue-type","quorum"));ch.queueBind(main,ex,"main");ch.queueBind(retry,ex,"retry");ch.queueBind(poison,ex,"poison");
   RabbitDelivery.confirmed(rabbit,ex,"main","{}",UUID.randomUUID().toString(),0);int deliveries=0;long start=System.nanoTime();
   while(deliveries<13){GetResponse r=null;long deadline=System.nanoTime()+20_000_000_000L;while(r==null&&System.nanoTime()<deadline){r=ch.basicGet(main,false);if(r==null)Thread.sleep(50);}if(r==null)throw new AssertionError("TTL_MESSAGE_MISSING");var props=new org.springframework.amqp.core.MessageProperties();props.setDeliveryTag(r.getEnvelope().getDeliveryTag());props.setMessageId(r.getProps().getMessageId());props.getHeaders().putAll(r.getProps().getHeaders());var message=new Message(r.getBody(),props);RabbitDelivery.receive(message,ch,rabbit,ex,"retry",payload->{throw new org.springframework.dao.TransientDataAccessResourceException("INJECTED_TRANSIENT_DEPENDENCY");});deliveries++;}
   GetResponse dead=null;for(int i=0;i<100&&dead==null;i++){dead=ch.basicGet(poison,false);if(dead==null)Thread.sleep(50);}if(dead==null||ch.queueDeclarePassive(main).getMessageCount()!=0||ch.queueDeclarePassive(retry).getMessageCount()!=0)throw new AssertionError("POISON_NOT_RETAINED");ch.basicAck(dead.getEnvelope().getDeliveryTag(),false);System.out.println("REAL_12_RETRIES_13_DELIVERIES_POISON_PASS elapsedSeconds="+(System.nanoTime()-start)/1e9);ch.queueDelete(main);ch.queueDelete(retry);ch.queueDelete(poison);ch.exchangeDelete(ex);
  }finally{rabbit.destroy();factory.destroy();}
 }
}
