package com.flowgate.control.controller;

import com.flowgate.contract.Session;
import com.flowgate.control.service.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 登录/退出及 Caller 管理；凭证只在签发响应中出现。 */
@RestController
public class AdminAuthController {
    private final IdentityService identity;
    public AdminAuthController(IdentityService identity) { this.identity = identity; }
    public record Login(String username, String password) {}
    public record Caller(String username, List<String> scopes) {}
    @PostMapping("/admin/auth/login")
    public Object login(@RequestBody Login input) { return identity.login(input.username(), input.password()); }
    @PostMapping("/admin/auth/logout") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) { identity.logout((String)request.getAttribute("token")); }
    @PostMapping("/admin/callers") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Object caller(@RequestBody Caller input, HttpServletRequest request) {
        long actor = ((Session)request.getAttribute("session")).principalId();
        var result = identity.createCaller(input.username(), input.scopes(), actor);
        return result;
    }
    @PostMapping("/admin/callers/{id}/revoke") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable long id, HttpServletRequest request) {
        identity.revoke(id, ((Session)request.getAttribute("session")).principalId());
    }
}
