package com.flowgate.control.async;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** 日期不足以分页：同一天仍保留应用与API的稳定位置。 */
class V11UsageCursorTest {
 @Test void stableCompositePositionRoundTrips(){var position=new UsageCursor("2026-11-01",22,33);assertEquals(position,UsageCursor.parse(position.encode()));assertNull(UsageCursor.parse(null));}
 @Test void rejectsArbitrarySqlAndNoncanonicalCursor(){for(String value:new String[]{"x","!","a".repeat(129),java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("2026-11-01|1 OR 1=1|2".getBytes())})assertThrows(IllegalArgumentException.class,()->UsageCursor.parse(value));}
}
