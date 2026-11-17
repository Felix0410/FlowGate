# V12-B 普通API订单与模拟结算

本阶段不收取真实资金。模拟付款确认、权益开通、实际Gateway授权是三个不同结果。抢购、优惠券、自动续费、同月升级和追加包尚未提供。

## 普通用户

1. 打开`/portal/`注册登录，在“我的应用”创建自己的应用。
2. 在API市场打开商品，选择正价BASE报价和本人应用。页面展示人民币金额、授权单位和UTC月份；确认后创建待付款订单，自动预占这个应用/API/月的唯一基础权益位置。免费报价仍直接领取，不走付款。
3. 在“我的订单”查看详情，15分钟内点“模拟支付（不扣真钱）”。显示“已模拟付款，等待权益”时请等待或刷新，不能据此判断API已经可以调用。可取消尚未付款订单，超时由后台关闭并释放该订单预占位置。
4. 状态变为“权益已开通”后，到“我的应用与凭证”签发3600秒APP Token，再通过真实Gateway调用API。下月套餐须到下月UTC第一秒才有效，提前开通也不能提前调用。Token只显示一次，不要放进URL或日志。
5. 在“权益与用量”查权威余额/授权流水。日报来自独立Rabbit用量链，可能稍后到达。授权即消耗，下游失败不退单位。
6. 订单详情的“模拟退款”仅对已付款未履约、或完全没授权过调用的权益可用。退款后已有订阅会暂停；已经开通过的月权益位置保持占用，不能当月重复购买。只要有一次授权，返回`USED_ENTITLEMENT_NON_REFUNDABLE`，不会回滚历史Ledger。

相同请求号网络重试返回原订单；不要通过刷新制造新请求来解决未知结果，先到订单列表查。对同一个应用/API/月已有免费权益或待付款单时，会提示基础权益冲突。订购下月请选择显式下月SKU，客户端不能自填月份或价格。

## 运营

Console“客户业务→API市场”可新增正价BASE SKU，价格单位为整数分，范围1..99,999,999分。报价创建后不可修改，改价须创建新SKU。上下架只改变可售状态，不改订单历史快照。ADMIN可写；OPERATOR/VIEWER仅只读。

同页订单运营可查看订单与异常业务事件。`PAID_PENDING_FULFILLMENT`表示收据已落MySQL但权益尚未开通；`FULFILLMENT_REVIEW`表示已无法安全履约（如月份过期或路由失效），应核查原因或模拟退款。`FULFILLED`才表示Grant/订阅/余额事务已经提交。当天完整性为PROVISIONAL，待处理为PENDING，超过首版1000对象有界范围为UNKNOWN，不把它当正式资金结算。

Rabbit暂时故障会保留原Outbox并退避重投。格式/权威冲突进入Poison，未知异常最多12次重试；本地永久坏Outbox标FAILED_PERMANENT，修复需要先核对数据库权威来源。SENT超过300秒、进程启动也超过300秒且仍未消费时，ADMIN可确认弹窗重排原eventId，系统追加审计。不重新模拟付款，不手工改余额。Poison实际队列由Rabbit运维检查，不能清空队列冒充恢复。

## 隔离运行与恢复

默认`FLOWGATE_V12_COMMERCE_ENABLED=false`、`FLOWGATE_V12_COMMERCE_ASYNC_ENABLED=false`。关闭时就绪接口返回DISABLED且不读B表；不为隐藏提示自动迁移用户旧库。只在获授权的新隔离实例安装V1/V11/C/A/B SQL，B增加六张表；`scripts/v12-b-smoke.py`使用随机owner/端口/私有配置，验证后自动停机且保留卷，`--keep-running`仅供同轮浏览器验证。

- A前置：Portal、V12 quota、V12 async真实就绪，完整接流Gateway能力与版本已确认。
- B开启：两个Commerce开关均为true；`/admin/commerce/readiness`还会真实检查六表、业务Rabbit拓扑和活跃Worker。
- `flowgate.commerce.v12`、`flowgate.commerce.v12.orders/retry/poison`与A usage队列隔离。V11-C继续Kafka，不迁移旧消息或账本。
- Broker恢复后保留原卷及固定Rabbit节点主机名，原Outbox和消费去重可恢复；单节点quorum不提供多数派高可用。
- 测试结束只按本轮`.local/v12-b/<run>/cleanup.json`与owner、进程启动身份停止自有实例，不删除卷、不操作用户旧实例或IDE。

V12-B候选完成后仍需源码复审与负责人1280/375、键盘及错误恢复真人验收。自动浏览器与隔离验证不等于正式上线。
