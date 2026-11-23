package com.flowgate.control.promotion;

import com.flowgate.control.portal.*;
import com.flowgate.control.commerce.*;
import com.flowgate.control.security.ApiFailure;
import com.flowgate.control.service.AuditService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import static com.flowgate.control.portal.PortalStore.*;
import static com.flowgate.control.service.BusinessInputs.*;

/** 活动管理与公开投影；报价在DRAFT冻结，发布后不再接受价格、库存或月份编辑。 */
@Service
public class PromotionAdminService {
    private final PortalStore db;private final MarketCatalogService market;private final PromotionReadiness ready;private final PromotionCandidateGate gate;private final CatalogCacheService cache;private final AuditService audit;private final TransactionTemplate tx;private final ObjectProvider<RabbitTemplate> rabbit;
    public PromotionAdminService(PortalStore db,MarketCatalogService market,PromotionReadiness ready,PromotionCandidateGate gate,CatalogCacheService cache,AuditService audit,PlatformTransactionManager manager,@Qualifier("apiUsageRabbitTemplate")ObjectProvider<RabbitTemplate> rabbit){this.db=db;this.market=market;this.ready=ready;this.gate=gate;this.cache=cache;this.audit=audit;this.rabbit=rabbit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(8);}
    public Object list(long after,int limit){if(!ready.available())return List.of();page(after,limit);return db.rows("SELECT p.*,s.total_stock,s.reserved,s.sold,l.title FROM fg_promotion p JOIN fg_promo_stock s ON s.promotion_id=p.id JOIN fg_api_sku sku ON sku.id=p.sku_id JOIN fg_api_listing l ON l.id=sku.listing_id WHERE p.id>? ORDER BY p.id LIMIT ?",after,limit);}
    public Map<String,Object> detail(long id){ready.require();return db.need("SELECT p.*,s.total_stock,s.reserved,s.sold FROM fg_promotion p JOIN fg_promo_stock s ON s.promotion_id=p.id WHERE p.id=?",id);}
    public Object create(Map<String,Object> b,long actor){ready.require();fields(b,"name","skuId","promoPriceCents","totalStock","beginsAt","endsAt","orderCreationTtlSeconds","payTtlSeconds");return tx.execute(status->{
        // 1. SKU仍从MySQL共享锁读取，并核验真实已发布API；不接受客户端库存版本和用户资格。
        var offer=market.paidOffer(number(b.get("skuId"),Long.MAX_VALUE,"套餐"));Instant now=db.nowUtc();String period=PortalApplicationService.period("NEXT_MONTH".equals(offer.get("period_policy"))?now.atZone(ZoneOffset.UTC).plusMonths(1).toInstant():now);var window=OrderStateMachine.window(period,s(offer,"period_policy"),now);
        Instant begins=instant(b.get("beginsAt")),ends=instant(b.get("endsAt"));require(begins.isBefore(ends)&&ends.isAfter(now)&&!ends.isAfter(window.end().minusSeconds(900)),"INVALID_PROMOTION_WINDOW","活动结束须在固定权益月份售卖截止之前");
        long price=number(b.get("promoPriceCents"),n(offer,"price_cents"),"活动价格（分）"),stock=number(b.get("totalStock"),100000,"名额"),creation=number(b.getOrDefault("orderCreationTtlSeconds",60),300,"创单期限"),pay=number(b.getOrDefault("payTtlSeconds",900),900,"付款期限");require(pay>=5,"INVALID_PAY_TTL","付款期限至少5秒");
        long id=db.insert("INSERT INTO fg_promotion(name,sku_id,api_id,sku_revision,quota_units,sku_price_cents,promo_price_cents,period_yyyymm,period_policy,begins_at,ends_at,order_creation_ttl_seconds,pay_ttl_seconds) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",text(b.get("name"),"活动名称",200),offer.get("id"),offer.get("api_id"),offer.get("revision"),offer.get("quota_units"),offer.get("price_cents"),price,period,offer.get("period_policy"),Timestamp.from(begins),Timestamp.from(ends),creation,pay);
        db.update("INSERT INTO fg_promo_stock(promotion_id,total_stock) VALUES(?,?)",id,stock);audit.record(actor,"CREATE_PROMOTION","promotion",Long.toString(id),null,"建立固定套餐/月份/价格与库存草稿");cache.invalidateAfterCommit();return detail(id);
    });}
    public Object edit(long id,Map<String,Object> b,long actor){ready.require();fields(b,"name","promoPriceCents","totalStock","beginsAt","endsAt");return tx.execute(status->{var row=db.need("SELECT * FROM fg_promotion WHERE id=? FOR UPDATE",id);if(!"DRAFT".equals(row.get("status")))throw new ApiFailure(409,"PROMOTION_IMMUTABLE","发布后的报价和库存不可编辑，请新建活动");
        String name=b.containsKey("name")?text(b.get("name"),"活动名称",200):s(row,"name");long price=b.containsKey("promoPriceCents")?number(b.get("promoPriceCents"),n(row,"sku_price_cents"),"活动价格"):n(row,"promo_price_cents");Instant begins=b.containsKey("beginsAt")?instant(b.get("beginsAt")):OrderService.instant(row,"begins_at"),ends=b.containsKey("endsAt")?instant(b.get("endsAt")):OrderService.instant(row,"ends_at");require(begins.isBefore(ends)&&ends.isAfter(db.nowUtc())&&!ends.isAfter(YearMonth.of(Integer.parseInt(s(row,"period_yyyymm").substring(0,4)),Integer.parseInt(s(row,"period_yyyymm").substring(4))).plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(900)),"INVALID_PROMOTION_WINDOW","活动时间无效");
        db.update("UPDATE fg_promotion SET name=?,promo_price_cents=?,begins_at=?,ends_at=?,version=version+1 WHERE id=?",name,price,Timestamp.from(begins),Timestamp.from(ends),id);if(b.containsKey("totalStock"))db.update("UPDATE fg_promo_stock SET total_stock=?,version=version+1 WHERE promotion_id=?",number(b.get("totalStock"),100000,"库存"),id);audit.record(actor,"EDIT_PROMOTION","promotion",Long.toString(id),null,"编辑未发布活动");cache.invalidateAfterCommit();return detail(id);});}
    public Object operate(long id,String action,long actor){ready.require();var row=detail(id);String before=s(row,"status");
        if(Set.of("publish","resume","rebuild").contains(action)){
            if(action.equals("publish")&&!"DRAFT".equals(before)||action.equals("resume")&&!Set.of("PAUSED","REBUILDING").contains(before)||action.equals("rebuild")&&Set.of("DRAFT","ENDED").contains(before))throw new ApiFailure(409,"PROMOTION_STATE_CONFLICT","活动状态不支持该操作");
            if(!db.nowUtc().isBefore(OrderService.instant(row,"ends_at")))throw new ApiFailure(409,"PROMOTION_ENDED","活动已经结束");market.paidOffer(n(row,"sku_id"));
            try{var template=rabbit.getIfAvailable();if(template==null)throw new IllegalStateException();template.execute(channel->{var info=channel.queueDeclarePassive(PromotionIntentRabbitConfiguration.QUEUE);channel.queueDeclarePassive(PromotionIntentRabbitConfiguration.RETRY);channel.queueDeclarePassive(PromotionIntentRabbitConfiguration.POISON);if(info.getConsumerCount()<1)throw new IllegalStateException();return true;});}catch(RuntimeException unavailable){throw new ApiFailure(503,"PROMOTION_WORKER_NOT_READY","活动消息队列或消费者尚未就绪");}
            String target="rebuild".equals(action)&&Set.of("PAUSED","REBUILDING").contains(before)?"PAUSED":db.nowUtc().isBefore(OrderService.instant(row,"begins_at"))?"READY":"RUNNING";return gate.rebuild(id,actor,target);
        }
        if(!Set.of("pause","end").contains(action))throw new ApiFailure(404,"NOT_FOUND","活动操作不存在");String target=action.equals("pause")?"PAUSED":"ENDED";
        var changed=tx.execute(status->{var current=db.need("SELECT * FROM fg_promotion WHERE id=? FOR UPDATE",id);if(Set.of("DRAFT","ENDED","REBUILDING").contains(s(current,"status")))throw new ApiFailure(409,"PROMOTION_STATE_CONFLICT","活动状态已变化");db.update("UPDATE fg_promotion SET status=?,version=version+1 WHERE id=?",target,id);audit.record(actor,"STATE_PROMOTION","promotion",Long.toString(id),null,target);cache.invalidateAfterCommit();return db.need("SELECT * FROM fg_promotion WHERE id=?",id);});gate.close(id,n(changed,"epoch"));return changed;
    }
    public Map<String,Object> queues(){try{var template=rabbit.getIfAvailable();if(template==null)throw new IllegalStateException();return template.execute(channel->{var main=channel.queueDeclarePassive(PromotionIntentRabbitConfiguration.QUEUE);var retry=channel.queueDeclarePassive(PromotionIntentRabbitConfiguration.RETRY);var poison=channel.queueDeclarePassive(PromotionIntentRabbitConfiguration.POISON);return Map.<String,Object>of("status","OBSERVED","pending",main.getMessageCount(),"consumers",main.getConsumerCount(),"retry",retry.getMessageCount(),"poison",poison.getMessageCount());});}catch(RuntimeException unavailable){return Map.of("status","UNKNOWN");}}
    private String publicSql(){return "SELECT p.id,p.name,p.api_id,p.sku_id,p.period_yyyymm,p.quota_units,p.sku_price_cents,p.promo_price_cents,p.currency,p.begins_at,p.ends_at,p.status,p.epoch,p.version,s.total_stock,l.title FROM fg_promotion p JOIN fg_promo_stock s ON s.promotion_id=p.id JOIN fg_api_sku sku ON sku.id=p.sku_id JOIN fg_api_listing l ON l.id=sku.listing_id ";}
    public Object publicList(){if(!ready.available())return List.of();return cache.get("promotions:list",4,1,true,()->db.rows(publicSql()+"WHERE p.status<>'DRAFT' ORDER BY p.id DESC LIMIT 20").stream().map(this::publicProjection).toList());}
    public Map<String,Object> publicDetail(long id){ready.require();id(id);return (Map<String,Object>)cache.get("promotions:detail:"+id,4,1,true,()->publicProjection(db.need(publicSql()+"WHERE p.id=? AND p.status<>'DRAFT'",id)));}
    private Map<String,Object> publicProjection(Map<String,Object> row){var view=new LinkedHashMap<>(row);view.put("begins_at",OrderService.instant(row,"begins_at").toString());view.put("ends_at",OrderService.instant(row,"ends_at").toString());return view;}
    @Scheduled(fixedDelay=30000) public void maintain(){if(!ready.available())return;try{
        // 2. 时钟推进不是准入权威；受理事务仍校验DB UTC。软库存保守耗尽在租约过后节流重建。
        db.update("UPDATE fg_promotion SET status='RUNNING',version=version+1 WHERE status='READY' AND begins_at<=UTC_TIMESTAMP(3) AND ends_at>UTC_TIMESTAMP(3)");
        for(var row:db.rows("SELECT p.*,s.total_stock-s.reserved-s.sold available FROM fg_promotion p JOIN fg_promo_stock s ON s.promotion_id=p.id WHERE p.status IN ('READY','RUNNING') ORDER BY p.id LIMIT 20")){
            long id=n(row,"id");if(!db.nowUtc().isBefore(OrderService.instant(row,"ends_at"))){db.update("UPDATE fg_promotion SET status='ENDED',version=version+1 WHERE id=? AND status IN ('READY','RUNNING')",id);gate.close(id,n(row,"epoch"));cache.invalidate();}
            else if(gate.staleOrExhausted(id,n(row,"available")))gate.rebuild(id,0,db.nowUtc().isBefore(OrderService.instant(row,"begins_at"))?"READY":"RUNNING");
        }
    }catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("PROMOTION_MAINTENANCE_PENDING");}}
}
