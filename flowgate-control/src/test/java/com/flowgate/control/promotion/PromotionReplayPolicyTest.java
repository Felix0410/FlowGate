package com.flowgate.control.promotion;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PromotionReplayPolicyTest {
    final Instant due=Instant.parse("2026-11-23T00:00:00Z");
    @Test void exhaustedFailedCanRecoverWithoutChangingDatabaseState(){
        assertEquals("RETRY_EXHAUSTED",PromotionReplayPolicy.display("FAILED",8,false));
        assertTrue(PromotionReplayPolicy.eligible("FAILED",8,false,false,due,due.plusSeconds(300),300));
    }
    @Test void activeLeaseConsumedAndPermanentNeverReplay(){
        assertFalse(PromotionReplayPolicy.eligible("FAILED",8,true,false,due,due.plusSeconds(400),400));
        assertFalse(PromotionReplayPolicy.eligible("FAILED",8,false,true,due,due.plusSeconds(400),400));
        assertFalse(PromotionReplayPolicy.eligible("FAILED_PERMANENT",8,false,false,due,due.plusSeconds(400),400));
        assertFalse(PromotionReplayPolicy.eligible("FAILED",7,false,false,due,due.plusSeconds(400),400));
    }
    @Test void sentRecoveryKeepsBothCooldowns(){
        assertFalse(PromotionReplayPolicy.eligible("SENT",1,false,false,due,due.plusSeconds(299),400));
        assertFalse(PromotionReplayPolicy.eligible("SENT",1,false,false,due,due.plusSeconds(400),299));
        assertTrue(PromotionReplayPolicy.eligible("SENT",1,false,false,due,due.plusSeconds(300),300));
    }
}
