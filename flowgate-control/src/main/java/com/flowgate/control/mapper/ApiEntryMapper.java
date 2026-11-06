package com.flowgate.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.ApiEntry;
import org.apache.ibatis.annotations.Mapper;

/** fg_api 的最小 MyBatis-Plus 访问接口。 */
@Mapper
public interface ApiEntryMapper extends BaseMapper<ApiEntry> {
}
