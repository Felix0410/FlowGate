# FlowGate｜本地Codex唯一当前任务（2026-11-07）

> 当前V11-A全部业务代码、产品页面及Codex定向测试**已真实提交远端**。PR #7 `feat/v11-a-business` → `docs/v1.1-openapi-platform`，仍是Draft；main不合并。**目前唯一开发工作是修复A的两项审核P0边界并补真实MySQL测试，不得开始B/C/D。**

## 立即阅读
1. `docs/reviews/v11-a-static-review-2026-11-07.md`：远端真实代码审查，确认历史17 JUnit/155条HTTP、P0-A01和P0-A02；
2. `CODEX_V11_A_REVIEW_FIX_PROMPT.md`：本地唯一可执行修复任务F0～F4；
3. `docs/progress/v11-a-business.md` 第A-08/A-09节：历史已交付与当前未解决项；`docs/design/openapi-platform-v1.1.md`第12节确保A不会越界；
4. `CODEX_V11_A_EXEC_PROMPT.md`是之前A0～A6的历史开发合同，**不要重新编码全部A**。

## 本轮目标
- **F1：同月套餐冻结**。`SubscriptionSchedule.validate`不允许同一app本月已过期的Free订阅后又新增本月Pro订阅，即使两个区间不重叠；下一UTC月首日正常衔接。补JUnit和MySQL真实HTTP反例。
- **F2：连接池UTC保证**。`connectionTimeZone=UTC`不等于MySQL会话UTC。以受控连接初始化/等价正确方案保障全部业务SQL会话UTC，测试MySQL/JVM非UTC组合及月末`Instant`回读，保护旧V1数据格式。
- **F3：如有精力改进产品日期选择**：本月/下月首日简洁选项；不得删旧`/console/`与`/test-console/`。
- **F4：定向17+新增JUnit、Node、独立MySQL/Redis、155旧HTTP所涉范围重新验证**，新证据单独保存，旧历史原样保留。追加SHA/命令/通过数/失败数到A进度；只修改当前A分支，提交Conventional Commits。
- **之后停止并报告**。V1负责人真实浏览器验收仍待完成；A修复完成后需复审，B/C/D另行安排。

## 安全和技术长期约束
Java17/Spring Boot4/Control MVC + MyBatis-Plus/MySQL8.4/Redis7，产品前端原生HTML/CSS/ES Modules。新业务管理写仅ADMIN；A凭证接口保持`409 FEATURE_NOT_READY`，**任何APP有效Token、USAGE_QUOTA、Gateway改动、QuotaBalance/Ledger/Outbox/Kafka均不能提前出现**。原Caller权限/旧快照完全兼容。全部业务关键代码中文注释，长逻辑按1/2/3分条。

保护用户的未提交内容、IDE进程、Docker持久卷及`.env`；不`reset --hard`、不`git clean -fd`、不全局Docker清理、不强推、不合并任何PR/main。不把Codex的历史push403当成目前远端没有源码，也不把本轮文档提交当成两项P0实际已修。
