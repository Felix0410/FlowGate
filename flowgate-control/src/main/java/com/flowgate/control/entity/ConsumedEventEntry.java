package com.flowgate.control.entity;
import com.baomidou.mybatisplus.annotation.*;
/** 消费读模型幂等标记；与日汇总同事务提交。 */
@TableName("fg_consumed_event")
public class ConsumedEventEntry {
 @TableId public String eventId;
 public String chargeId;
 public String payloadSha256;
 @TableField(insertStrategy=FieldStrategy.NEVER) public java.time.Instant consumedAt;
}
