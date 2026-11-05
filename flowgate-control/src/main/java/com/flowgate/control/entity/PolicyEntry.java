package com.flowgate.control.entity;

import com.baomidou.mybatisplus.annotation.*;

/** fg_policy 的控制面持久化记录；不作为发布协议直接暴露。 */
@TableName("fg_policy")
public class PolicyEntry {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String code;
    public String type;
    public String scope;
    public String parametersJson;
    public Boolean enabled;
}
