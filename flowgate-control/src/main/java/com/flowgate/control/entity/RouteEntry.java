package com.flowgate.control.entity;

import com.baomidou.mybatisplus.annotation.*;

/** fg_route 的控制面持久化记录；不作为发布协议直接暴露。 */
@TableName("fg_route")
public class RouteEntry {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long apiId;
    public String routeId;
    public Integer priority;
    public Boolean enabled;
}
