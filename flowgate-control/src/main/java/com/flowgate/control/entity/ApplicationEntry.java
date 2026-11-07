package com.flowgate.control.entity;

import com.baomidou.mybatisplus.annotation.*;

/** V11-A管理记录；数据库维护UTC时间，凭证关联表不保存明文或摘要会话。 */
@TableName("fg_application")
public class ApplicationEntry {
    @TableId(type = IdType.AUTO) public Long id;
    public Long developerId;
    public String code;
    public String name;
    public String status;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    public java.time.Instant createdAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    public java.time.Instant updatedAt;
}
