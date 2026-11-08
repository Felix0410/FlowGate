package com.flowgate.control.async;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** 严格事件协议和UTC日/月边界，未知字段不能静默进入读模型。 */
class V11UsageEventTest {
    String event="{\"version\":1,\"eventId\":\"00000000-0000-0000-0000-000000000001\",\"chargeId\":\"00000000-0000-0000-0000-000000000002\",\"applicationId\":1,\"apiId\":2,\"periodUtc\":\"202611\",\"units\":1,\"eventTime\":\"2026-11-30T23:59:59.999Z\"}";
    @Test void canonicalReplayAndDayAreStable(){var e=UsageGrantedEvent.parse(event);assertEquals("2026-11-30",e.day().toString());assertEquals(e,UsageGrantedEvent.parse(e.canonical()));assertEquals(e.fingerprint(),UsageGrantedEvent.parse(e.canonical()).fingerprint());}
    @Test void rejectsUnknownFieldsWrongMonthAndCoercion(){for(String bad:new String[]{event.replace("202611","202612"),event.replace("\"units\":1","\"units\":1.5"),event.replace("\"version\":1","\"version\":2"),event.replace("}",",\"token\":\"x\"}"),"x".repeat(4097)})assertThrows(RuntimeException.class,()->UsageGrantedEvent.parse(bad));}
}
