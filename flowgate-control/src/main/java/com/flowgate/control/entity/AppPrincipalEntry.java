package com.flowgate.control.entity;

import com.baomidou.mybatisplus.annotation.*;

/** V11-A管理记录；数据库维护UTC时间，凭证关联表不保存明文或摘要会话。 */
@TableName("fg_app_principal")
public class AppPrincipalEntry {
    @TableId(type = IdType.INPUT) public Long principalId;
    public Long applicationId;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    public java.time.Instant createdAt;
}
