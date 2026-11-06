# FlowGate 功能块 3｜本地 Codex 一次性交接提示词

> 负责人于 2026-11-06 明确授权进入功能块 3。**本文件是立即可执行的编码提示词**；仓库中业务任务书 `docs/progress/block-3-resilience.md` 为唯一细化实施契约，不需要重新做开放式技术选型。

## 第一部分：先确保基线正确

1. 在 FlowGate 根目录：`pwd`、`git status -sb`、`git log -1 --oneline`、`git remote -v`；发现用户未提交/未跟踪的业务改动，先保护并说明，不能 `reset --hard`、`clean -fd`、覆盖 `.env`、强推、合并 main。
2. `git fetch origin` 并安全检出/跟踪 **已由 ChatGPT 创建的远端** `feat/block3-resilience-observability`。初次本地不存在时：`git switch -c feat/block3-resilience-observability --track origin/feat/block3-resilience-observability`；如已有同名本地分支须先核对差异再切换，**不要从旧 main 新建同名分支**。
3. 仔细阅读 `AGENTS.md` → `docs/progress/block-3-resilience.md` → `docs/reviews/block-2-static-review-2026-11-06.md` → `docs/standards/test-console-ux-v2.md` → `docs/standards/frontend-sync-rule.md` → `docs/guides/test-console-guide.md` → `docs/guides/block3-fault-testing.md`。若内容冲突，本块新任务书优先，实际运行情况高于旧状态记录。
4. 核查现有 Java17/Boot4/Gateway5.0.4、Control MyBatis、Redis、启动脚本与测试前端。不要额外引入 WebMVC Gateway、Sentinel、Kafka、Nacos、Redisson、Node 构建链或生产监控堆栈。

## 第二部分：按顺序编码，不自由扩展业务

**B3-00：先完成上块审核补修**
- 已有策略编辑页中目标选择与真正绑定不一致：最小修正显示/禁用/提示，使用户不被误导；对应 JS 定向测试。
- `SnapshotPollingService` 中“取消→异步缓存恢复→下一版本应用”存在静态竞态窗口：加可控交错 JUnit；若复现则最小串行化修复。验证路由缓存与 `applied` 同版、迟到事件不倒退；记录真实结果并单独 `fix(block2)` 提交。
- 若 Block2 缓存无法确定安全，明确报告阻塞、停止新 CB 代码。

**B3-01：Control + Contract**
- 仅复用 `fg_policy/fg_policy_binding` 和原管理 API，允许 TIMEOUT `{"timeoutMs":...}`、CIRCUIT_BREAKER 现有四字段及 GET-only 可选 `fallbackMode`。
- 新发布 `ENFORCED_BLOCK3`，历史 `PENDING_BLOCK3` 不执行；Route metadata 只从同版 Snapshot 生成；发布/回滚 SHA 与 CAS 完全保留。
- 严格按任务书参数范围、scope、重复规则、路由 HTTP 方法校验，不加表、不改写历史发布内容。

**B3-02：Gateway Timeout**
- 使用 Gateway 官方 per-route response-timeout metadata（毫秒），受控 connect-timeout；网关主动超时 504 `UPSTREAM_TIMEOUT` 与下游原始 5xx 区分，终止时原并发许可释放。
- 不是用浏览器 fetch 超时伪装网关超时；先用真实模块定向测试确认配置键与异常映射。

**B3-03：CircuitBreaker + fallback**
- 只为 Gateway 引入 Spring Cloud BOM 管理的 `spring-cloud-starter-circuitbreaker-reactor-resilience4j`。使用官方 `CircuitBreaker` GatewayFilter Factory 与动态配置的 Resilience4j 实例，不自写 Closed/Open/HalfOpen 状态机。
- 稳定 `policyId+revision+routeId` breaker 名与已匹配 Route 同版规则，配置变化换实例，无关发布不重置；测试 500/503 触发、OPEN 503 短路不达 Demo、HALF_OPEN 两次试探、恢复 CLOSED。
- 静态降级只允许 GET/HEAD，固定内部 `forward:` fallback，响应 503 + `degraded:true`（不是假成功），不能绕开原鉴权/限流，也不能公开一个可被直接访问的内部 fallback 管理路由。
- 指标低基数，节点应用与在线/迟滞真实可辨。禁止每请求到 Control/MySQL 查询。

**B3-04：演示+恢复**
- Demo 增加仅 loopback/显式实验开启的受控 `GET /demo/fault?mode=ok|500|503|slow`，延迟 <=2000ms 与真实到达计数；原 fast/echo/slow 保持可用。
- Control 停止/恢复、Gateway 重启无可信快照、Redis 停止/恢复，按任务书确定性验证；不污染已有数据库、Docker 卷和端口。

**B3-05：前端与中文说明**
- 保留 Block0/1/2 全部按钮和能力，修正已有策略编辑误导；新增“故障保护”页签，参数有中文说明、范围校验、保存草稿→发布→节点实际应用→演示 504/503→半开恢复/指标的真实按钮。
- 用户指出前端曾太难使用，务必保持五步入门、原生 HTML/CSS/JS、真实错误就地显示与可读帮助；修改 `docs/guides/test-console-guide.md` 和 `docs/guides/block3-fault-testing.md`，不能仅在 README 附一行 URL。
- 真实浏览器操作需负责人手动进行，Codex 不自行操作用户电脑；自动 Node 契约测试和 HTTP 冒烟仍须执行，不能混淆两者。

**B3-06：测试与 Git 交付**
- 仅新增/变更/直接受影响模块的 Maven、Node 和三 JVM+隔离 MySQL/Redis 定向测试。重点 fail-close/无绕过、Route 版本竞态、半开、504、取消释放、历史 PENDING、Web UX 不伪造结果。
- 每一步同步前端、阶段进度、README；关键 Java/JS/Reactor 逻辑中文注释，长流程按 `// 1.`、`// 2.`、`// 3.`，避免重复分层和引入非必要技术。
- Conventional Commits：修旧块 `fix(block2): ...`、新功能 `feat(block3): ...`、测试 `test(block3): ...`、文档 `docs(block3): ...`。仅提交到 `feat/block3-resilience-observability`，禁止改/合并/强推 main，禁止擅自进入最终验收块。

## 第三部分：交付时务必填写

1. 实际修改模块/文件和新增依赖、与实施方案的最小差异及理由。
2. 本块定向测试的**真实命令、通过/失败/跳过数字、HTTP 响应、异常日志摘要**，失败仍保留记录；不要伪造 Maven、真实浏览器或最终性能验证。
3. Block2 R1/R2 收口状态与用例；已发布历史协议、GET fallback、安全边界及 Route 同版的证据。
4. 最新 `git status -sb`、分支名、完整提交 SHA、是否推送成功、PR/阻塞、前端/指南更新位置。
5. `docs/progress/block-3-resilience.md` 第11节按实际进度回填。未运行写“未运行”，无法复现写“存在风险”，不要写“已验收”。
6. 停止在功能块 3，等待我审核；不要合并 main 或启动最终统一验收。

**你主要负责严格执行编码和定向验证，不自行重新规划产品方向。**