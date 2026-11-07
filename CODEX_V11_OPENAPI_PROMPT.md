# Codex 工作指令｜FlowGate V1.1 OpenAPI 平台业务化改造

> **2026-11-07 用户最新授权覆盖以下旧A指令**：V11-A已编码并有历史17JUnit/155HTTP，A审核两项P0现**并入V11-B B0**。当前唯一有效的本地编码指令是`CODEX_V11_B_EXEC_PROMPT.md`，权威细节`docs/design/v11-b-quota-execution.md`及`docs/progress/v11-b-quota.md` B-07～B-15；B0实测通过前不开展B1，B真正身份和配额闭环完成前不对外签发APP Token/发布计量。以下文字为旧V11-A阶段历史合同，仅作背景，**不得当成当前任务执行**。

> **2026-11-06 已完成V11设计复审**：严格先读 `docs/reviews/v11-design-review-2026-11-06.md`、`docs/design/openapi-platform-v1.1.md`第12节，再执行 **`CODEX_V11_A_EXEC_PROMPT.md`**。后者是当前唯一详细的本地编码交接；新应用Token、USAGE_QUOTA、Kafka和四组性能均尚未实现。V1人工浏览器验收与最终收口仍待负责人进行，V11-A可在隔离新分支先做不激活Token的管理域，B之前须完成前置门槛。

> 当前默认工作仅为**阅读规划并开始 V11-A（前提：负责人确认旧 V1 收口与工作树安全）**。不得把设计文档标记为已编码。具体业务约束权威源：docs/design/openapi-platform-v1.1.md。
>
> 远端文档设计分支：docs/v1.1-openapi-platform，起点是 V1 最完整的 test/block4-final-acceptance SHA ec16efe0bb1a5a07824c82f2352789a6c95391b1；main 仍停留在 Block0。旧 PR#1–#5 是逐级依赖的 Draft，不得改 PR base、强推或自行 merge main。开发分支须以负责人确认的最新完整功能分支起步，先 fetch 核对 SHA。

## 0. 执行入口与顺序

必读顺序：
1. README.md 中现有 V1 功能和限制；docs/acceptance/final-results.md、docs/progress/block-4-acceptance.md（核查人工验收尚未全部完成）。
2. docs/design/openapi-platform-v1.1.md（需求、边界、一致性、数据模型、验收）。
3. 当前唯一任务：docs/progress/v11-a-business.md。按实际开发阶段仅当上一块通过并被确认后读 v11-b-quota.md → v11-c-async.md → v11-d-acceptance.md。
4. 当前源码：sql/flowgate-init.sql；flowgate-control 的 IdentityService、ReleaseService、CatalogService、AdminAuthInterceptor；flowgate-gateway CallerAuthFilter、AdmissionGlobalFilter、EffectivePolicyResolver；/console/ 现有 ES Modules。不要复制 Plus 订单/秒杀业务。

必须先做只读审计：
- git status --short、git branch -vv、git fetch；检查未提交/未跟踪、IDE/JVM 和 Docker 现有服务与数据卷；若现存改动属于用户，不覆盖或 clean。
- 核对 Java17/Maven 当前实际依赖、Module、旧测试脚本、产品 Guide/权限；实际文件与任务合同不一致时，**以正确性和现有数据安全优先**，记录差异后做最小修正。
- 清楚标记：目前三个新业务块都还没有代码，不能声称整体 V1.1 完成；已有测试是 V1 的证据，不是 V1.1。

## 1. V11-A 开发要求（只做本块，避免提前进入 B/C）

1. 在原有 Control 模块增量建表/实体/Mapper/Service/Controller；业务对象 Developer、Application、ApiPlan、Subscription、App-Caller 映射；不得改 V1 的既有表数据。
2. 权限清楚：ADMIN 写、OPERATOR/VIEWER 首版只读；Caller 不能访问管理 API；所有错误返回明确 400/401/403/404/409。**A 阶段不实际签发新的应用活跃 Token**：/admin/apps/{id}/credentials 暂返回 409 FEATURE_NOT_READY，直到 B 同时实现 app-only 路由限制与持久状态核验；不能让 app Token 沿用旧 CALLER Scope 绕过订阅限制。
3. 新增接口按总设计第 6 节，必须遵守字段验证、唯一约束和订阅互斥事务；新增真实列表分页、可读名称、空数据状态。
4. 每个新增管理功能同步更新 /console/ 导航与表单、/console/guide.html 和 docs/guides/product-console-guide.md。正式前端应像产品而不是“JSON 接口调试器”；375px 移动端和 1280px PC 验证。
5. 中文注释：较长关键逻辑使用 // 1.、// 2.、// 3.；遵从现有 Java17、Boot4、MyBatis-Plus、HTML/CSS/JS ES Modules，不新增 Vue/React/npm、新微服务或不必要库。
6. 本块完成隔离 MySQL/Redis 的最小 HTTP 验证、权限与凭证安全、增量 DDL 重复执行、前端 Node 合同测试，以及受影响范围 Maven 编译/单测。**不要启动全量长压测，也不要停止用户正在运行的 IDE 服务。**

## 2. B/C/D 提前锁定的架构原则（本次不编码）

- B：首次真正签发/轮换 app Caller Token 时必须同步上线 app-only 计量路由访问限制、App 停用验证和吊销；Redis Lua 的 RATE_LIMIT 是速率；MySQL QuotaBalance/Ledger 是月度额度，后者不由 Redis 缓存决定。显式 USAGE_QUOTA 策略要求 protected route；Gateway 用 WebClient 非阻塞访问可信内部 Quota 接口，Control 用单 MySQL 事务原子扣减+Outbox，chargeId 幂等；失败 fail-closed，不污染旧路由。
- **B 的重要实现细节**：对配额请求中 Gateway 报告的 appliedVersion，要核对对应 ConfigRelease 的已发布历史快照和 route/policy 绑定；不能只拿 Control 当前最新版验证，否则网关落后一个合法版本时出现错扣/误拒。这个接口由可信服务调用，但版本/规则仍需在 Control 侧二次校验；无法核对对应历史发布就拒绝。本条列为 B 的独立测试。
- C：Outbox Kafka 至少一次送达、消费者幂等，异步统计不是权威余额；Kafka 挂了账本扣减仍成功，事件在 MySQL Outbox 等待恢复。可靠性证明见 C 文件。
- D：SQL 数据规模与 EXPLAIN、四组对照、真实浏览器、双网关与故障注入证据；避免复用旧 V1 纯代理性能作为 V1.1 结论。

## 3. Git 与阶段报告

- 任务基线的仓库文件必须与本地核对；使用独立的 feat/v11-a-business（后续 B/C/D 单独分支或 PR）进行开发，禁止直接在 main 做变更；沿用现有 Git 格式风格 feat(v11-a): ...、test(v11-a): ...、docs(v11-a): ...。
- 每一块先实现代码和定向验证，再提交对应文件和进度；不将失败写成成功、跳过写成已执行。保持阶段报告独立；只追加真相，不覆盖历史事实。
- Block A 结束输出：真正变更的文件表、为什么改、SQL 迁移方案、接口与错误码、RBAC、真实 Maven/Node/HTTP 命令与退出码、具体用例通过失败数、已知限制、commit SHA、下一块依赖。未做真人浏览器应明确“待负责人”。
- 不要擅自合并 main、结束现有 Draft PR、改写 Git 历史、推送明文密钥和用户私有本地日志，也不要代替用户完成浏览器主观体验。

## 4. 重要安全与完整性阻断项

- 业务配额必须防超额与跨客户越权；不得依赖客户端传递 appId 为真实身份，不得假设 Redis 与 MySQL 自动强一致。
- 旧 Caller Token/Scope、旧发布版本回滚和网关路由须回归；API Key 生命周期中的旧 Token/Redis 失败不能静默放行。
- C 的 Kafka/Outbox 不在 Gateway 请求热路径；除业务确需外不新增 Nacos、Seata、Redisson、分库分表、真实计费。
- 代码中发现与设计相冲突的真实边界时，先保留 V1 正确性、缩小首版范围，记录真实原因；严禁为了增加技术点而制造隐形一致性漏洞。

**当前立即执行目标：核对 V1 基线 + 实施 V11-A 并提交本块真实报告，随后由负责人检查；不要一次性实现 B/C/D。**
