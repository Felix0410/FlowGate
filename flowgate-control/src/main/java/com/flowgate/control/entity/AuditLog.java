package com.flowgate.control.entity;

import com.baomidou.mybatisplus.annotation.*;

/** fg_audit_log 的控制面持久化记录；不作为发布协议直接暴露。 */
@TableName("fg_audit_log")
public class AuditLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long actorId;
    public String action;
    public String targetType;
    public String targetId;
    public Long releaseVersion;
    public String summary;
    public java.time.LocalDateTime createdAt;
}
