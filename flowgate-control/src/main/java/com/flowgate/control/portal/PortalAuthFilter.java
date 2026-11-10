package com.flowgate.control.portal;

import com.flowgate.contract.SnapshotCodec;
import com.flowgate.control.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在MVC方法匹配前保护全部Portal API；公开市场只开放GET。 */
@Component
public class PortalAuthFilter extends OncePerRequestFilter {
    private final PortalIdentityService identity;private final boolean enabled;private final Set<String> origins;
    public PortalAuthFilter(PortalIdentityService identity,@Value("${FLOWGATE_PORTAL_ENABLED:false}")boolean enabled,@Value("${FLOWGATE_PORTAL_ORIGINS:}")String origins){this.identity=identity;this.enabled=enabled;this.origins=new HashSet<>();for(var o:origins.split(","))if(!o.isBlank())this.origins.add(o.trim());}
    @Override protected boolean shouldNotFilter(HttpServletRequest r){String p=AdminAuthInterceptor.managementPath(r);return !p.equals("/portal/api")&&!p.startsWith("/portal/api/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String requestId=UUID.randomUUID().toString();request.setAttribute("requestId",requestId);response.setHeader("X-Request-Id",requestId);response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");
        try{
            if(!enabled)throw new ApiFailure(503,"PORTAL_DISABLED","Portal尚未启用，请联系平台运营");
            // 1. 限制小型JSON请求；分块请求使用计数流同样有硬上限。
            if(request.getContentLengthLong()>16384)throw new ApiFailure(413,"REQUEST_TOO_LARGE","请求内容过大");
            String origin=request.getHeader("Origin");
            if(origin!=null){String local=request.getScheme()+"://"+request.getServerName()+((request.getServerPort()==80||request.getServerPort()==443)?"":":"+request.getServerPort());if(!origins.contains(origin)&&!local.equals(origin))throw new ApiFailure(403,"ORIGIN_FORBIDDEN","来源未获允许");URI.create(origin);}
            String path=AdminAuthInterceptor.managementPath(request);
            boolean publicPath=request.getMethod().equals("GET")&&path.startsWith("/portal/api/catalog/")||request.getMethod().equals("POST")&&Set.of("/portal/api/auth/register","/portal/api/auth/login").contains(path);
            // 2. 不读取客户端userId/owner；会话用途与禁用状态在每次请求复验。
            if(!publicPath){String h=request.getHeader("Authorization");String token=h!=null&&h.startsWith("Bearer ")?h.substring(7):null;request.setAttribute("portalUser",identity.authenticate(token));request.setAttribute("portalToken",token);}
        }catch(ApiFailure failure){error(response,failure.status,failure.code,failure.getMessage(),requestId);return;}
        catch(org.springframework.dao.DataAccessException unavailable){error(response,503,"DEPENDENCY_UNAVAILABLE","认证依赖暂不可用",requestId);return;}
        // 3. 包装输入流防止未知Content-Length绕过内存上限，不打印任何请求体。
        chain.doFilter(new HttpServletRequestWrapper(request){
            @Override public ServletInputStream getInputStream()throws IOException{var source=super.getInputStream();return new ServletInputStream(){int total;public boolean isFinished(){return source.isFinished();}public boolean isReady(){return source.isReady();}public void setReadListener(ReadListener listener){source.setReadListener(listener);}public int read()throws IOException{int b=source.read();if(b>=0&&++total>16384)throw new IOException("REQUEST_TOO_LARGE");return b;}};}
        },response);
    }
    private void error(HttpServletResponse r,int status,String code,String message,String id)throws IOException{r.setStatus(status);r.setContentType("application/json;charset=UTF-8");r.getWriter().write(SnapshotCodec.JSON.writeValueAsString(Map.of("code",code,"message",message,"requestId",id)));}
}
