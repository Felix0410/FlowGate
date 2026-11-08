# V11-B 复审收口｜本地Codex最小修复任务（2026-11-08）

> 负责人刚提交了B0～B6完整实现。本轮**不重新开发B、不开始V11-C Kafka或V11-D长压测**。最新审核源自`docs/reviews/v11-b-static-review-2026-11-08.md`，其2项P1分别是既有A历史订阅迁移安全与计量功能关闭时的紧急凭证撤销。核心B真实并发测试已通过（Codex证据），不能篡改为“失败”；修复后需增量实测而非重用旧结果。
> 当前唯一改动分支：`feat/v11-b-quota`，Draft PR #8；原A/设计/旧V1分支与main不变。

## 0. 安全核对与阅读

1. `git status -sb; git fetch origin; git log -1 --oneline; git branch -vv`。若本地有未提交修改或同名分支先保护/对比；GitHub远端当前已能读到V11-B完整代码，不要因为Codex上次push403的历史记录强推、reset/clean或错误宣称远端只有设计。
2. 完整阅读`docs/reviews/v11-b-static-review-2026-11-08.md`、`docs/progress/v11-b-quota.md`的B-16/B-17、`docs/design/v11-b-quota-execution.md`；涉及源码只读：`QuotaChargeService`、`QuotaBalanceEntryMapper`、`PlanSubscriptionService`、`SubscriptionSchedule`、`AppCredentialController`、`AppCredentialService`、`QuotaActivationService`、V11-B现有JUnit/Node/Python。
3. 只使用隔离MySQL8.4/Redis7随机端口与测试Lab；不启动/修改用户持久数据库、docker volume、IDE/JVM、.env；不`reset --hard`/`clean -fd`/强推/合并main。

## F1｜A→B存量多订阅与额度冻结一致性校验（P1，用户数据库计量开启前的硬门槛）

### 真实问题

原V11-A曾允许同一个App在同UTC月创建两条**非重叠**订阅，B0只禁止后续新建，不能自动修复已有行。目前B`QuotaBalanceEntryMapper.initialize`同月余额存在时`ON DUPLICATE KEY UPDATE id=id`，随后`QuotaChargeService.grant`并未检查旧余额冻结subscriptionId/planId/planVersion/totalUnits是否仍匹配当前订阅。

### 必须完成

1. 在`QuotaChargeService.grant`取得`balances.locked`行锁后，比较冻结`balance.subscriptionId/planId/planVersion/totalUnits`与所选`sub/plan`。不匹配应409 `QUOTA_SUBSCRIPTION_MISMATCH`（或等价明确错误），**本次不扣、不插入Ledger/Outbox、不修改余额内容**；不可覆盖旧月金额、静默换Plan、退款或改历史。最初幂等Ledger重复查询仍应该先行，确保已授予同chargeId跨月重试维持原归属。
2. 增加只读迁移预检SQL或文档+辅助脚本：列出`fg_subscription`同应用/UTC月占用两条以上的情况、跨月历史区间（可用SQL按年月边界拆解；如不便SQL实现，Java/Python按真实UTC Instant只读扫描后输出脱敏计数），以及余额与已冻结订阅不一致的情况。**不得自动UPDATE/DELETE历史数据、不得以“自建测试库没有脏数据”证明真实库无风险**。
3. 先通过隔离MySQL/Java测试构造A历史：同月Free`[2026-11-01,11-10)`、Pro`[11-11,12-01)`，验证预检返回风险；另构造Balance冻结Free却当前有效Pro，真实quota grant必须409且账本/余额无变化。正常Free5/100并发/跨月幂等保留原行为。
4. 不用新增MySQL表或复杂迁移框架，最多添加1个简单脚本/SQL，附实际执行方法、退出码和需要负责人手动备份/评估的说明。

## F2｜默认OFF/节点不可用时依然可以紧急撤销已签发APP凭证（P1）

1. `AppCredentialController.revoke`现在`activation.enabled()==false`直接409，导致曾经启用并签发的Token在紧急关闭计量后无法通过管理员页面永久撤销。**修改为签发/轮换仍必须B就绪，ADMIN撤销不依赖当前Quota开关/节点是否在线**。进入真实service后仍先DB generation+1并enabled=false，再尝试Redis删除，Redis失败明确返回当前部分结果，旧Token实际不能再准入。
2. **默认OFF尚未迁移B表**的旧V1仍应启动/操作原路由。若APP凭证表不存在或无对应主体，撤销可明确409 FEATURE_NOT_READY/404；不能因误访问撤销而使整个Control启动失败或导致凭证被当成有效。
3. 修改`V11QuotaCredentialControllerTest`旧断言（目前明确要求disabled不撤销）、补新定向测试：已有状态+开关OFF/节点未知能撤销；Redis清理失败后DB仍失效；重新开启后旧Redis会话保持403。真实HTTP覆盖权限矩阵ADMIN可撤销，OPERATOR/VIEWER仍403，不降低V11新业务RBAC。
4. 产品`/console/`凭证管理需要在OFF状态显示安全的“仍可撤销/不可签发”而不是统一“凭证未启用”；这需要修改`AppCredentialController.status`或安全状态API/前端少量逻辑，**只显示来自真实DB的状态**。若B表未部署，明确“功能尚未部署”，不要伪造ACTIVE/REVOKED。

## F3｜实测、进度与Git

1. 在受影响范围使用JDK17 `mvn -B -ntp -pl flowgate-control,flowgate-gateway -am test package`，运行新增Controller/Quota JUnit、V11-A和B产品ES模块Node、`scripts/v11-b-smoke.py`真两Gateway隔离验收（按README先构建A历史夹具）、只读预检；失败如实记录，可先独立跑F1/F2小脚本再补全B6。
2. 旧`docs/progress/evidence/v11-b0-smoke.json`、`v11-b3-smoke.json`、`v11-b-smoke.json`和A155条历史证据**原样保留**。另建`docs/progress/evidence/v11-b-review-fix.json`脱敏的真实测试摘要，包含当前SHA、修复前反例、修复后拒绝、OFF撤销、Java/Node/HTTP数字和退出码，不含Token/密码/请求Header/Body。
3. `docs/progress/v11-b-quota.md`追加实际修改文件/SQL/命令/退出码/通过失败/测试环境/完整SHA；更新README和产品指南安全操作路径。小步Conventional Commits建议：
   - `fix(v11-b): verify frozen subscription against balance`
   - `fix(v11-b): allow emergency credential revocation while disabled`
   - `test(v11-b): cover legacy-month and disabled-revoke edges`
   - `docs(v11-b): record review fixes and real evidence`
4. **修完后停止，等待ChatGPT复审与负责人真人浏览器验收**。不自行开始C/D/Kafka、禁止合并/重排任何Stacked PR，保护用户机器的已有持久数据。未运行的测试保持“未执行”，不要凭设计文档声称真实已修。
