package com.flowgate.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.AppPrincipalEntry;

/** 复用MyBatis-Plus的管理持久化接口，不访问Redis或Gateway。 */
@org.apache.ibatis.annotations.Mapper
public interface AppPrincipalEntryMapper extends BaseMapper<AppPrincipalEntry> {
}
