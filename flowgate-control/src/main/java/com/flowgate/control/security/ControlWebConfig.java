package com.flowgate.control.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

/** 管理与内部接口统一经过认证；产品页和开发工具只提供无内置凭证的静态客户端。 */
@Configuration
public class ControlWebConfig implements WebMvcConfigurer {
    private final AdminAuthInterceptor auth;
    public ControlWebConfig(AdminAuthInterceptor auth) { this.auth = auth; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/admin/**", "/internal/**");
    }
    @Override public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("redirect:/console/");
        registry.addViewController("/portal").setViewName("redirect:/portal/");
        registry.addViewController("/portal/").setViewName("forward:/portal/index.html");
        registry.addViewController("/console").setViewName("redirect:/console/");
        registry.addViewController("/console/").setViewName("forward:/console/index.html");
        registry.addViewController("/test-console/").setViewName("forward:/test-console/index.html");
    }
}
