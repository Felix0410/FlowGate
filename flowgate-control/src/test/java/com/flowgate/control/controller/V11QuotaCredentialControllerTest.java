package com.flowgate.control.controller;

import com.flowgate.contract.Session;
import com.flowgate.control.entity.ConfigRelease;
import com.flowgate.control.security.ApiFailure;
import com.flowgate.control.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 公开发证受施工开关和全节点门禁控制；撤销不得被节点离线阻碍。 */
class V11QuotaCredentialControllerTest {
    @Test void disabledActivationRejectsIssueAndRotateButAllowsEmergencyRevoke() {
        var service=mock(AppCredentialService.class);var activation=mock(QuotaActivationService.class);
        var controller=new AppCredentialController(service,mock(DeveloperApplicationService.class),activation,mock(ReleaseService.class));
        var request=new MockHttpServletRequest();request.setAttribute("session",new Session(1,"ADMIN","ADMIN",List.of()));
        assertEquals("FEATURE_NOT_READY",assertThrows(ApiFailure.class,()->controller.issue(1,Map.of("scopes",List.of("demo:read")),request)).code);
        assertEquals("FEATURE_NOT_READY",assertThrows(ApiFailure.class,()->controller.rotate(1,Map.of("scopes",List.of()),request)).code);
        when(service.summary(1)).thenReturn(Map.of("status","ACTIVE","generation",4L));
        var state=(Map<?,?>)controller.status(1);
        assertEquals("ACTIVE",state.get("status"));assertEquals(Boolean.FALSE,state.get("issuanceEnabled"));
        assertEquals(Map.of("status","REVOKED"),controller.revoke(1,request));verify(service).revoke(1,1);
        verify(service,never()).issue(anyLong(),anyList(),anyBoolean(),anyLong());
    }
    @Test void disabledAndNeverIssuedKeepsLegacyFeatureNotReadyState() {
        var service=mock(AppCredentialService.class);var activation=mock(QuotaActivationService.class);
        when(service.summary(1)).thenReturn(Map.of("status","NOT_ISSUED"));
        var controller=new AppCredentialController(service,mock(DeveloperApplicationService.class),activation,mock(ReleaseService.class));
        assertEquals(Map.of("status","FEATURE_NOT_READY"),controller.status(1));
    }
    @Test void nodeGateBlocksIssueButDoesNotPreventSafetyRevocation() {
        var service=mock(AppCredentialService.class);var activation=mock(QuotaActivationService.class);var releases=mock(ReleaseService.class);
        when(activation.enabled()).thenReturn(true);when(releases.current()).thenReturn(Map.of("version",2));
        var release=new ConfigRelease();release.sha256="sha";when(releases.find(2)).thenReturn(release);
        doThrow(new ApiFailure(409,"GATEWAY_CAPABILITY_NOT_READY","节点未知")).when(activation).requireReady(2,"sha");
        var controller=new AppCredentialController(service,mock(DeveloperApplicationService.class),activation,releases);
        var request=new MockHttpServletRequest();request.setAttribute("session",new Session(1,"ADMIN","ADMIN",List.of()));
        assertThrows(ApiFailure.class,()->controller.issue(1,Map.of("scopes",List.of()),request));verifyNoInteractions(service);
        assertEquals(Map.of("status","REVOKED"),controller.revoke(1,request));verify(service).revoke(1,1);
    }
}
