# FlowGate｜当前本地Codex统一入口（V1.1-A）

> 2026-11-06。负责人要求审查V1.1设计并分配开发任务。设计审查已完成，**仅V11-A管理业务代码获安排，尚未开发/实测**；V11-B配额、C Kafka/Outbox、D性能均不得提前编码。
> 实施分支：`feat/v11-a-business`，基于`docs/v1.1-openapi-platform`最新设计审查。旧PR #1–#6仍逐级Draft，`main`仍是旧基线；当前新PR为V11-A Draft，不允许自动合并或变更旧PR base。

## 立即执行顺序

1. 阅读 `docs/reviews/v11-design-review-2026-11-06.md` 的R01～R06安全边界和`docs/design/openapi-platform-v1.1.md`第12节。
2. 阅读 `docs/progress/v11-a-business.md` 第A-06/A-07节（**A0～A6唯一代码实施合同**）。
3. 阅读 `CODEX_V11_A_EXEC_PROMPT.md`（可直接执行的详细指令）。`CODEX_V11_OPENAPI_PROMPT.md`作为总体背景，不能覆盖A专属门禁。
4. 安全核对本地`git status -sb`、`git fetch origin`、未提交数据、JDK17/IDE/Docker与当前用户的MySQL/Redis卷，不`reset --hard`、`git clean -fd`、`docker prune`、强推或改`.env`。若无可隔离的DB环境先停止真实迁移。
5. **只编码A**：增量`sql/flowgate-v11.sql`/Entities/Mapper、ADMIN-only客户/应用/套餐/订阅API、行锁串行订阅、`/console/`真实业务页面/中文指南、受影响Java/Node/隔离SQL+HTTP验证，实际命令/退出码/失败情况/提交SHA回填阶段文件。
6. A阶段`POST /admin/apps/{id}/credentials`只返回`409 FEATURE_NOT_READY`，绝不签发有效APP Token或写`fg:auth:caller:`/`fg:auth:app:`。**B启用真实APP Token时必须采用独立namespace+APP_CALLER用途，与旧V1 Gateway隔离；没有V1最后人工验收/A复审之前不进入B。**
7. A完成后按Conventional Commits推送`feat/v11-a-business`，保留Draft PR，提交Codex本地真实结果等待ChatGPT和负责人复审；不得自行开始B/C/D或合并main。

## 永久代码和产品约束

Java17、Spring Boot4、Control MVC+MyBatis-Plus、Gateway WebFlux+Redis/Resilience4j和原生`/console/` ES Modules；**A不新增Kafka/新服务/Gateway计量链路/新前端框架**。关键业务中文注释，长流程`// 1.` `// 2.` `// 3.`分点。新业务管理角色ADMIN才能修改，OPERATOR/VIEWER只读，旧V1权限不能改变。数据库迁移不得删改旧表或用户数据；凭证不落日志/请求Body公开证据；静态/DOM合同测试不等于真人浏览器体验。

**当前真实状态：A任务已分配，业务代码与A实测还未执行；V1.1全部处于开发前准备。**
