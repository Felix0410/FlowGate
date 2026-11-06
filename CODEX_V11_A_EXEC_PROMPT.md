# FlowGate V1.1-A｜本地 Codex 详细执行提示（仅A块）

> 此文件由2026-11-06 V11设计审查产生。**用户要求审核并分配开发任务，当前仅分配A块具体编码**。V11-A是客户/应用/套餐/订阅的管理域；不启用应用Token、不访问Gateway扣额度、不接Kafka、不改原V1快照与鉴权。B/C/D要等前置审核。

## 0. 当前Git与数据安全

1. 先在本地执行 `pwd`、`git status -sb`、`git branch -vv`、`git remote -v`、`git fetch origin`。确认当前 `test/block4-final-acceptance` 的真实完整实现与 `docs/v1.1-openapi-platform` 最新设计；保留全部未提交/未跟踪文件，禁止`reset --hard`、`git clean -fd`、强推、删除/重建用户MySQL/Redis卷、修改`.env`或杀掉IDE/JVM。
2. 审核后设计分支已存在，**开发分支由ChatGPT在GitHub预建 `feat/v11-a-business`，其base为V11设计最新SHA**；本地同名不存在时执行`git switch --track origin/feat/v11-a-business`。若已存在先核对SHA与工作区，不强制切换覆盖。禁止从main建功能分支，现有PR #1–#6仍叠加Draft。
3. 必读顺序：`AGENTS.md`（若仍是V1状态，以本提示和V11审查为准）→ `docs/reviews/v11-design-review-2026-11-06.md` → `docs/design/openapi-platform-v1.1.md`第12节 → `docs/progress/v11-a-business.md`第A-06/A-07节 → 原设计第1～6节 → `docs/progress/v11-roadmap.md`。如源码与文档冲突，先记录真实差异，以原V1安全和数据不破坏为准。
4. 使用Java17/Maven/MySQL8.4+Redis7隔离容器、随机端口、新数据库。A仅跑涉及的定向测试和最小真实HTTP，不运行整个项目长性能压测。V1负责人浏览器验收尚未完成，不能把这项工作当作已完成主线基线；但可以在此独立分支安全实现A管理域，绝不在B开始前激活App Token/计量。

## 1. 按A0～A6实现，不进行开放式重新设计

**A0**：检查`sql/flowgate-init.sql`、Control现有`entity/mapper/service/controller`、`AdminAuthInterceptor`、`AuditService`、`IdentityService`和现有`console/{api,app,ui,catalog,governance,operations}.js`，固定接口/路径/字段及角色矩阵。

**A1**：按A任务书增量`sql/flowgate-v11.sql`和五个新Entity/Mapper，只有CREATE IF NOT EXISTS，保留V1表/快照；运行V1+V11重复迁移两次并证明旧数据未变。套餐`monthly_units`创建后不允许原地PATCH，`plan_version`冻结。

**A2**：仅新增Developer/App/Plan/Subscription安全管理API；固定`GET列表(稳定afterId+limit 1..100)/GET详情/POST/PATCH`，Subscription首版仅POST+GET；ADMIN写，OPERATOR和VIEWER读，Caller不能进管理API。明确403/400/404/409，唯一约束转换409、成功操作审计；**原V1 OPERATOR旧业务写权限原样保留**。`POST /admin/apps/{id}/credentials`暂时始终`409 FEATURE_NOT_READY`（有效ADMIN存在应用），不生成任何Redis App/普通Caller会话，不暴露明文。

**A3**：订阅`@Transactional`锁`fg_application`行后查已有有效/排队订阅；`[startUTC,endUTC)`无重叠、当前最多1+下个UTC首日最多1条排队。两请求并发同app重叠**恰好一成功一409**；无效套餐、应用停用、过期/时间重叠明确拒绝，不创建孤立订阅。

**A4**：在正式`/console/`新增“客户与应用”“套餐与订阅”两个任务式导航/真实列表、中文新建/编辑、名称下拉、分页、空/错/加载状态；旧服务/策略/发布/监控及旧`/test-console/`全部保留。应用详情显示`凭证功能待B阶段启用`，不可展示假的Token/余额。UI和`docs/guides/product-console-guide.md`、离线`/console/guide.html`同步。原生ES modules、无React/Vue/npm/新中间件。

**A5**：Java17受影响Maven test/package、Node旧`scripts/product-console-test.mjs`+新`scripts/v11-a-console-test.mjs`，隔离MySQL/Redis真实`scripts/v11-a-smoke.py`，包括ADMIN/OPERATOR/VIEWER/Caller角色、2客户2应用Free/Pro、订阅冲突、UTC边界、旧Caller签发撤销、409未激活App Token。检查SQL增量迁移重复执行两次，生成脱敏JSON，敏感凭证只在本次内存，公开证据不保存Header/Token/密码/请求body。

**A6**：在`docs/progress/v11-a-business.md`最后追加实际代码SHA、命令/退出码/JUnit数/HTTP数/失败修复与剩余问题；README和新产品指南同步。每个小块独立Conventional Commit，示例`feat(v11-a): add management schema`、`feat(v11-a): validate subscription scheduling`、`feat(console): support openapi business catalog`、`test(v11-a): verify role isolation and utc intervals`。只提交`feat/v11-a-business`，PR保持Draft，不修改main。

## 2. 一票否决检查

- **A阶段可签发APP活跃Token或借普通Caller前缀绕开计量**；不能做。
- OPERATOR能够修改新增客户/应用/套餐/订阅；不能做。
- 同一应用两个并发订阅可以重叠；不能做。
- 修改Plan monthlyUnits回写已存在历史额度语义；不能做。
- 新管理页面要求用户必须输入数据库ID/裸JSON，或服务端返回“草稿保存”却前端说“已发布”；不能做。
- 数据迁移会DROP/TRUNCATE旧表、覆盖用户Docker/数据库卷；不能做。
- 未实际执行的测试写“已通过”；不能做。

## 3. 回报格式（必须实际记录）

1. Git分支、当前完整SHA、push成功与否和PR链接；
2. 每一阶段实际修改文件、接口和表/字段，以及为什么没有修改旧V1功能；
3. Maven、Node、真实SQL/HTTP命令、退出码及通过/失败/跳过数字；订阅竞争/旧Caller安全与迁移幂等证据；
4. 新`/console/`的入口和关键操作，负责人需亲自体验的剩余点；
5. 当前未完成B/C/D、未合并main、当前V1人工验收状态。

**到A6后停止并通知复审，不自行开始B，除非负责人后续明确授权。**
