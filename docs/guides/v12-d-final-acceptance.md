# FlowGate｜V12-D Final 最终验收清单（负责人操作、独立签字）

> 这是最后一次全项目统一验收，不另开M/C/V11-D收尾阶段。**当前是验收计划，不是测试报告，以下勾选项均未在D阶段真实完成。** 请先让本地Codex按[统一实施合同](../progress/v12-d-final-delivery.md)完成D0～D8技术检查和证据，再由负责人在人机浏览器真实体验确认；Codex/ChatGPT不能代签本人验收。隔离项目名称/端口/账号/开关从实际运行报告读取，**不能假设本机8081当前已运行D**，更不能默认启动`flowgate-local`部署C/D。

## 1. 先确认当前运行环境（保护用户常驻服务）

- [ ] 所有项目脚本已经实际校验`owner`，使用**新的D随机Compose project/MySQL/Redis/Rabbit命名卷/2Control/2Gateway/1Demo**，根`.env`与常驻`flowgate-local`没有改动；截图不含真实Token/密码。
- [ ] 启动前在隔离环境运行`python3 scripts/v11-local.py doctor --isolated`或D交付的等效owner保护`doctor`命令（以新runbook真实支持的参数为准，**不要盲执行示例**）；独立数据库B/M/C表已安全初始化，4类Rabbit消息消费链都真实Ready，C功能默认OFF与显式开启均核对。
- [ ] 文档指向的C预览`python3 scripts/v12-c-preview.py start|status|stop`属于**旧C owner**，只有明确目标是C留存数据时才用；D验收不能自动重启C旧实例或本机`flowgate-local`。D完成后按本轮owner安全停机并保留证据。

## 2. 真人 Portal 用户业务闭环（1280px和375px各完成一轮）

- [ ] 浏览`/portal/`，注册/登录普通用户，看到真实API列表、分类/文档/免费/付费/活动详情，价格以“元”正确换算整数分，真实API下线时不应能够购买/授权。
- [ ] 创建Developer空间和自己的APP、确认OWNER，选择一个已发布可调用API免费BASE，按当前/月上限领取；真实签发一次性APP Token（不在后续页面/截图重复泄露），Gateway调用返回200、余额与V12额度账本出现真实记录。
- [ ] 对一个正价PAID_BASE SKU创建普通B订单（未付款），点击模拟付款→PAYMENT_CONFIRMED Rabbit→唯一Grant→FULFILLED；两Gateway真实HTTP授权并产生Quota Ledger；旧V11额度不会误减少。使用后模拟退款明确409，未使用的受控模拟退款行为符合B定义。
- [ ] 对一个真正配置的C限时活动，选择**自己的APP**抢购，看到“202已受理，正在排队创建订单”而不是“已付款”；Rabbit异步订单生成后可跳转B订单、模拟付款、真正Grant/Quota→Gateway调用；一人重复请求应读回原Intent、不能拿第二份。本人可查看取消/超时/退款，非本人访问Intent/订单404。
- [ ] 验证`CURRENT_MONTH`与`NEXT_MONTH`固定UTC月，不会越界提前生效；同一APP/API/月只能一个BASE，普通订单、免费领取、促销抢购互斥；退出时余额/用量页没有“未开通=余额0”的误导。
- [ ] 窄屏375px无水平溢出、按钮无遮挡、移动端下拉/Tab与Enter可操作，错误401/403/404/409/429/503说明原因及下一步，加载/空态不伪报成功。

## 3. 真人 Console 管理与运维闭环

- [ ] ADMIN通过`/console/`上架真实服务/路由/API，确认发布快照版本、错误发布、回滚及Gateway路由保护；查看限流429、并发503、Resilience4j熔断OPEN/HALF_OPEN状态而非假的全绿。
- [ ] 管理API市场真实类目、Listing、SKU、C促销活动冻结价格/库存/UTC月，暂停/恢复/重新构建Redis epoch，页面显示真实MySQL reserved/sold/available、缓存hit/miss/失效和Redis状态；缓存失联时正确显示UNKNOWN/REBUILDING，新受理503。
- [ ] 检查V11 APP旧月账本/日报、V12 APP×API月统计、B Payment Commerce与C Promo Intent四Rabbit流。手动查看一次已消费/重投记录，**知道SENT≠Consumed**；对故障隔离实例模拟`FAILED attempt_count>=8`后，运营能看到`RETRY_EXHAUSTED`，按真实ADMIN权限+冷却+审计重新入队，确认重复/过期不会多建单。
- [ ] VIEWER和OPERATOR尝试错误写操作得到403；Portal_USER/Caller/APP Caller不能调用后台或查看别人订单/库存内部字段；一次性密钥不在日志/网络截图/前端存储泄露。
- [ ] Console左导航清楚区分服务/流量治理/客户业务/订单/活动/观测，不到处塞技术标语；真实工作台无需操作员输入SQL、Redis Key、内部UUID来完成基本流程，偶发异常有可执行修复提示。

## 4. 技术证据逐项核验（不是必须人工跑所有压测）

| 项 | 负责人需看到的真证据 | 若没执行 |
|---|---|---|
| P1 Outbox | Rabbit停机8次FAILED耗尽、ADMIN恢复、Consumed/Intent/Stock一一核验；300s真实wait/坏事件Poison | `BLOCKED` |
| Kafka-free四Rabbit | V11/V12/B/C每条独立主/Retry/Poison实测、复投原eventId、DB COMMIT后ACK恢复、双Control/双Gateway，无Kafka依赖 | `PENDING` |
| SQL≥100000 | 新隔离DB合法样本、EXPLAIN ANALYZE/索引与游标真实前后对照、原始数据计时 | `NOT_REACHED`需负责人决定 |
| Gateway四组 | DIRECT/PROXY/GOVERNED/METERED，双模式V11/V12，p50/95/99+实际完成QPS/CLIENT_SHED与CPU/RSS，至少两轮 | `NOT_EXECUTED` |
| C高并发 | 100份限量，1k/5k/20k**请求数**阶梯，真实库存0超卖+Ledger一致、p95及成功/失败；不声称20k QPS | `NOT_REACHED`需负责人决定 |
| D Java/Node/Python | 最终D源码SHA、JDK17/包hash、实际命令exitCode/测试数量/失败与修复 | `PENDING` |
| 1280/375 | Codex真实Chromium截图、本人再次手动操作流畅/错误可理解 | 本人未操作只能`PENDING_OWNER` |
| 完整PR历史 | #20/#21当前相同Tree的安全结论，B/M/C/D的代码事实与建议合并顺序，无force/重复merge | `PENDING_OWNER` |
| 安全资源 | 根.env/flowgate-local/旧Kafka卷/原Ledger未被D测试改动，所有压测故障只动D owner | 缺证据不验收 |

## 5. 最终签字模板（必须真人填，不要Codex代签）

```text
源码版本（Git SHA）：____________________
浏览器人工测试日期/时间：_______________
本人操作设备/宽度：PC 1280 / 手机 375
业务链路：PASS / FAIL / NOT_EXECUTED
后台运营与权限：PASS / FAIL / NOT_EXECUTED
高并发/SQL/四组实验：PASS / ACCEPTED_LIMITATION / NOT_EXECUTED
故障恢复与P1：PASS / FAIL / NOT_EXECUTED
仓库安全/原资源未动：PASS / FAIL / UNKNOWN
我明确接受的剩余限制：___________________
最终结论：ACCEPTED / CONDITIONALLY_ACCEPTED / REJECTED
负责人确认：_____________________________
```

`ACCEPTED`要求P1修复、核心计量/订单/库存零完整性缺口、最终Java/Node/Python/浏览器及必需性能证据均通过并有负责人真人确认；资源导致压测档位没达到时只可在负责人书面接受差异后标`CONDITIONALLY_ACCEPTED`；不满足则`PENDING_OWNER`或`BLOCKED`。**签字不等于有权自动merge main、打tag或部署原常驻环境；这些要另行显式授权。**
