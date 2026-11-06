package com.flowgate.control.entity;

import com.baomidou.mybatisplus.annotation.*;

/** fg_principal 的控制面持久化记录；不作为发布协议直接暴露。 */
@TableName("fg_principal")
public class Principal {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String principalType;
    public String username;
    public String passwordHash;
    public String role;
    public String scopesJson;
    public Boolean enabled;
}
