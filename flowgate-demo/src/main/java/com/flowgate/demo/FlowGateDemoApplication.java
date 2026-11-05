package com.flowgate.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 独立 MVC 演示下游，无数据库和中间件依赖。 */
@SpringBootApplication
public class FlowGateDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlowGateDemoApplication.class, args);
    }
}
