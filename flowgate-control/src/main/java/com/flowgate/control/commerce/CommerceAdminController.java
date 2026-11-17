package com.flowgate.control.commerce;

import com.flowgate.contract.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class CommerceAdminController {
    private final CommerceService commerce;private final CommerceReadiness readiness;
    public CommerceAdminController(CommerceService commerce,CommerceReadiness readiness){this.commerce=commerce;this.readiness=readiness;}
    @GetMapping("/admin/commerce/readiness") public Object readiness(){return readiness.status();}
    @GetMapping("/admin/orders/{orderNo}") public Object detail(@PathVariable String orderNo){return commerce.adminDetail(orderNo);}
    @GetMapping("/admin/orders") public Object orders(@RequestParam(defaultValue="0")long afterId,@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String status){return commerce.adminOrders(afterId,limit,status);}
    @GetMapping("/admin/commerce/events") public Object events(@RequestParam(defaultValue="0")long afterId,@RequestParam(defaultValue="20")int limit){return commerce.events(afterId,limit);}
    @GetMapping("/admin/commerce/reconciliation") public Object reconcile(@RequestParam(required=false)java.time.LocalDate day){return commerce.reconcile(day==null?java.time.LocalDate.now(java.time.ZoneOffset.UTC):day);}
    @PostMapping("/admin/commerce/events/{id}/replay") public Object replay(@PathVariable String id,HttpServletRequest r){return commerce.replay(id,((Session)r.getAttribute("session")).principalId());}
}
