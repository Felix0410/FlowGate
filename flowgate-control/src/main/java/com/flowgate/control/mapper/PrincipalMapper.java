package com.flowgate.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.Principal;
import org.apache.ibatis.annotations.Mapper;

/** fg_principal 的最小 MyBatis-Plus 访问接口。 */
@Mapper
public interface PrincipalMapper extends BaseMapper<Principal> {
}
