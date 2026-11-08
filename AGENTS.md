# FlowGate｜当前本地Codex唯一开发入口（V11-B本地实现完成，待复审）

> 2026-11-07 负责人明确授权：**把V11-A遗留两项P0放在V11-B第一阶段一并修复，并开始B设计。**
> 2026-11-08：B0～B6已在`feat/v11-b-quota`完成本地实现和定向验证（Java57项、5套Node、双网关真实HTTP20组及A兼容155条）；详见进度B-16/B-17及脱敏证据。当前等待ChatGPT与负责人复审；本地通过不代表远端推送成功、人工验收或可合并。
> 目标分支：`feat/v11-b-quota`，基线是`feat/v11-a-business`（A完整源码及审查，PR #7 Draft）；当前B单独Draft PR，旧PR #1–#7与main均不得擅自合并/改base。

## 当前唯一读取入口

1. `docs/design/v11-b-quota-execution.md`：**B权威详细设计**，同月套餐、数据库UTC、独立App Token、历史版本与节点门禁、MySQL唯一账本、Gateway执行顺序、失败处理和验收。
2. `docs/progress/v11-b-quota.md` 第 B-07～B-15 节：B0～B6逐提交可执行任务、文件/接口、每步停止门槛。
3. `CODEX_V11_B_EXEC_PROMPT.md`：本地Codex阶段执行提示。旧`CODEX_V11_A_REVIEW_FIX_PROMPT.md`仅是**历史A审核任务**，**现在由B0代替执行，不要单独切回A修复**。
4. 其他历史参考：`docs/reviews/v11-a-static-review-2026-11-07.md`、`docs/design/openapi-platform-v1.1.md`第12节、`docs/progress/v11-a-business.md` A-08/A-09及真实原V1源码。

## 本轮开发顺序

- **B0 P0阻断**：先修`SubscriptionSchedule`同UTC月禁止第二次套餐选择；使Hikari MySQL业务连接统一会话UTC并验证`Instant`读写。独立Java+隔离MySQL真实测试，B0不通过**禁止启动B1**。
- **B1**：USAGE_QUOTA策略独立元数据/执行标识、可信可接流节点能力门禁。发布功能开关默认OFF，**只在B4完整计量链路通过后才允许开启**。旧V1快照和旧路由不回归。
- **B2**：`fgapp_`+43字节符Token、`fg:auth:app:`独立Redis命名空间、DB `APP_CALLER`主体、持久generation防撤销漏洞；先开发内部状态与受控测试，**对外发证保持A阶段409**直到B3+B4全套安全闭环完成。
- **B3**：MySQL余额/流水/Outbox同事务、历史`appliedVersion`重验、chargeId重复/跨UTC月幂等、quota内部API；无Kafka。
- **B4**：Gateway Reactor WebClient非阻塞预准入、真实组合Filter顺序与CB OPEN测试、被计量Fail-Closed、未计量不依赖Control；此后才准许B6隔离环境激活App Token和Quota发布。
- **B5/B6**：`/console/`凭证、Quota、账本及真实规则/发布界面，中文指南；Java17定向、Node旧新模块、MySQL8.4/Redis7+两Gateway真HTTP并发、故障注入、脱敏证据及进度记录。阶段完成后交ChatGPT复审，不自行进入C/D/Kafka与长期性能验收。

## 长期安全与工程底线

所有工作基于独立Git功能分支、小步Conventional Commits、中文关键注释`// 1.` `// 2.` `// 3.`。保留Java17/Boot4/Gateway WebFlux/Control MVC+MyBatis/Redis7/MySQL8.4/原生ES modules，**不新增Kafka、Nacos、Seata、Redis分布式锁、新JS框架**。APP Token专用命名空间，不能被旧Gateway/旧Caller路径识别；配额余额以MySQL权威，不从Redis缓存推导；成功Grant即消耗，失败不冒充成功业务调用。

保护用户未提交资产、IDE、MySQL/Redis原有持久化容器、`.env`、现有分支及旧证据；禁止`reset --hard`、`clean -fd`、`docker prune`、强推、直接合并main。Java/Node/真HTTP没有实际执行的测试必须记“未执行”；V1负责人真人浏览器验收与旧PR正式收口仍是后续交付门槛。

**B0已先独立通过，随后B1～B6按门禁实施。现在停在B复审；只处理明确授权的审核修复，不自行重跑所有阶段、不进入C/D/Kafka、不合并。用户持久数据库未迁移、计量开关未自动打开，真人浏览器验收仍待负责人。**
