package com.flowgate.control.service;

import com.flowgate.control.entity.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.security.ApiFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实绑定只读DTO校验：不泄露Caller实体秘密，缺失与空绑定分别返回404/空列表。 */
class BindingReadTest {
    @Test void returnsOnlyBindingFactsAndSafeTargetLabels() {
        var services=mock(ServiceEntryMapper.class); var apis=mock(ApiEntryMapper.class);
        var routes=mock(RouteEntryMapper.class); var policies=mock(PolicyEntryMapper.class);
        var bindings=mock(BindingEntryMapper.class); var principals=mock(PrincipalMapper.class);
        var service=new CatalogService(services,apis,routes,policies,bindings,principals,mock(AuditService.class),"http://127.0.0.1:8085");
        var policy=new PolicyEntry(); policy.id=7L; when(policies.selectById(7L)).thenReturn(policy);
        var caller=new Principal(); caller.username="报表系统";caller.principalType="CALLER";caller.passwordHash="must-not-leak";
        when(principals.selectById(9L)).thenReturn(caller);
        var route=new RouteEntry();route.routeId="query";when(routes.selectById(10L)).thenReturn(route);
        var first=new BindingEntry();first.id=1L;first.policyId=7L;first.targetType="CALLER";first.targetId=9L;
        var second=new BindingEntry();second.id=2L;second.policyId=7L;second.targetType="ROUTE";second.targetId=10L;
        when(bindings.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(first,second));
        var result=service.bindingViews(7L);
        assertEquals("报表系统",result.get(0).targetLabel());assertNull(result.get(0).targetRouteId());
        assertEquals("query",result.get(1).targetRouteId());assertEquals(10L,result.get(1).targetId());
        assertFalse(com.flowgate.contract.SnapshotCodec.JSON.writeValueAsString(result).contains("must-not-leak"));
        verify(bindings,never()).insert(any(BindingEntry.class));
    }
    @Test void missingPolicyIsNotAnEmptySuccess() {
        var policies=mock(PolicyEntryMapper.class);var bindings=mock(BindingEntryMapper.class);
        var service=new CatalogService(mock(ServiceEntryMapper.class),mock(ApiEntryMapper.class),mock(RouteEntryMapper.class),policies,bindings,mock(PrincipalMapper.class),mock(AuditService.class),"http://127.0.0.1:8085");
        assertEquals(404,assertThrows(ApiFailure.class,()->service.bindingViews(99)).status);
        verifyNoInteractions(bindings);
        var policy=new PolicyEntry();when(policies.selectById(1L)).thenReturn(policy);
        when(bindings.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
        assertEquals(List.of(),service.bindingViews(1));
    }
}
