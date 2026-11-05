package com.flowgate.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 独立的响应式网关入口，只负责本地 Demo 的静态 HTTP 代理。 */
@SpringBootApplication
public class FlowGateGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlowGateGatewayApplication.class, args);
    }
}
