package com.flowgate.control.controller;

import com.flowgate.contract.Session;
import com.flowgate.control.service.PlanSubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 套餐额度定义不可变，订阅只开放创建/查询；不提供绕开行锁的区间PATCH。 */
@RestController
public class PlanSubscriptionController {
    private final PlanSubscriptionService business;
    public PlanSubscriptionController(PlanSubscriptionService business){this.business=business;}
    private long actor(HttpServletRequest request){return ((Session)request.getAttribute("session")).principalId();}
    @GetMapping("/admin/plans") public Object plans(@RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="20") int limit){return business.plans(afterId,limit);}
    @GetMapping("/admin/plans/{id}") public Object plan(@PathVariable long id){return business.plan(id);}
    @PostMapping("/admin/plans") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Object createPlan(@RequestBody Map<String,Object> input,HttpServletRequest req){return business.savePlan(null,input,actor(req));}
    @PatchMapping("/admin/plans/{id}") public Object updatePlan(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest req){return business.savePlan(id,input,actor(req));}
    @GetMapping("/admin/subscriptions") public Object subscriptions(@RequestParam(required=false) Long applicationId,@RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="20") int limit){return business.subscriptions(applicationId,afterId,limit);}
    @GetMapping("/admin/subscriptions/{id}") public Object subscription(@PathVariable long id){return business.subscription(id);}
    @PostMapping("/admin/subscriptions") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Object subscribe(@RequestBody Map<String,Object> input,HttpServletRequest req){return business.subscribe(input,actor(req));}
}
