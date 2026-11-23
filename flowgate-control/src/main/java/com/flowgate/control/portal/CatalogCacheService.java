package com.flowgate.control.portal;

import com.flowgate.contract.SnapshotCodec;
import com.flowgate.control.security.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

/** 只缓存公开投影：短锁单飞、代次CAS和有限回源，交易资格仍实时查MySQL。 */
@Service
public class CatalogCacheService {
    static final String GEN="fg:c:catalog:generation";
    private static final DefaultRedisScript<Long> PUT=new DefaultRedisScript<>("if redis.call('GET',KEYS[1])==ARGV[1] and redis.call('GET',KEYS[3])==ARGV[2] then redis.call('SET',KEYS[2],ARGV[3],'EX',ARGV[4]); return 1 end; return 0",Long.class);
    private static final DefaultRedisScript<Long> UNLOCK=new DefaultRedisScript<>("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end; return 0",Long.class);
    private final StringRedisTemplate redis;private final boolean enabled;private final Semaphore fallback=new Semaphore(2);
    private final Map<String,LongAdder> counters=new LinkedHashMap<>();private volatile long retryRedisAt;private volatile String lastInvalidatedAt="NOT_EXECUTED";
    public CatalogCacheService(StringRedisTemplate redis,@Value("${FLOWGATE_V12_CATALOG_CACHE_ENABLED:false}")boolean enabled){this.redis=redis;this.enabled=enabled;for(String key:List.of("HIT","MISS","DB_LOAD","NEGATIVE_HIT","SINGLE_FLIGHT_WAIT","DEGRADED","INVALIDATED","INVALIDATION_FAILED"))counters.put(key,new LongAdder());}
    public boolean enabled(){return enabled;}
    private void count(String name){counters.get(name).increment();}
    public Map<String,Object> diagnostics(){var counts=new LinkedHashMap<String,Long>();counters.forEach((k,v)->counts.put(k,v.sum()));return Map.of("enabled",enabled,"scope","本实例公开投影计数；不是所有SQL查询量","counters",counts,"lastInvalidatedAt",lastInvalidatedAt);}
    private Object load(Supplier<?> source){if(!fallback.tryAcquire())throw new ApiFailure(503,"CACHE_DEGRADED","商品查询繁忙，请稍后重试");try{count("DB_LOAD");return source.get();}finally{fallback.release();}}
    private Object hit(String key){String raw=redis.opsForValue().get(key);if(raw==null)return null;try{var node=SnapshotCodec.JSON.readTree(raw);if(node.path("schemaVersion").intValue()!=1||node.path("expiresAt").longValue()<=System.currentTimeMillis())return null;if(node.path("notFound").booleanValue()){count("NEGATIVE_HIT");throw missing();}count("HIT");return SnapshotCodec.JSON.treeToValue(node.get("value"),Object.class);}catch(ApiFailure absent){throw absent;}catch(RuntimeException invalid){redis.delete(key);return null;}}
    private ApiFailure missing(){return new ApiFailure(404,"NOT_FOUND","对象不存在或不可访问");}
    public Object get(String scope,int seconds,int jitter,boolean cacheable,Supplier<?> source){
        if(!enabled)return source.get();if(!cacheable)return load(source);
        if(System.currentTimeMillis()<retryRedisAt){count("DEGRADED");return load(source);}
        String lock=null,token=UUID.randomUUID().toString();
        try{
            // 1. 不过期的随机代次防Redis失忆产生ABA；旧数据键自然TTL回收，不做KEYS全扫。
            String generation=redis.opsForValue().get(GEN);if(generation==null){redis.opsForValue().setIfAbsent(GEN,token);generation=redis.opsForValue().get(GEN);}if(generation==null)throw new IllegalStateException();
            String key="fg:c:catalog:v1:"+generation+":"+SnapshotCodec.digest(scope);Object cached=hit(key);if(cached!=null)return cached;count("MISS");lock="fg:c:catalog:lock:"+SnapshotCodec.digest(key);
            if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock,token,Duration.ofMillis(1500)))){
                count("SINGLE_FLIGHT_WAIT");for(int i=0;i<5;i++){try{Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}cached=hit(key);if(cached!=null)return cached;}
                return load(source);
            }
            // 2. 短锁只保护公开回源；金额、OWNER、配额、订单从不放入此缓存。
            Object value;boolean notFound=false;
            try{value=load(source);}catch(ApiFailure absent){if(absent.status!=404)throw absent;value=null;notFound=true;}
            int ttl=notFound?ThreadLocalRandom.current().nextInt(3,6):seconds+ThreadLocalRandom.current().nextInt(-jitter,jitter+1);
            boolean write=true;
            if(notFound){String budget="fg:c:catalog:negative-budget:"+(System.currentTimeMillis()/5000);Long size=redis.opsForValue().increment(budget);if(size!=null&&size==1)redis.expire(budget,Duration.ofSeconds(6));write=size!=null&&size<=128;}
            var envelope=new LinkedHashMap<String,Object>();envelope.put("schemaVersion",1);envelope.put("expiresAt",System.currentTimeMillis()+ttl*1000L);envelope.put("notFound",notFound);envelope.put("value",value);String json=SnapshotCodec.JSON.writeValueAsString(envelope);
            // 3. afterCommit滚代次后，旧Reader即使最后返回也不能写入新代次；失效失败最多陈旧一个TTL。
            if(write&&json.getBytes(StandardCharsets.UTF_8).length<=32768)redis.execute(PUT,List.of(GEN,key,lock),generation,token,json,Integer.toString(ttl));
            if(notFound)throw missing();return value;
        }catch(org.springframework.data.redis.RedisConnectionFailureException|org.springframework.dao.QueryTimeoutException unavailable){retryRedisAt=System.currentTimeMillis()+1000;count("DEGRADED");return load(source);}
        finally{if(lock!=null)try{redis.execute(UNLOCK,List.of(lock),token);}catch(org.springframework.dao.DataAccessException unavailable){/* TTL释放短锁，不影响已返回业务事实。 */}}
    }
    public void invalidateAfterCommit(){if(!enabled)return;if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){invalidate();}});else invalidate();}
    public void invalidate(){if(!enabled)return;try{redis.opsForValue().set(GEN,UUID.randomUUID().toString());lastInvalidatedAt=java.time.Instant.now().toString();count("INVALIDATED");}catch(org.springframework.dao.DataAccessException unavailable){count("INVALIDATION_FAILED");}}
}
