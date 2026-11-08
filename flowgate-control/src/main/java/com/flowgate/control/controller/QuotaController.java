package com.flowgate.control.controller;
import com.flowgate.contract.QuotaCharge;
import com.flowgate.control.service.*;
import com.flowgate.control.security.ApiFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static com.flowgate.control.service.BusinessInputs.*;
/** 内部准入与管理只读查询分开，浏览器不携内部密钥或权威AppId。 */
@RestController
public class QuotaController {
 private final QuotaChargeService quota;private final QuotaActivationService activation;
 public QuotaController(QuotaChargeService quota,QuotaActivationService activation){this.quota=quota;this.activation=activation;}
 @PostMapping("/internal/quota/charge") public Object charge(@RequestBody Map<String,Object> body,HttpServletRequest request)throws java.net.UnknownHostException {
  if(!java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress())throw new ApiFailure(403,"INTERNAL_NETWORK_REQUIRED","配额端点仅允许可信本机网络");
  fields(body,"chargeId","principalId","credentialGeneration","apiId","routeId","appliedVersion","snapshotChecksum","units");
  return quota.charge(new QuotaCharge(text(body.get("chargeId"),"chargeId",36),number(body.get("principalId"),Long.MAX_VALUE,"主体"),number(body.get("credentialGeneration"),Long.MAX_VALUE,"代次"),number(body.get("apiId"),Long.MAX_VALUE,"API"),text(body.get("routeId"),"路由",200),number(body.get("appliedVersion"),Long.MAX_VALUE,"版本"),text(body.get("snapshotChecksum"),"校验和",64),(int)number(body.get("units"),100,"准入单位")));
 }
 @GetMapping("/admin/quota/status") public Object status(){return activation.status();}
 @GetMapping("/admin/quota/balances") public Object balances(@RequestParam long applicationId,@RequestParam String period){return quota.balance(applicationId,period);}
 @GetMapping("/admin/quota/ledger") public Object ledger(@RequestParam long applicationId,@RequestParam String period,@RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="20") int limit){return quota.history(applicationId,period,afterId,limit);}
}
