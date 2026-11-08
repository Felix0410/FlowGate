package com.flowgate.control.service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.flowgate.contract.*;
import com.flowgate.control.entity.*;
import com.flowgate.control.mapper.*;
import com.flowgate.control.security.ApiFailure;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** DB先提交代次，再向Redis发布会话；Redis失败不恢复旧代次，明确承认跨系统非原子。 */
@Service
public class AppCredentialService {
 private final ApplicationEntryMapper apps;private final DeveloperEntryMapper developers;private final AppPrincipalEntryMapper links;private final PrincipalMapper principals;private final AppCredentialStateMapper states;private final AuditService audit;private final StringRedisTemplate redis;private final TransactionTemplate tx;private final SecureRandom random=new SecureRandom();
 public AppCredentialService(ApplicationEntryMapper apps,DeveloperEntryMapper developers,AppPrincipalEntryMapper links,PrincipalMapper principals,AppCredentialStateMapper states,AuditService audit,StringRedisTemplate redis,PlatformTransactionManager manager){this.apps=apps;this.developers=developers;this.links=links;this.principals=principals;this.states=states;this.audit=audit;this.redis=redis;this.tx=new TransactionTemplate(manager);this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
 private record Issued(long principalId,long generation,List<String> scopes){}
 public Map<String,Object> issue(long app,List<String> scopes,boolean rotate,long actor){
  Rules.require(scopes!=null&&scopes.size()<=100,"Scope列表不合法");scopes.forEach(Rules::scope);
  // 1. 应用行锁串行首次签发/轮换；DB成功提交后才能操作Redis，不借旧createCaller签发。
  Issued issued=tx.execute(status->{
   var application=apps.lockById(BusinessInputs.id(app));if(application==null)throw BusinessInputs.missing("应用");
   var developer=developers.shareById(application.developerId);
   if(!"ACTIVE".equals(application.status)||developer==null||!"ACTIVE".equals(developer.status))throw new ApiFailure(403,"APP_DISABLED","应用或客户已停用");
   var mappings=links.selectList(new QueryWrapper<AppPrincipalEntry>().eq("application_id",app).last("LIMIT 2"));
   if(mappings.size()>1)throw new ApiFailure(409,"APP_BINDING_CONFLICT","应用主体关联异常");
   Principal principal;AppCredentialState state;
   if(mappings.isEmpty()){
    if(rotate)throw BusinessInputs.missing("待轮换凭证");
    principal=new Principal();principal.username="fgapp-"+app+"-"+UUID.randomUUID();principal.principalType="APP_CALLER";principal.role="APP_CALLER";principal.enabled=true;principal.scopesJson=SnapshotCodec.JSON.writeValueAsString(scopes);principals.insert(principal);
    var link=new AppPrincipalEntry();link.principalId=principal.id;link.applicationId=app;links.insert(link);
    state=new AppCredentialState();state.principalId=principal.id;state.generation=1L;state.enabled=true;states.insert(state);
   }else{
    if(!rotate)throw new ApiFailure(409,"USE_ROTATE","凭证已签发，请使用轮换操作");
    principal=principals.selectById(mappings.get(0).principalId);state=states.selectById(principal.id);
    if(state==null||!"APP_CALLER".equals(principal.principalType))throw new ApiFailure(409,"APP_BINDING_CONFLICT","凭证主体状态异常");
    state.generation=Math.addExact(state.generation,1);state.enabled=true;states.updateById(state);principal.scopesJson=SnapshotCodec.JSON.writeValueAsString(scopes);principal.enabled=true;principals.updateById(principal);
   }
   audit.record(actor,rotate?"ROTATE_APP_CREDENTIAL":"ISSUE_APP_CREDENTIAL","application",Long.toString(app),null,"提交APP专用凭证代次；会话发布与DB非原子");
   return new Issued(principal.id,state.generation,List.copyOf(scopes));
  });
  byte[] bytes=new byte[32];random.nextBytes(bytes);String token="fgapp_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);String digest=SnapshotCodec.digest(token);
  var session=new AppCredentialSession(issued.principalId,"APP_CALLER",issued.scopes,issued.generation);
  // 2. Lua拒绝迟到旧代次覆盖新会话；只有Redis明确ACK且DB代次仍匹配才返回明文。
  Long ack;
  try { ack=redis.execute(new DefaultRedisScript<>("""
   local old=redis.call('GET',KEYS[2])
   if old then
    local value=redis.call('GET','fg:auth:app:'..old)
    if value and cjson.decode(value).credentialGeneration>tonumber(ARGV[3]) then return 0 end
    redis.call('DEL','fg:auth:app:'..old)
   end
   redis.call('SET',KEYS[1],ARGV[1],'EX',3600)
   redis.call('SET',KEYS[2],ARGV[2],'EX',3600)
   return 1
   """,Long.class),List.of("fg:auth:app:"+digest,"fg:auth:app-index:"+issued.principalId),SnapshotCodec.JSON.writeValueAsString(session),digest,Long.toString(issued.generation)); }
  catch(org.springframework.dao.DataAccessException unavailable){throw new ApiFailure(503,"CREDENTIAL_PUBLISH_UNAVAILABLE","新代次已提交，旧凭证失效；Redis发布未完成，请恢复后轮换重试");}
  var current=states.selectById(issued.principalId);
  if(!Long.valueOf(1).equals(ack)||!Boolean.TRUE.equals(current.enabled)||current.generation!=issued.generation)throw new ApiFailure(409,"CREDENTIAL_SUPERSEDED","凭证已被并发轮换或撤销，请重新操作");
  return Map.of("principalId",issued.principalId,"generation",issued.generation,"token",token,"expiresInSeconds",3600);
 }
 public void revoke(long app,long actor){
  Issued state=tx.execute(status->{
   var application=apps.lockById(BusinessInputs.id(app));if(application==null)throw BusinessInputs.missing("应用");
   var link=links.selectOne(new QueryWrapper<AppPrincipalEntry>().eq("application_id",app));if(link==null)throw BusinessInputs.missing("凭证");
   var entry=states.selectById(link.principalId);entry.generation=Math.addExact(entry.generation,1);entry.enabled=false;states.updateById(entry);
   audit.record(actor,"REVOKE_APP_CREDENTIAL","application",Long.toString(app),null,"DB撤销代次已提交；Redis清理失败仍拒绝旧凭证");return new Issued(entry.principalId,entry.generation,List.of());
  });
  // 3. 清理失败不会回滚DB撤销；以后每次配额授权仍校验enabled/generation。
  try { redis.execute(new DefaultRedisScript<>("""
   local old=redis.call('GET',KEYS[1])
   if old then
    local value=redis.call('GET','fg:auth:app:'..old)
    if value and cjson.decode(value).credentialGeneration>tonumber(ARGV[1]) then return 0 end
    redis.call('DEL','fg:auth:app:'..old)
   end
   return redis.call('DEL',KEYS[1])
   """,Long.class),List.of("fg:auth:app-index:"+state.principalId),Long.toString(state.generation)); }
  catch(org.springframework.dao.DataAccessException unavailable){throw new ApiFailure(503,"CREDENTIAL_REVOKED_REDIS_CLEANUP_PENDING","DB撤销已提交，旧凭证不能再授权；Redis清理未完成");}
 }
 public Object summary(long app){
  var link=links.selectOne(new QueryWrapper<AppPrincipalEntry>().eq("application_id",BusinessInputs.id(app)));
  if(link==null)return Map.of("status","NOT_ISSUED");var state=states.selectById(link.principalId);
  return Map.of("status",Boolean.TRUE.equals(state.enabled)?"ACTIVE":"REVOKED","generation",state.generation);
 }
}
