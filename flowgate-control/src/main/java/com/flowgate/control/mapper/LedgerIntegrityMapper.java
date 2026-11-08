package com.flowgate.control.mapper;
import java.util.*;
import java.time.*;
import org.apache.ibatis.annotations.*;
/** 同一只读快照内的有界完整性诊断，不用未知事件日期伪造日报。 */
@Mapper
public interface LedgerIntegrityMapper {
 @SelectProvider(type=Sql.class,method="scan")
 List<Map<String,Object>> scan(@Param("fromDate")LocalDate fromDate,@Param("toDate")LocalDate toDate,@Param("fromTs")Instant fromTs,@Param("toTs")Instant toTs,@Param("app")Long app,@Param("api")Long api,@Param("limit")int limit);
 class Sql {
  private static String charge(String alias){return "JSON_UNQUOTE(JSON_EXTRACT("+alias+".payload_json,'$.chargeId'))";}
  private static String app(String alias){return "CAST(JSON_UNQUOTE(JSON_EXTRACT("+alias+".payload_json,'$.applicationId')) AS UNSIGNED)";}
  private static String api(String alias){return "CAST(JSON_UNQUOTE(JSON_EXTRACT("+alias+".payload_json,'$.apiId')) AS UNSIGNED)";}
  private static String filters(String app,String api){return "<if test='app!=null'> AND "+app+"=#{app}</if><if test='api!=null'> AND "+api+"=#{api}</if>";}
  public static String scan(){
   // 1. 缺事件的Ledger只能以写入时间作候选筛选；其真实日归属仍未知，不计算成任何日的expected。
   String missing="SELECT 'LEDGER_WITHOUT_OUTBOX' code,l.id recordId,l.application_id applicationId,l.api_id apiId,0 relatedCount FROM fg_quota_ledger l WHERE l.created_at>=#{fromTs} AND l.created_at&lt;#{toTs}"+filters("l.application_id","l.api_id")+" AND NOT EXISTS(SELECT 1 FROM fg_outbox_event o WHERE o.event_type='USAGE_GRANTED' AND "+charge("o")+"=l.charge_id) ORDER BY l.id LIMIT #{limit}";
   String window="o.event_type='USAGE_GRANTED' AND ((o.created_at>=#{fromTs} AND o.created_at&lt;#{toTs}) OR LEFT(JSON_UNQUOTE(JSON_EXTRACT(o.payload_json,'$.eventTime')),10) BETWEEN #{fromDate} AND #{toDate} OR EXISTS(SELECT 1 FROM fg_quota_ledger linked WHERE linked.charge_id="+charge("o")+" AND linked.created_at>=#{fromTs} AND linked.created_at&lt;#{toTs}))"+filters(app("o"),api("o"));
   String columns="o.id recordId,"+app("o")+" applicationId,"+api("o")+" apiId,";
   // 2. 反向孤儿与同charge多事件独立报告；只回传安全ID/数量，不回传payload或凭证。
   String orphan="SELECT 'OUTBOX_WITHOUT_LEDGER' code,"+columns+"0 relatedCount FROM fg_outbox_event o WHERE "+window+" AND NOT EXISTS(SELECT 1 FROM fg_quota_ledger l WHERE l.charge_id="+charge("o")+") ORDER BY o.id LIMIT #{limit}";
   String duplicate="SELECT 'DUPLICATE_OUTBOX' code,"+columns+"(SELECT COUNT(*) FROM fg_outbox_event p WHERE p.event_type='USAGE_GRANTED' AND "+charge("p")+"="+charge("o")+") relatedCount FROM fg_outbox_event o WHERE "+window+" AND EXISTS(SELECT 1 FROM fg_outbox_event newer WHERE newer.event_type='USAGE_GRANTED' AND newer.id>o.id AND "+charge("newer")+"="+charge("o")+") AND NOT EXISTS(SELECT 1 FROM fg_outbox_event older WHERE older.event_type='USAGE_GRANTED' AND older.id&lt;o.id AND "+charge("older")+"="+charge("o")+") ORDER BY o.id LIMIT #{limit}";
   String invalidTime="SELECT 'EVENT_TIME_MISSING' code,"+columns+"0 relatedCount FROM fg_outbox_event o WHERE "+window+" AND (JSON_EXTRACT(o.payload_json,'$.eventTime') IS NULL OR JSON_TYPE(JSON_EXTRACT(o.payload_json,'$.eventTime'))!='STRING' OR JSON_UNQUOTE(JSON_EXTRACT(o.payload_json,'$.eventTime')) NOT LIKE '%Z') ORDER BY o.id LIMIT #{limit}";
   // 3. 每类及总返回均有上限，日期/应用/API在SQL内过滤，禁止N+1查询或自动修复权威数据。
   return "<script>SELECT * FROM (("+missing+") UNION ALL ("+orphan+") UNION ALL ("+duplicate+") UNION ALL ("+invalidTime+")) issues ORDER BY code,recordId LIMIT #{limit}</script>";
  }
 }
}
