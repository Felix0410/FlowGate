package com.flowgate.control.controller;

import com.flowgate.contract.Session;
import com.flowgate.control.service.DeveloperApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** V11客户与应用业务接口；写权限由管理拦截器按新业务路径显式限制为ADMIN。 */
@RestController
public class DeveloperApplicationController {
    private final DeveloperApplicationService business;
    public DeveloperApplicationController(DeveloperApplicationService business) {this.business=business;}
    private long actor(HttpServletRequest request){return ((Session)request.getAttribute("session")).principalId();}
    @GetMapping("/admin/developers") public Object developers(@RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="20") int limit){return business.developers(afterId,limit);}
    @GetMapping("/admin/developers/{id}") public Object developer(@PathVariable long id){return business.developer(id);}
    @PostMapping("/admin/developers") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Object createDeveloper(@RequestBody Map<String,Object> input,HttpServletRequest req){return business.saveDeveloper(null,input,actor(req));}
    @PatchMapping("/admin/developers/{id}") public Object updateDeveloper(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest req){return business.saveDeveloper(id,input,actor(req));}
    @GetMapping("/admin/apps") public Object apps(@RequestParam(required=false) Long developerId,@RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="20") int limit){return business.applications(developerId,afterId,limit);}
    @GetMapping("/admin/apps/{id}") public Object app(@PathVariable long id){return business.app(id);}
    @PostMapping("/admin/apps") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Object createApp(@RequestBody Map<String,Object> input,HttpServletRequest req){return business.saveApp(null,input,actor(req));}
    @PatchMapping("/admin/apps/{id}") public Object updateApp(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest req){return business.saveApp(id,input,actor(req));}
}
