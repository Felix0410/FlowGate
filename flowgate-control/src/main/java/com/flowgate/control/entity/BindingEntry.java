package com.flowgate.control.entity;

import com.baomidou.mybatisplus.annotation.*;

/** fg_policy_binding 的控制面持久化记录；不作为发布协议直接暴露。 */
@TableName("fg_policy_binding")
public class BindingEntry {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long policyId;
    public String targetType;
    public Long targetId;
}
