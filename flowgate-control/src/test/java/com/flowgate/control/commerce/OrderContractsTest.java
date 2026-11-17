package com.flowgate.control.commerce;

import com.flowgate.control.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderContractsTest {
    @Test void utcMonthWindowAndFixedPaymentCutoff(){var now=Instant.parse("2026-11-30T23:44:00Z");var w=OrderStateMachine.window("202611","CURRENT_MONTH",now);assertEquals(Instant.parse("2026-11-30T23:55:00Z"),w.paymentDeadline());assertEquals(Instant.parse("2026-12-01T00:00:00Z"),w.end());assertThrows(ApiFailure.class,()->OrderStateMachine.window("202611","CURRENT_MONTH",Instant.parse("2026-11-30T23:45:00Z")));}
    @Test void nextMonthDoesNotBecomeCurrentAndDeadlineIsFifteenMinutes(){var now=Instant.parse("2026-12-31T23:59:00Z");var w=OrderStateMachine.window("202701","NEXT_MONTH",now);assertEquals(Instant.parse("2027-01-01T00:00:00Z"),w.start());assertEquals(now.plusSeconds(900),w.paymentDeadline());assertThrows(ApiFailure.class,()->OrderStateMachine.window("202612","NEXT_MONTH",now));}
    @Test void closedRefundedExpiredOrdersCannotPay(){for(String state:List.of("CANCELLED","CLOSED_TIMEOUT","REFUNDED","FULFILLED"))assertThrows(ApiFailure.class,()->OrderStateMachine.payable(state,Instant.MAX,Instant.EPOCH));assertThrows(ApiFailure.class,()->OrderStateMachine.payable("PENDING_PAYMENT",Instant.EPOCH,Instant.EPOCH));}
    @Test void strictCommerceCodecRejectsBadJsonVersionTypesAndExtraFields(){var e=new CommerceEvent(1,"11111111-1111-4111-8111-111111111111","PAYMENT_CONFIRMED",7,1,"2026-11-18T00:00:00Z");assertEquals(e,CommerceEvent.parse(e.canonical()));for(String bad:List.of("{broken","null","[]",e.canonical().replace("\"orderId\":7","\"orderId\":7.5"),e.canonical().replace("\"eventVersion\":1","\"eventVersion\":2"),e.canonical().replace("}",",\"price\":1}")))assertThrows(IllegalArgumentException.class,()->CommerceEvent.parse(bad));}
    @Test void commerceWriteDomainsAreAdminOnlyIncludingEncodedPaths(){assertTrue(AdminAuthInterceptor.v11Path("/admin/orders"));assertTrue(AdminAuthInterceptor.v11Path("/admin/commerce/events/id/replay"));var r=new org.springframework.mock.web.MockHttpServletRequest();r.setRequestURI("/admin/commerce;v=1/events/id/replay");assertTrue(AdminAuthInterceptor.v11Path(AdminAuthInterceptor.managementPath(r)));}
}
