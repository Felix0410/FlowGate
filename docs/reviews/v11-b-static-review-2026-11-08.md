# FlowGate V11-B｜远端实现与真实测试证据源码复审（2026-11-08）

> 审核起始远端SHA：`8de389f2820dc4cdab43b98e90a046b511862859`，分支`feat/v11-b-quota`，Draft PR #8，base `feat/v11-a-business`。静态审核远端实际Java/SQL/ES Module/Python测试与提交的本地执行报告。
> **诚实边界**：本轮ChatGPT未独立运行Java17/Maven、MySQL8.4、Redis7、两Gateway或真实浏览器。下文的57项JUnit/20组真实HTTP/100并发是**Codex报告+提交的脚本/证据**，不是本轮重新执行的成绩。PR仍Draft，main未合并。
> **结论**：B0～B6核心代码已落地，业务边界和多数安全取舍符合合同，具备进入**小范围交付收口与负责人浏览器体验**的条件；发现2项上线前建议修正的安全运维/旧数据兼容问题（见第3节）。这两项并非已实测的余额超卖或身份绕过，不能误称既有并发测试失败。V1.1完整验收、Kafka、长期性能均未完成。

## 1. 交付真实性：已经不是设计PR

- 现有远端对比A分支：`22`个提交、`70+`处文件变更，含`QuotaPolicy`、`QuotaCharge`、`AppCredentialSession`、`QuotaChargeService`、`AppCredentialService`、`QuotaActivationService`、`QuotaAdmissionGlobalFilter`、`CallerAuthFilter`改造、`sql/flowgate-v11.sql`新增5表、`/console/`配额与凭证页、JUnit和隔离Py脚本。
- 源码B0 `SubscriptionSchedule.validate`显式判定同UTC月内任何历史订阅区间交集，仍用应用行FOR UPDATE；Hikari`connection-init-sql: SET time_zone = '+00:00'`且`ApplicationEntryMapper.nowUtc`使用UTC_TIMESTAMP(3)；真时区证据在`docs/progress/evidence/v11-b0-smoke.json`。
- 源码B1 `PolicyRules.QUOTA`独立执行标记、`QuotaPolicy.Plan`同版路由元数据、Control`QuotaActivationService`接流白名单+可信节点新特性+15秒心跳、默认开关false；旧V1快照/普通Caller不改。
- 源码B2 新Token`fgapp_`+43随机字符，与旧43字符Caller格式/Redis命名空间不同；Redis Lua指数替换，数据库`fg_app_credential_state`generation先提交；计量Service每次查DB生成代次/停用，故不依赖Redis删旧Token成功。
- 源码B3 `fg_quota_balance`唯一(app,UTC月)、唯一chargeId Ledger和Outbox同事务、历史ConfigRelease核验、余额条件UPDATE、重复授权返回冻结历史期间、撤销后不重复放行；事务`READ_COMMITTED`，竞争经唯一约束+余额行锁串行。
- 源码B4 `QuotaAdmissionGlobalFilter` Reactive WebClient、64本机hop上限、同chargeId一次网络重试、单hop2秒/总5秒；旧路线不请求Control，QuotaDenied区分403/429/503，不计入CB失败样本；候选order10经真实过滤器排序日志及官方CB实测。
- 源码B5 新`/console/`业务页具备真实应用凭证状态、签发/轮换/吊销、一次性Token领取与清空、配额/账本游标、计量策略/发布门禁、订阅UTC月选项；保留旧测试台，无新前端框架。
- B6源码`scripts/v11-b-smoke.py`确实运行真Gateway请求/真实并发、控制面回包丢失代理、真实历史A二进制混合版本、MySQL断连、Redis Lua故障、CB OPEN、RequestId/POST透传，不是纯JSON字符串自我断言；需先按README构建`.local/v11-a-legacy-binary`旧夹具。

## 2. 测试记录与限制

| 阶段 | 已提交证据与Codex记录 | 本轮独立执行 |
|---|---|---|
| B0 | 两组异时区MySQL/JVM、旧Caller兼容，19 JUnit、`v11-b0-smoke.json` passed=true | 未执行 |
| B1～B5 | Codex阶段性定向JDK17测试从46至49项、Node产品ES模块/模拟DOM通过 | 未执行 |
| B3 | `v11-b3-smoke.json` passed=true，真实MySQL100并发：5授权/95额度拒绝、100重复chargeId只1Ledger、原月份幂等 | 未执行 |
| B6 | Codex报告最终57JUnit通过（Contract10+Control17+Gateway26+Demo4）；`v11-b-smoke.json` passed=true，**20组HTTP真实场景**，含两新Gateway、混合旧版、CB排序、5+95、Redis/DB故障；外部校验原A155条回归 | 未执行 |

**严禁**把“20组场景”称为只有20次HTTP请求；脚本在每组场景内执行多次真实请求。证据文件是脱敏检查摘要，不是每一次HTTP包体/真实浏览器点击结果。V11-C Kafka并未引入：Outbox状态只有NEW，不代表消息已投递或用量已异步归集。D阶段正式四组性能/10万级SQL与手工浏览器测试尚未执行。

**远端状态更正**：`docs/progress/v11-b-quota.md`第B-17末尾的`git push` HTTP403是Codex**当时**的真实失败；审核起始GitHub远端已经拥有B业务源码、测试及证据，不能继续说“PR仅设计/远端没有B代码”。仍保留当时执行失败的历史，不删除证据或篡改旧日志。

## 3. 两项建议正式部署前收口的审核问题

### P1-B01｜存量A阶段同月多订阅兼容核验（**上线迁移前必核查**）

**真实源码**：`SubscriptionSchedule.validate`现在会禁止新增同一UTC月第二个套餐，但**只作用于未来CREATE**；A阶段代码曾允许“同月两个不重叠区间”，用户若在A阶段真实建过历史记录，升级B不会自动清理这些记录。`QuotaChargeService.grant`选出当前唯一ACTIVE订阅`sub`后，调用`balances.initialize(app,period,subId,planId,version,monthlyUnits)`；SQL`ON DUPLICATE KEY UPDATE id=id`在有现存Balance时**完全不比较冻结subscription_id/plan_id/plan_version/total_units**。在脏数据/跨版本异常切换情况下，这会使账本按新订阅继续扣旧余额，而没有明确拒绝。

**最小任务**：
1. 加**非破坏性迁移预检SQL**，按应用/UTC月检测`fg_subscription`的区间占用多条、跨月历史；记录个数和脱敏ID摘要，不自动DELETE/UPDATE用户数据。
2. `QuotaChargeService`持余额行锁后核验`subscriptionId/planId/planVersion/totalUnits`与当前订阅一致；不一致返回409`QUOTA_SUBSCRIPTION_MISMATCH`并回滚本次新Ledger/Outbox，**不可自行补差/把历史余额换套餐**。
3. 补真实MySQL：先用A允许的旧历史数据构造两段同月订阅，检查上线前预检能查到；再构造余额与当前订阅不一致，确认本次不发生新扣减且可诊断；正常用户的5次额度授权/重复chargeId不回归。

**优先级**：本地干净隔离库的5+95守恒验证并未失败；这是**A→B升级的存量数据安全防线**。如果项目明确没有用户持久化A业务数据，仍需记录预检结果再启用真实Quota，不能假设已运行过该核查。

### P1-B02｜紧急撤销不应受计量激活开关阻断

**真实源码**：`AppCredentialController.revoke`调用`activation.enabled()`，若false直接409 FEATURE_NOT_READY、**不调用`AppCredentialService.revoke`**；`V11QuotaCredentialControllerTest.disabledActivationNeverIssuesOrRevokesCredentials`还刻意验证了这个行为。运营人员如果在曾启用发证后关闭配额功能开关，可能无法及时将已签发的Token在DB中永久撤销。Quota已关闭时请求会503 fail-closed，所以这不是已发现的“停用期间还能偷扣额度”，而是**安全运营操作被功能开关错误耦合**。

**最小任务**：
1. 已建立APP主体/凭证状态存在时，ADMIN即使Quota activation=false/名单节点不在线也允许`POST /admin/apps/{id}/credentials/revoke`执行DB generation+1/disabled=true并记录审计。签发/轮换继续受就绪门禁，不放松。
2. 若表尚未迁移/不存在，默认OFF的老V1应能正常启动和操作旧路由；在缺B表且未曾有APP凭证场景可返回409 FEATURE_NOT_READY，不查询不存在表导致旧V1整体回归失败。
3. 增加Controller Mock/真实HTTP：先签发→将功能关掉/名单不就绪→撤销可成功，Redis清理失败DB仍不可再准入；恢复开关后旧Token不可复活。绝不在关闭状态重新签发。

### P2｜阶段收尾与产品体验

- 负责人在`/console/`用ADMIN、OPERATOR、VIEWER亲自体验客户→应用→订阅→发证→发布计量规则→消耗5次→第6次429→查流水，及375px/1280px、Tab/ESC、一次性Token销毁。Node模拟DOM不等于浏览器人工。
- 当前默认`FLOWGATE_QUOTA_ACTIVATION_ENABLED=false`、接流节点名单空，意味着**安全默认关闭**。用户持久库还未运行V11增量SQL，不能直接说“本地默认启动就能计量”。需备份核对、全节点升级和显式授权开启，不自动改.env/DB。
- 旧V1 CB修订实例随版本累计、原Caller无持久列表、主要监控固定本地Gateway等已知历史限制仍在，别将V11-B功能视为已解决全部历史问题。
- `QuotaActivationService.requireReady`对能力采用JSON字符串包含判断，当前数据源来自受信`List<String>`的序列化，未见可经公网恶意构造的直接绕过；可以改为反序列化`List<String>`并`contains`，降低未来格式变化风险，**不是当前P0**。

## 4. 修复分配与明确停止条件

**当前建议不是重做B**。Codex在`feat/v11-b-quota`只实施P1-B01/B02两项小修与相应Java17/Node/隔离MySQL+真实HTTP回归，追加新脱敏证据，不覆盖B0/B3/B6/A历史证据；另写完整新SHA/退出码/通过失败跳过/已知限制。详细执行任务：`CODEX_V11_B_REVIEW_FIX_PROMPT.md`。

修复后等待ChatGPT复审+负责人真浏览器体验再决定是否进入V11-C编码；C Kafka开发、D四组长压测、所有Draft PR合并、真实用户数据库迁移，**均未获本轮授权**。

**结论**：B主体完成并有较充分真实集成证据，配额事务当前未发现被已有测试覆盖场景下的超卖或普通Caller身份绕过；两项存量升级/运维边界仍需收口。不把新增审查文档等同源码已修，也不宣布V1.1完整交付。
