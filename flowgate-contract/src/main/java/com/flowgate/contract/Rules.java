package com.flowgate.contract;

import java.net.URI;
import java.util.*;

/** 草稿、发布、网关三处共用的最小安全约束，拒绝宽泛匹配和任意上游。 */
public final class Rules {
    private Rules() {}
    public static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    public static void text(String value, String name) {
        require(value != null && !value.isBlank() && value.length() <= 200, name + "不能为空且最多200字符");
    }
    public static void scope(String value) {
        require(value != null && value.matches("[A-Za-z0-9:_-]{1,100}"), "Scope 格式不合法");
    }
    public static void upstream(String value, Set<String> allowed) {
        require(value != null && !value.isBlank(), "上游地址不能为空");
        URI uri = URI.create(value);
        require(uri.getScheme() != null && Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null
                && uri.getPort() > 0 && uri.getPort() <= 65535 && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && allowed.contains(value), "上游必须精确匹配可信 scheme://host:port 白名单");
        require(!Set.of(8080, 8081).contains(uri.getPort()) && !uri.getHost().equals("169.254.169.254"), "禁止代理管理或元数据地址");
    }

    /** 只支持 /api 下的静态片段和单级变量；上游仅允许 /demo 下的对应变量。 */
    public static void paths(String method, String publicPath, String upstreamPath) {
        require(method != null && Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD").contains(method), "HTTP 方法不合法");
        var external = variables(publicPath, "/api/");
        var internal = variables(upstreamPath, "/demo/");
        require(external.containsAll(internal), "上游变量必须来自公共路径同名变量");
    }
    private static Set<String> variables(String path, String prefix) {
        require(path != null && path.startsWith(prefix) && path.length() <= 300, "路径前缀或长度不合法");
        var vars = new HashSet<String>();
        for (String part : path.substring(1).split("/", -1)) {
            require(part.matches("[A-Za-z0-9_-]+|\\{[A-Za-z][A-Za-z0-9_]*}"), "仅支持静态片段和单级变量，禁止通配/编码/正则");
            if (part.startsWith("{")) require(vars.add(part), "路径变量不能重复");
        }
        return vars;
    }
    public static boolean overlaps(RouteSpec a, RouteSpec b) {
        if (!a.method().equals(b.method())) return false;
        String[] left = a.publicPath().split("/"), right = b.publicPath().split("/");
        if (left.length != right.length) return false;
        for (int i = 0; i < left.length; i++) {
            if (!left[i].startsWith("{") && !right[i].startsWith("{") && !left[i].equals(right[i])) return false;
        }
        return true;
    }
    /** 完整验证后才可把候选快照交给 Gateway 的路由缓存。 */
    public static void routes(List<RouteSpec> routes, long version, Set<String> allowed) {
        var ids = new HashSet<String>();
        for (int i = 0; i < routes.size(); i++) {
            RouteSpec route = routes.get(i);
            text(route.routeId(), "routeId");
            require(ids.add(route.routeId()) && route.configVersion() == version, "路由标识重复或版本不一致");
            paths(route.method(), route.publicPath(), route.upstreamPath());
            upstream(route.upstreamUri(), allowed);
            if (!route.isPublic()) scope(route.requiredScope());
            for (int j = 0; j < i; j++) require(!overlaps(route, routes.get(j)), "HTTP 方法/路径存在重叠歧义");
        }
    }
}
