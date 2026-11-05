package com.flowgate.control.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.flowgate.contract.*;
import com.flowgate.control.entity.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.service.ReleaseService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
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
    public record Applied(String nodeId, long version, String checksum) {}
    @GetMapping("/admin/releases") public Object history() { return releases.history(); }
    @GetMapping("/admin/releases/current") public Object current() { return releases.current(); }
    @PostMapping("/admin/releases/publish") public Object publish(@RequestBody Expected input, HttpServletRequest req) {
        return releases.publish(input.expectedVersion(), null, ((Session)req.getAttribute("session")).principalId());
    }
    @PostMapping("/admin/releases/{version}/rollback") public Object rollback(@PathVariable long version, @RequestBody Expected input, HttpServletRequest req) {
        return releases.publish(input.expectedVersion(), version, ((Session)req.getAttribute("session")).principalId());
    }
    @GetMapping("/admin/nodes") public Object nodes() { return nodes.selectList(new QueryWrapper<ReleaseNode>().orderByAsc("id").last("LIMIT 100")); }
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
        ReleaseNode node = nodes.selectOne(new QueryWrapper<ReleaseNode>().eq("node_id", input.nodeId()));
        if (node != null && node.appliedVersion > input.version()) return;
        boolean create = node == null;
        if (create) { node = new ReleaseNode(); node.nodeId = input.nodeId(); }
        node.appliedVersion = input.version(); node.checksum = input.checksum(); node.lastSeenAt = LocalDateTime.now();
        if (create) nodes.insert(node); else nodes.updateById(node);
    }
}
