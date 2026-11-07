package com.flowgate.control.service;
import com.flowgate.contract.*;
import com.flowgate.control.entity.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.security.ApiFailure;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 专用凭证只在DB代次提交后发布Redis；失败不得恢复旧代次或泄露Token。 */
class V11AppCredentialTest {
 @Test void newPurposeAndNamespaceArePublishedAfterCommit(){
  var apps=mock(ApplicationEntryMapper.class);var devs=mock(DeveloperEntryMapper.class);var links=mock(AppPrincipalEntryMapper.class);var principals=mock(PrincipalMapper.class);var states=mock(AppCredentialStateMapper.class);var redis=mock(StringRedisTemplate.class);var manager=mock(PlatformTransactionManager.class);
  var app=new ApplicationEntry();app.id=1L;app.developerId=2L;app.status="ACTIVE";var dev=new DeveloperEntry();dev.status="ACTIVE";
  when(apps.lockById(1)).thenReturn(app);when(devs.shareById(2)).thenReturn(dev);when(links.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
  when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  doAnswer(call->{((Principal)call.getArgument(0)).id=3L;return 1;}).when(principals).insert(any(Principal.class));
  var current=new AtomicReference<AppCredentialState>();doAnswer(call->{current.set(call.getArgument(0));return 1;}).when(states).insert(any(AppCredentialState.class));when(states.selectById(3L)).thenAnswer(call->current.get());
  when(redis.execute(any(RedisScript.class),anyList(),any(),any(),any())).thenReturn(1L);
  var service=new AppCredentialService(apps,devs,links,principals,states,mock(AuditService.class),redis,manager);
  var issued=service.issue(1,List.of("demo:read"),false,9);
  assertTrue(((String)issued.get("token")).matches("fgapp_[A-Za-z0-9_-]{43}"));assertEquals(1L,issued.get("generation"));
  var order=inOrder(manager,redis);order.verify(manager).commit(any());order.verify(redis).execute(any(RedisScript.class),argThat((List<String> keys)->keys.size()==2&&((String)keys.get(0)).startsWith("fg:auth:app:")&&((String)keys.get(1)).equals("fg:auth:app-index:3")),any(),any(),any());
  verify(principals).insert(argThat((Principal p)->p.principalType.equals("APP_CALLER")&&p.role.equals("APP_CALLER")));
  when(redis.execute(any(RedisScript.class),anyList(),any(),any(),any())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("not exposed"));
  assertEquals("CREDENTIAL_PUBLISH_UNAVAILABLE",assertThrows(ApiFailure.class,()->service.issue(1,List.of("demo:read"),false,9)).code);
 }
}
