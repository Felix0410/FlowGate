package com.flowgate.control.async;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;
/** 运营页有界真实Broker探测，5秒缓存并展示检查时间；不参与Quota热路径。 */
@Component
public class AsyncBrokerProbe {
 private final boolean enabled;private final Admin admin;private Instant checked=Instant.EPOCH;private String state="UNKNOWN";
 public AsyncBrokerProbe(@Value("${flowgate.async.enabled:false}")boolean enabled,KafkaAdmin kafka){this.enabled=enabled;if(enabled){var config=new HashMap<String,Object>(kafka.getConfigurationProperties());config.put("request.timeout.ms",1000);config.put("default.api.timeout.ms",2000);admin=Admin.create(config);}else admin=null;}
 public boolean enabled(){return enabled;}
 public synchronized Map<String,Object> status(){
  // 1. 默认关闭时不创建/连接Admin客户端，避免把Kafka加入旧V1/B依赖。
  if(!enabled)return Map.of("state","ASYNC_DISABLED","checkedAt",Instant.now());
  // 2. 每5秒最多一次真实集群请求，等待最多2秒；未知或故障不能标为可用。
  if(Duration.between(checked,Instant.now()).getSeconds()>=5){
   try{admin.describeCluster().clusterId().get(2,TimeUnit.SECONDS);state="BROKER_AVAILABLE";}
   catch(Exception unavailable){state="BROKER_UNAVAILABLE";if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();}
   checked=Instant.now();
  }
  // 3. 同时返回实际检查时间，短缓存状态不是即时事件流。
  return Map.of("state",state,"checkedAt",checked);
 }

 @PreDestroy public void close(){if(admin!=null)admin.close(Duration.ofSeconds(1));}
}
