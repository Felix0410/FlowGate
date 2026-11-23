# V12-M Rabbit用量与消息运维

当前源码和构建仅使用RabbitMQ。旧V11 APP月配额、V12 APP/API月配额、Commerce订单保持三套独立MySQL权威模型；同Broker分别使用`flowgate.usage.legacy`、`flowgate.usage.v12`与`flowgate.commerce.v12`主/Retry/Poison quorum拓扑。原旧eventId、chargeId、严格八字段Version1与UTC日不变，不重新扣额度或重算历史账。

`FLOWGATE_ASYNC_ENABLED`是旧用量统计的默认OFF开关，不是Broker选择开关。Control每轮读取数据库mode/epoch，只有RABBIT领取；缺表/缺行/UNKNOWN失败关闭，FROZEN不启动legacy消费者。运行中的DB短断走持久5秒TTL Retry。未知异常最多12次，永久格式或权威冲突进持久Poison，Poison不自动重注入。

“运行监控→异步事件”展示V11_APP来源、真实主/Retry/Poison消息/消费者数量、采样时间、栅栏mode/epoch和MySQL待消费数。SENT只表示publisher confirm ACK且无mandatory return，不代表日报追平。消费者调用旧聚合事务，真正COMMIT后才ACK；至少一次投递依靠原唯一键幂等，不宣称Broker exactly-once。

“用量与对账→用量统计/对账结果”仍沿原REST和UTC规则；当前日不算封账MATCH，缺口为PENDING或INTEGRITY_GAP。V12_APP_API在市场对账入口，订单Commerce在订单运营入口，不能跨链累计。

FAILED恢复和SENT补投均仅ADMIN、二次确认、原事件校验、CAS与审计/指纹。SENT须事件和节点各满300秒，服务端不允许缩短。已消费、源事件冲突、多Outbox、状态已变均拒绝；不修改Ledger或余额。

旧环境维护见[退役Runbook](v12-m-kafka-retirement-runbook.md)。工具只支持本机自有隔离实例，所有Control/未知IDE进程或租约存在都会阻断。最终版本不提供Kafka回退目标；兼容阶段`f26db25`仅保留在历史，不能用于常驻双投。单节点quorum不等于多节点HA。

本轮只完成隔离候选交付，用户常驻环境尚未迁移；PR保持Draft，待独立源码复审与负责人1280/375/键盘手测。
