package com.flowgate.control.portal;

import com.flowgate.contract.QuotaCharge;
import com.flowgate.control.security.ApiFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import org.springframework.web.bind.annotation.*;

/** 内部密钥继续由既有AdminAuthInterceptor检查；仅可信本机网络。 */
@RestController
public class ApiQuotaController {
    private final ApiQuotaChargeService quota;
    public ApiQuotaController(ApiQuotaChargeService quota){this.quota=quota;}
    @PostMapping("/internal/quota/charge-v12") public Object charge(@RequestBody QuotaCharge charge,HttpServletRequest request)throws java.net.UnknownHostException{if(!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress())throw new ApiFailure(403,"INTERNAL_NETWORK_REQUIRED","仅允许可信本机网络");return quota.charge(charge);}
}
