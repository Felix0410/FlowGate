package com.flowgate.control.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.flowgate.contract.*;
import com.flowgate.control.entity.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.service.ReleaseService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.bind.annotation.*;

/** 发布/回滚、审计与内部同步端点；内部密钥不提供给网页。 */
@RestController
public class ReleaseController {
    private final ReleaseService releases;
    private final ReleaseNodeMapper nodes;
    private final AuditLogMapper audits;
    public ReleaseController(ReleaseService releases, ReleaseNodeMapper nodes, AuditLogMapper audits) {
        this.releases = releases; this.nodes = nodes; this.audits = audits;
    }
    public record Expected(Long expectedVersion) {}
    public record Applied(String nodeId, long version, String checksum,List<Map<String,String>> circuitStates) {}
    private record RuntimeState(long version,String checksum,List<Map<String,String>> states) {}
    private final Map<String,RuntimeState> runtimeStates=new ConcurrentHashMap<>();
    @GetMapping("/admin/releases") public Object history() { return releases.history(); }
    @GetMapping("/admin/releases/current") public Object current() { return releases.current(); }
    @PostMapping("/admin/releases/publish") public Object publish(@RequestBody Expected input, HttpServletRequest req) {
        return releases.publish(input.expectedVersion(), null, ((Session)req.getAttribute("session")).principalId());
    }
    @PostMapping("/admin/releases/{version}/rollback") public Object rollback(@PathVariable long version, @RequestBody Expected input, HttpServletRequest req) {
        return releases.publish(input.expectedVersion(), version, ((Session)req.getAttribute("session")).principalId());
    }
    /** 在线状态是最近心跳的派生值；运行摘要只存内存，控制面重启后明确未知。 */
    @GetMapping("/admin/nodes") public Object nodes() {
        return nodes.selectList(new QueryWrapper<ReleaseNode>().orderByAsc("id").last("LIMIT 100")).stream().map(node->{
            var row=new LinkedHashMap<String,Object>(); row.put("id",node.id);row.put("nodeId",node.nodeId);
            row.put("appliedVersion",node.appliedVersion);row.put("checksum",node.checksum);row.put("lastSeenAt",node.lastSeenAt);
            long age=node.lastSeenAt==null?Long.MAX_VALUE:Duration.between(node.lastSeenAt,LocalDateTime.now(ZoneOffset.UTC)).getSeconds();
            row.put("freshness",age>=0 && age<=15?"ONLINE":"STALE");
            RuntimeState state=runtimeStates.get(node.nodeId);
            boolean known=state!=null && state.version()==node.appliedVersion && state.checksum().equals(node.checksum);
            row.put("circuitStates",known?state.states():List.of());row.put("runtimeStateKnown",known);
            row.put("runtimeNote","最近上报的运行状态；节点或控制面重启后可能重置，请核对lastSeenAt");
            return row;
        }).toList();
    }
    @GetMapping("/admin/audits") public Object audits(@RequestParam(defaultValue="0") long beforeId,
            @RequestParam(defaultValue="100") int limit) {
        Rules.require(limit >= 1 && limit <= 100 && beforeId >= 0, "分页参数范围不合法");
        return audits.selectList(new QueryWrapper<AuditLog>().lt(beforeId > 0, "id", beforeId).orderByDesc("id").last("LIMIT " + limit));
    }
    @GetMapping("/internal/snapshots/current") public Object snapshot() { return releases.snapshot(); }
    @PostMapping("/internal/nodes/applied") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void applied(@RequestBody Applied input) {
        Rules.text(input.nodeId(), "节点 ID");
        var release = releases.find(input.version());
        Rules.require(release.sha256.equals(input.checksum()), "节点校验和不匹配");
        // 1. 内部上报也严限大小/字段/路由关系，不把任意日志或凭证带到管理员页面。
        var states=input.circuitStates()==null?List.<Map<String,String>>of():input.circuitStates();
        Rules.require(states.size()<=100,"熔断摘要过大");
        var snapshot=SnapshotCodec.JSON.readValue(release.snapshotJson,Snapshot.class);
        var seen=new HashSet<String>();
        for(var entry:states) {
            Rules.require(entry.keySet().equals(Set.of("routeId","state","revision","stateChangedAt")),"熔断摘要字段非法");
            entry.values().forEach(v->Rules.text(v,"熔断摘要"));
            Rules.require(Set.of("CLOSED","OPEN","HALF_OPEN","DISABLED","FORCED_OPEN","METRICS_ONLY").contains(entry.get("state"))
                    && entry.get("revision").matches("[a-f0-9]{32}") && seen.add(entry.get("routeId"))
                    && snapshot.routes().stream().anyMatch(r->r.routeId().equals(entry.get("routeId"))),"熔断摘要关系非法");
        }
        // 2. 保持原持久化节点版本单调，上报摘要与同版checksum绑定。
        ReleaseNode node = nodes.selectOne(new QueryWrapper<ReleaseNode>().eq("node_id", input.nodeId()));
        if (node != null && node.appliedVersion > input.version()) return;
        boolean create = node == null;
        if (create) { node = new ReleaseNode(); node.nodeId = input.nodeId(); }
        node.appliedVersion = input.version(); node.checksum = input.checksum(); node.lastSeenAt = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        Rules.require(!create || nodes.selectCount(new QueryWrapper<ReleaseNode>())<100,"节点数超过本机演示上限");
        if (create) nodes.insert(node); else nodes.updateById(node);
        if(input.circuitStates()==null) runtimeStates.remove(input.nodeId());
        else runtimeStates.put(input.nodeId(),new RuntimeState(input.version(),input.checksum(),states.stream().map(Map::copyOf).toList()));
    }
}
