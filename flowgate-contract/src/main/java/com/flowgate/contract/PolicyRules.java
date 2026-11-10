package com.flowgate.contract;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** 两端共享的治理发布校验；历史待执行规则保持原义，不被新网关静默激活。 */
public final class PolicyRules {
    private PolicyRules() {}
    public static final String ENFORCED = "ENFORCED_BLOCK2";
    public static final String PROTECTED = "ENFORCED_BLOCK3";
    public static final String QUOTA_V12 = "ENFORCED_QUOTA_V12";
    public static final String QUOTA = "ENFORCED_QUOTA_V1";
    public static String execution(String type) { return type.equals("USAGE_QUOTA_V12")?QUOTA_V12:type.equals("USAGE_QUOTA")?QUOTA:Set.of("RATE_LIMIT","CONCURRENCY").contains(type)?ENFORCED:PROTECTED; }
    public static boolean active(Map<String,Object> policy) {
        return Boolean.TRUE.equals(policy.get("enabled")) && Set.of(ENFORCED,PROTECTED,QUOTA,QUOTA_V12).contains(String.valueOf(policy.get("executionStatus")));
    }
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
        // 1. 固定业务字段；只有CB允许可选的受控fallbackMode。
        Set<String> fields = switch(type) {
            case "RATE_LIMIT" -> Set.of("capacity", "refillPerSecond");
            case "CONCURRENCY" -> Set.of("maxConcurrent");
            case "TIMEOUT" -> Set.of("timeoutMs");
            case "USAGE_QUOTA", "USAGE_QUOTA_V12" -> Set.of("unitsPerCall");
            case "CIRCUIT_BREAKER" -> Set.of("failureRateThreshold","slidingWindowSize","minimumNumberOfCalls","waitDurationMs");
            default -> throw new IllegalArgumentException("不支持的执行策略");
        };
        JsonNode node = SnapshotCodec.JSON.readTree(json);
        boolean fallback=type.equals("CIRCUIT_BREAKER") && node.has("fallbackMode");
        Rules.require(node.isObject() && node.size() == fields.size()+(fallback?1:0), "治理参数结构错误");
        // 2. 数字必须完整、为整数且有统一精度上限，不在热路径补默认值。
        for (String field : fields) {
            JsonNode value = node.get(field);
            Rules.require(value != null && value.isIntegralNumber() && value.canConvertToLong()
                    && value.longValue() >= 1 && value.longValue() <= 1_000_000, "治理参数必须为1..1000000整数");
        }
        // 3. 保护类型再收紧业务范围，保留旧四字段CB参数契约。
        if(type.equals("USAGE_QUOTA") || type.equals("USAGE_QUOTA_V12")) Rules.require(node.get("unitsPerCall").intValue()<=100,"每次准入单位须1..100");
        if(type.equals("TIMEOUT")) Rules.require(node.get("timeoutMs").intValue()>=50 && node.get("timeoutMs").intValue()<=10000,"timeoutMs必须50..10000");
        if(type.equals("CIRCUIT_BREAKER")) {
            Rules.require(node.get("failureRateThreshold").intValue()<=100,"失败率必须1..100");
            int window=node.get("slidingWindowSize").intValue();
            Rules.require(window>=2 && window<=100 && node.get("minimumNumberOfCalls").intValue()<=window,"窗口必须2..100且不小于最小样本量");
            int wait=node.get("waitDurationMs").intValue(); Rules.require(wait>=500 && wait<=60000,"等待时间必须500..60000ms");
            if(fallback) Rules.require(node.get("fallbackMode").isString() && Set.of("NONE","STATIC_JSON").contains(node.get("fallbackMode").stringValue()),"fallbackMode非法");
        }
        return node;
    }
    /** 校验快照关系和唯一规则；ROUTE 使用稳定字符串标识而非误用数据库 ID。 */
    public static void validate(Snapshot snapshot) {
        // 1. 仅新发布的执行规则进入治理，旧历史内容及 checksum 不改写。
        var policies = new HashMap<Long,Map<String,Object>>();
        for (var policy : snapshot.policies()) {
            Rules.require(policies.put(id(policy,"id"), policy) == null, "重复策略 ID");
            if (active(policy)) {
                String type = (String)policy.get("type"), scope = (String)policy.get("scope");
                Rules.require(execution(type).equals(policy.get("executionStatus")),"执行标记与策略类型不一致");
                parameters(type, (String)policy.get("parametersJson"));
                Rules.require(Set.of("GLOBAL","SERVICE","API","ROUTE","CALLER").contains(scope), "非法治理作用域");
            }
        }
        // 2. 已启用相同维度只能一条，避免隐式优先级；绑定必须引用存在的策略。
        var unique = new HashSet<String>();
        for (var binding : snapshot.bindings()) {
            var policy = policies.get(id(binding,"policyId"));
            Rules.require(policy != null, "悬空策略绑定");
            if (!active(policy)) continue;
            String scope=(String)binding.get("targetType"); long target=id(binding,"targetId");
            Rules.require(scope.equals(policy.get("scope")), "绑定作用域不一致");
            if (Set.of("USAGE_QUOTA","USAGE_QUOTA_V12").contains(policy.get("type"))) {
                Rules.require(Set.of("API","ROUTE").contains(scope),"配额仅支持API/ROUTE");
                if("API".equals(scope)) {
                    var api=snapshot.apis().stream().filter(a->id(a,"id")==target).findFirst().orElse(Map.of());
                    Rules.require(Boolean.FALSE.equals(api.get("isPublic"))&&snapshot.routes().stream().anyMatch(r->r.method().equals(api.get("method"))&&r.publicPath().equals(api.get("publicPath"))),"计量API须为私有且已有明确路由");
                }
            }
            if ("CONCURRENCY".equals(policy.get("type"))) Rules.require(Set.of("SERVICE","ROUTE").contains(scope), "并发仅支持SERVICE/ROUTE");
            if ("TIMEOUT".equals(policy.get("type"))) Rules.require(Set.of("SERVICE","API","ROUTE").contains(scope),"超时仅支持SERVICE/API/ROUTE");
            if ("CIRCUIT_BREAKER".equals(policy.get("type"))) {
                Rules.require("ROUTE".equals(scope),"熔断仅支持ROUTE");
                var params=parameters("CIRCUIT_BREAKER",(String)policy.get("parametersJson"));
                if(params.has("fallbackMode") && "STATIC_JSON".equals(params.get("fallbackMode").stringValue())) {
                    Rules.require(snapshot.routes().stream().anyMatch(r -> r.routeId().equals(binding.get("targetRouteId")) && r.method().equals("GET")),"静态降级仅允许只读GET路由");
                }
            }
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
        snapshot.routes().forEach(route -> QuotaPolicy.freeze(snapshot,route));
    }

    private static boolean enabled(List<Map<String,Object>> entries,long target) {
        return entries.stream().anyMatch(e -> id(e,"id")==target && Boolean.TRUE.equals(e.get("enabled")));
    }
}
