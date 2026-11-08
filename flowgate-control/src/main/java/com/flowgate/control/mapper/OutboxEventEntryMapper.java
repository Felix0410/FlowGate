package com.flowgate.control.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.OutboxEventEntry;
/** 固定SQL的事务持久化接口，不把数据库暴露给Gateway。 */
@org.apache.ibatis.annotations.Mapper
public interface OutboxEventEntryMapper extends BaseMapper<OutboxEventEntry> {
}
