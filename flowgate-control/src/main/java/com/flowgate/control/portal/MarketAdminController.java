package com.flowgate.control.portal;

import com.flowgate.contract.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 写权限由管理过滤器统一强制ADMIN；与Portal客户端没有代理关系。 */
@RestController
@RequestMapping("/admin/market")
public class MarketAdminController {
    private final CatalogCacheService cache;private final MarketReadiness readiness;private final MarketCatalogService market;private final ApiUsageService usage;
    public MarketAdminController(MarketCatalogService market,ApiUsageService usage,MarketReadiness readiness,CatalogCacheService cache){this.cache=cache;this.readiness=readiness;this.market=market;this.usage=usage;}
    private long actor(HttpServletRequest r){return ((Session)r.getAttribute("session")).principalId();}
    @GetMapping("/cache") public Object cache(){return cache.diagnostics();}
    @GetMapping("/readiness") public Object readiness(){return readiness.status();}
    @GetMapping("/categories") public Object categories(){readiness.require();return market.categories(true);}
    @PostMapping("/categories") public Object category(@RequestBody Map<String,Object> b,HttpServletRequest r){readiness.require();return market.category(b,actor(r));}
    @GetMapping("/listings") public Object listings(@RequestParam(defaultValue="0")long afterId,@RequestParam(defaultValue="20")int limit){readiness.require();return market.adminListings(afterId,limit);}
    @PostMapping("/listings") public Object create(@RequestBody Map<String,Object> b,HttpServletRequest r){readiness.require();return market.save(null,b,actor(r));}
    @PatchMapping("/listings/{id}") public Object save(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest r){readiness.require();return market.save(id,b,actor(r));}
    @GetMapping("/listings/{id}/skus") public Object skus(@PathVariable long id){readiness.require();return market.offers(id);}
    @PostMapping("/listings/{id}/skus") public Object sku(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest r){readiness.require();return market.sku(id,b,actor(r));}
    @PostMapping("/listings/{id}/publish") public Object publish(@PathVariable long id,HttpServletRequest r){readiness.require();return market.publish(id,actor(r));}
    @GetMapping("/reconciliation") public Object reconcile(@RequestParam String day){readiness.require();return usage.reconcile(java.time.LocalDate.parse(day));}
    @PostMapping("/events/{eventId}/replay") public Object replay(@PathVariable String eventId,HttpServletRequest r){readiness.require();return usage.replay(eventId,actor(r));}
}
