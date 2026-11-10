package com.flowgate.control.security;

import com.flowgate.contract.Session;
import com.flowgate.control.service.IdentityService;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** MVC 管理边界：管理 Token 与内部密钥各司其职，不信任请求自报身份。 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {
    private final IdentityService identity;
    private final String internalKey;
    public AdminAuthInterceptor(IdentityService identity, @Value("${flowgate.internal-key}") String key) {
        if (key.length() < 32) throw new IllegalArgumentException("内部密钥至少32字符");
        this.identity = identity; this.internalKey = key;
    }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (Boolean.TRUE.equals(request.getAttribute("v11.auth.checked"))) return true;
        // 1. 控制面也使用独立请求标识，异常响应不泄露底层详情。
        String id = UUID.randomUUID().toString(); request.setAttribute("requestId", id); response.setHeader("X-Request-Id", id);
        String path = managementPath(request);
        if (path.startsWith("/internal/")) {
            String supplied = request.getHeader("X-FlowGate-Internal-Key");
            if (supplied == null || !MessageDigest.isEqual(internalKey.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiFailure(401, "INVALID_INTERNAL_KEY", "内部凭证无效");
            }
            return true;
        }
        if (path.equals("/admin/auth/login") && request.getMethod().equals("POST")) return true;
        // 2. 从正确用途的摘要会话恢复角色，未登录或用途错误一律拒绝。
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() > 300) throw new ApiFailure(401, "LOGIN_REQUIRED", "需要有效管理会话");
        String token = header.substring(7);
        Session session = identity.admin(token);
        request.setAttribute("session", session); request.setAttribute("token", token);
        // 3. 只读与修改分离，Caller 管理仅 ADMIN；未知管理路径由框架返回错误。
        boolean permitted = !path.startsWith("/admin/callers") || session.role().equals("ADMIN");
        permitted &= request.getMethod().equals("GET") || path.equals("/admin/auth/logout") || !session.role().equals("VIEWER");
        // V11新管理域全部写动作仅ADMIN；旧V1的OPERATOR写权限保持原契约。
        boolean newBusiness = v11Path(path);
        if (newBusiness && !request.getMethod().equals("GET")) permitted &= session.role().equals("ADMIN");
        if (!permitted) throw new ApiFailure(403, "FORBIDDEN", "当前角色无此操作权限");
        return true;
    }
    public static boolean v11Path(String path) {
        return java.util.List.of("/admin/market", "/admin/developers", "/admin/apps", "/admin/plans", "/admin/subscriptions", "/admin/quota", "/admin/usage", "/admin/async-events", "/admin/reconciliation")
                .stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }
    /** 与MVC路径段匹配一致：解码段值并移除矩阵参数，避免编码或分号绕开新域ACL。 */
    public static String managementPath(HttpServletRequest request) {
        return org.springframework.http.server.RequestPath.parse(request.getRequestURI(), request.getContextPath())
                .pathWithinApplication().elements().stream().map(element -> element instanceof org.springframework.http.server.PathContainer.PathSegment segment
                        ? segment.valueToMatch() : element.value()).collect(java.util.stream.Collectors.joining());
    }
}
