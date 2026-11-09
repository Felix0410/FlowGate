package com.flowgate.control.service;

import com.flowgate.control.async.AsyncDiagnostics;
import com.flowgate.control.entity.ConfigState;
import com.flowgate.control.entity.ConfigRelease;
import com.flowgate.control.mapper.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 交付诊断不能绕过真实门禁，默认关闭与缺证据不得标READY。 */
class V11DeliveryReadinessTest {
    @Test void disabledDoesNotQueryNewTables() {
        var caps=mock(NodeCapabilityEntryMapper.class);var nodes=mock(ReleaseNodeMapper.class);
        var gate=new QuotaActivationService(caps,nodes,false,"node");
        assertEquals("BLOCKED",((Map<?,?>)gate.status()).get("readiness"));
        verifyNoInteractions(caps,nodes);
    }
    @Test void zeroVersionCannotBecomeCredentialReady() {
        var caps=mock(NodeCapabilityEntryMapper.class);var nodes=mock(ReleaseNodeMapper.class);
        var states=mock(ConfigStateMapper.class);var releases=mock(ConfigReleaseMapper.class);
        var current=new ConfigState();current.currentVersion=0L;when(states.selectById(1)).thenReturn(current);
        var gate=new QuotaActivationService(caps,nodes,true,"node");gate.setReleaseFacts(states,releases);
        var result=(Map<?,?>)gate.status();assertEquals("BLOCKED",result.get("readiness"));assertEquals("INITIAL_RELEASE_REQUIRED",result.get("code"));
        verifyNoInteractions(caps,nodes,releases);
    }
    @Test void missingTrustedNodeStillUsesRealGate() {
        var caps=mock(NodeCapabilityEntryMapper.class);var nodes=mock(ReleaseNodeMapper.class);
        var states=mock(ConfigStateMapper.class);var releases=mock(ConfigReleaseMapper.class);
        var current=new ConfigState();current.currentVersion=1L;when(states.selectById(1)).thenReturn(current);
        var release=new ConfigRelease();release.sha256="sha";when(releases.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(release);
        var gate=new QuotaActivationService(caps,nodes,true,"node");gate.setReleaseFacts(states,releases);
        assertEquals("GATEWAY_CAPABILITY_NOT_READY",((Map<?,?>)gate.status()).get("code"));verify(caps).selectById("node");
    }
    @Test void missingReleaseFactsRemainUnknown() {
        var gate=new QuotaActivationService(mock(NodeCapabilityEntryMapper.class),mock(ReleaseNodeMapper.class),true,"node");
        assertEquals("UNKNOWN",((Map<?,?>)gate.status()).get("readiness"));
    }
    @Test void publisherHeartbeatIsIndependentFromHistoricalCounters() {
        var diagnostics=new AsyncDiagnostics();diagnostics.count("sent");
        assertEquals("UNKNOWN",diagnostics.publisherStatus().get("state"));
        diagnostics.publisherTick(true);assertEquals("READY",diagnostics.publisherStatus().get("state"));
        diagnostics.publisherTick(false);assertEquals("BLOCKED",diagnostics.publisherStatus().get("state"));
        assertEquals(1L,diagnostics.snapshot().get("sent"));
    }
}
