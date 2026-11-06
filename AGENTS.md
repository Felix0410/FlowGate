# FlowGate｜本地 Codex 仓库协作入口

> 当前阶段：**功能块 3（已授权编码，尚未实现）**；本文件指向唯一当前任务，不代替实际进度/测试事实。

## 现在应该执行什么

**远端分支已存在**：`feat/block3-resilience-observability`；建立基线时来自 `feat/block2-traffic-governance`，不是落后的 main。检查 `git status -sb`、`git fetch origin`，安全检出并跟踪已有功能分支；如同名本地分支或未提交修改存在，应先核对与保护，严禁 `reset --hard`、`clean -fd`、强推、直接合并 main 或自行变更 Git 凭据。

**按以下顺序阅读**：
1. [CODEX_BLOCK3_PROMPT.md](CODEX_BLOCK3_PROMPT.md)：直接执行的步骤、代码与 Git 约束。
2. [docs/progress/block-3-resilience.md](docs/progress/block-3-resilience.md)：**本块最高优先级的完整业务/API/安全/测试契约**，第 0 节先修上块遗留。
3. [docs/reviews/block-2-static-review-2026-11-06.md](docs/reviews/block-2-static-review-2026-11-06.md)：需要完成的 R1 已有策略绑定目标 UX 修复、R2 路由缓存取消/恢复竞态定向复测。
4. [docs/standards/test-console-ux-v2.md](docs/standards/test-console-ux-v2.md) 与 [docs/standards/frontend-sync-rule.md](docs/standards/frontend-sync-rule.md)：保留 Block0/1/2 全部已有功能、测试台与后端逐步同步。
5. [docs/guides/test-console-guide.md](docs/guides/test-console-guide.md) 与 [docs/guides/block3-fault-testing.md](docs/guides/block3-fault-testing.md)：当前页面使用说明和新故障演示的待实施场景。

## 代码必须遵守

1. **先执行 B3-00**：在本块 feature 分支独立修复/验证 Block2 R1/R2，记录 `fix(block2)` 提交。路由缓存与 `store.applied` 如无法保证一致，停止接入 CB 并报告。
2. 仅扩展现有 Java17、Boot4、Spring Cloud Gateway WebFlux、MyBatis-Plus、Redis、Micrometer；熔断只允许引入必要的 **Reactor Resilience4j CircuitBreaker Starter** 与 Gateway 官方路由 Filter，不自写状态机、不引 Kafka/Nacos/Sentinel/Redisson、不要额外建微服务/表。
3. TIMEOUT 沿用 `timeoutMs`，CIRCUIT_BREAKER 沿用已有四参数并只允许 GET/HEAD 可选静态降级；Block1/2 已发布快照 PENDING 不因升级自动启用；同版路由认证→准入→超时/熔断→代理→释放许可。
4. Gateway 热路径不得使用 JDBC、Servlet、同步 Redis、阻塞 sleep 或每请求访问 Control；Actuator 继续本机只读。所有错误返回实际 401/403/429/503/504，不把受保护路由降级为伪造 HTTP200。
5. 每完成一小步新功能立即同步 `/test-console/` 原生 HTML/CSS/JS 操作和中文说明，**旧功能不能退化**；用户对可用性提出过问题，UI 不是可跳过的附属物。
6. 代码关键路径有准确中文注释，长 Java/JavaScript 按 `// 1.`、`// 2.`、`// 3.` 分点；不得堆无用抽象层。只运行新增、修改、直接受影响部分的定向测试与一条真实三 JVM 故障联调；最终全量回归/正式压测属于下一阶段。
7. 每次提交遵循 Conventional Commits。不得提交 `.env`、密码/Token/内部 Key、日志、构建输出，不能破坏用户已有 Docker/数据库数据。碰到推送 403，记录实际错误、保留本地提交与 SHA，**不虚报已推送**。
8. 在 `docs/progress/block-3-resilience.md` 第 11 节填写真实进度、测试命令/结果、SHA 和已知问题；同步 README、前端状态、`docs/guides/`。负责人真实浏览器操作暂未委托给 Codex；不要冒称人工体验通过。

**完成目标**：仅 Block3 编码+定向验证+更新前端/指南+功能分支提交，等待 ChatGPT/负责人复审；不合并 main、不开始最终验收。