# 交给本地 Codex 的执行提示词｜FlowGate 功能块 1

你是本地 FlowGate 仓库的**实现型编码助手**，不是架构规划助手。用户已经批准功能块 1 的详细实施契约。本轮只开发“控制面 + 认证 + 动态路由发布 + 简易前端”，绝不跨到功能块 2。

## 一、开始前（必须执行）

1. 进入 FlowGate 仓库，执行 `pwd`、`git status -sb`、`git remote -v`、`git log -1 --oneline`；保护用户本地未提交修改，不允许擅自 `reset --hard`、`clean -fd` 或覆盖现有变更。
2. 按顺序阅读 `README.md`、`docs/progress/block-0-gateway.md`、`docs/progress/block-1-control.md`、`docs/standards/frontend-sync-rule.md`，按当前功能块文档实施；审计历史只在必要时阅读 `docs/stage-0-audit.md`。
3. 功能块 0 已在 main 形成实际实现，负责人要到最小 Demo 才统一手动验收，不要因为缺人工验收而擅自退回或重新实现块 0。请先确认本地和远端实际 SHA/分支状态，避免误覆盖。
4. **本任务分支已在远端创建且包含完整文档**。执行 `git fetch origin`，再 `git switch -c feat/block1-control-publish --track origin/feat/block1-control-publish`；若同名本地分支已存在，先核对差异并安全切换/快进，不覆盖未提交工作。不要从 main 再创建同名分支。历史曾发生 `git push` HTTP 403；若重现，记录错误、保留本地提交和 SHA，绝不修改用户凭据或绕过规范直接写 main。

## 二、唯一业务任务

严格实现 `docs/progress/block-1-control.md` 第 1—10 节，包括：Control MVC、MySQL 数据表与配置草稿、BCrypt Admin 登录与角色权限、Redis opaque Admin/Caller Token、Caller Scope 与吊销、发布事务的版本 CAS/不可变快照/审计、Gateway 响应式快照校验与热更新、Route metadata 一致鉴权、回滚及节点应用状态。

**强制新增极简前端**：`/test-console/`，使用原生 HTML/CSS/JS，无 npm；本页既要能单独操作本块新功能，也必须真实展示已有 Block 0 的 fast GET、echo POST、slow GET、Trace ID/HTTP 状态/指标入口。每次开发任务完成以后更新前端功能标记与实际可用操作；不要等全部后端完成才发现页面落后。具体按 `docs/standards/frontend-sync-rule.md`。

**不允许自由改动**：Java 17、Boot 4.0.9、Cloud 2025.1.4；Gateway 不引 JDBC/Servlet/阻塞 Redis；不增 Kafka/Redisson/Nacos/OAuth2/复杂框架；不为前端引 Vue/React/Vite；不做块 2 的实际限流执行；不写静态后门路由绕过新鉴权；不宣称发布持久化等同节点生效。

**注释强制标准**：业务类、核心方法和复杂边界用准确中文注释；较长方法/流程必须按 `// 1. ...`、`// 2. ...`、`// 3. ...` 分步骤描述，必要时继续编号；不写机械逐行注释，不留下无关点评业务注释。

## 三、执行顺序与测试

按照功能块文件第 8 节的 8 步实施，不需要再输出长篇候选方案。每一步只运行新增、修改、直接受影响的单测和必要小冒烟；最后运行第 9 节定向集合以及最小三服务端到端演示，**不要在此阶段进行全项目统一回归和大型性能压测**。

对本机确实存在的 MySQL/Redis 执行真实联调，若服务不可用，记录具体阻塞与未执行项；绝不可将模拟结果写成实际成功。安全和一致性失败是本块阻断性问题，需要在本块修复。

完成开发后更新 `docs/progress/block-1-control.md` 第 12 节，填写**实测命令、通过数、失败项、实际 SHA、技术差异、页面完成状态、启动与演示步骤**；同步 README 当前状态与前端规范要求。旧文件和新的代码/SQL 必须保持契约一致。

## 四、Git 规范与交付边界

- 采用 `feat(block1): ...` / `feat(control): ...` / `feat(gateway): ...` / `feat(console): ...` / `test(block1): ...` / `docs(block1): ...` 等 Conventional Commits；一次提交只包含相关变更。
- 提交前检查 `git diff --check` 和 staged files，不将 `.env`、真实密码、Token、数据库数据、构建产物提交仓库。
- 只允许提交本块 feature 分支；如权限允许可推送该分支以供审查，但**不得合并 main、force push、改写已有提交、自动开发块 2**。
- 输出最终交付报告：变更清单、后端/前端实现完成对照、真实启动/测试命令与结果、关键安全边界、未解决事项、Git SHA/PR（确有才写）、第一次人工 Demo 路径。之后等待负责人/ChatGPT 复核。

## 五、冲突决策原则

当旧 README、旧审计、Block 0 早期任务书与当前任务书存在矛盾时，以**当前 `docs/progress/block-1-control.md` 和 `docs/standards/frontend-sync-rule.md`**为准；对框架实际 API 不兼容，只做最小可运行调整并记录。若 Git 凭据或本机服务阻断，保留所有已完成且可验证部分，不要虚报完成，也不要反复索要用户已明确给出的要求。