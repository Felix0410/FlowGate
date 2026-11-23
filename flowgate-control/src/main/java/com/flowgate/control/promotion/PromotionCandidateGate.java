package com.flowgate.control.promotion;

import com.flowgate.control.portal.*;
import com.flowgate.control.security.ApiFailure;
import com.flowgate.control.service.AuditService;
import com.flowgate.contract.SnapshotCodec;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.flowgate.control.portal.PortalStore.*;

/** Lua只发短期候选；重建先持MySQL活动独占锁滚epoch，绝不以Redis回写库存。 */
@Service
public class PromotionCandidateGate {
    private final StringRedisTemplate redis;private final PortalStore db;private final AuditService audit;private final CatalogCacheService cache;private final TransactionTemplate tx;
    private final DefaultRedisScript<String> claim=script("promo-candidate.lua",String.class),compensate=script("promo-compensate.lua",String.class);
    private final DefaultRedisScript<Long> build=script("promo-rebuild.lua",Long.class);
    private final DefaultRedisScript<Long> state=new DefaultRedisScript<>("if redis.call('GET',KEYS[1])==ARGV[1] then redis.call('SET',KEYS[2],ARGV[2],'EX',ARGV[3]); return 1 end; return 0",Long.class);
    private final LongAdder rebuilt=new LongAdder(),failed=new LongAdder();
    public PromotionCandidateGate(StringRedisTemplate redis,PortalStore db,AuditService audit,CatalogCacheService cache,PlatformTransactionManager manager){this.redis=redis;this.db=db;this.audit=audit;this.cache=cache;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(8);}
    private static <T> DefaultRedisScript<T> script(String file,Class<T> type){var result=new DefaultRedisScript<T>();result.setLocation(new ClassPathResource("redis/"+file));result.setResultType(type);return result;}
    private String key(long id){return "fg:c:promo:gate:"+id;}
    public record Candidate(long promotionId,long epoch,String token,String userKey){}
    public Candidate acquire(long id,long epoch,long user){String prefix=key(id),token=UUID.randomUUID().toString(),userKey=prefix+":user:"+SnapshotCodec.digest(id+":"+user);
        try{String outcome=redis.execute(claim,List.of(prefix+":epoch",prefix+":state",prefix+":remaining",prefix+":candidate:"+token,userKey,prefix+":lastIssued"),Long.toString(epoch),token,"5000",Long.toString(System.currentTimeMillis()));
            if(!"CANDIDATE_ONLY".equals(outcome)){if("SOFT_SOLD_OUT".equals(outcome))throw new ApiFailure(409,"PROMOTION_BUSY_OR_SOLD_OUT","名额暂不可用，请稍后查询活动");if("USER_BUSY".equals(outcome))throw new ApiFailure(429,"PROMOTION_REQUEST_PENDING","请求正在处理中，请先查询我的抢购");throw new ApiFailure(503,"CACHE_NOT_READY","活动正在恢复，请稍后重试");}
            return new Candidate(id,epoch,token,userKey);
        }catch(org.springframework.dao.DataAccessException unavailable){throw new ApiFailure(503,"CACHE_NOT_READY","活动暂不可受理，已有订单不受影响");}
    }
    public void compensate(Candidate candidate){try{String p=key(candidate.promotionId());redis.execute(compensate,List.of(p+":epoch",p+":state",p+":candidate:"+candidate.token(),candidate.userKey(),p+":remaining"),Long.toString(candidate.epoch()),candidate.token());}catch(org.springframework.dao.DataAccessException unavailable){failed.increment();}}
    public void close(long id,long epoch){try{redis.execute(state,List.of(key(id)+":epoch",key(id)+":state"),Long.toString(epoch),"CLOSED","86400");}catch(org.springframework.dao.DataAccessException unavailable){failed.increment();}}
    public Map<String,Object> rebuild(long id,long actor,String target){
        // 1. 独占活动行等待旧共享锁事务完成；同版本并发发布只有一个能进入新epoch。
        var seed=db.need("SELECT epoch,version FROM fg_promotion WHERE id=?",id);
        var promotion=tx.execute(status->{var row=db.need("SELECT * FROM fg_promotion WHERE id=? FOR UPDATE",id);if(n(row,"version")!=n(seed,"version"))throw conflict();
            db.update("UPDATE fg_promotion SET status='REBUILDING',epoch=epoch+1,version=version+1 WHERE id=?",id);audit.record(actor,"REBUILD_PROMOTION","promotion",Long.toString(id),null,"按MySQL库存重建Redis，旧候选失效");cache.invalidateAfterCommit();return db.need("SELECT * FROM fg_promotion WHERE id=?",id);});
        long epoch=n(promotion,"epoch");
        // 2. 一条一致性查询核对库存与Intent/收据；重建期只允许下游释放或reserved→sold，available不会减小。
        var stock=db.need("SELECT s.*,(SELECT COUNT(*) FROM fg_promo_purchase_intent i WHERE i.promotion_id=s.promotion_id AND i.status IN ('ACCEPTED','ORDER_READY')) expected_reserved,(SELECT COUNT(*) FROM fg_promo_purchase_intent i JOIN fg_payment_record p ON p.order_id=i.order_id WHERE i.promotion_id=s.promotion_id AND i.status IN ('PAID','REFUNDED')) expected_sold FROM fg_promo_stock s WHERE promotion_id=?",id);
        if(n(stock,"reserved")!=n(stock,"expected_reserved")||n(stock,"sold")!=n(stock,"expected_sold"))throw new ApiFailure(409,"INTEGRITY_GAP","库存与受理/付款事实不一致，保持恢复中等待检查");
        long available=n(stock,"total_stock")-n(stock,"reserved")-n(stock,"sold");String p=key(id);long ttl=Math.max(60,Math.min(2678400,Duration.between(db.nowUtc(),com.flowgate.control.commerce.OrderService.instant(promotion,"ends_at")).getSeconds()+60));
        try{
            if(!Long.valueOf(1).equals(redis.execute(build,List.of(p+":epoch",p+":state",p+":remaining"),Long.toString(epoch),Long.toString(available),Long.toString(ttl))))throw conflict();
            // 3. Redis先CLOSED、DB CAS恢复，最后打开gate；任意失败均不能凭Redis名额绕过DB状态。
            tx.executeWithoutResult(status->{if(db.update("UPDATE fg_promotion SET status=?,version=version+1 WHERE id=? AND status='REBUILDING' AND epoch=?",target,id,epoch)!=1)throw conflict();cache.invalidateAfterCommit();});
            if(!Long.valueOf(1).equals(redis.execute(state,List.of(p+":epoch",p+":state"),Long.toString(epoch),Set.of("READY","RUNNING").contains(target)?"READY":"CLOSED",Long.toString(ttl))))throw conflict();
            rebuilt.increment();return db.need("SELECT * FROM fg_promotion WHERE id=?",id);
        }catch(org.springframework.dao.DataAccessException unavailable){failed.increment();tx.executeWithoutResult(status->{db.update("UPDATE fg_promotion SET status='REBUILDING',version=version+1 WHERE id=? AND epoch=?",id,epoch);cache.invalidateAfterCommit();});throw new ApiFailure(503,"CACHE_NOT_READY","Redis重建失败，活动暂不可受理");}
    }
    private ApiFailure conflict(){return new ApiFailure(409,"PROMOTION_VERSION_CONFLICT","活动状态或重建代次已变化，请刷新");}
    public Map<String,Object> diagnostics(long id){try{var values=redis.opsForValue().multiGet(List.of(key(id)+":epoch",key(id)+":state",key(id)+":remaining"));var result=new LinkedHashMap<String,Object>();result.put("epoch",values.get(0));result.put("gate",values.get(1));result.put("softRemaining",values.get(2));result.put("rebuilds",rebuilt.sum());result.put("failures",failed.sum());result.put("sampledAt",Instant.now());return result;}catch(org.springframework.dao.DataAccessException unavailable){return Map.of("state","UNKNOWN","reason","Redis暂不可达");}}
    public boolean staleOrExhausted(long id,long available){try{String p=key(id);var v=redis.opsForValue().multiGet(List.of(p+":epoch",p+":state",p+":remaining",p+":lastIssued"));if(v.get(0)==null||!"READY".equals(v.get(1)))return true;return available>0&&"0".equals(v.get(2))&&(v.get(3)==null||System.currentTimeMillis()-Long.parseLong(v.get(3))>5000);}catch(org.springframework.dao.DataAccessException unavailable){return false;}}
}
