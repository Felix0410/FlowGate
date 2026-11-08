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
    @Test void disabledActivationNeverIssuesOrRevokesCredentials() {
        var service=mock(AppCredentialService.class);var activation=mock(QuotaActivationService.class);
        var controller=new AppCredentialController(service,mock(DeveloperApplicationService.class),activation,mock(ReleaseService.class));
        assertEquals("FEATURE_NOT_READY",assertThrows(ApiFailure.class,()->controller.issue(1,Map.of("scopes",List.of("demo:read")),new MockHttpServletRequest())).code);
        assertEquals(Map.of("status","FEATURE_NOT_READY"),controller.status(1));
        assertThrows(ApiFailure.class,()->controller.revoke(1,new MockHttpServletRequest()));verifyNoInteractions(service);
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
