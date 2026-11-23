package com.flowgate.control.promotion;

import com.flowgate.control.portal.CatalogCacheService;
import com.flowgate.contract.Session;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 公共活动投影与本人Intent分开；ADMIN写权限由统一管理拦截器强制。 */
@RestController
public class PromotionController {
    private final PromotionAdminService admin;private final PromotionService promotions;private final PromotionReadiness ready;private final PromotionCandidateGate gate;private final CatalogCacheService cache;
    public PromotionController(PromotionAdminService admin,PromotionService promotions,PromotionReadiness ready,PromotionCandidateGate gate,CatalogCacheService cache){this.admin=admin;this.promotions=promotions;this.ready=ready;this.gate=gate;this.cache=cache;}
    private long actor(HttpServletRequest r){return ((Session)r.getAttribute("session")).principalId();}
    private long user(HttpServletRequest r){return (Long)r.getAttribute("portalUser");}
    @GetMapping("/admin/promotions/readiness") public Object readiness(){return ready.status();}
    @GetMapping("/admin/promotions") public Object list(@RequestParam(defaultValue="0")long afterId,@RequestParam(defaultValue="20")int limit){return admin.list(afterId,limit);}
    @PostMapping("/admin/promotions") public Object create(@RequestBody Map<String,Object> b,HttpServletRequest r){return admin.create(b,actor(r));}
    @PatchMapping("/admin/promotions/{id}") public Object edit(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest r){return admin.edit(id,b,actor(r));}
    @GetMapping("/admin/promotions/{id}") public Object detail(@PathVariable long id){var result=new LinkedHashMap<>(admin.detail(id));result.put("redis",gate.diagnostics(id));result.put("rabbit",admin.queues());result.put("catalogCache",cache.diagnostics());return result;}
    @PostMapping("/admin/promotions/{id}/{action:publish|pause|resume|end|rebuild}") public Object operate(@PathVariable long id,@PathVariable String action,HttpServletRequest r){return admin.operate(id,action,actor(r));}
    @GetMapping("/admin/promotions/{id}/reconciliation") public Object reconcile(@PathVariable long id){return promotions.reconciliation(id);}
    @GetMapping("/admin/promotions/{id}/events") public Object events(@PathVariable long id){return promotions.events(id);}
    @PostMapping("/admin/promotions/events/{eventId}/replay") public Object replay(@PathVariable String eventId,HttpServletRequest r){return promotions.replay(eventId,actor(r));}
    @GetMapping("/portal/api/promotions") public Object publicList(){return admin.publicList();}
    @GetMapping("/portal/api/promotions/{id}") public Object publicDetail(@PathVariable long id){return admin.publicDetail(id);}
    @PostMapping("/portal/api/promotions/{id}/claim") @ResponseStatus(HttpStatus.ACCEPTED) public Object claim(@PathVariable long id,@RequestBody Map<String,Object> b,HttpServletRequest r){return promotions.claim(user(r),id,b);}
    @GetMapping("/portal/api/promotions/{id}/my-claim") public Object mine(@PathVariable long id,HttpServletRequest r){return promotions.mine(user(r),id);}
    @GetMapping("/portal/api/purchase-intents/{id}") public Object intent(@PathVariable long id,HttpServletRequest r){return promotions.intent(user(r),id);}
}
