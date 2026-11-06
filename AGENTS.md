# FlowGate 本地 Codex 协作入口

> 本文件供本地编码助手自动发现任务使用；只约束 FlowGate 仓库当前已授权的功能块，不代表对应功能已完成。

## 当前唯一可执行任务

- 当前阶段：**功能块 2：分布式限速、本机并发保护与热点隔离**。
- 任务分支：`feat/block2-traffic-governance`，从块 1 的 R1 修复提交 `6a3e038` 创建，并合入设计文档。负责人于 2026-11-06 明确授权开始；先检查工作区，禁止改 main 或重写共享历史。
- **先完整阅读**：[本块实施契约](docs/progress/block-2-traffic.md)、[前端同步规范](docs/standards/frontend-sync-rule.md)、[测试台体验规范](docs/standards/test-console-ux-v2.md)、[可执行任务提示词](CODEX_BLOCK2_PROMPT.md)；必要时参照 README、[块 0 进度记录](docs/progress/block-0-gateway.md) 和历史审计。
- 优先级：本块任务书 > 前端同步规范（有关前端同步时两者须同时满足） > 本交接入口 > README 的旧历史说明。实际代码/API 与文档不符时进行**最小安全修正**并记录理由。

## 编码与交付硬性约束

1. **只实施本块**，不要启动块 3 的熔断、降级或自适应控制；不擅自引入 Kafka、Redisson、Nacos、复杂前端框架等。
2. Gateway 保持 WebFlux/Netty 非阻塞：禁止 JDBC、Servlet、同步 Redis/Redisson 进入热路径；Control 可以使用 MVC、MyBatis-Plus、同步 Redis。
3. 核心类、方法与关键异常流程使用准确中文注释；较长代码块采用 `// 1. ...`、`// 2. ...`、`// 3. ...` 的业务阶段说明。
4. 必须实现并持续维护同源 `/test-console/` 测试页面：原生 HTML/CSS/JS，保留块 0 的 fast/echo/slow 测试，保留认证、路由管理和发布回滚，新增当前块的策略向导与真实429/503测试，不伪造功能完成。
5. 每次修改只执行新增、修改、直接受影响代码的定向测试及最小联调；完整回归和性能压测留到最终验收。负责人手动测试延后到本块最小可执行 Demo 完成。
6. 在 `docs/progress/block-2-traffic.md` 第 12 节回填真实进度、测试命令、结果、SHA、已知风险；同步 README 与测试前端功能状态。
7. 遵循 Conventional Commits，不提交密码/Token、`.env`、构建产物；只在本块分支提交，不合并/强推 main。若 Git 或依赖环境阻断，记录实际错误并停在本块，绝不虚报完成。

**开始编码**：阅读 `CODEX_BLOCK2_PROMPT.md`，按块2契约执行；用户禁止电脑界面控制，浏览器手动验收不得冒充已执行。
