package com.flowgate.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.Principal;
import org.apache.ibatis.annotations.Mapper;

/** fg_principal 的最小 MyBatis-Plus 访问接口。 */
@Mapper
public interface PrincipalMapper extends BaseMapper<Principal> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM fg_principal WHERE id=#{id} FOR SHARE")
    Principal shareById(@org.apache.ibatis.annotations.Param("id") long id);
}
