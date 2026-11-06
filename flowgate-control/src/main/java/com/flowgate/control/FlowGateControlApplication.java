package com.flowgate.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** MVC 控制面入口，持久化与阻塞会话访问仅在本进程内执行。 */
@SpringBootApplication
public class FlowGateControlApplication {
    public static void main(String[] args) { SpringApplication.run(FlowGateControlApplication.class, args); }
}
