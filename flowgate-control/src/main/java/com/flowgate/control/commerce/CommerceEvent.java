package com.flowgate.control.commerce;

import com.flowgate.contract.SnapshotCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 固定版本事件，不携带客户端金额或凭证。消息必须与本地Outbox再次比对。 */
public record CommerceEvent(int eventVersion,String eventId,String type,long orderId,long orderVersion,String occurredAtUtc) {
    public CommerceEvent {
        if(eventVersion!=1||!UUID.fromString(eventId).toString().equals(eventId)||!"PAYMENT_CONFIRMED".equals(type)||orderId<=0||orderVersion<=0||!java.time.Instant.parse(occurredAtUtc).toString().equals(occurredAtUtc))throw new IllegalArgumentException("INVALID_COMMERCE_EVENT");
    }
    public String canonical(){return SnapshotCodec.JSON.writeValueAsString(this);}
    public String fingerprint(){return hash(canonical());}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    public static CommerceEvent parse(String payload){try{
        if(payload==null||payload.getBytes(StandardCharsets.UTF_8).length>4096)throw new IllegalArgumentException();
        var tree=SnapshotCodec.JSON.readTree(payload);
        if(!tree.isObject()||tree.size()!=6||!tree.path("eventVersion").isIntegralNumber()||!tree.path("orderId").isIntegralNumber()||!tree.path("orderId").canConvertToLong()||!tree.path("orderVersion").isIntegralNumber()||!tree.path("orderVersion").canConvertToLong()||!tree.path("eventId").isTextual()||!tree.path("type").isTextual()||!tree.path("occurredAtUtc").isTextual())throw new IllegalArgumentException();
        return new CommerceEvent(Math.toIntExact(tree.path("eventVersion").longValue()),tree.path("eventId").textValue(),tree.path("type").textValue(),tree.path("orderId").longValue(),tree.path("orderVersion").longValue(),tree.path("occurredAtUtc").textValue());
    }catch(RuntimeException invalid){throw new IllegalArgumentException("INVALID_COMMERCE_EVENT");}}
}
