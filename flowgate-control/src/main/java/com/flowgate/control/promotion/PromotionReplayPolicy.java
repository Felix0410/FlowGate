package com.flowgate.control.promotion;

import java.time.Instant;

/** 同一原事件的下一批有界投递：不自动解封永久错误，不跳过事件与节点冷却。 */
public final class PromotionReplayPolicy {
    private PromotionReplayPolicy() {}
    public static String display(String state,long attempts,boolean consumed) {
        return !consumed && "FAILED".equals(state) && attempts >= 8 ? "RETRY_EXHAUSTED" : state;
    }
    public static boolean eligible(String state,long attempts,boolean leased,boolean consumed,
                                   Instant nextAttempt,Instant now,long nodeUptimeSeconds) {
        return nodeUptimeSeconds >= 300 && !leased && !consumed
            && ("SENT".equals(state) || "FAILED".equals(state) && attempts >= 8)
            && !now.isBefore(nextAttempt.plusSeconds(300));
    }
}
