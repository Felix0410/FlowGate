package com.flowgate.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.ServiceEntry;
import org.apache.ibatis.annotations.Mapper;

/** fg_service 的最小 MyBatis-Plus 访问接口。 */
@Mapper
public interface ServiceEntryMapper extends BaseMapper<ServiceEntry> {
}
