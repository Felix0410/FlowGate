# V1.1 工作台UX重构实施

基线：设计分支 `docs/v11-console-workbench-redesign` / `3f5ea8d`；原实现 `a02e68a`。工作分支 `feat/v11-console-workbench`。当前合同 `CODEX_CONSOLE_WORKBENCH_UX_PROMPT.md` 和 `docs/design/v11-console-workbench-ux-rework.md`。

## UX-0：契约及完整能力映射

已阅读全部console模块、现有7个Node入口、业务手册、前后端同步规范及新设计。旧20项能力按下表完整保留；新分组不改变API、RBAC或写事务。

| 一级菜单 | 页内入口（旧key） |
| --- | --- |
| 工作台 | 概览 dashboard |
| API接入 | 服务 services、API apis、路由 routes、旧调用方凭证 callers |
| 客户业务 | 客户 developers、应用 apps、套餐 plans、订阅 subscriptions |
| 治理策略 | 限速 rate、并发 concurrency、故障保护 protection、计量规则 quota-policy |
| 用量与对账 | 余额与账本 quota、用量统计 usage、对账结果 reconciliation |
| 发布中心 | 当前版本/发布/历史/节点应用 releases |
| 运行监控 | 网关节点 monitoring、异步事件 async-events、操作审计 audits |

不支持的能力不伪造：逐对象发布差异、全量旧Caller列表、历史Token查询、绑定移动/删除、订阅任意区间修改、套餐额度编辑、未完整读取的关联集合统计。目录完整集合可本地过滤；客户关联应用、订阅按服务端游标分页。

安全：URL仅白名单业务ID/筛选；不放凭证/账号密码/完整响应。APP和旧Caller不混用；敏感回执离页销毁。MySQL余额、异步用量、对账三种事实独立。真实HTTP仅隔离Lab，浏览器夹具与真人验收分开。

本阶段只读审计与任务映射；未运行Java/浏览器/HTTP，不记通过。`git diff --check`通过。

## 阶段进度

- UX-0：契约与映射完成；提交SHA见本文件Git历史。
- UX-1～UX-5：待实施，禁止将设计或旧测试当成本次通过。

## UX-1：业务外壳

7个一级业务域及20个页内入口已接入；旧Caller只在API接入，APP凭证仍在应用业务。深色高对比侧栏、独立滚动正文、小屏抽屉和横向二级导航沿用原生控件；登录缩减为账号与操作区。`navigation.js`集中维护映射及后续路由字段白名单，不增加后端。

修改app/index/style/navigation及产品Node适配。`node scripts/product-console-test.mjs`退出0，`git diff --check`通过。Java/真HTTP/1280与375浏览器留UX-5，尚未运行本阶段浏览器，不标体验通过。
