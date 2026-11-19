package com.flowgate.control.async;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
/** 旧用量默认关闭；启用后只使用Rabbit，缺少DB栅栏时停止领取。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="flowgate.async",name="enabled",havingValue="true")
public class AsyncConfiguration {}
