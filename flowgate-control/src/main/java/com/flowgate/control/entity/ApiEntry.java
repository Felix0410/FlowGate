package com.flowgate.control.entity;

import com.baomidou.mybatisplus.annotation.*;

/** fg_api 的控制面持久化记录；不作为发布协议直接暴露。 */
@TableName("fg_api")
public class ApiEntry {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long serviceId;
    public String method;
    public String publicPath;
    public String upstreamPath;
    public Boolean isPublic;
    public String requiredScope;
    public Boolean enabled;
}
