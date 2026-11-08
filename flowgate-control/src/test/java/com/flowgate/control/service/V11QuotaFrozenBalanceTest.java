package com.flowgate.control.service;

import com.flowgate.control.entity.ApiPlanEntry;
import com.flowgate.control.entity.QuotaBalanceEntry;
import com.flowgate.control.entity.SubscriptionEntry;
import com.flowgate.control.security.ApiFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** B阶段旧订阅兼容：余额冻结事实不可被后来的另一段套餐覆盖。 */
class V11QuotaFrozenBalanceTest {
    private QuotaBalanceEntry balance(){
        var b=new QuotaBalanceEntry();
        b.subscriptionId=11L;b.planId=21L;b.planVersion=1;b.totalUnits=5L;
        b.remainingUnits=3L;
        return b;
    }
    private SubscriptionEntry subscription(){
        var s=new SubscriptionEntry();s.id=11L;s.planId=21L;s.planVersionSnapshot=1;return s;
    }
    private ApiPlanEntry plan(){
        var p=new ApiPlanEntry();p.id=21L;p.planVersion=1;p.monthlyUnits=5L;return p;
    }
    @Test void existingFrozenMonthAcceptsOnlyTheSameImmutableSubscriptionAndPlan(){
        var b=balance();var s=subscription();var p=plan();
        assertDoesNotThrow(()->QuotaChargeService.requireFrozenBalance(b,s,p));
        assertEquals(3L,b.remainingUnits);
        s.id=12L;assertMismatch(b,s,p);s.id=11L;
        p.id=22L;assertMismatch(b,s,p);p.id=21L;
        s.planVersionSnapshot=2;assertMismatch(b,s,p);s.planVersionSnapshot=1;
        p.monthlyUnits=20L;assertMismatch(b,s,p);
        // 1. 拒绝后不自动重置旧余额。真正的数据库写入在事务方法中被错误中止并回滚。
        assertEquals(3L,b.remainingUnits);
    }
    private void assertMismatch(QuotaBalanceEntry b,SubscriptionEntry s,ApiPlanEntry p){
        ApiFailure error=assertThrows(ApiFailure.class,()->QuotaChargeService.requireFrozenBalance(b,s,p));
        assertEquals(409,error.status);
        assertEquals("QUOTA_SUBSCRIPTION_MISMATCH",error.code);
    }
}
