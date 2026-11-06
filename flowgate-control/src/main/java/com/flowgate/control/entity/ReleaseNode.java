package com.flowgate.control.entity;

import com.baomidou.mybatisplus.annotation.*;

/** fg_release_node 的控制面持久化记录；不作为发布协议直接暴露。 */
@TableName("fg_release_node")
public class ReleaseNode {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String nodeId;
    public Long appliedVersion;
    public String checksum;
    public java.time.LocalDateTime lastSeenAt;
}
