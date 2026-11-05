package com.flowgate.control.entity;

import com.baomidou.mybatisplus.annotation.*;

/** fg_service 的控制面持久化记录；不作为发布协议直接暴露。 */
@TableName("fg_service")
public class ServiceEntry {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String code;
    public String name;
    public String upstreamUri;
    public Boolean enabled;
}
