package com.flowgate.control.controller;
import com.flowgate.contract.Session;
import com.flowgate.control.service.*;
import com.flowgate.control.security.ApiFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
/** 只有完整二进制、默认关闭开关及所有接流节点就绪才准发证；Scope与Token一次性输入输出。 */
@RestController
@RequestMapping("/admin/apps/{id}/credentials")
public class AppCredentialController {
 private final AppCredentialService credentials;private final DeveloperApplicationService apps;private final QuotaActivationService activation;private final ReleaseService releases;
 public AppCredentialController(AppCredentialService credentials,DeveloperApplicationService apps,QuotaActivationService activation,ReleaseService releases){this.credentials=credentials;this.apps=apps;this.activation=activation;this.releases=releases;}
 private long actor(HttpServletRequest request){return ((Session)request.getAttribute("session")).principalId();}
 private void ready(long id){apps.application(id);if(!activation.enabled())throw new ApiFailure(409,"FEATURE_NOT_READY","完整配额链路尚未启用，禁止签发应用凭证");var current=(Map<?,?>)releases.current();long version=((Number)current.get("version")).longValue();activation.requireReady(version,version==0?"":releases.find(version).sha256);}
 private List<String> scopes(Map<String,Object> input){BusinessInputs.fields(input,"scopes");Object value=input.get("scopes");BusinessInputs.require(value instanceof List<?> && ((List<?>)value).stream().allMatch(String.class::isInstance),"VALIDATION_ERROR","Scope列表必须为字符串");return ((List<?>)value).stream().map(String.class::cast).toList();}
 @PostMapping public Object issue(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){ready(id);return credentials.issue(id,scopes(input),false,actor(request));}
 @PostMapping("/rotate") public Object rotate(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){ready(id);return credentials.issue(id,scopes(input),true,actor(request));}
 // 1. 紧急撤销是安全动作，不能被新签发开关或接流节点缺失阻止；DB代次始终是权威。
 @PostMapping("/revoke") public Object revoke(@PathVariable long id,HttpServletRequest request){apps.application(id);credentials.revoke(id,actor(request));return Map.of("status","REVOKED");}
 @GetMapping("/status") public Object status(@PathVariable long id){
  apps.application(id);
  // 2. 计量关闭时也需要查询曾签发凭证的真实状态，才能在产品后台提供紧急撤销入口。
  // 尚无凭证时保留旧FEATURE_NOT_READY空态；仅影响管理查询，不启用新签发。
  Object raw=credentials.summary(id);
  if(!(raw instanceof Map<?,?> summary))throw new ApiFailure(503,"CREDENTIAL_STATUS_UNAVAILABLE","凭证状态未取得");
  Object state=summary.get("status");
  if(!activation.enabled()&&!Set.of("ACTIVE","REVOKED").contains(state))return Map.of("status","FEATURE_NOT_READY");
  var result=new LinkedHashMap<String,Object>();
  summary.forEach((key,value)->{if(key instanceof String k && value!=null)result.put(k,value);});
  result.put("issuanceEnabled",activation.enabled());
  return result;
 }
}
