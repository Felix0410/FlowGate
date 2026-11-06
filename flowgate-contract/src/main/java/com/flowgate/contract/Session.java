package com.flowgate.contract;

import java.util.List;

/** Redis 会话最小载荷；Token 只在签发时返回，持久化与日志不得包含明文。 */
public record Session(long principalId, String principalType, String role, List<String> scopes) {
    public Session { scopes = List.copyOf(scopes); }
}
