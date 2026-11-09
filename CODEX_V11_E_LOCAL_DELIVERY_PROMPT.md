# Codex 执行入口：FlowGate V1.1-E 本地完整交付收尾

> **负责人已授权本轮提交“收尾任务设计”；此文件是等待本地 Codex 执行的编码合同，并不表示本轮已实现任何新脚本/前端。**
> 唯一权威任务：`docs/progress/v11-e-local-delivery.md`。必须全文阅读；旧 `CODEX_CONSOLE_WORKBENCH_UX_PROMPT.md`、C修复合同和 `docs/progress/v11-d-acceptance.md` 是背景，不是本轮待办。

## 0. 任务锁定

目标是让已经完成的 V11-B/C 从“隔离测试过的功能代码”成为**可诊断、可隔离一键启动、可手动完成真实业务链路**的本地产品。不要重写既有网关/账本/Kafka功能，也不要推进 D 的 SQL 10万、性能对照和全故障压测。

GitHub基线：`docs/v11-e-local-delivery-closeout`，其父分支是已完成新工作台的 `feat/v11-console-workbench`。在本地已有改动时，先只检查 `git status -sb` 并保护用户工作；不得 hard reset、clean、覆盖未跟踪文件。建议创建 `feat/v11-e-local-delivery` 开发分支并推送为新的 Draft PR；不修改/合并 main、PR #1～#11 或旧功能分支。

## 1. 必读顺序（不要让 AI 重新选型）

1. `AGENTS.md`（优先遵守其中**本轮V11-E**新增任务栏；C和UX旧条目属于历史）。
2. `docs/progress/v11-e-local-delivery.md`（E-00、E-01、E0→E6，全章是验收合同）。
3. `README.md` 中的本地启动、B部署、C-Kafka约束。
4. `.env.example`、`scripts/run-local.py`、`compose.local.yml`、`sql/flowgate-v11.sql`、`sql/flowgate-v11-c.sql`、`sql/v11-b-upgrade-preflight.sql`。
5. `flowgate-control/src/main/java/com/flowgate/control/service/QuotaActivationService.java`、`AppCredentialController.java`、`ReleaseService.java`、异步服务与状态API；对应Gateway快照及Quota实现。
6. `flowgate-control/src/main/resources/static/console/` 的 `app.js`、`operations.js`、`openapi-business.js`、`quota.js`、`usage.js` 与原指南 `docs/guides/product-console-guide.md`。
7. `docs/progress/console-workbench-ux-rework.md`、`docs/progress/v11-d-acceptance.md`（区分现有测试事实和未实施的 D）。

## 2. 必须按此顺序实现（每项完毕一次提交）

- **E0：审计 / 安全盘点。** 不运行数据写操作。列清真实B/C激活条件、当前命令契约、原V1回归保护点，创建实施记录。禁止自动关闭安全检查。
- **E1：`scripts/v11-local.py doctor [--json]`。** 只读检查、三态+明确原因、严格的退出码、错误与敏感字段脱敏、无权限只能UNKNOWN。用测试夹具覆盖阻塞/就绪。
- **E2：`scripts/v11-local.py up --isolated`、`status --isolated`、`stop --isolated`。** 新Compose项目/新数据库/新卷，端口与进程归属检查；只迁移新库，先安全预检，Kafka协议真实就绪才开异步，计量开关仅显式隔离演示下启用。保留根.env、用户容器卷与业务数据；失败不删卷。先发布非计量版本并取得节点能力，不能直接先签发。
- **E3：工作台状态。** 优先复用既有状态API/组件；把“功能不可用”改成明确可执行原因；不能加广告文案、大段欢迎词或第八个一级菜单。敏感信息与角色检查不回归。
- **E4：独立库B/C闭环。** 客户→应用→Free5→当月订阅→V1非计量基线发布/节点能力→fgapp凭证→私有Quota1→计量发布→5个200、第6个429→余额Ledger/Outbox→Kafka日报；正确处理今日不封账，绝不虚构当天MATCH。
- **E5：真实回归/证据/指南。** 执行任务书要求的Java/Node/Chromium/真实HTTP/doctor及旧V1回归；所有退出码/成功数要来自实际运行。生成真实JSON脱敏证据与 `docs/guides/v11-local-delivery.md`，完成指南HTML同步与README入口更新。
- **E6：停止，申请人工验收。** 不把Codex自己的浏览器检查写成用户真人通过；负责人之后才勾选 E6。不可自行进入D阶段。

## 3. 安全与技术细则

- Java17/SpringBoot4、Python3标准库、现有MySQL8/Redis7/Kafka4.1.1、原生JS；禁止新增新中间件、微服务、npm框架、无关UI包装。
- **默认完全不碰用户常驻环境**：不要 `down -v`、删除卷、修改原 `.env`、强制重启用户IDE进程、重置密码、修改真实Ledger、假报Kafka-ready、用任意admin API操作原库。
- 只对明确由E脚本新建的隔离库允许自动 V11-A/B/C 增量迁移，严格先检查是否新库；对现有数据库只能先只读 preflight，任何已有库的迁移/破坏性清理需要独立负责人授权，本轮不要执行。
- Token不得出现在HTML持久存储、终端命令回显、测试日志、进度文件或Git。Java/较长脚本有中文编号注释，禁止直接把堆栈与敏感响应展示给用户。
- 测试报告里 `PERSISTED` ≠ 节点`applied`，Kafka `SENT` ≠已消费，异步日报 ≠MySQL余额，GET公开 ≠带配额私有，业务调用失败也不会自动退款。
- 每轮先保留受影响模块最小真实测试，E5补全；不能为了“通过”删除旧测试、修改宽松断言或跳过可用性验证。

## 4. 汇报格式（只有实测后填写）

```
E0-E5完成情况（逐条：完成/阻塞/未运行）：
分支与每阶段commit SHA：
更改文件清单：
Java17/Maven 命令与实际退出码、测试总数：
Node/Chromium 命令与实际退出码：
独立Compose项目名、数据卷身份、端口、停止后是否保留卷：
B真实5成功+1限额、Balance/Ledger/Outbox一致性证据：
C真实投递/消费/日报/封账规则：
doctor 未就绪提示与泄漏/越权检查：
用户本机环境和.env未修改的可验证事实：
已知风险、未测试项、Draft PR链接：
是否到达E6人工验收门槛：待负责人手测
```

完成编码/定向验证后停下来，不申请合并、不开展D。若远端推送遇到403，清楚报告本地真实提交与推送失败，绝不声称GitHub已有提交。
