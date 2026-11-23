package com.flowgate.control.promotion;

import com.flowgate.contract.SnapshotCodec;
import com.flowgate.control.commerce.CommerceEvent;
import java.time.Instant;
import java.util.UUID;

/** 独立六字段意图事件；Broker内容不作为库存、报价或付款来源。 */
public record PromotionIntentEvent(int eventVersion,String eventId,long intentId,long promotionId,long activityEpoch,String occurredAtUtc){
    public PromotionIntentEvent{if(eventVersion!=1||!UUID.fromString(eventId).toString().equals(eventId)||intentId<1||promotionId<1||activityEpoch<1||!Instant.parse(occurredAtUtc).toString().equals(occurredAtUtc))throw new IllegalArgumentException("INVALID_PROMO_EVENT");}
    public String canonical(){return SnapshotCodec.JSON.writeValueAsString(this);}
    public String fingerprint(){return CommerceEvent.hash(canonical());}
    public static PromotionIntentEvent parse(String payload){try{if(payload==null||payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException();var n=SnapshotCodec.JSON.readTree(payload);if(!n.isObject()||n.size()!=6)throw new IllegalArgumentException();for(String key:new String[]{"eventVersion","intentId","promotionId","activityEpoch"})if(!n.path(key).isIntegralNumber()||!n.path(key).canConvertToLong())throw new IllegalArgumentException();for(String key:new String[]{"eventId","occurredAtUtc"})if(!n.path(key).isString())throw new IllegalArgumentException();return new PromotionIntentEvent(Math.toIntExact(n.path("eventVersion").longValue()),n.path("eventId").stringValue(),n.path("intentId").longValue(),n.path("promotionId").longValue(),n.path("activityEpoch").longValue(),n.path("occurredAtUtc").stringValue());}catch(RuntimeException invalid){throw new IllegalArgumentException("INVALID_PROMO_EVENT");}}
}
