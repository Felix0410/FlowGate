package com.flowgate.control.controller;
import com.flowgate.control.service.AsyncOperationsService;
import com.flowgate.contract.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
/** C安全运营API，写权限由统一V11管理拦截器限制为ADMIN。 */
@RestController
public class UsageController {
 private final AsyncOperationsService operations;
 public UsageController(AsyncOperationsService operations){this.operations=operations;}
 @GetMapping("/admin/usage") public Object usage(@RequestParam(required=false)String from,@RequestParam(required=false)String to,@RequestParam(required=false)Long applicationId,@RequestParam(required=false)Long apiId,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return operations.query(false,from,to,applicationId,apiId,cursor,limit);}
 @GetMapping("/admin/reconciliation") public Object reconcile(@RequestParam(required=false)String from,@RequestParam(required=false)String to,@RequestParam(required=false)Long applicationId,@RequestParam(required=false)Long apiId,@RequestParam(required=false)String cursor,@RequestParam(defaultValue="20")int limit){return operations.query(true,from,to,applicationId,apiId,cursor,limit);}
 @GetMapping("/admin/async-events") public Object events(@RequestParam(required=false)String status,@RequestParam(defaultValue="0")long afterId,@RequestParam(defaultValue="20")int limit){return operations.events(status,afterId,limit);}
 @GetMapping("/admin/async-events/summary") public Object summary(){return operations.summary();}
 @PostMapping("/admin/async-events/{id}/replay") public Object replay(@PathVariable long id,HttpServletRequest request){return operations.replay(id,((Session)request.getAttribute("session")).principalId());}
 @PostMapping("/admin/async-events/{id}/retry") public Object retry(@PathVariable long id,HttpServletRequest request){return operations.retry(id,((Session)request.getAttribute("session")).principalId());}
}
