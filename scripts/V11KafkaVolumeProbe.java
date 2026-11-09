import java.util.*;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
/** 独立卷验收：真实ACK/offset提交后重建Broker，再校验消息和组位点均保留。 */
public class V11KafkaVolumeProbe {
 public static void main(String[]args)throws Exception{
  String bootstrap=args[0],mode=args[1],topic="flowgate.volume.proof",group="flowgate-volume-proof";var partition=new TopicPartition(topic,0);
  try(var admin=Admin.create(Map.of("bootstrap.servers",bootstrap))){
   // 1. 首次创建Topic并等待真实Broker ACK，重建校验不重新写入消息。
   if(mode.equals("write")){
    admin.createTopics(List.of(new NewTopic(topic,1,(short)1))).all().get(15,TimeUnit.SECONDS);
    var producerProps=new HashMap<String,Object>();producerProps.put("bootstrap.servers",bootstrap);producerProps.put("acks","all");producerProps.put("key.serializer","org.apache.kafka.common.serialization.StringSerializer");producerProps.put("value.serializer","org.apache.kafka.common.serialization.StringSerializer");
    try(var producer=new KafkaProducer<String,String>(producerProps)){producer.send(new ProducerRecord<>(topic,"proof","durable-checkpoint")).get(15,TimeUnit.SECONDS);}
   }
   var props=new HashMap<String,Object>();props.put("bootstrap.servers",bootstrap);props.put("group.id",group);props.put("enable.auto.commit",false);props.put("auto.offset.reset","earliest");props.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");props.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
   // 2. 明确从offset0读取保留消息，首次提交组offset1，避免auto-commit的不确定时序。
   try(var consumer=new KafkaConsumer<String,String>(props)){
    consumer.assign(List.of(partition));consumer.seek(partition,0);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);boolean found=false;
    while(!found&&System.nanoTime()<end)for(var record:consumer.poll(Duration.ofMillis(300))){if(record.offset()==0&&record.value().equals("durable-checkpoint"))found=true;}
    if(!found)throw new AssertionError("容器重建后消息缺失");
    if(mode.equals("write"))consumer.commitSync(Map.of(partition,new OffsetAndMetadata(1)));
   }
   // 3. 容器重建后独立查询Broker中的消费组位点，不能只凭客户端本地位置宣称持久。
   var offset=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(15,TimeUnit.SECONDS).get(partition);
   if(offset==null||offset.offset()!=1)throw new AssertionError("已提交offset缺失");
   System.out.println("{\"passed\":true,\"messageOffset\":0,\"committedOffset\":1}");
  }
 }
}
