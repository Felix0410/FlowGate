# FlowGate V11-A｜远端源码审查与定向验收（2026-11-07）

> 远端审核起始HEAD：`befc5d0586fa578d9fe6db398363132d4de3e480`（`feat/v11-a-business`，PR #7 Draft，base `docs/v1.1-openapi-platform`）。
> 与设计分支相比：8次提交、32个文件变化，新增五张管理表、领域Controller/Service/Mapper、独立权限Filter、产品端业务ES模块、Junit/Node/Python脚本与脱敏结果。
> **审核类型**：查看GitHub远端源码、测试代码和Codex提交的证据；ChatGPT未在负责人本地独立执行Java17/Maven/MySQL/Redis或浏览器。
> **结论**：A0–A6的主要业务管理开发与定向实测已交付，**有条件通过源码检查，但不建议立即进入V11-B**。需要先修订订阅同月套餐切换边界、真实数据库连接时区保证并补定向测试。真人产品页面仍待负责人体验，V1前置最终验收仍未全部收口。不得合并main或激活APP Token。

## 1. 已核验的代码与交付范围

| 项目 | 远端证据 | 审核 |
|---|---|---|
| 五张新表、兼容旧数据库 | `sql/flowgate-v11.sql` | 只CREATE IF NOT EXISTS，外键/唯一/状态/CHECK、UTC TIMESTAMP；未更改原V1 SQL |
| 领域CRUD | `DeveloperApplicationController`、`PlanSubscriptionController`、同名Service | 四域GET列表/详情，POST与PATCH、订阅仅POST；名称/归属/不可变Plan验证 |
| 服务端RBAC | `AdminAuthInterceptor`、`V11ManagementFilter` | 新域写ADMIN-only，旧V1 OPERATOR写不变；Filter提前保护未开放方法和编码路径 |
| 订阅事务 | `PlanSubscriptionService`、`SubscriptionSchedule`、Mapper FOR UPDATE/FOR SHARE | 同应用行锁、订阅当前读、审计同事务；有真实并发验证 |
| 不签发APP Token | `DeveloperApplicationService.credentials` | 409 FEATURE_NOT_READY；旧Caller和APP专用Redis命名空间尚未提前使用 |
| 产品管理入口 | `static/console/openapi-business.js`、`app.js`、指南 | 四个产品数据视图、关联名称下拉、分页、完整表单、失败提示、角色只读 |
| 本块测试 | `V11BusinessTest.java`、`scripts/v11-a-console-test.mjs`、`scripts/v11-a-smoke.py` | Java定向/Node模块与模拟DOM/隔离MySQL HTTP证据；非浏览器真人体验 |
| 没有越界 | 对比分支文件清单 | 无Gateway过滤器/ReleaseService修改，无Kafka、QuotaBalance、Ledger、Outbox；旧产品工具保留 |

已查`docs/progress/evidence/v11-a-smoke.json`：`passed:true`、`gitSha=b51f3b455f9f589a6d5f2f1bf3bf1d214154e59e`、**155条真实记录**，HTTP 200×45/201×24/204×1/400×17/401×11/403×42/404×5/405×3/409×7；含迁移二次执行稳定、APP凭证没有Redis/Principal副作用、同应用两个MySQL锁等待201+409与异应用独立执行、旧Caller回归、快照checksum不变。Codex记录JDK17 `mvn -B -ntp -pl flowgate-control -am test package` **17 JUnit通过**（Contract7+Control10）、四组Node验证通过；本轮ChatGPT未复测上述命令。

历史`docs/progress/v11-a-business.md`最后记载Codex自身git push HTTP403，**现在远端事实已发生变化**：审查起始HEAD已经真实包含全部A代码和155条证据。因此第A-08节的push失败要作为当时历史保留，不能继续把它作为“远端没有V11-A源码”的现时结论。

## 2. 必须在B之前修复/实测的P0问题

### P0-A01｜同一UTC自然月允许拆成两个非重叠套餐订阅，破坏B的月度额度冻结语义

定位：`flowgate-control/src/main/java/com/flowgate/control/service/SubscriptionSchedule.java`。`validate`当前只检查新旧`[start,end)`是否**直接重叠**，对已结束但仍位于同一UTC月的已有ACTIVE订阅不加限制。

可静态复现的最小反例：假设数据库UTC时间是`2026-11-20T12:00:00Z`；已有app的Free订阅 `2026-11-01T00:00:00Z ~ 2026-11-10T00:00:00Z`，状态ACTIVE（物理status不会随时间自动更新，只读`periodState`变EXPIRED）。再创建同app的Pro订阅 `2026-11-11T00:00:00Z ~ 2026-12-01T00:00:00Z`，代码的`start<=now`、`start>=monthStart`、`end>now`且两个区间不重叠，**会被放行**。这与V11设计“本月已冻结的额度/套餐不能中途更换，下月才生效”存在冲突；B首次创建余额后若遇到同月另一订阅会产生模糊来源。

**最小修改要求**：
1. 在`SubscriptionSchedule.validate`将当前UTC自然月**套餐选择锁定一次**的规则与纯时间重叠区分；如果该应用已经存在占用本UTC月的订阅，则不得再创建第二个当月生效的订阅，无论前一条是否已到期、是否直接重叠。保留允许下月首日的候选订阅；历史已关闭区间处理需明确。
2. 定义`periodState`与订阅`status`是不同概念，不靠手工把旧status改EXPIRED逃避锁定。首版仍不开放绕过应用行锁的PATCH。
3. 新增JUnit可控UTC Clock纯规则反例和真实MySQL两个不重叠同月套餐创建案例（第二个必须409，旧月最后毫秒/相邻下月仍允许）；业务API文案说明“本月套餐锁定，变更下月生效”。
4. 如果负责人选择支持“同月非重叠多套餐”，那必须先在**设计文档**定义完整的额度冻结/结转规则并提前实现；不允许保持当前隐式允许、等B才猜。

### P0-A02｜连接`connectionTimeZone=UTC`不会自动把MySQL会话时区设成UTC

定位：`flowgate-control/src/main/resources/application.yml`和`flowgate-control/.../mapper/ApplicationEntryMapper.java`。

- 当前JDBC默认连接为`jdbc:mysql://127.0.0.1:3306/flowgate?connectionTimeZone=UTC`，但官方MySQL Connector/J文档明确`connectionTimeZone`仅控制转换/时区假设，默认**不会设置服务端会话变量`time_zone`**；需`forceConnectionTimeZoneToSession=true`或等效会话初始化。迁移文件`SET time_zone='+00:00'`只对执行迁移的会话有效，不能保证Hikari其他池连接。
- `ApplicationEntryMapper.nowUtc()`实际上调用`SELECT CURRENT_TIMESTAMP(3)`，这个值受当前数据库会话时区影响。在非UTC MySQL server/session部署时，当前UTC月边界与返回的`Instant`可能偏离。现有隔离MySQL实测在默认环境成功，**并未覆盖服务器和JVM不同的时区**。

**最小修改要求**：
1. 统一所有Control管理数据库会话的UTC语义。推荐用Hikari安全`connection-init-sql: SET time_zone = '+00:00'`并保留JDBC UTC明确转换语义；也可验证等价的`connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`默认连接方式，但**FG_MYSQL_URL外部覆盖时仍需明确防护**。选择一种有测试支撑且与现有V1数据兼容的最小方案。
2. 关键业务当前时钟可改为明确UTC来源`UTC_TIMESTAMP(3)`，但必须检查MyBatis/JDBC如何映射为`Instant`，不可单纯改函数名称就宣布消除偏差。
3. 测试MySQL server`@@global.time_zone`/连接`@@session.time_zone`与JVM时区分别设不相同时，仍然准确计算`2026-11-30T23:59:59.999Z`和`2026-12-01T00:00:00.000Z`；需证明`TIMESTAMP(3)`写入/读取/回传`Z`一致，不允许用固定本地偏移猜。
4. 在A测试里记录实际数据库`UTC_TIMESTAMP(3)`/连接会话时区与用作订阅判断的`Instant`；原V1路由/发布表时间无格式回归。

权威依据：MySQL Connector/J Datetime Properties: https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html 。`forceConnectionTimeZoneToSession`默认false，`connectionTimeZone`单独使用不改变会话时区。

## 3. 体验改进与最终验收项（P1）

### P1-A03｜日期输入仍偏技术化
`openapi-business.js`的新增订阅表单要求人工填写带`Z`的ISO8601时间字符串。原需求希望非开发者可用，建议增加“本月开始 / 下月1日开始”的**业务选项**，显示UTC日期和明确的截止日期；由前端安全生成ISO值，后端始终复核。保留高级UTC日期输入在“高级选项”，不新引入框架。避免无意创建过期/跨月份和错误时区。补Node表单测试，并等待负责人1280px/375px真人反馈。

### P1-A04｜前端与业务导览未完成真人操作验收
源码中真实增改、关联下拉、错误重试/分页和新菜单均存在，但仅由Node模拟DOM及HTTP验证。需负责人在新的`http://127.0.0.1:8081/console/`中实际测试：ADMIN 2客户→2应用→Free/Pro→当前/下月订阅、OPERATOR/VIEWER只读、刷新后的真实列表、375px、Tab/ESC/日期字段；不能用脚本替代真人体验。V1整体Block4仍待人工和最终回归。

### P1-A05｜风险容量/已知V1缺口
A的列表`afterId+limit`稳定、不查unbounded整表，但逐应用关联名称存在N+1查询，A阶段小数据可接受；D阶段十万级查询审计与索引实验需要评估而不是宣称已优化。原V1的历史Caller无列表与CB配置revision累积等已知限制不会因为V11-A自动消失。

## 4. 阶段门槛、修复分工与Git

1. Codex**只在`feat/v11-a-business`修复P0-A01/P0-A02，补Java/隔离MySQL定向回归**；若P1-A03产品体验可小幅同步完成更好，不另开框架。不要在此分支提前修改`PolicyRules`、Gateway或引入QuotaBalance/Outbox/Kafka。
2. 建议提交：`fix(v11-a): lock one plan per UTC month`、`fix(v11-a): enforce mysql utc session for subscriptions`、`test(v11-a): cover intra-month switching and non-utc sessions`、`docs(v11-a): record reviewed boundary fixes`。
3. Codex在`docs/progress/v11-a-business.md`追加新SHA、命令/退出码、时间区间复现前后结果和浏览器待人工，不擦除已有155条旧证据/当时push失败；更新`docs/progress/evidence/v11-a-smoke.json`应新建增量版本或另存证据，不覆盖已有的真实性。
4. **只有P0改动通过定向与隔离实测、产品人工体验反馈收口、V1前置统一回归条件明确且负责人确认后**，才能讨论启动V11-B。PR #7继续Draft；旧#1–#6与main不动。

## 5. 审核结论

V11-A的新功能代码和测试证据可信度较高，核心权限/并发控制方向正确；**但月度订阅规则与跨时区UTC保证需要在启用任何真正配额扣减前收口**。当前标为**“主要实现已交付；源码审查有两项P0修订待解决；未最终验收”**。本报告本身不代表代码修复或真人浏览器验收已经完成。
