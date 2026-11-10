package com.flowgate.control.portal;

import com.flowgate.control.security.ApiFailure;
import com.flowgate.control.service.AuditService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import static com.flowgate.control.portal.PortalStore.*;

/** V12读模型专线：原事件核验、同事务去重聚合、只读完整性对账和受控补投。 */
@Service
public class ApiUsageService {
    private final PortalStore db;private final AuditService audit;private final TransactionTemplate tx;private final TransactionTemplate read;
    public ApiUsageService(PortalStore db,AuditService audit,PlatformTransactionManager manager){this.db=db;this.audit=audit;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(4);read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);read.setTimeout(4);}
    private ApiFailure conflict(){return new ApiFailure(409,"V12_EVENT_CONFLICT","事件与V12本地权威流水不一致");}
    private void validate(ApiUsageGrantedEvent e){
        var source=db.one("SELECT payload_json,event_type FROM fg_api_usage_outbox WHERE event_id=? AND charge_id=?",e.eventId(),e.chargeId());
        if(source==null||!"USAGE_GRANTED_V12".equals(source.get("event_type"))||!e.equals(ApiUsageGrantedEvent.parse(s(source,"payload_json"))))throw conflict();
        var row=db.one("SELECT application_id,api_id,units,period_yyyymm FROM fg_api_quota_ledger WHERE charge_id=?",e.chargeId());if(row==null||n(row,"application_id")!=e.applicationId()||n(row,"api_id")!=e.apiId()||n(row,"units")!=e.units()||!e.periodUtc().equals(row.get("period_yyyymm")))throw conflict();
    }
    private String duplicate(ApiUsageGrantedEvent e){var old=db.one("SELECT * FROM fg_api_consumed_event WHERE event_id=? OR charge_id=?",e.eventId(),e.chargeId());if(old==null)return null;if(!e.eventId().equals(old.get("event_id"))||!e.chargeId().equals(old.get("charge_id"))||!e.fingerprint().equals(old.get("payload_sha256")))throw conflict();return "DUPLICATE";}
    public String process(String payload){var e=ApiUsageGrantedEvent.parse(payload);try{return tx.execute(status->{
        // 1. 消息不是权威，验证Outbox及Ledger之后才能接受聚合数字。
        validate(e);String old=duplicate(e);if(old!=null)return old;
        db.update("INSERT INTO fg_api_consumed_event(event_id,charge_id,payload_sha256) VALUES(?,?,?)",e.eventId(),e.chargeId(),e.fingerprint());
        db.update("INSERT INTO fg_api_usage_daily(day_utc,application_id,api_id,authorized_units,grant_count) VALUES(?,?,?,?,1) ON DUPLICATE KEY UPDATE authorized_units=authorized_units+?,grant_count=grant_count+1",java.sql.Date.valueOf(e.day()),e.applicationId(),e.apiId(),e.units(),e.units());return "CONSUMED";
    });}catch(DuplicateKeyException collision){return tx.execute(status->{validate(e);String result=duplicate(e);if(result==null)throw conflict();return result;});}}
    public List<Map<String,Object>> claim(int limit){if(limit<1)return List.of();return tx.execute(status->{
        var rows=db.rows("SELECT * FROM fg_api_usage_outbox WHERE (status IN ('NEW','FAILED') AND next_attempt_at<=UTC_TIMESTAMP(3)) OR (status='IN_FLIGHT' AND lease_until<UTC_TIMESTAMP(3)) ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",Math.min(limit,20));
        for(var row:rows){String owner=UUID.randomUUID().toString();db.update("UPDATE fg_api_usage_outbox SET status='IN_FLIGHT',lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND),attempt_count=attempt_count+1 WHERE id=?",owner,row.get("id"));row.put("lease_owner",owner);}return rows;
    });}
    public void sent(Map<String,Object> row){db.update("UPDATE fg_api_usage_outbox SET status='SENT',lease_owner=NULL,lease_until=NULL,last_error_code=NULL,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 300 SECOND) WHERE id=? AND status='IN_FLIGHT' AND lease_owner=?",row.get("id"),row.get("lease_owner"));}
    public void failed(Map<String,Object> row,String code){db.update("UPDATE fg_api_usage_outbox SET status='FAILED',lease_owner=NULL,lease_until=NULL,last_error_code=?,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL LEAST(30,POW(2,LEAST(attempt_count,5))) SECOND) WHERE id=? AND status='IN_FLIGHT' AND lease_owner=?",code,row.get("id"),row.get("lease_owner"));}
    public Object replay(String id,long actor){if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();return tx.execute(status->{
        // 2. 只允许SENT且过300秒仍未消费者，以状态CAS补投原事件，永不再扣量。
        var row=db.need("SELECT o.id,o.status,(o.next_attempt_at<=UTC_TIMESTAMP(3)) AS waited,c.event_id AS consumed FROM fg_api_usage_outbox o LEFT JOIN fg_api_consumed_event c ON c.event_id=o.event_id WHERE o.event_id=? FOR UPDATE",id);
        if(!"SENT".equals(row.get("status"))||n(row,"waited")!=1||row.get("consumed")!=null)throw new ApiFailure(409,"REPLAY_NOT_ELIGIBLE","仅已发送超过300秒且未消费的事件可补投");
        if(db.update("UPDATE fg_api_usage_outbox SET status='NEW',next_attempt_at=UTC_TIMESTAMP(3) WHERE id=? AND status='SENT'",row.get("id"))!=1)throw conflict();audit.record(actor,"REPLAY_V12_USAGE","market",id,null,"补投原V12事件；不修改Ledger和余额");return Map.of("status","QUEUED","eventId",id);
    });}
    public Object reconcile(LocalDate day){return read.execute(status->{
        // 3. 全局孤儿检查与指定日聚合分开，不能把无来源记录猜归某日或放大expected。
        long gaps=n(db.need("SELECT (SELECT COUNT(*) FROM fg_api_quota_ledger l LEFT JOIN fg_api_usage_outbox o ON o.charge_id=l.charge_id WHERE o.id IS NULL)+(SELECT COUNT(*) FROM fg_api_usage_outbox o LEFT JOIN fg_api_quota_ledger l ON l.charge_id=o.charge_id WHERE l.id IS NULL)+(SELECT COUNT(*) FROM fg_api_consumed_event c LEFT JOIN fg_api_usage_outbox o ON o.event_id=c.event_id AND o.charge_id=c.charge_id WHERE o.id IS NULL) AS gaps"),"gaps");
        var sources=db.rows("SELECT o.payload_json,c.event_id AS consumed FROM fg_api_usage_outbox o LEFT JOIN fg_api_consumed_event c ON c.event_id=o.event_id ORDER BY o.id LIMIT 10001");
        if(sources.size()>10000)return Map.of("status","UNKNOWN","reason","超过首版有界10000事件检查范围");long grants=0,units=0,consumed=0;var expected=new TreeMap<String,List<Long>>();
        for(var source:sources){try{var event=ApiUsageGrantedEvent.parse(s(source,"payload_json"));validate(event);if(event.day().equals(day)){grants++;units+=event.units();if(source.get("consumed")!=null){consumed++;var numbers=expected.computeIfAbsent(event.applicationId()+":"+event.apiId(),k->new ArrayList<>(List.of(0L,0L)));numbers.set(0,numbers.get(0)+event.units());numbers.set(1,numbers.get(1)+1);}}}catch(IllegalArgumentException|ApiFailure invalid){gaps++;}}
        var daily=db.rows("SELECT application_id,api_id,authorized_units,grant_count FROM fg_api_usage_daily WHERE day_utc=?",java.sql.Date.valueOf(day));var actual=new TreeMap<String,List<Long>>();for(var row:daily)actual.put(n(row,"application_id")+":"+n(row,"api_id"),List.of(n(row,"authorized_units"),n(row,"grant_count")));
        boolean same=expected.equals(actual);String result=gaps>0?"INTEGRITY_GAP":!same?"MISMATCH":consumed<grants?"PENDING":!day.isBefore(LocalDate.now(ZoneOffset.UTC))?"PROVISIONAL":"MATCH";
        return Map.of("status",result,"dayUtc",day.toString(),"ledgerUnits",units,"ledgerGrants",grants,"consumedGrants",consumed,"integrityGaps",gaps,"source","V12_APP_API");
    });}
}
