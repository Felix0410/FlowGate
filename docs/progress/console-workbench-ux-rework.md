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

## UX-2：对象详情与业务上下文

服务详情使用完整目录确认API，API详情确认关联路由；从详情新建时预选服务/API。客户详情按正式API分页读取客户应用，应用详情含独立APP凭证/订阅/配额与用量分区，新增应用/订阅预选来源对象。旧Caller仍在API接入。列表名称/查看进入详情，编辑移到详情；套餐额度/订阅区间限制不变。业务列表保存实际游标与筛选，返回后重新读取同页，不用旧行冒充当前结果。

修改catalog/openapi-business/ui、对应旧产品/A测试，并新增workbench-console-test。`node scripts/workbench-console-test.mjs`、`product-console-test.mjs`、`v11-b-console-test.mjs`、`v11-c-review-console-test.mjs`退出0；`git diff --check`通过。旧A断言从不再准确的固定“尚未启用”改为实际保存反馈，权限断言移到真实详情编辑按钮，没有取消RBAC检查。Java/真实HTTP/浏览器留UX-5；尚未负责人验收。

## UX-3：策略与共享查询

策略统一搜索/草稿状态筛选/单主操作；从目录详情携带目标后只按正式绑定筛选，读取失败明确未知，新建预选可支持的真实目标，既有绑定不可移动。故障保护通过规则类型选择保留超时与熔断。规则参数的实质帮助保留，高级原理折叠。

余额/用量/对账共用内存中的应用与UTC日期选择：月余额按起始月，显式改月才调整日报范围；同月读取不覆盖既有范围。对账截止昨日并明确说明调整；应用来源上下文自动查询。条件改变立即清旧结果，不冒充0或旧应用结果。异步操作只在运行监控分区，FAILED重试与SENT补投和确认/RBAC原样保留。发布三阶段仍使用真实版本与节点状态。

新增report-context并修改governance/quota/usage/navigation及测试。工作台、B、C复审Node退出0；新增跨月/月契约/共享选择断言通过；`git diff --check`通过。Java/真HTTP/浏览器在UX-5实际执行。
