package com.flowgate.control.service;
import com.flowgate.control.entity.SubscriptionEntry;
import com.flowgate.control.security.ApiFailure;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** B0同月历史不能更换套餐，月末半开端点及单月范围单独验证。 */
class V11B0Test {
 @Test void expiredNonOverlappingChoiceStillLocksMonthRegardlessOfRowState(){
  for(String state:List.of("ACTIVE","SUSPENDED","EXPIRED")){
   var row=new SubscriptionEntry();row.status=state;row.effectiveAt=Instant.parse("2026-11-01T00:00:00Z");row.expiresAt=Instant.parse("2026-11-10T00:00:00Z");
   assertEquals("SUBSCRIPTION_CONFLICT",assertThrows(ApiFailure.class,()->SubscriptionSchedule.validate(Instant.parse("2026-11-11T00:00:00Z"),Instant.parse("2026-12-01T00:00:00Z"),Instant.parse("2026-11-20T12:00:00Z"),List.of(row))).code);
  }
 }
 @Test void adjacentNextMonthAllowedButMultiMonthRejected(){
  var row=new SubscriptionEntry();row.status="ACTIVE";row.effectiveAt=Instant.parse("2026-11-01T00:00:00Z");row.expiresAt=Instant.parse("2026-12-01T00:00:00Z");
  var now=Instant.parse("2026-11-30T23:59:59.999Z");
  SubscriptionSchedule.validate(row.expiresAt,Instant.parse("2027-01-01T00:00:00Z"),now,List.of(row));
  assertThrows(ApiFailure.class,()->SubscriptionSchedule.validate(row.expiresAt,Instant.parse("2027-02-01T00:00:00Z"),now,List.of()));
 }
}
