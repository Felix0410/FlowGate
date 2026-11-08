# FlowGate｜当前本地Codex唯一任务：V11-B复审收口

> 2026-11-08：**V11-B B0～B6实际Java/SQL/JS代码和Codex定向测试已远端提交**，PR #8仍Draft。当前不重新开发B、不提前开启C Kafka或D性能任务。静态复审发现两项小范围P1缺口，须在实际迁移/对外激活配额之前完善。
> 最新源代码审核：`docs/reviews/v11-b-static-review-2026-11-08.md`。**当前唯一详细执行提示：`CODEX_V11_B_REVIEW_FIX_PROMPT.md`**。

## 本轮只做的事

1. 先安全核对`git status -sb`、`git fetch origin`与`feat/v11-b-quota`当前远端HEAD；本地未提交工作与既有数据库/容器/IDE/`.env`均不能覆盖，禁止`reset --hard`、`clean -fd`、强推及合并main。
2. 阅读 `docs/reviews/v11-b-static-review-2026-11-08.md` 和 `CODEX_V11_B_REVIEW_FIX_PROMPT.md`，对照 `docs/progress/v11-b-quota.md` 的B-16～B-18真实已实现记录。
3. **F1：余额冻结与存量订阅一致性**。现B0锁住未来新建同月第二套餐，但A旧表可能已有历史同月分段。`QuotaChargeService`复用已有余额时要比较冻结订阅/套餐/版本/额度，不一致须失败关闭、不能修改余额或账本；加只读迁移预检和真实MySQL反例。
4. **F2：紧急凭证撤销**。管理员在Quota激活开关关闭/节点失联时也应能够撤销此前已签发的APP Token，通过DB generation+1/disabled确保后续不能重新使用；签发与轮换依旧受完整门禁。若未迁移B表且无凭证，默认旧V1安全行为不回归。前端显示真实的可撤销状态。
5. 受影响Java17/Maven、Node产品、真实隔离MySQL/Redis+双Gateway原B6最小回归；新证据独立新增、不得覆盖A历史155条/B原20组，更新README/阶段记录/产品指南，逐个Conventional Commit。未实际执行写“未执行”。
6. 完成后**停下等待ChatGPT代码复审与负责人真人浏览器体验**，不开始V11-C Kafka、不自行合并任何PR/main，不重做B0～B6。

## 技术与安全固定约束

Java17/Spring Boot4、Control MVC+MyBatis/MySQL8.4、Gateway WebFlux/Redis7、原生`/console/` JS ES modules；配额MySQL Balance/Ledger/Outbox权威同事务，Redis只处理Token和速率，App Token不进入旧Caller namespace，成功grant不按业务HTTP2xx退款。本轮不增Kafka/Seata/Nacos/Vue/React/npm。中文注释，关键长流程按1/2/3分点；保护原有用户容器/个人DB/密钥和全部旧PR。

**当前真实状态：B技术主体已交付、Codex集成测试有证据，静态复审新增2项小范围修复尚未编码，V11.1最终验收未完成。**
