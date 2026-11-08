package com.flowgate.control.service;
import com.flowgate.control.entity.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.security.ApiFailure;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 施工默认OFF及完整接流名单门禁，缺失/旧能力/过期不能只凭ONLINE放行。 */
class V11QuotaGateTest {
 @Test void disabledOrMissingNodeFailsClosed(){var caps=mock(NodeCapabilityEntryMapper.class);var nodes=mock(ReleaseNodeMapper.class);assertEquals("QUOTA_FEATURE_NOT_READY",assertThrows(ApiFailure.class,()->new QuotaActivationService(caps,nodes,false,"a,b").requireReady(1,"sha")).code);assertThrows(ApiFailure.class,()->new QuotaActivationService(caps,nodes,true,"").requireReady(1,"sha"));assertThrows(ApiFailure.class,()->new QuotaActivationService(caps,nodes,true,"a,b").requireReady(1,"sha"));}
 @Test void everyNodeMustHaveSameTrustedVersionAndFreshCapability(){
  var caps=mock(NodeCapabilityEntryMapper.class);var nodes=mock(ReleaseNodeMapper.class);var c=new NodeCapabilityEntry();c.nodeId="a";c.version=1L;c.checksum="sha";c.featuresJson="[\"USAGE_QUOTA_V1\"]";c.lastSeenAt=Instant.now();
  var n=new ReleaseNode();n.nodeId="a";n.appliedVersion=1L;n.checksum="sha";n.lastSeenAt=LocalDateTime.now(ZoneOffset.UTC).withNano(0);
  when(caps.selectById("a")).thenReturn(c);when(nodes.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(n);
  var gate=new QuotaActivationService(caps,nodes,true,"a");gate.requireReady(1,"sha");c.featuresJson="[]";assertThrows(ApiFailure.class,()->gate.requireReady(1,"sha"));c.featuresJson="[\"USAGE_QUOTA_V1\"]";c.lastSeenAt=Instant.now().minusSeconds(20);assertThrows(ApiFailure.class,()->gate.requireReady(1,"sha"));
 }
}
