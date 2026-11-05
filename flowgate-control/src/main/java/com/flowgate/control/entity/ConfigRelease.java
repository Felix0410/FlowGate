package com.flowgate.control.entity;

import com.baomidou.mybatisplus.annotation.*;

/** fg_config_release 的控制面持久化记录；不作为发布协议直接暴露。 */
@TableName("fg_config_release")
public class ConfigRelease {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long version;
    public String snapshotJson;
    public String sha256;
    public Long actorId;
    public Long rollbackFrom;
    public java.time.LocalDateTime createdAt;
}
