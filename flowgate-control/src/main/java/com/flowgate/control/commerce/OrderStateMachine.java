package com.flowgate.control.commerce;

import com.flowgate.control.security.ApiFailure;
import java.time.*;

/** 只计算固定UTC月和状态前置条件，时间由数据库调用方提供。 */
public final class OrderStateMachine {
    private OrderStateMachine(){}
    public record Window(Instant start,Instant end,Instant paymentDeadline){}
    public static Window window(String period,String policy,Instant now){
        com.flowgate.control.portal.PortalApplicationService.validPeriod(period);
        var current=YearMonth.from(now.atZone(ZoneOffset.UTC));var wanted=YearMonth.of(Integer.parseInt(period.substring(0,4)),Integer.parseInt(period.substring(4)));
        var allowed="NEXT_MONTH".equals(policy)?current.plusMonths(1):current;
        if(!wanted.equals(allowed))throw new ApiFailure(409,"PERIOD_UNAVAILABLE","请选择SKU允许的UTC月份");
        Instant start=wanted.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),end=wanted.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant cutoff=end.minusSeconds(900);if(!now.isBefore(cutoff))throw new ApiFailure(409,"MONTH_SALE_CLOSED","距月末不足15分钟，请选择下月SKU");
        return new Window(start,end,now.plusSeconds(900).isBefore(end.minusSeconds(300))?now.plusSeconds(900):end.minusSeconds(300));
    }
    public static boolean paid(String state){return java.util.Set.of("PAID_PENDING_FULFILLMENT","FULFILLMENT_RETRY","FULFILLMENT_REVIEW","FULFILLED").contains(state);}
    public static void payable(String state,Instant expires,Instant now){if(!"PENDING_PAYMENT".equals(state)||!now.isBefore(expires))throw new ApiFailure(409,"ORDER_NOT_PAYABLE","订单已关闭、已付款或超过付款截止");}
}
