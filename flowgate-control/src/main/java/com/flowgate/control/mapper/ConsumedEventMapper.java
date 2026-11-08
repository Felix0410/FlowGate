package com.flowgate.control.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.ConsumedEventEntry;
import org.apache.ibatis.annotations.*;
/** eventId和chargeId双重数据库唯一约束，不以Redis去重。 */
@Mapper
public interface ConsumedEventMapper extends BaseMapper<ConsumedEventEntry> {
 @Select("SELECT * FROM fg_consumed_event WHERE event_id=#{event} OR charge_id=#{charge} LIMIT 1")
 ConsumedEventEntry existing(@Param("event")String event,@Param("charge")String charge);
}
