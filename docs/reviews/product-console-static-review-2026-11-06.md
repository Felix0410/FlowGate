# FlowGate V1 产品后台｜远端源码静态复审（2026-11-06）

> 审核分支：`test/block4-final-acceptance`，**产品实现提交基准** `075878747ea45590cefda41f6fcd185cc163afa2`；本轮检查原始远端分支提交 `2ac5998c2aa55b1a66efbbd012035474c6ac652b`，PR #5。
> 审核方式：逐个阅读新 `static/console/` ES modules、CSS/HTML、Control只读绑定GET API、RBAC、JUnit与Node测试源码、64条产品定向HTTP脱敏证据，抽查Block4全量和性能结果摘要。**ChatGPT本轮未在负责人机器重跑Maven/Docker，也没有打开真实浏览器/操作系统GUI。**
> **结论**：产品后台不再是仅设计或测试台换皮；登录、工作台、目录、规则、发布、Caller及监控均有实际业务代码。产品代码可进入负责人真实浏览器验收。**V1最终验收尚未通过**；存在产品可用性改进项和新版最终统一回归门槛，不应合并main。

## 1. 远端实际交付核查

| 目标 | 文件/依据 | 静态审核 |
|---|---|---|
| 登录与独立产品入口 | `static/console/index.html`、`ControlWebConfig` | `/console/`转发到真正产品页；未登录隐藏管理界面，主站`/`重定向产品入口 |
| 工作台、分组导航、状态 | `app.js` | 真实加载服务/API/路由/策略、持久化发布版本、节点/依赖，读取失败用未知而非假0 |
| 真实目录完整CRUD | `catalog.js` | 服务/API/路由三类列表、搜索、新建编辑、全字段回填PUT；保存只作草稿，没有伪造删除 |
| 四类治理规则 | `governance.js` | RATE_LIMIT/CONCURRENCY/TIMEOUT/CIRCUIT_BREAKER分类型表单，已有绑定只读、不暗中移动 |
| 只读绑定查询 | `CatalogController`、`CatalogService`、`BindingReadTest` | `GET /admin/policies/{id}/bindings`；RBAC沿管理GET，固定DTO，不返回Principal实体或密钥 |
| 发布与回滚 | `operations.js` | 真实CAS expectedVersion、409无自动重试、历史回滚产生更大版本、节点实际应用状态 |
| Caller、监控、审计 | `operations.js` | 管理员创建/吊销、一次性Token仅内存、节点心跳/CB最近摘要、实际指标与审计 |
| 视觉/可访问性 | `style.css`、`ui.js` | 深色侧栏/顶部栏/卡片/表格/原生dialog，375px和1280px相关CSS、ESC/焦点逻辑存在；**没有浏览器人工证据** |
| 旧开发工具与指南 | `static/test-console/`、`product-console-guide.md`、`static/console/guide.html` | 旧HTTP/故障实验仍可访问；新帮助按7个真实业务任务组织 |

源码安全检查：`api.js`管理会话保存在模块内存、同源 `/admin/**`、限定回环Gateway metrics；`ui.js`构造动态文字用DOM/textContent，没有直接拼接不可信HTML；401会清会话，客户端假请求仍有后端RBAC复验。只读绑定DTO不返回Caller凭证。上述是静态源码事实，**不是独立渗透/实际浏览器审计**。

## 2. 仓库已有运行证据与真实性边界

1. `docs/progress/evidence/product-console-smoke.json`：`passed=true`，外层 `requests` 31条、`productModuleResult.records` 33条，共**64条**；产品模块独立登录后执行完整服务/API/路由CRUD、策略绑定GET、VIEWER/OPERATOR只读权限、两节点发布、Caller撤销、409和回滚。来源SHA=`075878747ea45590cefda41f6fcd185cc163afa2`；主样本状态码200×20/201×9/401×1/404×1，产品模块状态200×18/201×6/204×4/401×1/403×2/404×1/409×1。测试是**实际后端HTTP+产品JS模块**，不是“真人用浏览器点击产品页面”的证据。
2. 产品 Java17 定向 `-pl flowgate-control -am test package`：Codex报告 Contract7+Control3＝**10个JUnit通过**；Node ES模块/DOM模拟契约通过，Block2/3旧Node契约通过。真实测试记录需保留原报告，ChatGPT本轮没有独立运行。
3. `docs/acceptance/final-results.md` 记录在**产品改造前**成功运行`mvn -B -ntp verify`，共**34个JUnit**；当时有Mockito在受限环境attach失败的首次记录，权限正常后重跑通过，不删历史失败。
4. Block4旧基线三组2轮、每组30秒的`docs/progress/evidence/block4-performance-summary.json`：每组480预定请求，六组总2880，治理组fast每组240/240成功，slow被429/503拒绝；记录了CLIENT_SHED=0、P95/P99、资源和原始样本本机路径。**这些结果发生在产品前端源码改造前，不代表当前新产品版本已经完整重测**。性能结论只适用于当次单机隔离实验，不能宣布生产SLA。
5. 旧CB按revision在20次变更中官方注册表实例数1→21，长期清理仍为已知容量限制，尚不具备“无限高频修改长期稳定”证据。

## 3. 需要反馈/修复的风险与体验缺口

### P1｜调用方管理尚不是完整可操作的日常产品页面

**已确认事实**：`AdminAuthController` 仅有 `POST /admin/callers` 与 `POST /admin/callers/{id}/revoke`，没有 Caller 列表只读 API。`operations.js` 的“本次登录签发记录”来自内存 `ctx.callers`；刷新页面后消失，吊销旧调用方必须手填数字ID。`governance.js` 对 CALLER 规则可选目标也只能使用本次内存记录。这不会导致后端绕过或伪造列表，但**真实管理员无法方便地管理重启/刷新前已存在的Caller**，属于产品体验缺口。

**建议的最小收口**：在现有 IdentityService/PrincipalMapper 基础上加**ADMIN专用只读** `GET /admin/callers`（或管理员可见的安全摘要），只允许返回编号、用户名、Scopes、启用/吊销状态、必要创建时间等非凭证字段，绝不返回 Token、密码哈希、Session/内部Key。页面把它做成可搜索列表+明确的吊销二次确认，治理CALLER目标选择从后端真实列表来；新API补RBAC/脱敏/状态/分页上限定向JUnit和真实HTTP测试。**不增加表或依赖，不开放Token查询/找回，不改变既有认证契约。** 这个改进建议应在负责人最终产品验收时决定是否列为V1阻断；至少不能把“仅本次会话记录”宣传为完整Caller管理。

### P2｜移动端/桌面/键盘视觉仍缺真实人工验证

`style.css` 包含767px/1100px断点及侧栏隐藏，`ui.js` 使用原生Dialog与焦点恢复；但当前仅Node模拟DOM、静态资源200和真实HTTP模块契约，未提供真实Chrome/Safari 1280px、375px、Tab/ESC/焦点、屏幕缩放、长表格滚动、长中文文本溢出等浏览器验证证据。负责人此前认为旧前端杂乱，本项是**不能跳过的验收门槛**。无需重写框架，先真机体验再按具体问题修改。

### P2｜监控指标仅固定本地Gateway0

`api.js` 把 metrics 源固定为 `http://127.0.0.1:8080`，`operations.js` 明确标注这一点；产品虽然显示多个网关节点，但只有默认Gateway的metrics。对V1本机默认环境可接受，**不能把它呈现为整个集群所有节点的聚合数据**。如果后续要支持多个端口，先评估已有固定允许列表，不要开放浏览器任意URL或复杂监控栈。

### P2｜产品修改后的最终全量回归仍待复测

34 JUnit和六组性能对照属于前端产品化之前的基线，新增Binding GET和静态产品页面后只运行过受影响模块10项JUnit及对应HTTP/Node。按`docs/progress/block-4-acceptance.md` 新门槛，在负责人完成产品体验与必要修复后，**再运行一次最终完整verify、全链路回归和有针对性的正式性能确认**；未运行仍写“未执行”，不伪装最新版全面验收通过。

## 4. 推荐负责人实操路线（无需先读源码）

1. 本机启动Control/Demo/Gateway/MySQL/Redis（不删除已有数据）；打开 `http://127.0.0.1:8081/console/`，先看登录页和工作台是否符合正常产品直觉。
2. 从左侧“服务管理→API管理→路由管理”新建/编辑一个示例，检查列表、查找、完整字段回填，保存后是否提示“草稿未发布”。
3. 在速率或故障规则页选择真实业务目标、保存，前往发布中心确认CAS→新版本→真实节点已应用；验证409和回滚的提示。
4. 用ADMIN看调用方签发与撤销的安全回执，退出重进检查“旧Caller能否被管理”这一缺口；用VIEWER/OPERATOR确认权限按钮。
5. 在375px与1280px窗口检查导航、表格、对话框、帮助链接、键盘Tab/ESC。HTTP故障实验仍进入旧 `/test-console/`，不要把它当正式产品首页。
6. 把有问题的具体页面/动作/预期和实际反馈给Codex；阻断问题在同一Block4分支修复与定向验证。

## 5. Git及状态结论

- 仓库本轮核查起始SHA为`2ac5998c2aa55b1a66efbbd012035474c6ac652b`，PR #5为Draft、base `feat/block3-resilience-observability`；旧PR仍叠加，未授权合并main。
- Codex 阶段记录里出现`git push`权限报错，是它当时的执行情况；**目前实际产品代码已远端可读**，不能沿用“代码尚未到GitHub”的过时结论。保留旧失败历史，不篡改原记录。
- **静态审查阶段建议**：产品重建代码达到“可交人工验收”的阶段，不需要重新做大型前端框架选型。优先实际体验和Caller管理闭环收口；之后完成最终综合回归、Git审核与V1标记，**现在不合并main或宣布全部完成**。
