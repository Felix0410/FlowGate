package com.flowgate.control.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.ConfigState;
import org.apache.ibatis.annotations.Mapper;

/** fg_config_state 的最小 MyBatis-Plus 访问接口。 */
@Mapper
public interface ConfigStateMapper extends BaseMapper<ConfigState> {

    /** 同时比较业务版本和乐观锁；只有一个发布事务能前进。 */
    @org.apache.ibatis.annotations.Update("UPDATE fg_config_state SET current_version=#{next}, optimistic_version=optimistic_version+1 WHERE id=1 AND current_version=#{expected} AND optimistic_version=#{lock}")
    int advance(@org.apache.ibatis.annotations.Param("expected") long expected,
                @org.apache.ibatis.annotations.Param("lock") long lock,
                @org.apache.ibatis.annotations.Param("next") long next);
}
