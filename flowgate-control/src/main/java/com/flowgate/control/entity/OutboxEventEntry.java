package com.flowgate.control.entity;
import com.baomidou.mybatisplus.annotation.*;
/** B3权威准入记录；不包含Token、请求体或用户Header。 */
@TableName("fg_outbox_event")
public class OutboxEventEntry {
 @TableId(type=IdType.AUTO) public Long id;
 public String eventId;
 public String eventType;
 public String payloadJson;
 public String status;
 public Integer attemptCount;
 public String leaseOwner;
 public java.time.Instant leaseUntil;
 public String lastErrorCode;
 @TableField(insertStrategy=FieldStrategy.NEVER,updateStrategy=FieldStrategy.NEVER) public java.time.Instant createdAt;
}
