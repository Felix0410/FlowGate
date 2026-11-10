package com.flowgate.control.portal;

import com.flowgate.contract.*;
import com.flowgate.control.async.UsageGrantedEvent;
import java.time.*;
import java.util.*;

/** 九字段V12专用事件，旧C严格八字段监听无法误消费。 */
public record ApiUsageGrantedEvent(int version,String eventType,String eventId,String chargeId,long applicationId,long apiId,String periodUtc,int units,Instant eventTime){
    public static ApiUsageGrantedEvent parse(String payload){
        Rules.require(payload!=null&&payload.length()<=4096,"INVALID_API_USAGE_EVENT");var n=SnapshotCodec.JSON.readTree(payload);
        Rules.require(n.isObject()&&n.size()==9&&n.has("version")&&n.get("version").isIntegralNumber()&&n.get("version").longValue()==12&&n.has("eventType")&&"USAGE_GRANTED_V12".equals(n.get("eventType").asText()),"INVALID_API_USAGE_EVENT");
        // 1. 复用旧事件严格字段校验原语，但不复用旧Topic或旧表。
        var old=((tools.jackson.databind.node.ObjectNode)n).deepCopy();old.remove("eventType");old.put("version",1);var e=UsageGrantedEvent.parse(SnapshotCodec.JSON.writeValueAsString(old));
        return new ApiUsageGrantedEvent(12,"USAGE_GRANTED_V12",e.eventId(),e.chargeId(),e.applicationId(),e.apiId(),e.periodUtc(),e.units(),e.eventTime());
    }
    public String canonical(){return SnapshotCodec.JSON.writeValueAsString(this);}
    public String fingerprint(){return SnapshotCodec.digest(canonical());}
    public LocalDate day(){return eventTime.atZone(ZoneOffset.UTC).toLocalDate();}
}
