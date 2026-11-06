package com.flowgate.control.entity;

import com.baomidou.mybatisplus.annotation.*;

/** fg_config_state 的控制面持久化记录；不作为发布协议直接暴露。 */
@TableName("fg_config_state")
public class ConfigState {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long currentVersion;
    public Long optimisticVersion;
}
