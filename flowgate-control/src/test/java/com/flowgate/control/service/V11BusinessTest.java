package com.flowgate.control.service;

import com.flowgate.contract.Session;
import com.flowgate.control.entity.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.security.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** V11管理域的输入、UTC半开区间、角色矩阵及不发证边界；数据库互斥另跑真实MySQL。 */
class V11BusinessTest {
    @Test void subscriptionLocksApplicationBeforeCurrentReadAndRollsBackConflict() {
        var apps=mock(ApplicationEntryMapper.class);var developers=mock(DeveloperEntryMapper.class);var plans=mock(ApiPlanEntryMapper.class);var subs=mock(SubscriptionEntryMapper.class);var audit=mock(AuditService.class);
        var app=new ApplicationEntry();app.id=1L;app.developerId=2L;app.status="ACTIVE";
        var developer=new DeveloperEntry();developer.status="ACTIVE";var plan=new ApiPlanEntry();plan.status="ACTIVE";plan.planVersion=1;
        when(apps.lockById(1)).thenReturn(app);when(developers.shareById(2)).thenReturn(developer);when(plans.shareById(3)).thenReturn(plan);
        when(apps.nowUtc()).thenReturn(Instant.parse("2026-11-07T00:00:00Z"));
        var existing=new SubscriptionEntry();existing.status="ACTIVE";existing.effectiveAt=Instant.parse("2026-11-01T00:00:00Z");existing.expiresAt=Instant.parse("2026-12-01T00:00:00Z");
        when(subs.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(existing));
        var service=new PlanSubscriptionService(plans,subs,apps,developers,audit);
        assertEquals("SUBSCRIPTION_CONFLICT",assertThrows(ApiFailure.class,()->service.subscribe(Map.of("applicationId",1,"planId",3,"effectiveAt","2026-11-01T00:00:00Z","expiresAt","2026-12-01T00:00:00Z"),1)).code);
        var order=inOrder(apps,subs);order.verify(apps).lockById(1);order.verify(apps).nowUtc();
        order.verify(subs).selectList(argThat(wrapper->wrapper.getSqlSegment().contains("FOR UPDATE")));
        verify(subs,never()).insert(any(SubscriptionEntry.class));verifyNoInteractions(audit);
    }
    @Test void filterAuthorizesUnsupportedMethodsBeforeMvcMatching() throws Exception {
        var identity=mock(IdentityService.class);when(identity.admin("test")).thenReturn(new Session(1,"ADMIN","OPERATOR",List.of()));
        var filter=new V11ManagementFilter(new AdminAuthInterceptor(identity,"k".repeat(32)));
        var request=new MockHttpServletRequest("DELETE","/admin/apps/1");request.addHeader("Authorization","Bearer test");
        var response=new MockHttpServletResponse();var chain=mock(jakarta.servlet.FilterChain.class);
        filter.doFilter(request,response,chain);assertEquals(403,response.getStatus());verifyNoInteractions(chain);
        assertFalse(response.getContentAsString().contains("test"));
        for(String path:List.of("/admin/apps;x=1","/admin/%61pps","/admin/apps/1;v=1/credentials")) {
            var encoded=new MockHttpServletRequest("POST",path);encoded.addHeader("Authorization","Bearer test");var denied=new MockHttpServletResponse();
            filter.doFilter(encoded,denied,chain);assertEquals(403,denied.getStatus());
        }
        when(identity.admin("test")).thenReturn(new Session(1,"ADMIN","ADMIN",List.of()));
        request=new MockHttpServletRequest("PUT","/admin/plans/1");request.addHeader("Authorization","Bearer test");response=new MockHttpServletResponse();
        filter.doFilter(request,response,chain);assertEquals(true,request.getAttribute("v11.auth.checked"));verify(chain).doFilter(request,response);
    }
    @Test void strictInputsRejectInvalidCodesAmountsAndNonUtcDates() {
        assertThrows(ApiFailure.class,()->BusinessInputs.code("a/b"));
        assertThrows(ApiFailure.class,()->BusinessInputs.number(0,1000000,"额度"));
        assertThrows(ApiFailure.class,()->BusinessInputs.number(1.5,1000000,"额度"));
        assertThrows(ApiFailure.class,()->BusinessInputs.instant("2026-12-01T08:00:00+08:00"));
        assertThrows(ApiFailure.class,()->BusinessInputs.instant("2026-12-01T00:00:00.000001Z"));
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"),BusinessInputs.instant("2026-12-01T00:00:00Z"));
        assertThrows(ApiFailure.class,()->BusinessInputs.page(-1,20));assertThrows(ApiFailure.class,()->BusinessInputs.page(0,101));
    }
    @Test void utcMonthEndAndAdjacentIntervalsAreDeterministic() {
        var now=Instant.parse("2026-11-30T23:59:59.999Z");var next=Instant.parse("2026-12-01T00:00:00Z");
        var current=new SubscriptionEntry();current.status="ACTIVE";current.effectiveAt=Instant.parse("2026-11-01T00:00:00Z");current.expiresAt=next;
        SubscriptionSchedule.validate(next,Instant.parse("2027-01-01T00:00:00Z"),now,List.of(current));
        assertThrows(ApiFailure.class,()->SubscriptionSchedule.validate(next.minusMillis(1),next.plusSeconds(1),now,List.of(current)));
        assertThrows(ApiFailure.class,()->SubscriptionSchedule.validate(next.plusMillis(1),next.plusSeconds(1),now,List.of()));
        assertThrows(ApiFailure.class,()->SubscriptionSchedule.validate(Instant.parse("2026-10-31T23:59:59Z"),next,now,List.of()));
        // 月初恰好下一天时原排队变为当前，允许新的下月，但不能重叠旧区间。
        SubscriptionSchedule.validate(next,next.plusSeconds(30),next,List.of(current));
        var queued=new SubscriptionEntry();queued.status="ACTIVE";queued.effectiveAt=next;queued.expiresAt=next.plusSeconds(30);
        assertEquals("SUBSCRIPTION_CONFLICT",assertThrows(ApiFailure.class,()->SubscriptionSchedule.validate(next,next.plusSeconds(60),now,List.of(queued))).code);
    }
    @Test void newWritesAdminOnlyWhileV1OperatorStillWrites() {
        var identity=mock(IdentityService.class);var interceptor=new AdminAuthInterceptor(identity,"k".repeat(32));
        for(String role:List.of("ADMIN","OPERATOR","VIEWER")) {
            when(identity.admin("test")).thenReturn(new Session(1,"ADMIN",role,List.of()));
            for(String path:List.of("/admin/developers","/admin/apps/1/credentials","/admin/plans/2","/admin/subscriptions")) {
                for(String method:List.of("POST","PATCH","PUT","DELETE")) {
                    var req=new MockHttpServletRequest(method,path);req.addHeader("Authorization","Bearer test");
                    if(role.equals("ADMIN"))assertTrue(interceptor.preHandle(req,new MockHttpServletResponse(),null));
                    else assertEquals(403,assertThrows(ApiFailure.class,()->interceptor.preHandle(req,new MockHttpServletResponse(),null)).status);
                }
                var read=new MockHttpServletRequest("GET",path);read.addHeader("Authorization","Bearer test");assertTrue(interceptor.preHandle(read,new MockHttpServletResponse(),null));
            }
            var old=new MockHttpServletRequest("POST","/admin/services");old.addHeader("Authorization","Bearer test");
            if(role.equals("VIEWER"))assertThrows(ApiFailure.class,()->interceptor.preHandle(old,new MockHttpServletResponse(),null));else assertTrue(interceptor.preHandle(old,new MockHttpServletResponse(),null));
        }
    }
    @Test void placeholderNeverTouchesIdentityOrAuditAndDuplicatesAreExplicit() {
        var dev=mock(DeveloperEntryMapper.class);var apps=mock(ApplicationEntryMapper.class);var audit=mock(AuditService.class);
        var service=new DeveloperApplicationService(dev,apps,audit);var row=new ApplicationEntry();row.id=1L;when(apps.selectById(1L)).thenReturn(row);
        assertEquals("FEATURE_NOT_READY",assertThrows(ApiFailure.class,()->service.credentials(1)).code);
        verifyNoInteractions(dev,audit);assertEquals(404,assertThrows(ApiFailure.class,()->service.credentials(2)).status);
        when(dev.insert(any(DeveloperEntry.class))).thenThrow(new DuplicateKeyException("hidden SQL"));
        assertEquals("DUPLICATE_CODE",assertThrows(ApiFailure.class,()->service.saveDeveloper(null,Map.of("code","a","name","客户"),1)).code);
        verifyNoInteractions(audit);
    }
    @Test void planUnitsImmutableAndPatchKeepsExistingFields() {
        var plans=mock(ApiPlanEntryMapper.class);var row=new ApiPlanEntry();row.id=1L;row.code="free";row.name="Free";row.monthlyUnits=5L;row.planVersion=1;row.status="ACTIVE";
        when(plans.lockById(1)).thenReturn(row);when(plans.selectById(1L)).thenReturn(row);
        var audit=mock(AuditService.class);var service=new PlanSubscriptionService(plans,mock(SubscriptionEntryMapper.class),mock(ApplicationEntryMapper.class),mock(DeveloperEntryMapper.class),audit);
        assertEquals("IMMUTABLE_PLAN",assertThrows(ApiFailure.class,()->service.savePlan(1L,Map.of("monthlyUnits",5),1)).code);
        service.savePlan(1L,Map.of("name","Free新名称","status","SUSPENDED"),1);
        assertEquals(5L,row.monthlyUnits);assertEquals("free",row.code);assertEquals("SUSPENDED",row.status);
        verify(audit).record(eq(1L),eq("UPDATE_PLAN"),eq("plan"),eq("1"),isNull(),anyString());
    }
}
