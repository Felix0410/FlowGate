# FlowGate V1 产品管理后台｜本地 Codex 立即执行任务

> **当前最高优先级**：负责人不接受“只有开发者能看懂的测试前端”，要求在 V1 完成前真正提供一个产品化管理后台。
> **这不是单纯换主题**。必须产出可使用的服务/API/路由/策略/发布/监控/调用方管理后台，具有清晰列表、创建/编辑流程、空状态和权限反馈。
> 本次仓库已在 `test/block4-final-acceptance` 提交**详细权威合同**：`docs/design/product-console-v1.md`。你主要负责编码，不重新做开放式产品/技术选型。

## 先处理 Git 基线

1. `pwd; git status -sb; git log -1 --oneline; git remote -v`，保护所有本地未提交修改，不 `reset --hard`、`clean -fd`、强推、改凭据/主线。
2. `git fetch origin`，安全检出远端已有的 `test/block4-final-acceptance`。若本地存在同名分支，先核对 SHAs/工作区，绝不能直接覆盖工作成果。
3. 按顺序完整阅读 `docs/design/product-console-v1.md`、`docs/progress/block-4-acceptance.md`、`docs/standards/frontend-sync-rule.md`、`docs/guides/test-console-guide.md`、当前 `flowgate-control/src/main/resources/static/test-console/index.html, app.js, style.css`、`CatalogController/ReleaseController`、登录/权限 Controller、相关服务实体和 RBAC 代码。对照真实接口而不是猜测。

## 开发顺序：PC-00..PC-06，必须做完产品流程

**PC-00 业务合同核对**：清点 API 和角色能力，记录不会做的事（例如后端无 DELETE，不展示删除；已有策略没有绑定查询时必须增最小只读 GET 或标明不能查）。保护原 `/test-console/` 全部开发/验收能力。

**PC-01 产品壳与工作台**：新增 `/console/`（跟随 Control 静态资源部署）。登录前是简洁登录页，登录后左侧导航+顶部状态+真实概览卡。显示真实服务/API/路由/策略数量、Control/MySQL/Redis/Demo状态、当前发布版本/各Gateway节点应用版本，失败显示未知/重新读取。无硬编码 KPI/“已生效”。

**PC-02 服务/API/路由管理**：三张真实数据表、筛选/空状态/编辑。新建和修改都使用完整中文表单（不要求用户填原始JSON、route DB数字ID），关联服务和 API 通过已有 `GET /admin/*` 选项；更新时必须完整回填再PUT，不能覆盖用户没编辑的字段。保存仅代表草稿，提示“前往发布中心”。

**PC-03 四类治理规则**：列表 + 按 RATE_LIMIT/CONCURRENCY/TIMEOUT/CIRCUIT_BREAKER 显示必需参数、业务解释与安全范围。用户先选治理目标，再保存；已存在规则不能用伪装目标替换的下拉框。若需要实际查询绑定，最小加 `GET /admin/policies/{id}/bindings`，继承已有RBAC/MyBatis和数据库绑定关系，校验返回字段且不用新增表；对应Java定向测试。条件不成熟不要新增 DELETE/PUT绑定API。

**PC-04 发布/调用方/监控**：发布中心读取真实 current.version，提供发布确认、409冲突恢复提示、历史版本和回滚二次确认，显示节点 APPLIED/STALE。Caller仅ADMIN可创建/吊销，Token一次性显示且仅驻留JS内存。监控/审计无样本时解释“暂无/未取得”，不造假图表；开发故障/压测留旧 `/test-console/`，只从底部“开发工具”链接访问。

**PC-05 视觉和可用性打磨**：用设计合同中的token和页面结构完成看起来真正成熟的管理后台：深色左侧栏、64px浅色顶部、白色业务卡、表格、详情抽屉/对话框、明确主/次/危险按钮、微文案、反馈/错误、空状态、搜索、移动端折叠菜单。桌面1280px及宽度375px均可用。键盘、焦点、Escape/模态、输入label、角色禁用提示可靠；不要用炫动画/伪造状态替代信息质量。

**PC-06 测试、使用指南与提交**：
1. 编写 Node 内置/原生 DOM 的产品控制台定向测试：真实 API 路径/Body、各角色权限、完整PUT回填、保存草稿/发布/409/回滚、Token仅内存、状态未知/网络错误、对旧入口没有回归；测试必须针对真实产品JS，不只查文件文字存在。
2. 用项目最小本机 Control+MySQL/Redis+Gateway HTTP 路径确认 `/console/` 及静态资源返回200、真实登录/列表/新增/发布可请求；失败要留记录。
3. 继续运行 `node scripts/block2-console-test.js`、`node scripts/block3-console-test.js`，必要时运行受变更 Java Controller 直接影响的定向 Maven 测试；**此轮不做Block4全部长压测**，要等产品页完成可复核后再进入。
4. 写 `docs/guides/product-console-guide.md`（面向非开发者，只讲登录、创建API、保存、发布、策略、Caller、监控、常见问题；避免14章字段字典），更新 README 以 `/console/` 为主入口，标明旧页仅开发工具。
5. 每一阶段用 Conventional Commits 如 `feat(console): add product shell and dashboard`、`feat(console): implement catalog and policy workflows`、`test(console): cover management flows`、`docs(console): publish user guide`，全部提交到当前 Block4 分支，更新 `docs/progress/block-4-acceptance.md` 和 `docs/design/product-console-v1.md` 的真实实施状态、SHA、测试结果；不合并 main。
6. 完成后停止等待 ChatGPT 复审与负责人真实浏览器体验，不凭 Node 测试宣称真人体验完成；发现问题只修当前分支。

## 固定工程与安全边界

- 保留 Java17、Boot4、Control MVC/MyBatis、Gateway WebFlux、MySQL/Redis；**产品化不需要引入Vue/React/npm/Vite/Tailwind/新服务/数据库**。原生 ES modules+CSS 足够，若遇到无法合理实施的具体瓶颈需报告事实而不是自行上新栈。
- 新 `/console/` 与旧 `/test-console/` 都是本机管理入口，不向公网开放；所有管理请求使用既有 `/admin/**`，浏览器绝不调用 `/internal/**`、不处理 `FLOWGATE_INTERNAL_KEY`；Token/密码只在当前内存和必要密码框，不写cookie/localStorage/sessionStorage/URL/query/log。
- Gateway的认证/授权/CAS/checksum/准入/熔断语义不可从JS本地推演生效；**只展示实际API与节点回报**，不能为视觉美观造“已生效”“在线”等状态。
- 核心JS/Java重要流程中文注释，长代码按 `// 1.`、`// 2.`、`// 3.` 分点；避免无意义多层抽象与重复CRUD。
- 不覆盖用户本地.env、数据库/容器或IDE服务，不改main，不伪造测试与提交/推送成功。

**验收口径**：没有源码知识的操作员能在新 `/console/` 用中文表单完整完成“登录→新建/编辑API/路由→策略→发布→节点生效→监控”，且不用进旧HTTP测试台完成业务管理；旧测试台只用于开发和验收实验。
