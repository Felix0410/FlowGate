import com.rabbitmq.client.*;
import java.io.*;
/** 仅供隔离Smoke投递两个重复事件；配置从stdin读取，不进入进程参数或日志。 */
public class V12RabbitPublishProbe {
    public static void main(String[] args)throws Exception{
        var in=new BufferedReader(new InputStreamReader(System.in));var factory=new ConnectionFactory();factory.setHost(in.readLine());factory.setPort(Integer.parseInt(in.readLine()));factory.setUsername(in.readLine());factory.setPassword(in.readLine());factory.setVirtualHost("/flowgate-v12");factory.setConnectionTimeout(3000);
        try(var connection=factory.newConnection();var channel=connection.createChannel()){channel.confirmSelect();if(args.length>2 && args[2].equals("unbind")){channel.queueUnbind("flowgate.usage.v12.aggregate",args[0],args[1]);return;}if(args.length>2 && args[2].equals("bind")){channel.queueBind("flowgate.usage.v12.aggregate",args[0],args[1]);return;}if(args.length>1 && args[0].isEmpty() && args[1].equals("flowgate.b0.persist"))channel.queueDeclare(args[1],true,false,false,java.util.Map.of("x-queue-type","quorum"));for(String payload;(payload=in.readLine())!=null;){var props=new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build();channel.basicPublish(args.length>0?args[0]:"flowgate.usage.v12",args.length>1?args[1]:"granted",true,props,payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));}channel.waitForConfirmsOrDie(5000);}
    }
}
