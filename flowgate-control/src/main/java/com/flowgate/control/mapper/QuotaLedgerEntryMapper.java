package com.flowgate.control.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowgate.control.entity.QuotaLedgerEntry;
/** 固定SQL的事务持久化接口，不把数据库暴露给Gateway。 */
@org.apache.ibatis.annotations.Mapper
public interface QuotaLedgerEntryMapper extends BaseMapper<QuotaLedgerEntry> {

 @org.apache.ibatis.annotations.Select("SELECT * FROM fg_quota_ledger WHERE charge_id=#{charge}")
 QuotaLedgerEntry find(@org.apache.ibatis.annotations.Param("charge") String charge);
 @org.apache.ibatis.annotations.Select("SELECT * FROM fg_quota_ledger WHERE charge_id=#{charge} FOR UPDATE")
 QuotaLedgerEntry current(@org.apache.ibatis.annotations.Param("charge") String charge);
}
