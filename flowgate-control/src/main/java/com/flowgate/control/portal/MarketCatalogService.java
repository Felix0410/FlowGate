package com.flowgate.control.portal;

import com.flowgate.control.security.ApiFailure;
import com.flowgate.control.service.AuditService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.flowgate.control.service.BusinessInputs.*;
import static com.flowgate.control.portal.PortalStore.*;

/** 市场商品引用已发布API；展示信息和真实可领取资格分别计算。 */
@Service
public class MarketCatalogService {
    private final com.flowgate.control.commerce.CommerceReadiness commerce;private final PortalStore db;private final V12Readiness readiness;private final AuditService audit;
    public MarketCatalogService(PortalStore db,V12Readiness readiness,AuditService audit,com.flowgate.control.commerce.CommerceReadiness commerce){this.commerce=commerce;this.db=db;this.readiness=readiness;this.audit=audit;}
    public Object categories(boolean admin){return db.rows("SELECT id,code,name,status,sort_order FROM fg_api_category "+(admin?"":"WHERE status='ACTIVE' ")+"ORDER BY sort_order,id");}
    @Transactional public Object category(Map<String,Object> b,long actor){fields(b,"code","name","sortOrder");long id=db.insert("INSERT INTO fg_api_category(code,name,sort_order) VALUES(?,?,?)",code(b.get("code")),text(b.get("name"),"分类名称",100),b.containsKey("sortOrder")?(int)number(b.get("sortOrder"),1000000,"排序"):0);audit.record(actor,"CREATE_MARKET_CATEGORY","market",Long.toString(id),null,"创建市场分类");return db.need("SELECT * FROM fg_api_category WHERE id=?",id);}
    public Object adminListings(long after,int limit){page(after,limit);return db.rows("SELECT l.*,a.method,a.public_path,c.name AS category_name FROM fg_api_listing l JOIN fg_api a ON a.id=l.api_id JOIN fg_api_category c ON c.id=l.category_id WHERE l.id>? ORDER BY l.id LIMIT ?",after,limit);}
    @Transactional public Object save(Long id,Map<String,Object> b,long actor){
        fields(b,"apiId","categoryId","title","summary","docText","status","revision");
        if(id==null){long api=number(b.get("apiId"),Long.MAX_VALUE,"API");db.need("SELECT id FROM fg_api WHERE id=?",api);long category=number(b.get("categoryId"),Long.MAX_VALUE,"分类");db.need("SELECT id FROM fg_api_category WHERE id=?",category);id=db.insert("INSERT INTO fg_api_listing(api_id,category_id,title,summary,doc_text) VALUES(?,?,?,?,?)",api,category,text(b.get("title"),"标题",200),text(b.getOrDefault("summary","暂无说明"),"摘要",1000),document(b.getOrDefault("docText","")));}
        else{
            var old=db.need("SELECT * FROM fg_api_listing WHERE id=? FOR UPDATE",id);
            require(!b.containsKey("apiId")||n(old,"api_id")==number(b.get("apiId"),Long.MAX_VALUE,"API"),"IMMUTABLE_API","已建商品不可改为其他API");
            if(b.containsKey("revision")&&n(old,"revision")!=number(b.get("revision"),Long.MAX_VALUE,"版本"))throw new ApiFailure(409,"VERSION_CONFLICT","商品已被修改，请刷新");
            String status=b.containsKey("status")?String.valueOf(b.get("status")):s(old,"status");require(Set.of("DRAFT","OFFLINE").contains(status)||status.equals(old.get("status")),"VALIDATION_ERROR","上架请使用上架操作");
            long cat=b.containsKey("categoryId")?number(b.get("categoryId"),Long.MAX_VALUE,"分类"):n(old,"category_id");db.need("SELECT id FROM fg_api_category WHERE id=?",cat);
            db.update("UPDATE fg_api_listing SET category_id=?,title=?,summary=?,doc_text=?,status=?,revision=revision+1 WHERE id=?",cat,b.containsKey("title")?text(b.get("title"),"标题",200):old.get("title"),b.containsKey("summary")?text(b.get("summary"),"摘要",1000):old.get("summary"),b.containsKey("docText")?document(b.get("docText")):old.get("doc_text"),status,id);
        }
        audit.record(actor,"SAVE_MARKET_LISTING","market",id.toString(),null,"保存商品展示信息；未修改路由快照");return db.need("SELECT * FROM fg_api_listing WHERE id=?",id);
    }
    private String document(Object value){require(value instanceof String&&((String)value).length()<=10000,"VALIDATION_ERROR","文档最多10000字");return (String)value;}
    @Transactional public Object sku(long listing,Map<String,Object> b,long actor){
        fields(b,"code","kind","quotaUnits","priceCents","periodPolicy");db.need("SELECT id FROM fg_api_listing WHERE id=? FOR UPDATE",listing);
        String kind=String.valueOf(b.getOrDefault("kind","FREE_BASE")),policy=String.valueOf(b.getOrDefault("periodPolicy","CURRENT_MONTH"));
        require(Set.of("FREE_BASE","PAID_BASE").contains(kind)&&Set.of("CURRENT_MONTH","NEXT_MONTH").contains(policy),"VALIDATION_ERROR","仅支持免费/付费BASE及本月/下月SKU");
        long price=0;if(kind.equals("PAID_BASE")){commerce.requireSchema();price=number(b.get("priceCents"),99999999,"价格（分）");}else require("CURRENT_MONTH".equals(policy)&&new java.math.BigDecimal(b.getOrDefault("priceCents",0).toString()).compareTo(java.math.BigDecimal.ZERO)==0,"VALIDATION_ERROR","免费仅支持本月零元SKU");
        long id=db.insert("INSERT INTO fg_api_sku(listing_id,code,kind,quota_units,price_cents,period_policy) VALUES(?,?,?,?,?,?)",listing,code(b.get("code")),kind,number(b.get("quotaUnits"),1000000,"额度"),price,policy);
        audit.record(actor,"CREATE_MARKET_SKU","market",Long.toString(id),null,"新增不可变BASE报价");return db.need("SELECT * FROM fg_api_sku WHERE id=?",id);
    }
    @Transactional public Object publish(long id,long actor){
        // 1. 商品行锁串行上架/编辑/SKU；必须真实已应用而非仅草稿已保存。
        var listing=db.need("SELECT * FROM fg_api_listing WHERE id=? FOR UPDATE",id);db.need("SELECT id FROM fg_api_category WHERE id=? AND status='ACTIVE'",listing.get("category_id"));readiness.api(n(listing,"api_id"));
        if(db.one("SELECT id FROM fg_api_sku WHERE listing_id=? AND status='ACTIVE' LIMIT 1",id)==null)throw new ApiFailure(409,"NO_ACTIVE_SKU","请先创建有效SKU");
        db.update("UPDATE fg_api_listing SET status='LISTED',revision=revision+1 WHERE id=?",id);audit.record(actor,"PUBLISH_MARKET_LISTING","market",Long.toString(id),null,"上架真实就绪的API商品");return db.need("SELECT * FROM fg_api_listing WHERE id=?",id);
    }
    private String projection(){return "SELECT l.id,l.api_id,l.category_id,l.title,l.summary,l.doc_text,l.status,l.revision,a.method,a.public_path,c.name AS category_name FROM fg_api_listing l JOIN fg_api a ON a.id=l.api_id JOIN fg_api_category c ON c.id=l.category_id ";}
    public Object search(String q,Long category,long after,int limit){page(after,limit);require(limit<=50&&q!=null&&q.length()<=100,"VALIDATION_ERROR","搜索最多100字、每页最多50项");String escaped=q.replace("!","!!").replace("%","!%").replace("_","!_");return db.rows(projection()+"WHERE l.status='LISTED' AND c.status='ACTIVE' AND l.id>? AND (? IS NULL OR l.category_id=?) AND (l.title LIKE ? ESCAPE '!' OR l.summary LIKE ? ESCAPE '!') ORDER BY l.id LIMIT ?",after,category,category,"%"+escaped+"%","%"+escaped+"%",limit);}
    public Map<String,Object> detail(long id){var row=db.need(projection()+"WHERE l.id=? AND l.status='LISTED' AND c.status='ACTIVE'",id);var result=new LinkedHashMap<>(row);try{readiness.api(n(row,"api_id"));result.put("claimable",true);result.put("reason","当前可领取；提交时会再次核验");}catch(ApiFailure blocked){result.put("claimable",false);result.put("reason",blocked.getMessage());result.put("reasonCode",blocked.code);}return result;}
    public Object offers(long id){var listing=detail(id);var offers=db.rows("SELECT id,code,kind,quota_units,price_cents,period_policy FROM fg_api_sku WHERE listing_id=? AND status='ACTIVE' ORDER BY id",id);var state=offers.stream().anyMatch(o->"PAID_BASE".equals(o.get("kind")))?commerce.status():Map.<String,Object>of("status","DISABLED","message","免费领取不需要订单");
        return offers.stream().map(row->{var view=new LinkedHashMap<>(row);boolean paid="PAID_BASE".equals(row.get("kind"));boolean ready=Boolean.TRUE.equals(listing.get("claimable"))&&(!paid||"READY".equals(state.get("status")));view.put("skuId",row.get("id"));view.put("priceCents",row.get("price_cents"));view.put("quotaUnits",row.get("quota_units"));view.put("currency","CNY");view.put("periodPolicy",row.get("period_policy"));view.put("purchasable",ready);view.put("reason",!Boolean.TRUE.equals(listing.get("claimable"))?listing.get("reason"):paid?state.get("message"):"免费领取每个用户对该API终身限一次");
            var now=db.nowUtc();String period=PortalApplicationService.period("NEXT_MONTH".equals(row.get("period_policy"))?now.atZone(java.time.ZoneOffset.UTC).plusMonths(1).toInstant():now);view.put("periodUtc",period);
            if(paid)try{var window=com.flowgate.control.commerce.OrderStateMachine.window(period,s(row,"period_policy"),now);view.put("validityPreview",Map.of("effectiveAt",window.start(),"expiresAt",window.end(),"payDeadlineAt",window.paymentDeadline()));}catch(ApiFailure closed){view.put("purchasable",false);view.put("reason",closed.getMessage());}
            return view;}).toList();}
    public Map<String,Object> paidOffer(long sku){
        var row=db.need("SELECT s.*,l.api_id,l.status AS listing_status,c.status AS category_status FROM fg_api_sku s JOIN fg_api_listing l ON l.id=s.listing_id JOIN fg_api_category c ON c.id=l.category_id WHERE s.id=? FOR SHARE",sku);
        if(!"ACTIVE".equals(row.get("status"))||!"LISTED".equals(row.get("listing_status"))||!"ACTIVE".equals(row.get("category_status"))||!"PAID_BASE".equals(row.get("kind"))||n(row,"price_cents")<=0)throw new ApiFailure(409,"OFFER_UNAVAILABLE","该付费商品当前不可购买");
        readiness.api(n(row,"api_id"));return row;
    }
    /** 交易内锁定商品与SKU状态；价格和额度只能从数据库推导。 */
    public Map<String,Object> claimOffer(long sku){var row=db.need("SELECT s.id,s.quota_units,s.kind,s.price_cents,s.period_policy,s.status,l.api_id,l.status AS listing_status,c.status AS category_status FROM fg_api_sku s JOIN fg_api_listing l ON l.id=s.listing_id JOIN fg_api_category c ON c.id=l.category_id WHERE s.id=? FOR SHARE",sku);require("ACTIVE".equals(row.get("status"))&&"LISTED".equals(row.get("listing_status"))&&"ACTIVE".equals(row.get("category_status"))&&"FREE_BASE".equals(row.get("kind"))&&n(row,"price_cents")==0&&"CURRENT_MONTH".equals(row.get("period_policy")),"OFFER_UNAVAILABLE","商品当前不可领取");readiness.api(n(row,"api_id"));return row;}
}
