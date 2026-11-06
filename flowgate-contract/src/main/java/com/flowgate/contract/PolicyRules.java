package com.flowgate.contract;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** 两端共享的治理发布校验；历史待执行规则保持原义，不被新网关静默激活。 */
public final class PolicyRules {
    private PolicyRules() {}
    public static final String ENFORCED = "ENFORCED_BLOCK2";
    public static long id(Map<String,Object> entry, String field) {
        Object value = entry.get(field);
        Rules.require(value instanceof Number, "缺失数字字段 " + field);
        return ((Number)value).longValue();
    }
    public static boolean enforced(Map<String,Object> policy) {
        return Boolean.TRUE.equals(policy.get("enabled")) && ENFORCED.equals(policy.get("executionStatus"));
    }
    /** 完整参数必须在发布和应用前校验，热路径不补默认值。 */
    public static JsonNode parameters(String type, String json) {
        Set<String> fields = switch(type) {
            case "RATE_LIMIT" -> Set.of("capacity", "refillPerSecond");
            case "CONCURRENCY" -> Set.of("maxConcurrent");
            default -> throw new IllegalArgumentException("不支持的执行策略");
        };
        JsonNode node = SnapshotCodec.JSON.readTree(json);
        Rules.require(node.isObject() && node.size() == fields.size(), "治理参数结构错误");
        for (String field : fields) {
            JsonNode value = node.get(field);
            Rules.require(value != null && value.isIntegralNumber() && value.canConvertToLong()
                    && value.longValue() >= 1 && value.longValue() <= 1_000_000, "治理参数必须为1..1000000整数");
        }
        return node;
    }
    /** 校验快照关系和唯一规则；ROUTE 使用稳定字符串标识而非误用数据库 ID。 */
    public static void validate(Snapshot snapshot) {
        // 1. 仅新发布的执行规则进入治理，旧历史内容及 checksum 不改写。
        var policies = new HashMap<Long,Map<String,Object>>();
        for (var policy : snapshot.policies()) {
            Rules.require(policies.put(id(policy,"id"), policy) == null, "重复策略 ID");
            if (enforced(policy)) {
                String type = (String)policy.get("type"), scope = (String)policy.get("scope");
                parameters(type, (String)policy.get("parametersJson"));
                Rules.require(Set.of("GLOBAL","SERVICE","API","ROUTE","CALLER").contains(scope), "非法治理作用域");
            }
        }
        // 2. 已启用相同维度只能一条，避免隐式优先级；绑定必须引用存在的策略。
        var unique = new HashSet<String>();
        for (var binding : snapshot.bindings()) {
            var policy = policies.get(id(binding,"policyId"));
            Rules.require(policy != null, "悬空策略绑定");
            if (!enforced(policy)) continue;
            String scope=(String)binding.get("targetType"); long target=id(binding,"targetId");
            Rules.require(scope.equals(policy.get("scope")), "绑定作用域不一致");
            if ("CONCURRENCY".equals(policy.get("type"))) Rules.require(Set.of("SERVICE","ROUTE").contains(scope), "并发仅支持SERVICE/ROUTE");
            Rules.require(unique.add(policy.get("type")+":"+scope+":"+target), "同一目标存在重复启用策略");
            // 3. 引用必须有效且启用；Caller 的启用状态在 Control 数据库侧再次校验。
            boolean valid = switch(scope) {
                case "GLOBAL" -> target==0;
                case "SERVICE" -> enabled(snapshot.services(),target);
                case "API" -> enabled(snapshot.apis(),target);
                case "ROUTE" -> binding.get("targetRouteId") instanceof String routeId
                        && snapshot.routes().stream().anyMatch(r -> r.routeId().equals(routeId));
                case "CALLER" -> target>0;
                default -> false;
            };
            Rules.require(valid, "治理绑定目标不存在或未启用");
        }
    }
    private static boolean enabled(List<Map<String,Object>> entries,long target) {
        return entries.stream().anyMatch(e -> id(e,"id")==target && Boolean.TRUE.equals(e.get("enabled")));
    }
}
