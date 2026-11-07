# FlowGate V11-A｜2026-11-07 源码复审后的最小修复任务

> 当前V11-A业务实现已真实提交远端；Codex本轮**不重新开发A，也不得进入B/C/D**。请先完整阅读 `docs/reviews/v11-a-static-review-2026-11-07.md`。本文件把该报告转为唯一可编码清单。
> 目标分支：`feat/v11-a-business`（现有Draft PR #7），不是`main`或`docs/v1.1-openapi-platform`。禁止删除旧155条HTTP证据，未重跑的测试不能宣称通过。

## F0｜安全与基线

1. `git status -sb`、`git fetch origin`、`git log -1 --oneline`、JDK17/MySQL/Redis版本；如本地未提交或远端SHA变动，保护现有工作树，先检查差异。旧 Codex push HTTP403是历史事实，**当前远端已能读到V11-A全部源码**，不允许因此重置/强推。
2. 原V1本地持久化MySQL/Redis/IDE不动；只用脚本安全隔离Lab和新随机端口。当前MySQL迁移`sql/flowgate-v11.sql`不能未经用户备份批准就跑到用户数据库。
3. 重点查 `SubscriptionSchedule`、`PlanSubscriptionService`、`ApplicationEntryMapper.nowUtc`、`application.yml`、`openapi-business.js`和现有`V11BusinessTest`、`scripts/v11-a-smoke.py`。

## F1｜必须修复：月中已过期旧订阅 + 新订阅不重叠，却允许更换套餐

现有规则只校验两个区间是否重叠，允许同一个App在本月Free已结束后再创建当月Pro。**此时B无法决定当月余额究竟冻结哪一套餐**。

- 同一App在一个UTC自然月内的当前套餐选择只能发生一次：若已有`ACTIVE`订阅曾覆盖该UTC月（即使现在已过期），再建该月生效的另一条必须409 `SUBSCRIPTION_CONFLICT`；允许原有当月订阅区间与**下月1日**开始的排队订阅合法相接。不能用仅检查`overlap`替代月内规则。
- 具体例子固定：`now=2026-11-20T12:00Z`；旧`Free [2026-11-01,2026-11-10)`；新`Pro [2026-11-11,2026-12-01)`：**改前可通过，改后必须409**。旧`[11月1日,12月1日)`+新`[12月1日,1月1日)`：应201。不同应用继续互不阻塞。
- 维持A原来的`@Transactional`、`SELECT FOR UPDATE`应用行和当前读；禁止在前端或Redis做“是否同月”最终判定。旧行STATUS/视图periodState的含义不能混淆；没有给A新增计费和强制发证行为。
- 编写同样场景的JUnit纯规则测试和隔离MySQL真实HTTP用例，包含最后一毫秒、次月排队、已过期旧订阅、防重复审计。

## F2｜必须修复：MySQL会话UTC不由connectionTimeZone参数保证

- Connector/J官方说明`connectionTimeZone=UTC`**不会自动改变MySQL服务端会话时区**，`forceConnectionTimeZoneToSession`默认false。迁移脚本自己的`SET time_zone`不等于Hikari连接池配置。
- 在`flowgate-control/src/main/resources/application.yml`采用**一个有证据的最小方案**保障所有业务SQL会话`@@session.time_zone='+00:00'`，如Hikari`connection-init-sql: SET time_zone = '+00:00'`，或兼容自定义FG_MYSQL_URL的等效方式；`ApplicationEntryMapper.nowUtc()`明确使用可靠UTC时钟并验证MyBatis的Instant映射，不仅依赖函数名。
- 用独立MySQL会话不同时区以及进程JVM`-Duser.timezone=Asia/Shanghai`复测日期毫秒的写入/读取、`nowUtc`和跨月判断；记录实际`SELECT @@session.time_zone, UTC_TIMESTAMP(3), CURRENT_TIMESTAMP(3)`，并在UTC测试期间界值。
- 如连接初始化SQL可能影响V1原时间语义，必须先用隔离环境确认既有审计/发布/节点心跳真实数据不变和读写兼容，再提交。**不要修改用户的真实MySQL服务全局时区，也不改本机.env**。
- 文档参考：https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html 。

## F3｜产品体验修订（P1，若能小幅完成则同步）

- 现订阅表单默认要求用户手写`2026-12-01T00:00:00Z`。增加“本月开始”和“下月首日开始”的产品级选择，自动转换UTC时间；截止日明确表示`[start,end)`，后端仍是权威，不能把本地时区混入账本。
- 保留高级UTC输入/原有测试，中文版`/console/guide.html`与`docs/guides/product-console-guide.md`同步。不可将表单视觉模拟当真人375px/1280px验收。

## F4｜复测、提交、回填

1. 首次重现F1/F2失败场景，真实命令与结果留在本地隔离测试记录；修复后重新执行`mvn -B -ntp -pl flowgate-control -am test package`、`node scripts/v11-a-console-test.mjs`、`node scripts/product-console-test.mjs`、`python3 scripts/v11-a-smoke.py`及影响范围其他旧契约。失败如实报告，不跳过。
2. **保留旧`docs/progress/evidence/v11-a-smoke.json`**原样；修复后新增`docs/progress/evidence/v11-a-review-fix-smoke.json`的脱敏摘要，注明实际新SHA、SQL时区条件和同月两次订阅的拒绝，以及测试命令。
3. 追加`docs/progress/v11-a-business.md`进度与`README.md`真实状态，修复审查报告对应问题。提交建议：
   - `fix(v11-a): enforce one plan selection per utc month`
   - `fix(v11-a): align mysql pool sessions to utc`
   - `test(v11-a): cover midmonth switch and timezone differences`
   - `docs(v11-a): record review fixes and evidence`
4. 完成后停在PR #7 Draft，回报完整SHA/远端推送结果/通过与失败数字、遗留P1及用户真人体验待办。**不要开发B的APP Token/USAGE_QUOTA/QuotaBalance/Ledger/Kafka，不改main，不合并任何PR**。

判定：F1/F2两项均通过**真实隔离MySQL**补测后，才能标记V11-A技术审查主要P0项收口；最终通过还需负责人产品体验/V1前置收口。
