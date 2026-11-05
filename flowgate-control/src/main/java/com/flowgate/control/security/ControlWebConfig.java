package com.flowgate.control.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

/** 管理与内部接口统一经过认证；静态测试台只提供客户端操作，不包含凭证。 */
@Configuration
public class ControlWebConfig implements WebMvcConfigurer {
    private final AdminAuthInterceptor auth;
    public ControlWebConfig(AdminAuthInterceptor auth) { this.auth = auth; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/admin/**", "/internal/**");
    }
    @Override public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/test-console/").setViewName("forward:/test-console/index.html");
    }
}
