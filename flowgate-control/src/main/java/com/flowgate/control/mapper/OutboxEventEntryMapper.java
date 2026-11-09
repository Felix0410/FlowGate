package com.flowgate.control.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;
import com.flowgate.control.entity.OutboxEventEntry;
/** 固定SQL的事务持久化接口，不把数据库暴露给Gateway。 */
@org.apache.ibatis.annotations.Mapper
public interface OutboxEventEntryMapper extends BaseMapper<OutboxEventEntry> {

 @Select("SELECT * FROM fg_outbox_event WHERE event_type='USAGE_GRANTED' AND ((status='NEW' AND next_attempt_at<=UTC_TIMESTAMP(3)) OR (status='LEASED' AND lease_until<UTC_TIMESTAMP(3))) AND attempt_count<8 ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
 List<OutboxEventEntry> claimable(@Param("limit") int limit);
 @Update("UPDATE fg_outbox_event SET status='LEASED',lease_owner=#{owner},lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND),attempt_count=attempt_count+1 WHERE id=#{id}")
 int lease(@Param("id") long id,@Param("owner") String owner);
 @Update("UPDATE fg_outbox_event SET status='FAILED',lease_owner=NULL,lease_until=NULL,last_error_code='LEASE_RETRY_EXHAUSTED' WHERE status='LEASED' AND lease_until<UTC_TIMESTAMP(3) AND attempt_count>=8")
 int expireExhausted();
 @Update("UPDATE fg_outbox_event SET status='SENT',next_attempt_at=UTC_TIMESTAMP(3),lease_owner=NULL,lease_until=NULL,last_error_code=NULL WHERE id=#{id} AND status='LEASED' AND lease_owner=#{owner} AND lease_until>=UTC_TIMESTAMP(3)")
 int sent(@Param("id") long id,@Param("owner") String owner);
 @Update("UPDATE fg_outbox_event SET status=IF(attempt_count>=8,'FAILED','NEW'),next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL #{backoff} SECOND),last_error_code=#{code},lease_owner=NULL,lease_until=NULL WHERE id=#{id} AND status='LEASED' AND lease_owner=#{owner} AND lease_until>=UTC_TIMESTAMP(3)")
 int failed(@Param("id") long id,@Param("owner") String owner,@Param("backoff") int backoff,@Param("code") String code);
}
