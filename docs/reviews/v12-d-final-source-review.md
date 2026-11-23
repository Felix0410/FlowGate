# V12-D Final 源码与证据自审（2026-11-24）

这是同一实现者的源码自审，不是独立复审。外部审查PENDING_REVIEW、负责人PENDING_OWNER。范围为D相对5541eb3的源码、运行脚本、真实证据与现有C/B核心事务；未动main、旧PR或日常库。

| 审查项 | 源码结论与证据 |
|---|---|
| 耗尽自动投递 | claimOutbox仍attempt_count<8，不改变永久错误分类；FAILED>=8仅派生RETRY_EXHAUSTED，无新表或自动无限重试 |
| 受控补投 | PromotionReplayPolicy复用SENT/耗尽FAILED、双300秒、无租约/未Consumed；replay在原Outbox行锁后核验原payload与Intent，CAS重排及Audit同事务；不修改Stock/Slot/Order。D1真8失败、自然等待、双ADMIN、迟到终态、100重复通过 |
| 权限 | 原Promotion写接口ADMIN约束保持；新/admin/messaging加入AdminAuthInterceptor路径集合，OPERATOR/VIEWER只读。D1/D2真HTTP写403及认证域隔离 |
| 四链诊断 | 表/队列名是源码白名单，缺表NOT_READY、依赖故障UNKNOWN；被动声明不建拓扑；不同时间的统计不伪装RR对账，Broker Ready不含unacked。不能用总数相等代替权威关联对账 |
| 账本、库存及事务 | 既有APP→Slot→Order锁顺序及唯一Grant/Intent约束未重构；新replay不拿库存锁。D0/D7实测取消/付款竞态、已用退款409、晚到终态、Redis丢失重建与原Ledger不变。UTC与daily故障在新schema真事务完成 |
| AMQP可靠性 | 既有confirm+mandatory/回调错误分类/COMMIT后ACK保持；D2真NACK/Return、12TTL重试，D7真Channel在提交后关闭/重投幂等。注入回调错误明确为注入，不称真实DB异常；四链坏JSON实际进独立Poison |
| 指标 | Gateway Timer只新增routeId低基数p95，未加principal/chargeId标签；D4各节点各路由单独读phi=0.95。小样本诊断窗不合并冒充全局p95 |
| 数据库实验 | 10万fixture位于新克隆schema，不混入演示业务库；推荐索引仅文件，不自动迁移生产。原id游标不改语义；无生产收益、写放大或10万订单/促销的虚假结论 |
| 脚本归属 | 新D Lab断言不是C owner；端口、Jar/PID/owner与容器标签核验后才故障；preview只停D并持卷恢复；全机M离线工具遇非D Control先拒绝，保留原全局护栏。首次部分停D失败有记录，无常驻进程停机 |
| 前端与中文帮助 | 页面源仍原生ESM，真实冷却与availableAt；四链手动读取。补投提示现包含SENT和RETRY_EXHAUSTED。Portal202明确待创单/待付款，无伪造库存和价格权威 |
| 秘密与交付 | 公开JSON不含password/token/authorization字段值，截图已逐张观察；真实凭证仅.local0600。gzip原始文件保留原行和SHA，Git App交付树/父链另验，不强推 |

当前自审没有发现新增未解决的P1，但不能由此宣布外部审核通过。已知验收限制：额外M全机FROZEN/CAS（受保护实例）、SQL订单/促销10万与写放大、compute/DBheavy与极限容量、D5完整逐档CPU/RSS、浏览器全错误样式矩阵与耗尽现场截图。它们在最终结果明确NOT_EXECUTED；总体CONDITIONALLY_READY。性能实验不能证明生产容量，所有人手验收由负责人填写原清单。

最终Java97/Node9/Python20通过，末次中文静态文案仅重新package；最终Jar/owner/持卷恢复/安全停机哈希见v12-d7-full-regression.json与v12-d7-lifecycle.json。旧PR处理方案在v12-d-pr-stack-audit.md，未执行关闭、改base/head、合并或打tag。
