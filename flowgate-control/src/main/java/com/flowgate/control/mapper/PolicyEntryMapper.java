package com.flowgate.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.PolicyEntry;
import org.apache.ibatis.annotations.Mapper;

/** fg_policy 的最小 MyBatis-Plus 访问接口。 */
@Mapper
public interface PolicyEntryMapper extends BaseMapper<PolicyEntry> {
}
