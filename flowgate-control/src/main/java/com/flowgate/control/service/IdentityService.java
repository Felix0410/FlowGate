package com.flowgate.control.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.flowgate.contract.*;
import com.flowgate.control.entity.Principal;
import com.flowgate.control.mapper.PrincipalMapper;
import com.flowgate.control.security.ApiFailure;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理会话和 Caller 凭证：用途分离、摘要索引、固定 TTL，不记录明文 Token。 */
@Service
public class IdentityService {
    private final PrincipalMapper principals;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash = passwords.encode(UUID.randomUUID().toString());
    private final long adminTtl;
    private final long callerTtl;
    private final AuditService audit;

    public IdentityService(PrincipalMapper principals, StringRedisTemplate redis,
            @Value("${flowgate.admin-ttl-seconds}") long adminTtl,
            @Value("${flowgate.caller-ttl-seconds}") long callerTtl, AuditService audit) {
        this.principals = principals; this.redis = redis; this.adminTtl = adminTtl; this.callerTtl = callerTtl;
        this.audit = audit;
    }

    /** 无论用户名不存在或密码错误，都执行 BCrypt 校验并返回相同错误。 */
    public Map<String, Object> login(String username, String password) {
        // 1. 限定输入并查询主体，不让不存在用户名跳过密码计算。
        Rules.text(username, "用户名");
        Rules.require(password != null && password.length() <= 200, "密码长度不合法");
        Principal principal = principals.selectOne(new QueryWrapper<Principal>().eq("username", username));
        boolean matches = passwords.matches(password, principal == null || principal.passwordHash == null ? dummyHash : principal.passwordHash);
        // 2. 主体用途和启用状态必须同时满足，对外统一认证失败信息。
        if (!matches || principal == null || !Boolean.TRUE.equals(principal.enabled)
                || !"ADMIN".equals(principal.principalType)) {
            throw new ApiFailure(401, "INVALID_CREDENTIAL", "用户名或密码错误");
        }
        String token = token();
        // 3. Redis 明确成功写入摘要会话后，才把一次性明文 Token 返回调用方。
        redis.opsForValue().set("fg:auth:admin:" + SnapshotCodec.digest(token),
                SnapshotCodec.JSON.writeValueAsString(new Session(principal.id, "ADMIN", principal.role, List.of())), Duration.ofSeconds(adminTtl));
        return Map.of("token", token, "expiresInSeconds", adminTtl, "role", principal.role);
    }

    public Session admin(String token) {
        String json = redis.opsForValue().get("fg:auth:admin:" + SnapshotCodec.digest(token));
        if (json == null) throw new ApiFailure(401, "INVALID_SESSION", "管理会话无效或已到期");
        Session session = SnapshotCodec.JSON.readValue(json, Session.class);
        if (!"ADMIN".equals(session.principalType()) || !Set.of("ADMIN", "OPERATOR", "VIEWER").contains(session.role())) {
            throw new ApiFailure(401, "INVALID_SESSION", "管理会话无效");
        }
        return session;
    }

    public void logout(String token) { redis.delete("fg:auth:admin:" + SnapshotCodec.digest(token)); }

    /** 同一事务创建 Caller，Redis 的两类 key 原子维护；会话写失败则数据库回滚。 */
    @Transactional
    public Map<String, Object> createCaller(String username, List<String> scopes, long actor) {
        // 1. 校验身份输入，主体只保存 Scope 和用途，不保存凭证。
        Rules.text(username, "用户名");
        Rules.require(scopes != null && scopes.size() <= 100, "Scope 列表不合法");
        scopes.forEach(Rules::scope);
        Principal principal = new Principal();
        principal.username = username; principal.principalType = "CALLER"; principal.role = "CALLER";
        principal.scopesJson = SnapshotCodec.JSON.writeValueAsString(scopes); principal.enabled = true;
        principals.insert(principal);
        // 2. 固定 key 前缀避免用途混用，Lua 原子替换旧会话及索引，确保每个主体只有一个 Token。
        String token = token(), digest = SnapshotCodec.digest(token);
        String key = "fg:auth:caller:" + digest, index = "fg:auth:caller-index:" + principal.id;
        String json = SnapshotCodec.JSON.writeValueAsString(new Session(principal.id, "CALLER", "CALLER", scopes));
        redis.execute(new DefaultRedisScript<>("""
                -- 1. 删除该主体旧的凭证，防止更换后双 Token 并存。
                local old = redis.call('GET', KEYS[2])
                if old then redis.call('DEL', 'fg:auth:caller:' .. old) end
                -- 2. 会话和摘要索引同时设置相同 TTL。
                redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
                redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
                return 1
                """, Long.class), List.of(key, index), json, digest, Long.toString(callerTtl));
        // 3. 数据库提交失败时删除刚写入的会话，不给客户端返回孤立有效凭证。
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) redis.delete(List.of(key, index));
                    }
                });
        audit.record(actor, "CREATE_CALLER", "caller", principal.id.toString(), null, "创建调用方及授权");
        return Map.of("id", principal.id, "token", token, "expiresInSeconds", callerTtl);
    }

    /** 仅成功执行原子删除才宣称吊销；不存在的主体返回 404。 */
    @Transactional
    public void revoke(long id, long actor) {
        Principal principal = principals.selectById(id);
        if (principal == null || !"CALLER".equals(principal.principalType)) throw new ApiFailure(404, "NOT_FOUND", "Caller 不存在");
        redis.execute(new DefaultRedisScript<>("""
                -- 1. 从主体索引找当前摘要，不需要保存或恢复明文 Token。
                local digest = redis.call('GET', KEYS[1])
                -- 2. 同一原子操作删除会话和索引，重复吊销仍安全。
                if digest then redis.call('DEL', 'fg:auth:caller:' .. digest) end
                redis.call('DEL', KEYS[1])
                return 1
                """, Long.class), List.of("fg:auth:caller-index:" + id));
        audit.record(actor, "REVOKE_CALLER", "caller", Long.toString(id), null, "吊销调用方凭证");
    }

    private String token() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
