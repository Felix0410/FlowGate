package com.flowgate.contract;
import java.util.*;
/** Gateway可信内部准入上下文；不接受调用方自报AppId或月份。 */
public record QuotaCharge(String chargeId,long principalId,long credentialGeneration,long apiId,String routeId,long appliedVersion,String snapshotChecksum,int units) {
 public QuotaCharge {
  Rules.require(chargeId!=null&&chargeId.length()==36&&UUID.fromString(chargeId).toString().equals(chargeId),"chargeId必须为规范UUID");
  Rules.require(principalId>0&&credentialGeneration>0&&apiId>0&&appliedVersion>0&&units>=1&&units<=100,"准入参数范围不合法");
  Rules.require(routeId!=null&&routeId.matches("[A-Za-z0-9_-]{1,200}")&&snapshotChecksum!=null&&snapshotChecksum.matches("[a-f0-9]{64}"),"准入版本或路由不合法");
 }
}
