package com.flowgate.control.portal;

import com.flowgate.control.security.ApiFailure;
import com.flowgate.control.service.BusinessInputs;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;

/** 普通用户API独立入口，所有个人对象均由会话主体和OWNER检查限定。 */
@RestController
@RequestMapping("/portal/api")
public class PortalController {
    private final PortalIdentityService identity;private final PortalOwnershipService owner;private final PortalApplicationService apps;private final MarketCatalogService market;
    public PortalController(PortalIdentityService identity,PortalOwnershipService owner,PortalApplicationService apps,MarketCatalogService market){this.identity=identity;this.owner=owner;this.apps=apps;this.market=market;}
    private long user(HttpServletRequest r){return (Long)r.getAttribute("portalUser");}
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED) public Object register(@RequestBody Map<String,Object> b,HttpServletRequest r){return identity.register(b,r.getRemoteAddr());}
    @PostMapping("/auth/login") public Object login(@RequestBody Map<String,Object> b,HttpServletRequest r){return identity.login(b,r.getRemoteAddr());}
    @PostMapping("/auth/logout") public Object logout(HttpServletRequest r){identity.logout((String)r.getAttribute("portalToken"));return Map.of("status","LOGGED_OUT");}
    @GetMapping("/me") public Object me(HttpServletRequest r){return identity.me(user(r));}
    @GetMapping("/my-developers") public Object developers(HttpServletRequest r){return owner.developers(user(r));}
    @GetMapping("/catalog/categories") public Object categories(){return market.categories(false);}
    @GetMapping("/catalog/apis") public Object catalog(@RequestParam(defaultValue="")String q,@RequestParam(required=false)Long categoryId,@RequestParam(defaultValue="0")long afterId,@RequestParam(defaultValue="20")int limit){return market.search(q,categoryId,afterId,limit);}
    @GetMapping("/catalog/apis/{id}") public Object detail(@PathVariable long id){return market.detail(id);}
    @GetMapping("/catalog/apis/{id}/offers") public Object offers(@PathVariable long id){return market.offers(id);}
    @GetMapping("/apps") public Object apps(@RequestParam(defaultValue="0")long afterId,@RequestParam(defaultValue="20")int limit,HttpServletRequest r){return apps.apps(user(r),afterId,limit);}
    @PostMapping("/apps") @ResponseStatus(HttpStatus.CREATED) public Object create(@RequestBody Map<String,Object> b,HttpServletRequest r){return apps.create(user(r),b);}
    @GetMapping("/apps/{id}") public Object app(@PathVariable long id,HttpServletRequest r){return owner.app(user(r),id);}
    @GetMapping("/apps/{id}/credentials/status") public Object status(@PathVariable long id,HttpServletRequest r){return apps.credential(user(r),id,"status");}
    @PostMapping("/apps/{id}/credentials/{action}") public Object credential(@PathVariable long id,@PathVariable String action,@RequestBody(required=false)Map<String,Object> b,HttpServletRequest r){if(!Set.of("issue","rotate","revoke").contains(action))throw new ApiFailure(404,"NOT_FOUND","凭证操作不存在");if(b!=null)BusinessInputs.require(b.isEmpty(),"VALIDATION_ERROR","不接受自报身份或Scope字段");return apps.credential(user(r),id,action);}
    @PostMapping("/free-grants") @ResponseStatus(HttpStatus.CREATED) public Object grant(@RequestBody Map<String,Object> b,HttpServletRequest r){return apps.claim(user(r),b);}
    @GetMapping("/subscriptions") public Object subscriptions(@RequestParam long applicationId,@RequestParam(defaultValue="0")long afterId,@RequestParam(defaultValue="20")int limit,HttpServletRequest r){return apps.subscriptions(user(r),applicationId,afterId,limit);}
    @GetMapping("/quota") public Object quota(@RequestParam long applicationId,@RequestParam long apiId,@RequestParam String period,HttpServletRequest r){return apps.quota(user(r),applicationId,apiId,period);}
    @GetMapping("/ledger") public Object ledger(@RequestParam long applicationId,@RequestParam long apiId,@RequestParam String period,@RequestParam(defaultValue="0")long afterId,@RequestParam(defaultValue="20")int limit,HttpServletRequest r){return apps.ledger(user(r),applicationId,apiId,period,afterId,limit);}
    @GetMapping("/usage") public Object usage(@RequestParam long applicationId,@RequestParam long apiId,@RequestParam String period,HttpServletRequest r){return apps.usage(user(r),applicationId,apiId,period);}
}
