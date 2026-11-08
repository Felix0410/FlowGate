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
 public synchronized Map<String,Object> status(){if(!enabled)return Map.of("state","ASYNC_DISABLED","checkedAt",Instant.now());if(Duration.between(checked,Instant.now()).getSeconds()>=5){try{admin.describeCluster().clusterId().get(2,TimeUnit.SECONDS);state="BROKER_AVAILABLE";}catch(Exception unavailable){state="BROKER_UNAVAILABLE";if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();}checked=Instant.now();}return Map.of("state",state,"checkedAt",checked);}
 @PreDestroy public void close(){if(admin!=null)admin.close(Duration.ofSeconds(1));}
}
