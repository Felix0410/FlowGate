package com.flowgate.control.service;

import com.flowgate.control.entity.SubscriptionEntry;
import com.flowgate.control.security.ApiFailure;
import java.time.*;
import java.util.List;
import static com.flowgate.control.service.BusinessInputs.*;

/** 纯UTC期间规则；调用者必须先持有应用行锁，再读取候选区间传入验证。 */
public final class SubscriptionSchedule {
    private SubscriptionSchedule() {}
    public static Instant monthStart(Instant now) { return now.atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant(); }
    public static void validate(Instant start,Instant end,Instant now,List<SubscriptionEntry> existing) {
        // 1. 禁止历史月回填/已到期创建；未来只能从下一UTC自然月的第一瞬间开始。
        require(start.isBefore(end) && end.isAfter(now) && !start.isBefore(monthStart(now)),"INVALID_PERIOD","日期倒置、已过期或历史月回填不允许");
        Instant next=monthStart(now).atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        require(!start.isAfter(now) || start.equals(next),"INVALID_PERIOD","未来订阅仅允许下个UTC月首日00:00生效");
        // 2. 半开区间相接合法，重叠拒绝；当前及排队各至多一条。
        for(var row:existing) if("ACTIVE".equals(row.status)) {
            boolean overlap=start.isBefore(row.expiresAt) && end.isAfter(row.effectiveAt);
            boolean queued=start.isAfter(now) && row.effectiveAt.isAfter(now);
            if(overlap || queued) throw new ApiFailure(409,"SUBSCRIPTION_CONFLICT","订阅期间重叠或已有下月排队订阅");
        }
    }
}
