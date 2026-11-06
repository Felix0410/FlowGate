package com.flowgate.control.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.contract.*;
import com.flowgate.control.entity.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.security.ApiFailure;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 控制面草稿目录；修改只影响下次发布，禁止直接修改已发布快照。 */
@Service
public class CatalogService {
    private final ServiceEntryMapper services;
    private final ApiEntryMapper apis;
    private final RouteEntryMapper routes;
    private final PolicyEntryMapper policies;
    private final BindingEntryMapper bindings;
    private final PrincipalMapper principals;
    private final AuditService audit;
    private final Set<String> allowed;
    public CatalogService(ServiceEntryMapper services, ApiEntryMapper apis, RouteEntryMapper routes,
            PolicyEntryMapper policies, BindingEntryMapper bindings, PrincipalMapper principals, AuditService audit,
            @Value("${flowgate.allowed-upstreams}") String allowed) {
        this.services = services; this.apis = apis; this.routes = routes; this.policies = policies;
        this.bindings = bindings; this.principals = principals; this.audit = audit;
        this.allowed = Set.copyOf(Arrays.asList(allowed.split(",")));
    }
    // 通过方法访问业务依赖，避免从 Spring 事务代理的未初始化实例字段读取状态。
    public ServiceEntryMapper services() { return services; }
    public ApiEntryMapper apis() { return apis; }
    public RouteEntryMapper routes() { return routes; }
    public PolicyEntryMapper policies() { return policies; }
    public BindingEntryMapper bindings() { return bindings; }
    public Set<String> allowed() { return allowed; }
    public record BindingView(Long bindingId, Long policyId, String targetType, Long targetId,
                              String targetLabel, String targetRouteId) {}
    /** 产品页只读绑定事实；缺失引用明确显示未知，绝不把principal实体或密钥序列化。 */
    public List<BindingView> bindingViews(long policyId) {
        if (policies.selectById(policyId) == null) throw new ApiFailure(404, "NOT_FOUND", "策略不存在");
        return bindings.selectList(new QueryWrapper<BindingEntry>().eq("policy_id", policyId).orderByAsc("id")).stream().map(binding -> {
            // 1. 标签只从同一个真实引用取得；没有查询到不猜测目标名称。
            String label = "目标不存在或未取得", routeId = null;
            switch (binding.targetType) {
                case "GLOBAL" -> label = "全局共享预算";
                case "SERVICE" -> { var value = services.selectById(binding.targetId); if (value != null) label = value.name; }
                case "API" -> { var value = apis.selectById(binding.targetId); if (value != null) label = value.method + " " + value.publicPath; }
                case "ROUTE" -> { var value = routes.selectById(binding.targetId); if (value != null) { label = value.routeId; routeId = value.routeId; } }
                case "CALLER" -> { var value = principals.selectById(binding.targetId); if (value != null && "CALLER".equals(value.principalType)) label = value.username; }
                default -> { }
            }
            // 2. 使用固定只读DTO，不返回完整实体/会话，也不允许通过此接口修改绑定。
            return new BindingView(binding.id, binding.policyId, binding.targetType, binding.targetId, label, routeId);
        }).toList();
    }
    private record Catalog(BaseMapper mapper, Class<?> type) {}
    private Catalog catalog(String kind) {
        return switch (kind) {
            case "services" -> new Catalog(services, ServiceEntry.class);
            case "apis" -> new Catalog(apis, ApiEntry.class);
            case "routes" -> new Catalog(routes, RouteEntry.class);
            case "policies" -> new Catalog(policies, PolicyEntry.class);
            default -> throw new ApiFailure(404, "NOT_FOUND", "目录不存在");
        };
    }
    @SuppressWarnings("unchecked")
    public List<?> list(String kind) { return catalog(kind).mapper.selectList(new QueryWrapper<>().orderByAsc("id")); }

    /** 创建/更新完整草稿并事务写审计；客户端不能指定数据库 ID 或时间字段。 */
    @Transactional
    @SuppressWarnings("unchecked")
    public Object save(String kind, Long id, Map<String, Object> input, long actor) {
        // 1. 白名单反序列化：实体仅作本地 CRUD 载体，不允许覆盖 ID。
        Catalog catalog = catalog(kind);
        var validFields = new HashSet<String>();
        for (var field : catalog.type.getFields()) if (!field.getName().equals("id")) validFields.add(field.getName());
        Rules.require(validFields.containsAll(input.keySet()), "存在未知或不可修改字段");
        Object entity = SnapshotCodec.JSON.convertValue(input, catalog.type);
        validate(entity);
        // 2. 关系与关键参数验证成功后才写入，PUT 不进行隐式插入。
        if (id == null) catalog.mapper.insert(entity);
        else {
            if (catalog.mapper.selectById(id) == null) throw new ApiFailure(404, "NOT_FOUND", "草稿不存在");
            try { catalog.type.getField("id").set(entity, id); }
            catch (ReflectiveOperationException impossible) { throw new IllegalStateException(impossible); }
            catalog.mapper.updateById(entity);
        }
        // 3. 同一事务记录变更，摘要不含凭证、原始参数或请求体。
        audit.record(actor, id == null ? "CREATE" : "UPDATE", kind, String.valueOf(id == null ? idOf(entity) : id), null, "保存目录草稿");
        return entity;
    }
    private Object idOf(Object entity) {
        try { return entity.getClass().getField("id").get(entity); }
        catch (ReflectiveOperationException impossible) { throw new IllegalStateException(impossible); }
    }
    public void validate(Object entity) {
        // 1. 服务草稿必须命中部署白名单；API 的路径和鉴权要求独立验证。
        if (entity instanceof ServiceEntry value) {
            Rules.text(value.code, "code"); Rules.text(value.name, "name");
            Rules.require(value.enabled != null, "enabled 必填"); Rules.upstream(value.upstreamUri, allowed);
        } else if (entity instanceof ApiEntry value) {
            Rules.require(value.serviceId != null && services.selectById(value.serviceId) != null, "服务引用不存在");
            Rules.paths(value.method, value.publicPath, value.upstreamPath);
            Rules.require(value.enabled != null && value.isPublic != null, "enabled/isPublic 必填");
            if (!value.isPublic) Rules.scope(value.requiredScope);
            if (value.requiredScope == null) value.requiredScope = "";
        } else if (entity instanceof RouteEntry value) {
            // 2. 路由只引用既有 API，并要求稳定标识和明确优先级。
            Rules.require(value.apiId != null && apis.selectById(value.apiId) != null, "API 引用不存在");
            Rules.text(value.routeId, "routeId");
            Rules.require(value.routeId.matches("[A-Za-z0-9_-]+"), "routeId 只支持字母数字下划线和横线");
            Rules.require(value.priority != null && value.enabled != null, "priority/enabled 必填");
        } else if (entity instanceof PolicyEntry value) {
            // 3. 草稿仅校验结构/数值；治理执行以新发布快照及节点应用为准。
            Rules.text(value.code, "code");
            Rules.require(value.enabled != null && value.scope != null && Set.of("GLOBAL", "SERVICE", "API", "ROUTE", "CALLER").contains(value.scope), "策略作用域非法");
            Rules.require(value.type != null, "策略类型必填");
            Rules.require(value.parametersJson != null, "策略参数必填");
            PolicyRules.parameters(value.type,value.parametersJson);
        }
    }

    @Transactional
    public BindingEntry bind(long policyId, String type, Long target, long actor) {
        BindingEntry binding = new BindingEntry(); binding.policyId = policyId; binding.targetType = type; binding.targetId = target;
        validateBinding(binding); bindings.insert(binding);
        audit.record(actor, "BIND", "policies", Long.toString(policyId), null, "绑定策略草稿（尚不执行）");
        return binding;
    }
    public void validateBinding(BindingEntry value) {
        PolicyEntry policy = value.policyId == null ? null : policies.selectById(value.policyId);
        Rules.require(policy != null && value.targetType != null && value.targetId != null && policy.scope.equals(value.targetType), "绑定与策略作用域不匹配");
        boolean exists = switch (value.targetType) {
            case "GLOBAL" -> value.targetId == 0;
            case "SERVICE" -> services.selectById(value.targetId) != null;
            case "API" -> apis.selectById(value.targetId) != null;
            case "ROUTE" -> routes.selectById(value.targetId) != null;
            case "CALLER" -> { var p = principals.selectById(value.targetId); yield p != null && "CALLER".equals(p.principalType); }
            default -> false;
        };
        Rules.require(exists, "策略绑定目标不存在");
    }
    /** 发布边界重验启用目标与并发作用域，草稿保存仍允许未绑定的其他作用域。 */
    public void validatePublishedBinding(BindingEntry binding) {
        PolicyEntry policy=policies.selectById(binding.policyId);
        if (!Boolean.TRUE.equals(policy.enabled)) return;
        if ("CONCURRENCY".equals(policy.type)) Rules.require(Set.of("SERVICE","ROUTE").contains(policy.scope), "并发只支持服务/路由");
        boolean enabled=switch(binding.targetType) {
            case "GLOBAL" -> true;
            case "SERVICE" -> Boolean.TRUE.equals(services.selectById(binding.targetId).enabled);
            case "API" -> Boolean.TRUE.equals(apis.selectById(binding.targetId).enabled);
            case "ROUTE" -> Boolean.TRUE.equals(routes.selectById(binding.targetId).enabled);
            case "CALLER" -> Boolean.TRUE.equals(principals.selectById(binding.targetId).enabled);
            default -> false;
        };
        Rules.require(enabled,"启用策略绑定了停用目标");
    }

}
