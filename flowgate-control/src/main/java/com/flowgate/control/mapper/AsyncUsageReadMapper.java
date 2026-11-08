package com.flowgate.control.mapper;
import java.util.*;
import java.time.LocalDate;
import org.apache.ibatis.annotations.*;
/** 有界运营查询；复合游标只绑定参数，SQL标识符不可来自用户。 */
@Mapper
public interface AsyncUsageReadMapper {
 @SelectProvider(type=Sql.class,method="usage") List<Map<String,Object>> usage(@Param("from")LocalDate from,@Param("to")LocalDate to,@Param("app")Long app,@Param("api")Long api,@Param("day")String day,@Param("cursorApp")Long cursorApp,@Param("cursorApi")Long cursorApi,@Param("limit")int limit);
 @SelectProvider(type=Sql.class,method="reconciliation") List<Map<String,Object>> reconciliation(@Param("from")LocalDate from,@Param("to")LocalDate to,@Param("app")Long app,@Param("api")Long api,@Param("day")String day,@Param("cursorApp")Long cursorApp,@Param("cursorApi")Long cursorApi,@Param("limit")int limit);
 @Select("<script>SELECT id,LEFT(event_id,8) eventLabel,event_type eventType,status,attempt_count attemptCount,next_attempt_at nextAttemptAt,lease_until leaseUntil,last_error_code lastErrorCode,created_at createdAt,EXISTS(SELECT 1 FROM fg_consumed_event c WHERE c.event_id=o.event_id) consumed FROM fg_outbox_event o WHERE id&gt;#{after}<if test='status!=null'> AND status=#{status}</if> ORDER BY id LIMIT #{limit}</script>")
 List<Map<String,Object>> events(@Param("status")String status,@Param("after")long after,@Param("limit")int limit);
 @Select("SELECT COUNT(*) totalCount,COALESCE(SUM(status='NEW'),0) newCount,COALESCE(SUM(status='LEASED'),0) leasedCount,COALESCE(SUM(status='SENT'),0) sentCount,COALESCE(SUM(status='FAILED'),0) failedCount,COALESCE(SUM(NOT EXISTS(SELECT 1 FROM fg_consumed_event c WHERE c.event_id=o.event_id)),0) pendingConsumption,COALESCE(TIMESTAMPDIFF(SECOND,MIN(IF(status<>'SENT',created_at,NULL)),UTC_TIMESTAMP(3)),0) oldestAgeSeconds FROM fg_outbox_event o")
 Map<String,Object> summary();
 @Update("UPDATE fg_outbox_event SET status='NEW',attempt_count=0,next_attempt_at=UTC_TIMESTAMP(3),lease_owner=NULL,lease_until=NULL,last_error_code=NULL WHERE id=#{id} AND status='FAILED'") int retry(@Param("id")long id);
 /** 两张表统一转成相同有序三元组，防止同日多应用在翻页时遗漏。 */
 class Sql {
  private static final String FILTER=" WHERE x.day_utc BETWEEN #{from} AND #{to}<if test='app!=null'> AND x.application_id=#{app}</if><if test='api!=null'> AND x.api_id=#{api}</if><if test='day!=null'> AND (x.day_utc,x.application_id,x.api_id)&lt;(#{day},#{cursorApp},#{cursorApi})</if>";
  private static final String NAMES=" LEFT JOIN fg_application a ON a.id=x.application_id LEFT JOIN fg_api api ON api.id=x.api_id ";
  public static String usage(){return "<script>SELECT DATE_FORMAT(x.day_utc,'%Y-%m-%d') dayUtc,x.application_id applicationId,x.api_id apiId,a.name applicationName,CONCAT(api.method,' ',api.public_path) apiName,x.authorized_units authorizedUnits,x.grant_count grantCount,x.updated_at lastAggregatedAt FROM fg_usage_daily x"+NAMES+FILTER+" ORDER BY x.day_utc DESC,x.application_id DESC,x.api_id DESC LIMIT #{limit}</script>";}
  public static String reconciliation(){
   // 1. 权威日取Outbox事件时间，不能从Ledger.created_at偷换日边界。
   String expected="SELECT CAST(LEFT(JSON_UNQUOTE(JSON_EXTRACT(o.payload_json,'$.eventTime')),10) AS DATE) day_utc,l.application_id,l.api_id,l.units expectedUnits,1 expectedCount,IF(c.event_id IS NULL,0,l.units) consumedUnits,IF(c.event_id IS NULL,0,1) consumedCount,0 aggregatedUnits,0 aggregatedCount FROM fg_quota_ledger l JOIN fg_outbox_event o ON JSON_UNQUOTE(JSON_EXTRACT(o.payload_json,'$.chargeId'))=l.charge_id AND o.event_type='USAGE_GRANTED' LEFT JOIN fg_consumed_event c ON c.event_id=o.event_id AND c.charge_id=l.charge_id";
   // 2. UNION读模型可发现多余聚合行；所有读取由Service的只读RR快照包裹。
   String actual="SELECT day_utc,application_id,api_id,0,0,0,0,authorized_units,grant_count FROM fg_usage_daily";
   return "<script>SELECT DATE_FORMAT(x.day_utc,'%Y-%m-%d') dayUtc,x.application_id applicationId,x.api_id apiId,MAX(a.name) applicationName,MAX(CONCAT(api.method,' ',api.public_path)) apiName,SUM(expectedUnits) expectedUnits,SUM(expectedCount) expectedCount,SUM(consumedUnits) consumedUnits,SUM(consumedCount) consumedCount,SUM(aggregatedUnits) aggregatedUnits,SUM(aggregatedCount) aggregatedCount FROM ("+expected+" UNION ALL "+actual+") x"+NAMES+FILTER+" GROUP BY x.day_utc,x.application_id,x.api_id ORDER BY x.day_utc DESC,x.application_id DESC,x.api_id DESC LIMIT #{limit}</script>";
  }
 }
}
