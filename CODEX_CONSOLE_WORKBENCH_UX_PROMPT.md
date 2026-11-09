# Codex 执行入口：FlowGate V1.1 工作台重构

此文档为任务分派，不是已完成证明。**先读取** `docs/design/v11-console-workbench-ux-rework.md` 全文，再按段落实。当前基线是 `feat/v11-console-unslop` 的 `a02e68a`，设计分支为 `docs/v11-console-workbench-redesign`。不改 `main`，不得合并既有 stacked PR，不提前执行 V11-D。

## 核心目标

用户在不看开发手册的情况下，用一个真正的中文管理工作台完成：

- API：服务 → API → 路由 → 规则 → 保存草稿 → 发布 → 节点确认。
- 客户：客户 → 应用 → 套餐/订阅 → APP凭证 → 配额账本 → 用量 → 对账。
- 运行：网关异常 → 异步事件诊断 → 安全重试/补投 → 操作审计。

改造必须解决“20个侧栏入口太散、导航区分度低、文案像广告/开发手册、跨页面不能连续操作”。

## 开发纪律

1. 先审阅旧 `console/` 全部模块、`docs/standards/frontend-sync-rule.md`、现有 `scripts/*console*test*`、`docs/guides/product-console-guide.md`，列旧20入口到新7业务入口/Tab的准确映射。不得删除一项能力。
2. 实施 `UX-0 → UX-5`。一个阶段完成后提交一次，建议 `refactor(console): ...`、`test(console): ...`，提交前均运行 `git diff --check`。只修改本次允许范围，不混入其他作者的本地修改。
3. 技术锁定：原生HTML/CSS/JS与Spring MVC静态资源；**不增加前端构建链**，不新增数据库/Redis/Kafka语义、Gateway路径、核心API，除非明确证明现有接口无法满足且得到批准。
4. 优先改 `app.js` 顶层导航、`style.css` 对比/页面结构，再改 `openapi-business.js`、`catalog.js`、`governance.js`、`quota.js`、`usage.js`、`operations.js`；复用真实API与RBAC、版本/节点事实、状态及错误处理。
5. 不造假：未读取≠0；草稿≠发布≠已应用；APP Token与Caller Token分离；Failed事件重试≠Sent事件补投；后台无差异预览/Caller全量查询时不能虚构。
6. 大段逻辑需按“1/2/3”中文编号注释；其余注释聚焦原因，避免无用口号。开发完成更新离线中文指南与项目进度，不留过时导航图。
7. 优先在新功能分支编码，base 从 `docs/v11-console-workbench-redesign` 或其完整快照分叉，保留设计分支独立。不得推 main、不得自动合并 PR 或宣称D已通过。
8. 保持用户真实数据库/.env/IDE运行进程不变。真实HTTP用独立沙箱；浏览器测试需要实际的 1280px + 375px，出具屏幕截图或明确“未进行”。

## 最低交付物

- 新的7项一级导航、易辨认高对比侧栏、业务域内二级Tab/对象详情、正常的浏览器前进与后退。
- 客户→应用→订阅→配额/用量的业务上下文跳转；一处条件不用每页重填。数据分页/查询严格遵循实际API。
- 登录、首页与业务页清除口号、长串流程说明和页脚重复宣传；保留实质的限制、危险操作与错误。
- 列表、详情、空/加载/失败、确认、权限禁用、恢复路径一致；不新增假的功能/图表。
- 新旧Node+Java定向测试，最小真实隔离HTTP链路，键盘、375/1280布局及权限检查；`/test-console/` 不回归。
- 更新 `docs/guides/product-console-guide.md`、`docs/progress/console-workbench-ux-rework.md` 记录SHA、实际命令、结果、未测项目、缺陷和负责人手工验收清单。

## 交付报告格式

每段完成后报告“阶段 / 修改文件 / 新菜单或流程 / 测试真实结果 / 没测的内容 / commit SHA”。只有负责人手测通过后才能标注“工作台体验通过”；不能用脚本模拟点击来替代真人验收。
