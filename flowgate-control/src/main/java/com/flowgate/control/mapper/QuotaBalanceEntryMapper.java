package com.flowgate.control.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.QuotaBalanceEntry;
/** 固定SQL的事务持久化接口，不把数据库暴露给Gateway。 */
@org.apache.ibatis.annotations.Mapper
public interface QuotaBalanceEntryMapper extends BaseMapper<QuotaBalanceEntry> {

 @org.apache.ibatis.annotations.Insert("INSERT INTO fg_quota_balance(application_id,period_yyyymm,subscription_id,plan_id,plan_version,total_units,remaining_units,version) VALUES(#{app},#{period},#{sub},#{plan},#{revision},#{units},#{units},0) ON DUPLICATE KEY UPDATE id=id")
 int initialize(@org.apache.ibatis.annotations.Param("app") long app,@org.apache.ibatis.annotations.Param("period") String period,@org.apache.ibatis.annotations.Param("sub") long sub,@org.apache.ibatis.annotations.Param("plan") long plan,@org.apache.ibatis.annotations.Param("revision") int revision,@org.apache.ibatis.annotations.Param("units") long units);
 @org.apache.ibatis.annotations.Select("SELECT * FROM fg_quota_balance WHERE application_id=#{app} AND period_yyyymm=#{period} FOR UPDATE")
 QuotaBalanceEntry locked(@org.apache.ibatis.annotations.Param("app") long app,@org.apache.ibatis.annotations.Param("period") String period);
 @org.apache.ibatis.annotations.Update("UPDATE fg_quota_balance SET remaining_units=remaining_units-#{units},version=version+1 WHERE id=#{id} AND remaining_units>=#{units}")
 int deduct(@org.apache.ibatis.annotations.Param("id") long id,@org.apache.ibatis.annotations.Param("units") int units);
}
