package com.flowgate.gateway.config;

import com.flowgate.contract.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 保存已校验候选与实际应用快照；请求鉴权使用匹配 Route 的元数据，不读取此全局状态。 */
@Component
public class ActiveSnapshotStore {
    private final AtomicReference<Snapshot> candidate = new AtomicReference<>();
    private final AtomicReference<Snapshot> applied = new AtomicReference<>();
    private final Set<String> allowed;
    public ActiveSnapshotStore(@Value("${flowgate.allowed-upstreams}") String allowed) {
        this.allowed = Set.copyOf(Arrays.asList(allowed.split(",")));
    }
    public Snapshot candidate() { return candidate.get(); }
    public Snapshot applied() { return applied.get(); }
    public long version() { return applied.get() == null ? 0 : applied.get().version(); }
    public void stage(Snapshot next) {
        SnapshotCodec.verify(next);
        PolicyRules.validate(next);
        Rules.routes(next.routes(), next.version(), allowed);
        for (var service : next.services()) Rules.upstream((String)service.get("upstreamUri"), allowed);
        Rules.require(next.version() > version(), "拒绝非递增版本");
        candidate.set(next);
    }
    public void confirm(Snapshot next) { applied.set(next); }
    public void restore() { candidate.set(applied.get()); }
}
