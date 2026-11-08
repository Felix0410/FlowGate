package com.flowgate.control.entity;
import com.baomidou.mybatisplus.annotation.*;
/** B3权威准入记录；不包含Token、请求体或用户Header。 */
@TableName("fg_quota_ledger")
public class QuotaLedgerEntry {
 @TableId(type=IdType.AUTO) public Long id;
 public String chargeId;
 public String requestFingerprint;
 public Long applicationId;
 public Long principalId;
 public Long credentialGeneration;
 public Long apiId;
 public String routeId;
 public Long appliedVersion;
 public Long policyId;
 public String periodYyyymm;
 public Integer units;
 public Long remainingUnitsAfter;
 @TableField(insertStrategy=FieldStrategy.NEVER,updateStrategy=FieldStrategy.NEVER) public java.time.Instant createdAt;
}
