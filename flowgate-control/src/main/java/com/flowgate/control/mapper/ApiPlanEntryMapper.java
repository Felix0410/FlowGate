package com.flowgate.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.ApiPlanEntry;

/** 复用MyBatis-Plus的管理持久化接口，不访问Redis或Gateway。 */
@org.apache.ibatis.annotations.Mapper
public interface ApiPlanEntryMapper extends BaseMapper<ApiPlanEntry> {
    /** 订阅事务使用当前读行锁，串行同一应用及相关状态变更。 */
    @org.apache.ibatis.annotations.Select("SELECT * FROM fg_api_plan WHERE id=#{id} FOR UPDATE")
    ApiPlanEntry lockById(@org.apache.ibatis.annotations.Param("id") long id);
}
