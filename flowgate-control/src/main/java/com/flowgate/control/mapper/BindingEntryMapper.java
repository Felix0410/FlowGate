package com.flowgate.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.BindingEntry;
import org.apache.ibatis.annotations.Mapper;

/** fg_policy_binding 的最小 MyBatis-Plus 访问接口。 */
@Mapper
public interface BindingEntryMapper extends BaseMapper<BindingEntry> {
}
