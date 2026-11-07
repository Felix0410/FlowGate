package com.flowgate.control.security;

import com.flowgate.contract.SnapshotCodec;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 新管理域在MVC方法匹配前鉴权，使未开放PUT/DELETE也先按角色返回401/403。 */
@Component
public class V11ManagementFilter extends OncePerRequestFilter {
    private final AdminAuthInterceptor auth;
    public V11ManagementFilter(AdminAuthInterceptor auth) { this.auth=auth; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !AdminAuthInterceptor.v11Path(AdminAuthInterceptor.managementPath(request)); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        // 1. 复用唯一的管理Session与ACL实现，不触碰旧V1路径或Caller凭证逻辑。
        try { auth.preHandle(request,response,this); request.setAttribute("v11.auth.checked",true); }
        catch(ApiFailure failure) { error(request,response,failure.status,failure.code,failure.getMessage());return; }
        catch(org.springframework.dao.DataAccessException unavailable) { error(request,response,503,"DEPENDENCY_UNAVAILABLE","会话服务不可用");return; }
        // 2. 认证通过后交给MVC；支持的动作继续业务校验，不支持的方法由MVC返回405。
        chain.doFilter(request,response);
    }
    private void error(HttpServletRequest request,HttpServletResponse response,int status,String code,String message) throws IOException {
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SnapshotCodec.JSON.writeValueAsString(Map.of("code",code,"message",message,"requestId",String.valueOf(request.getAttribute("requestId")))));
    }
}
