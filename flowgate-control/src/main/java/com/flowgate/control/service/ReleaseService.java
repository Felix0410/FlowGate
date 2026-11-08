package com.flowgate.control.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.flowgate.contract.*;
import com.flowgate.control.entity.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.security.ApiFailure;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL CAS 发布服务；快照、版本状态、审计在同一事务提交，历史只读。 */
@Service
public class ReleaseService {
    private final CatalogService catalog;
    private final ConfigStateMapper state;
    private final ConfigReleaseMapper releases;
    private final AuditService audit;
    private QuotaActivationService quota;
    @org.springframework.beans.factory.annotation.Autowired public void setQuota(QuotaActivationService quota) { this.quota=quota; }
    public ReleaseService(CatalogService catalog, ConfigStateMapper state, ConfigReleaseMapper releases, AuditService audit) {
        this.catalog = catalog; this.state = state; this.releases = releases; this.audit = audit;
    }
    public ConfigRelease find(long version) {
        var release = releases.selectOne(new QueryWrapper<ConfigRelease>().eq("version", version));
        if (release == null) throw new ApiFailure(404, "NOT_FOUND", "发布版本不存在");
        return release;
    }
    public Object current() {
        long version = state.selectById(1).currentVersion;
        if (version == 0) return Map.of("version",0,"status","EMPTY","policyStates",List.of());
        ConfigRelease release=find(version);
        var result=new LinkedHashMap<String,Object>(summary(release));
        Snapshot snapshot=SnapshotCodec.JSON.readValue(release.snapshotJson,Snapshot.class);
        result.put("policyStates",snapshot.policies().stream().map(p -> Map.of("code",p.get("code"),
                "type",p.get("type"),"enabled",p.get("enabled"),"executionStatus",p.get("executionStatus"))).toList());
        return result;
    }
    public Snapshot snapshot() {
        long version = state.selectById(1).currentVersion;
        if (version == 0) throw new ApiFailure(404, "EMPTY", "尚未发布配置");
        return SnapshotCodec.JSON.readValue(find(version).snapshotJson, Snapshot.class);
    }
    public List<?> history() {
        return releases.selectList(new QueryWrapper<ConfigRelease>().orderByDesc("version").last("LIMIT 100")).stream().map(this::summary).toList();
    }
    private Map<String,Object> summary(ConfigRelease release) {
        var result = new LinkedHashMap<String,Object>(); result.put("version", release.version); result.put("status", "PERSISTED");
        result.put("checksum", release.sha256); result.put("rollbackFrom", release.rollbackFrom); result.put("createdAt", release.createdAt);
        return result;
    }

    /** expectedVersion 必须等于当前版本；回滚复制历史内容而非倒退版本号。 */
    @Transactional
    public Object publish(Long expected, Long rollback, long actor) {
        // 1. 在一致性读取视图中生成完整候选配置，先校验再尝试推进状态。
        Rules.require(expected != null && expected >= 0, "expectedVersion 必填且非负");
        ConfigState current = state.selectById(1);
        if (!expected.equals(current.currentVersion)) throw new ApiFailure(409, "VERSION_CONFLICT", "预期版本已过期");
        long next = Math.addExact(current.currentVersion, 1);
        Snapshot candidate = rollback == null ? build(next) : rollback(rollback, next);
        Rules.routes(candidate.routes(), next, catalog.allowed());
        PolicyRules.validate(candidate);
        if (candidate.routes().stream().anyMatch(route -> QuotaPolicy.freeze(candidate,route).metered())) {
            if(quota==null)throw new ApiFailure(409,"QUOTA_FEATURE_NOT_READY","配额链路未启用");
            quota.requireReady(current.currentVersion,current.currentVersion==0?"":find(current.currentVersion).sha256);
        }
        // 2. CAS 防止两个发布者丢更新；失败会回滚全部写入，没有孤立版本。
        if (state.advance(expected, current.optimisticVersion, next) != 1) throw new ApiFailure(409, "VERSION_CONFLICT", "其他发布已先提交");
        ConfigRelease release = new ConfigRelease(); release.version = next; release.sha256 = candidate.checksum();
        release.snapshotJson = SnapshotCodec.JSON.writeValueAsString(candidate); release.actorId = actor; release.rollbackFrom = rollback;
        releases.insert(release);
        // 3. 仅返回 PERSISTED；真正路由加载成功后由节点独立上报 applied。
        audit.record(actor, rollback == null ? "PUBLISH" : "ROLLBACK", "release", Long.toString(next), next, "持久化不可变配置版本");
        return summary(release);
    }
    private Snapshot rollback(long version, long next) {
        Snapshot old = SnapshotCodec.JSON.readValue(find(version).snapshotJson, Snapshot.class);
        SnapshotCodec.verify(old);
        for (var service : old.services()) Rules.upstream((String)service.get("upstreamUri"), catalog.allowed());
        return SnapshotCodec.seal(new Snapshot(1, next, "", old.services(), old.apis(), old.routes().stream().map(r ->
                new RouteSpec(r.routeId(), r.method(), r.publicPath(), r.upstreamPath(), r.upstreamUri(), r.isPublic(), r.requiredScope(), r.priority(), next)).toList(),
                old.policies(), old.bindings()));
    }
    private Snapshot build(long version) {
        // 1. 读取稳定 ID 顺序的草稿并重验，包括策略绑定；不直接序列化实体为协议。
        List<ServiceEntry> services = catalog.services().selectList(new QueryWrapper<ServiceEntry>().orderByAsc("id"));
        List<ApiEntry> apis = catalog.apis().selectList(new QueryWrapper<ApiEntry>().orderByAsc("id"));
        List<RouteEntry> routes = catalog.routes().selectList(new QueryWrapper<RouteEntry>().orderByAsc("route_id"));
        List<PolicyEntry> policies = catalog.policies().selectList(new QueryWrapper<PolicyEntry>().orderByAsc("id"));
        List<BindingEntry> bindings = catalog.bindings().selectList(new QueryWrapper<BindingEntry>().orderByAsc("id"));
        services.forEach(catalog::validate); apis.forEach(catalog::validate); routes.forEach(catalog::validate);
        policies.forEach(catalog::validate); bindings.forEach(catalog::validateBinding);
        bindings.forEach(catalog::validatePublishedBinding);
        // 目录内的启用 API 即使还未绑定路由，也不能留下方法/路径歧义。
        var apiChecks = apis.stream().filter(a -> a.enabled).map(a -> {
            ServiceEntry service = services.stream().filter(s -> s.id.equals(a.serviceId)).findFirst().orElseThrow();
            return new RouteSpec("api-" + a.id, a.method, a.publicPath, a.upstreamPath, service.upstreamUri,
                    a.isPublic, a.requiredScope, 0, version);
        }).toList();
        Rules.routes(apiChecks, version, catalog.allowed());
        var specs = new ArrayList<RouteSpec>();
        // 2. 启用路由必须关联启用的 API/服务，禁用父级不会悄悄发布孤立路由。
        for (RouteEntry route : routes) if (route.enabled) {
            ApiEntry api = apis.stream().filter(a -> a.id.equals(route.apiId)).findFirst().orElseThrow();
            ServiceEntry service = services.stream().filter(s -> s.id.equals(api.serviceId)).findFirst().orElseThrow();
            Rules.require(api.enabled && service.enabled, "启用路由引用了停用 API 或服务");
            specs.add(new RouteSpec(route.routeId, api.method, api.publicPath, api.upstreamPath, service.upstreamUri,
                    api.isPublic, api.requiredScope, route.priority, version));
        }
        // 3. 只选白名单字段；策略分别标注块2执行/块3待办；规范化载荷后计算 SHA-256。
        return SnapshotCodec.seal(new Snapshot(1, version, "",
                services.stream().map(s -> Map.<String,Object>of("id", s.id, "code", s.code, "name", s.name, "upstreamUri", s.upstreamUri, "enabled", s.enabled)).toList(),
                apis.stream().map(a -> Map.<String,Object>of("id", a.id, "serviceId", a.serviceId, "method", a.method, "publicPath", a.publicPath,
                        "upstreamPath", a.upstreamPath, "isPublic", a.isPublic, "requiredScope", a.requiredScope, "enabled", a.enabled)).toList(), specs,
                policies.stream().map(p -> Map.<String,Object>of("id", p.id, "code", p.code, "type", p.type, "scope", p.scope,
                        "parametersJson", p.parametersJson, "enabled", p.enabled, "executionStatus", PolicyRules.execution(p.type))).toList(),
                bindings.stream().map(b -> {
                    var entry = new LinkedHashMap<String,Object>();
                    entry.put("id",b.id); entry.put("policyId",b.policyId); entry.put("targetType",b.targetType); entry.put("targetId",b.targetId);
                    if ("ROUTE".equals(b.targetType)) entry.put("targetRouteId", routes.stream().filter(r -> r.id.equals(b.targetId)).findFirst().orElseThrow().routeId);
                    return (Map<String,Object>)entry;
                }).toList()));
    }
}
