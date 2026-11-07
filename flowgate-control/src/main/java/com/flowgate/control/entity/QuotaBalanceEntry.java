package com.flowgate.control.entity;
import com.baomidou.mybatisplus.annotation.*;
/** B3权威准入记录；不包含Token、请求体或用户Header。 */
@TableName("fg_quota_balance")
public class QuotaBalanceEntry {
 @TableId(type=IdType.AUTO) public Long id;
 public Long applicationId;
 public String periodYyyymm;
 public Long subscriptionId;
 public Long planId;
 public Integer planVersion;
 public Long totalUnits;
 public Long remainingUnits;
 public Long version;
 @TableField(insertStrategy=FieldStrategy.NEVER,updateStrategy=FieldStrategy.NEVER) public java.time.Instant createdAt;
 @TableField(insertStrategy=FieldStrategy.NEVER,updateStrategy=FieldStrategy.NEVER) public java.time.Instant updatedAt;
}
