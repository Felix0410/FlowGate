package com.flowgate.control.portal;

import com.flowgate.contract.*;
import com.flowgate.control.security.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.dao.DuplicateKeyException;
import static com.flowgate.control.service.BusinessInputs.*;
import static com.flowgate.control.portal.PortalStore.*;

/** Portal专用用途、摘要会话和事务注册；不借用管理会话或APP凭证。 */
@Service
public class PortalIdentityService {
    private final PortalStore db;private final StringRedisTemplate redis;private final TransactionTemplate tx;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();private final String dummy=passwords.encode(UUID.randomUUID().toString());
    public PortalIdentityService(PortalStore db,StringRedisTemplate redis,PlatformTransactionManager manager){this.db=db;this.redis=redis;this.tx=new TransactionTemplate(manager);}
    public static String normalize(String input){String name=Normalizer.normalize(input==null?"":input.trim(),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);require(name.matches("[a-z0-9][a-z0-9_.-]{2,63}"),"VALIDATION_ERROR","用户名须为3至64位字母、数字、点、横线或下划线");return name;}
    public void limit(String source,String name){
        for(String part:List.of("source:"+source,"name:"+name)){
            Long count=redis.execute(new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n",Long.class),List.of("fg:auth:portal-limit:"+SnapshotCodec.digest(part)));
            if(count==null||count>20)throw new ApiFailure(429,"AUTH_RATE_LIMIT","尝试过于频繁，请稍后再试");
        }
    }
    public Object register(Map<String,Object> body,String source){
        fields(body,"loginName","password","displayName");String name=normalize(text(body.get("loginName"),"用户名",100));limit(source,name);
        require(body.get("password") instanceof String,"VALIDATION_ERROR","密码必填");String password=(String)body.get("password");int bytes=password.getBytes(StandardCharsets.UTF_8).length;require(bytes>=8&&bytes<=72,"VALIDATION_ERROR","密码须为8至72个UTF-8字节");String display=text(body.get("displayName"),"显示名称",100);String hash=passwords.encode(password);
        try{return tx.execute(status->{
            // 1. 内部主体名隔离旧全局username空间，所有归属记录与密码同事务落库。
            long user=db.insert("INSERT INTO fg_principal(username,password_hash,principal_type,role,scopes_json,enabled) VALUES(?,?,'PORTAL_USER','PORTAL_USER','[]',TRUE)","portal-"+UUID.randomUUID(),hash);
            db.update("INSERT INTO fg_portal_user(principal_id,login_name_normalized,display_name) VALUES(?,?,?)",user,name,display);
            long developer=db.insert("INSERT INTO fg_developer(code,name,status) VALUES(?,?,'ACTIVE')","portal-"+UUID.randomUUID(),display+"的工作区");
            db.update("INSERT INTO fg_developer_member(developer_id,principal_id) VALUES(?,?)",developer,user);
            // 2. 注册不发布会话，提交失败不会产生孤立Redis凭证。
            return Map.of("userId",user,"displayName",display);
        });}catch(DuplicateKeyException duplicate){throw new ApiFailure(409,"LOGIN_NAME_TAKEN","用户名已被使用");}
    }
    public Object login(Map<String,Object> body,String source){
        fields(body,"loginName","password");String name=normalize(text(body.get("loginName"),"用户名",100));limit(source,name);String password=body.get("password") instanceof String p&&p.getBytes(StandardCharsets.UTF_8).length<=72?p:"";
        var user=db.one("SELECT p.id,p.password_hash,p.enabled,p.principal_type,u.status FROM fg_portal_user u JOIN fg_principal p ON p.id=u.principal_id WHERE u.login_name_normalized=?",name);
        boolean matches=passwords.matches(password,user==null?dummy:s(user,"password_hash"));
        if(!matches||user==null||!Boolean.TRUE.equals(user.get("enabled"))||!"PORTAL_USER".equals(user.get("principal_type"))||!"ACTIVE".equals(user.get("status")))throw new ApiFailure(401,"INVALID_CREDENTIAL","用户名或密码错误");
        String token="fgportal_"+UUID.randomUUID()+UUID.randomUUID().toString().replace("-","");
        // 3. 仅Redis写成功才回显明文；会话只存摘要键与真实主体ID。
        redis.opsForValue().set("fg:auth:portal:"+SnapshotCodec.digest(token),Long.toString(n(user,"id")),Duration.ofSeconds(1800));
        return Map.of("token",token,"expiresInSeconds",1800,"role","PORTAL_USER");
    }
    public long authenticate(String token){
        if(token==null||!token.startsWith("fgportal_")||token.length()>200)throw new ApiFailure(401,"LOGIN_REQUIRED","请先登录Portal");
        String id=redis.opsForValue().get("fg:auth:portal:"+SnapshotCodec.digest(token));if(id==null)throw new ApiFailure(401,"INVALID_SESSION","登录已过期，请重新登录");
        long user=Long.parseLong(id);var row=db.one("SELECT u.status,p.enabled,p.principal_type FROM fg_portal_user u JOIN fg_principal p ON p.id=u.principal_id WHERE u.principal_id=?",user);
        if(row==null||!"PORTAL_USER".equals(row.get("principal_type")))throw new ApiFailure(401,"INVALID_SESSION","会话用途无效");
        if(!"ACTIVE".equals(row.get("status"))||!Boolean.TRUE.equals(row.get("enabled")))throw new ApiFailure(403,"USER_DISABLED","账号已停用");return user;
    }
    public void logout(String token){redis.delete("fg:auth:portal:"+SnapshotCodec.digest(token));}
    public Object me(long user){return db.need("SELECT principal_id AS userId,display_name AS displayName FROM fg_portal_user WHERE principal_id=?",user);}
}
