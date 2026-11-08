package com.flowgate.control.entity;
import com.baomidou.mybatisplus.annotation.*;
/** 凭证启停与单调代次，历史Token即使残留Redis也须被DB拒绝。 */
@TableName("fg_app_credential_state")
public class AppCredentialState {
 @TableId(type=IdType.INPUT) public Long principalId;
 public Long generation;public Boolean enabled;
 @TableField(insertStrategy=FieldStrategy.NEVER,updateStrategy=FieldStrategy.NEVER) public java.time.Instant updatedAt;
}
