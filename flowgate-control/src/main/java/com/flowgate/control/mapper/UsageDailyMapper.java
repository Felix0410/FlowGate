package com.flowgate.control.mapper;
import java.time.LocalDate;
import org.apache.ibatis.annotations.*;
/** 一条SQL增量汇总，必须和消费标记处于同一MySQL事务。 */
@Mapper
public interface UsageDailyMapper {
 @Insert("INSERT INTO fg_usage_daily(day_utc,application_id,api_id,authorized_units,grant_count) VALUES(#{day},#{app},#{api},#{units},1) ON DUPLICATE KEY UPDATE authorized_units=authorized_units+VALUES(authorized_units),grant_count=grant_count+1")
 int add(@Param("day")LocalDate day,@Param("app")long app,@Param("api")long api,@Param("units")int units);
}
