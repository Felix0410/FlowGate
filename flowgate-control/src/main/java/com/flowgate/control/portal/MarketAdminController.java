package com.flowgate.control.portal;

import com.flowgate.contract.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 写权限由管理过滤器统一强制ADMIN；与Portal客户端没有代理关系。 */
@RestController
@RequestMapping("/admin/market")
public class MarketAdminController {
    private final MarketCatalogService market;private final ApiUsageService usage;
    public MarketAdminController(MarketCatalogService market,ApiUsageService usage){this.market=market;this.usage=usage;}
    private long actor(HttpServletRequest r){return ((Session)r.getAttribute("session")).principalId();}
    @GetMapping("/categories") public Object categories(){return market.categories(true);}
    @PostMapping("/categories") public Object category(@RequestBody Map<String,Object> b,HttpServletRequest r){return market.category(b,actor(r));}
    @GetMapping("/listings") public Object listings(@RequestParam(defaultValue="0")long afterId,@RequestParam(defaultValue="20")int limit){return market.adminListings(afterId,limit);}
    @PostMapping("/listings") public Object create(@RequestBody Map<String,Object> b,HttpServletRequest r){return market.save(null,b,actor(r));}
    @PatchMapping("/listings/{id}") public Object save(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest r){return market.save(id,b,actor(r));}
    @PostMapping("/listings/{id}/skus") public Object sku(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest r){return market.sku(id,b,actor(r));}
    @PostMapping("/listings/{id}/publish") public Object publish(@PathVariable long id,HttpServletRequest r){return market.publish(id,actor(r));}
    @GetMapping("/reconciliation") public Object reconcile(@RequestParam String day){return usage.reconcile(java.time.LocalDate.parse(day));}
    @PostMapping("/events/{eventId}/replay") public Object replay(@PathVariable String eventId,HttpServletRequest r){return usage.replay(eventId,actor(r));}
}
