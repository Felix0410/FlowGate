package com.flowgate.control.async;

import com.flowgate.contract.SnapshotCodec;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** B不可变Outbox的严格八字段事件；规范化指纹消除JSON字段顺序差异。 */
public record UsageGrantedEvent(int version,String eventId,String chargeId,long applicationId,long apiId,String periodUtc,int units,Instant eventTime) {
    public static UsageGrantedEvent parse(String json) {
        // 1. 长度、字段集合和数字类型先校验，禁止宽松反序列化吞掉额外字段。
        require(json!=null&&json.getBytes(StandardCharsets.UTF_8).length<=4096);
        JsonNode n=SnapshotCodec.JSON.readTree(json);
        require(n.isObject()&&n.size()==8);
        for(String name:List.of("eventId","chargeId","periodUtc","eventTime"))require(n.has(name)&&n.get(name).isString());
        for(String name:List.of("version","applicationId","apiId","units"))require(n.has(name)&&n.get(name).isIntegralNumber()&&n.get(name).canConvertToLong());
        require(n.get("version").longValue()==1&&n.get("applicationId").longValue()>0&&n.get("apiId").longValue()>0&&n.get("units").longValue()>=1&&n.get("units").longValue()<=100);
        // 2. 规范UUID、UTC瞬间和归属月份必须一致，不使用Ledger插入日期替代事件日期。
        String event=n.get("eventId").stringValue(),charge=n.get("chargeId").stringValue(),period=n.get("periodUtc").stringValue(),time=n.get("eventTime").stringValue();
        require(UUID.fromString(event).toString().equals(event)&&UUID.fromString(charge).toString().equals(charge)&&time.endsWith("Z"));
        Instant instant=Instant.parse(time);String expected=java.time.format.DateTimeFormatter.ofPattern("yyyyMM").withZone(ZoneOffset.UTC).format(instant);
        require(period.matches("[0-9]{4}(0[1-9]|1[0-2])")&&expected.equals(period));
        // 3. 只构造显式字段，不保留原始请求、Header或未知元信息。
        return new UsageGrantedEvent(1,event,charge,n.get("applicationId").longValue(),n.get("apiId").longValue(),period,n.get("units").intValue(),instant);
    }
    private static void require(boolean ok){if(!ok)throw new IllegalArgumentException("INVALID_USAGE_EVENT");}
    public String canonical(){return SnapshotCodec.JSON.writeValueAsString(this);}
    public String fingerprint(){return SnapshotCodec.digest(canonical());}
    public LocalDate day(){return eventTime.atZone(ZoneOffset.UTC).toLocalDate();}
}
