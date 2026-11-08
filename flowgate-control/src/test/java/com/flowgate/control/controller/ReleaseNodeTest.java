package com.flowgate.control.controller;

import com.flowgate.control.entity.ReleaseNode;
import com.flowgate.control.mapper.*;
import com.flowgate.control.service.ReleaseService;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 节点新鲜度来自服务端时间，持久化记录不能冒充重启后仍已取得熔断运行状态。 */
class ReleaseNodeTest {
    @Test void separatesRecentStaleAndUnknownRuntime() {
        var nodes=mock(ReleaseNodeMapper.class);
        var recent=new ReleaseNode();recent.id=1L;recent.nodeId="recent";recent.appliedVersion=2L;recent.checksum="sha";recent.lastSeenAt=LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0);
        var stale=new ReleaseNode();stale.id=2L;stale.nodeId="stale";stale.appliedVersion=1L;stale.checksum="old";stale.lastSeenAt=LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(30);
        when(nodes.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(recent,stale));
        var controller=new ReleaseController(mock(ReleaseService.class),nodes,mock(AuditLogMapper.class));
        var result=(List<?>)controller.nodes();
        assertEquals("ONLINE",((Map<?,?>)result.get(0)).get("freshness"));
        assertEquals("STALE",((Map<?,?>)result.get(1)).get("freshness"));
        assertEquals(false,((Map<?,?>)result.get(0)).get("runtimeStateKnown"));
        assertEquals(List.of(),((Map<?,?>)result.get(0)).get("circuitStates"));
    }
}
