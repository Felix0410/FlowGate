# 已合并入V12-B0（历史参考，请勿单独执行）

> **2026-11-10新决定**：负责人要求原A三项P1随B设计一起实施。此历史任务不再单独派工，具体修复/实测与顺序以`CODEX_V12_B_EXEC_PROMPT.md`和`docs/progress/v12-b-order-payment.md`的**B0**为准。A功能已编码，三项P1未修，B0做完有真实证据后才能进入B1。

---

# 本地Codex｜V12-A RabbitMQ可靠性复审补丁（仅F0～F4，不进入订单/抢购）

> **仅针对已经远端提交的PR #15 `feat/v12-a-portal`**。当前ChatGPT读取的基线 `472d9eb02a49c9a9384656bf1acd2b18ac9e30d9`；本复审只发现少量P1可靠性/旧环境友好性缺口，**不要求重做A业务与UI、不允许扩张为B/C**。请按`docs/reviews/v12-a-rabbitmq-static-review-2026-11-10.md`及`docs/design/v12-rabbitmq-adaptation.md`的源码合同执行；实际开发前`git fetch origin`并验证远端最新SHA、不清理本地未提交文件。

## F0 安全与状态确认（只读开始）

- `git status -sb`、`git branch --show-current`、`git remote -v`、`git log -6 --oneline`，确认当前仅在`feat/v12-a-portal`或从其最新远端分出安全补丁分支；不得回到main、强推、reset-hard、clean-fd、擅自合并PR#1～#15/修改其他PR base。
- 只对**你新建且可验证归属**的隔离Compose/随机端口、全新MySQL/Redis/Kafka/Rabbit卷做故障测试。原用户.env/.local配置、IDE、端口、MySQL/Redis/Kafka/Rabbit卷只读；不要自动docker down -v或DELETE用户数据。取代镜像时只修改本任务isolated compose，并记录真实版本。
- 校验JDK17/Boot4/Rabbit3.13.7实运行版本与Java/Node已记录基线。失败记录保留，不把历史82项改成“补丁后全跑82项”。

## F1 修正永久错误分类与有界重试（P1-A01）

**文件**：`flowgate-control/src/main/java/com/flowgate/control/portal/ApiUsageGrantedEvent.java`、`ApiUsageWorker.java`、`ApiUsageService.java`和必要的小型异常类型及定向测试。

1. 在`ApiUsageGrantedEvent.parse`边界统一识别Jackson3的`tools.jackson.core.JacksonException`（它是RuntimeException、不是IllegalArgumentException）；语法坏JSON、超长载荷、非法字段数/版本/UUID/时间都转成**明确的永久事件格式错误**，不记录敏感payload。消息的未知Java RuntimeException不能默认永远是DB暂故障。
2. Rabbit receive路径分三类：永久格式/权威数据冲突→`basicReject(tag,false)`到quorum Poison；已明确可恢复的SQL连接/暂时锁等待→确认持久retry副本落到retry队列**后**手动ACK原件；其他未知异常→有界可观测策略、隔离且保留原消息/审计，不静默丢弃。原C Kafka错误策略不要修改。
3. 保留Rabbit publisher Confirm+Mandatory Return、eventId、Outbox发送后CAS语义；对永久损坏Outbox单独标记`FAILED_PERMANENT`或在现有状态中保留明确不可自动重领的类型，避免源码`claim()`循环领取所有FAILED。对于真正暂时Broker故障保留自动退避重领，不能改变MySQL权威Grant。
4. 明确重试计数/时间上限与处理结果（例：消息级显式retry-header，12次后持久Poison供管理员审计），并避免永久队列循环/死信接回主队列。按需要添加轻量metrics，只用低基数错误码，不能用eventId作为指标label。

**测试**：JUnit分别投喂`{broken`、字段类型非法、有完整格式但与本地Ledger冲突、真实DB不可用、永久Outbox坏数据；记录主/Retry/Poison的期望状态、重复幂等和ACK顺序。不能只Mock假返202。

## F2 真实隔离Rabbit故障补证（P1-A02）

只新增少量重点情形，不需要当前就跑10万SQL/100并发/D完整压测：

1. Rabbit可用时发布5笔（保持Free5），停**本任务自有**Rabbit容器后再有新的V12授权（用另一个独立免费应用/API/额度测试，确保不是已耗尽旧池），验证MySQL Ledger+Outbox成功但尚未SENT、不因Broker失败退Quota；恢复Broker后原eventId最终去重入Daily。
2. 对本任务隔离Rabbit做容器重建但**保留自身命名卷**，验证已经confirm/持久化但未ACK的消息仍可读且不会重复汇总；不操作原Kafka的用户卷。
3. 实际发布无效JSON和权威字段冲突到主quorum Queue，验证落Poison且TTL5s不重返；真实中断MySQL后再恢复，验证retry queue中途存在、Rabbit确认了新副本后才ACK旧message，最终只一笔日报。
4. 用Rabbit诊断命令记录本轮真实版本、3个quorum队列`x-dead-letter-strategy=at-least-once`/`x-overflow=reject-publish`参数及`stream_queue`feature flag启用事实，固定隔离Compose镜像版本或digest以可复现。
5. 保存`docs/progress/evidence/v12-a-rabbit-review-smoke.json`：真实命令、退出码、隔离owner/数据卷身份、broker版本、before/after counts、所有事件ID脱敏或统计、恢复耗时、失败尝试，`NOT_EXECUTED`项单独列。执行结束关闭仅自有进程/容器并保留卷。

## F3 旧V11环境默认OFF友好性（P1-A03）

**文件**：`MarketAdminController.java`、`MarketCatalogService.java`、`static/console/market.js`与Console导航或必要只读门禁。

- 新增单独只读`/admin/market/readiness`，在缺V12表/Portal OFF/Quota OFF时返回明确`DISABLED/SCHEMA_NOT_READY`，**不查询新业务表**；管理写接口按V12独立授权与开关拒绝，不能借ADMIN权限自动迁移用户库。
- Console页先读状态，再决定是否调categories/listings/reconciliation。旧V11默认OFF时市场页只显示真实不可用原因与如何运行V12隔离实例，不产生SQL表不存在的500；旧Console其他20业务能力保持正常。
- V12隔离环境已Ready时，运营上架与新Portal业务完整保留；所有管理POST仍限制ADMIN，OPERATOR/VIEWER不能绕过。相应中文指南/帮助同步，不能放假数据或默认管理员密码。
- 用两组**真实不同独立新库**测试：旧V11建到C但不安装V12表、全部V12开关OFF；V12安装14表并手动明确开启。不得用现有用户常驻库当“旧库”测试。

## F4 定向回归、文档、Git交付

- Java17`mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package`能运行就记录真实完整汇总（有Mockito本机限制则使用之前允许的Java agent并记实际命令）；新增Rabbit/Portal专项JUnit；Node Portal/Console三入口；Rabbit专项真实隔离脚本；旧V11 B23/C26若本轮未运行，保持`NOT_EXECUTED`，不能冒充已经跑了。
- 更新`docs/progress/v12-a-implementation.md`、`docs/progress/v12-a-portal-free-subscription.md` A5/A7、`docs/guides/v12-a-portal.md`、产品Console生成指南；**把V12新RabbitMQ与V11旧Kafka清楚区分**，不要把旧合同遗留文字当V12仍用Kafka。保持`docs/progress/evidence/v12-a-*.json`旧记录不可覆盖，新增单独证据。中文核心类/长方法用`// 1.`、`// 2.`、`// 3.`。
- Convention commits`fix(v12-a): ...`,`test(v12-a): ...`,`docs(v12-a): ...`，只推PR#15的功能分支，Draft不合并；如果push 403用既有已连接GitHub应用方式，必须有远端新SHA并保留本地SHA/tree对应。GitHub远端可核对才报“已提交”。
- F4结束后**停止**，等ChatGPT新源码复审、负责人Portal/Console手动验收；**不要自行进入V12-B订单/模拟付款、V12-C抢购、D压测或迁移全项目Kafka**。反馈要明确哪些P1确实修复、Rabbit真实Broker测试是否执行、还有哪些故障未测，而不笼统“完成”。
