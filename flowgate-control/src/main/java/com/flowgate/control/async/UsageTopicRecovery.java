package com.flowgate.control.async;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** 首次启动无Broker时，恢复后重试已声明Topic；不开放自动乱建、不进入B授权事务。 */
@Component
@ConditionalOnProperty(prefix="flowgate.async",name="enabled",havingValue="true")
public class UsageTopicRecovery {
 private final KafkaAdmin admin;private final AsyncDiagnostics diagnostics;
 public UsageTopicRecovery(KafkaAdmin admin,AsyncDiagnostics diagnostics){this.admin=admin;this.diagnostics=diagnostics;}
 @Scheduled(initialDelay=5000,fixedDelay=30000) public void recover(){
  // 1. 使用同一KafkaAdmin，只处理配置中显式声明的NewTopic，操作已有3秒上限。
  try{if(!admin.initialize())diagnostics.count("workerUnavailable");}
  // 2. Broker故障只影响异步诊断；保留原事件和租约，不让B授权依赖此结果。
  catch(RuntimeException unavailable){diagnostics.count("workerUnavailable");}
 }
}
