package com.flowgate.control.commerce;

import com.flowgate.control.portal.PortalStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

/** 每轮有界扫描；只关闭仍未付款订单，不接触用户旧服务或旧账本。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="FLOWGATE_V12_COMMERCE_ENABLED",havingValue="true")
public class OrderTimeoutCloser {
    private final PortalStore db;private final OrderService orders;
    public OrderTimeoutCloser(PortalStore db,OrderService orders){this.db=db;this.orders=orders;}
    @Scheduled(fixedDelay=5000) public void close(){try{for(var row:db.rows("SELECT id FROM fg_order WHERE status='PENDING_PAYMENT' AND pay_deadline_at<=UTC_TIMESTAMP(3) ORDER BY pay_deadline_at,id LIMIT 20")){try{orders.timeout(PortalStore.n(row,"id"));}catch(RuntimeException retry){org.slf4j.LoggerFactory.getLogger(getClass()).warn("订单超时关闭暂不可用，保留状态下轮重试");}}}catch(RuntimeException unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("订单超时扫描暂不可用，请检查V12-B隔离迁移");}}
}
