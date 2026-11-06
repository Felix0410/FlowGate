package com.flowgate.contract;

import java.util.List;
import java.util.Map;

/** 跨进程配置快照；目录白名单字段随发布冻结，不引用数据库实体。 */
public record Snapshot(int schemaVersion, long version, String checksum,
        List<Map<String, Object>> services, List<Map<String, Object>> apis,
        List<RouteSpec> routes, List<Map<String, Object>> policies, List<Map<String, Object>> bindings) {
    public Snapshot {
        services = freeze(services); apis = freeze(apis); routes = List.copyOf(routes);
        policies = freeze(policies); bindings = freeze(bindings);
    }
    private static List<Map<String, Object>> freeze(List<Map<String, Object>> entries) {
        return entries.stream().map(Map::copyOf).toList();
    }
}
