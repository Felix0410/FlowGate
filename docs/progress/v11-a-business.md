# FlowGate V1.1-A：开发者、应用、套餐与订阅（Codex 编码合同）

> 状态：未开始；先读 docs/design/openapi-platform-v1.1.md 与 CODEX_V11_OPENAPI_PROMPT.md；绝不能把设计写成已完成。基线为已完成 V1 的 Block4 工作树，先确认已有未提交改动/PR。此阶段**不添加 Kafka、不扣月度额度、不修改网关请求链路**。

## A-00 范围与业务流程

用户在正式 /console/：以 ADMIN 登录 → 建立客户 → 建立客户所属应用 → 建立 Free/Pro 套餐 → 为应用绑定一个有效订阅 → 在应用详情看到“凭证待启用”及后续发放入口 → 按 app 查询状态/订阅。**A 阶段只实现应用与主体的数据库关联及凭证 UI 占位，不发放可用于现有 V1 路由的活跃 Token；真正的应用凭证签发和 app-only 访问限制在 B 阶段同一功能块上线。**VIEWER 只能查看，OPERATOR 不允许更改套餐与凭证（首版固定）；旧 /admin/callers 创建和吊销 API 仍不受影响。

## A-01 数据层任务

1. 新建 sql/flowgate-v11.sql，CREATE IF NOT EXISTS，仅新增：fg_developer(id,code UNIQUE,name,status)、fg_application(id,developer_id,code UNIQUE,name,status)、fg_app_principal(principal_id UNIQUE,application_id,created_at)、fg_api_plan(id,code UNIQUE,name,monthly_units,version,status)、fg_subscription(id,application_id,plan_id,effective_at,expires_at,status,version,created_at)。加外键/匹配查询索引，使用 UTC 时区。沿用 fg_principal 的 CALLER 身份及现有 Redis 摘要会话，不存明文 Token。
2. 有效订阅互斥采用**明确的事务协议**：在数据库事务内按应用行 SELECT FOR UPDATE，再校验时间窗与现有有效/排队订阅，拒绝重叠。不可用“status=ACTIVE 唯一索引”来假设 MySQL 支持部分唯一索引。首次版本只允许一个当前订阅和一个下月待生效变更；同一 UTC 月中不改写已锁定额度。约束在服务方法和集成测试中验证。
3. 主体唯一 code 长度、套餐 monthly_units>0 与上限、日期 start<end、状态枚举、应用归属都要验证；按唯一约束转换 409 错误，不泄露 SQL 堆栈。

## A-02 Control 任务

1. 新增 Entity/Mapper/Service/Controller，路由 /admin/developers、/admin/apps、/admin/plans、/admin/subscriptions。采用真实分页查询或合理上限；查询输出关联名称与稳定 ID，支持状态、创建时间。写操作审计含操作者、对象和摘要；不上传完整请求凭证。
2. A 阶段先建立应用与原有 CALLER principal 的关联模型，新增 POST /admin/apps/{id}/credentials 的接口合同与产品入口，但在 B 阶段完成网关 app-only 路由隔离前，返回 409 FEATURE_NOT_READY，不真正签发可用 Token。**否则 app Token 可以利用既有 Scope 访问非计量旧路由，破坏“停用 app 必须拒绝”的安全承诺。**B 阶段复用 IdentityService 正式签发一次性 Token、记录关联、轮换/吊销并验证 app-only 边界。
3. 新增暂停/恢复应用并在 MySQL 中持久化状态；A 阶段不发放应用活跃 Token，故不需要声称已做 Redis 跨系统撤销。B 阶段配合正式发证增加关联 Token 原子吊销与配额授权查库，清理失败要有明确状态/补偿。
4. 权限按 ADMIN 写/VIEWER 读/OPERATOR 只读客户套餐，复用既有 AdminAuthInterceptor；Caller 本身不得访问 /admin。

## A-03 正式 /console/ 页面

新增“客户与应用”“套餐与订阅”两个产品导航。业务化新建/编辑表单、客户列表、按客户查看应用、订阅到期/待生效状态、明确的“凭证待 B 阶段启用”提示、空态/失败与字段帮助。用客户/套餐中文名下拉选项，不让用户手填数据库数字 ID；原有服务/API/路由/发布/监控页面保留，旧 /test-console/ 不删。产品使用指南同步更新。

## A-04 定向测试与通过条件

- Java17 Maven 对新增 Control/Contract 及受影响模块运行 test/package；真实 MySQL8.4+Redis7 隔离实例。
- 从空 V1 数据库执行 V1 再执行增量 DDL 两次无破坏；已有 fg_principal、路由、发布快照不变。
- 两客户同名 code/重复 app code 返回 409；无效套餐额度/日期 400；订阅重叠失败；月末日期按 UTC；VIEWER/OPERATOR 写 403；ADMIN 写 201/200；Caller 访问 /admin 401/403。
- A 阶段应用凭证接口 409 FEATURE_NOT_READY，不能生成 Redis 活跃凭证或在 UI 上伪造成功；正式一次性 Token 签发/暂停后吊销由 B 块集成验收。A 仍需验证原有旧 Caller 认证、撤销与审计回归。
- 浏览器 1280px 与 375px 真实可操作，Tab 焦点与中文提示可用；脚本模拟 DOM 不能替代负责人体验。
- 最小真实业务：新建 2 客户+2 应用+Free/Pro 套餐与订阅，验证凭证功能未启用的明确提示和旧 Caller 回归，保留脱敏 JSON 证据。
- 把实际 SHA、文件、命令、通过/失败/跳过计数和阻断问题追加本文件末尾；**本文件当前没有任何已完成测试**。完成后由负责人确认，再开始 B。

## A-05 明确不做

不新增“注册支付”、不做独立开发者前端、不提前实现用量统计、不让应用凭证访问未开启计量业务的额外路由、不把内部个人数据暴露给 API 调用方、不改旧发布链路。


## A-06 2026-11-06 审核后：**逐提交开发任务单**（本块唯一执行顺序）

> 追加本节是因为原A-01..A-04适合作为需求大纲，却没有把Codex下一步的代码位置、接口字段/失败行为和交付证据锁定。本节以 `docs/design/openapi-platform-v1.1.md#12-设计审核后强制修订2026-11-06优先于前文含糊描述` 和 `docs/reviews/v11-design-review-2026-11-06.md` 为准。**开发仅限本A块**。

### A0｜只读工作区/接口核对（不要提交功能代码）

输入：V1完整基线`test/block4-final-acceptance`、当前文档`docs/v1.1-openapi-platform`、现有`CatalogController`、`AdminAuthInterceptor`、`IdentityService`、`AuditService`、`/console/`的原生ES modules、`sql/flowgate-init.sql`。输出：固定Git SHA和原有PR状态；`JAVA_HOME` JDK17；本地未提交状态、IDE进程/旧MySQL/Redis容器与安全隔离方案；现有API/RBAC矩阵。**没有可靠隔离MySQL就只做源码/测试，不碰用户数据。**

### A1｜增量数据模型（提交1：`feat(v11-a): add developer application plan subscription schema`）

实施文件：
- **新增** `sql/flowgate-v11.sql`（单独增量SQL，原`sql/flowgate-init.sql`不改）；
- **新增** `flowgate-control/src/main/java/com/flowgate/control/entity/{DeveloperEntry,ApplicationEntry,AppPrincipalEntry,ApiPlanEntry,SubscriptionEntry}.java`（命名与现有Entity约定保持一致）；
- **新增** `flowgate-control/.../mapper/...`对应MyBatis-Plus mapper，先不新起Repository框架。

精确数据约束：
1. `fg_developer(id BIGINT PK, code VARCHAR(100) UNIQUE, name VARCHAR(200), status ACTIVE|SUSPENDED,created_at,updated_at)`；`fg_application(id,developer_id FK, code UNIQUE,name,status,created_at,updated_at)`。
2. `fg_api_plan(id,code UNIQUE,name,monthly_units BIGINT >0,plan_version INT DEFAULT 1,status,created_at,updated_at)`；首版`monthly_units`创建后**不可通过PATCH修改**（不做复杂版本表），修改额度通过新套餐与订阅。取值先限定1..1_000_000，所有Java/SQL边界一致。
3. `fg_subscription(id,application_id FK,plan_id FK,effective_at TIMESTAMP/UTC,expires_at TIMESTAMP/UTC,status ACTIVE|SUSPENDED|EXPIRED,plan_version_snapshot,created_at,updated_at)`；`[start,end)`、start必须小于end，未来变更只能安排**下一UTC自然月第一天**，每app最多当前有效1条+下月待生效1条。
4. `fg_app_principal(principal_id UNIQUE FK,application_id FK,created_at)`：A阶段只是**预留映射表**，不产生app活跃Token/Redis键；由B增加真实凭证关联。订阅下月切换发生时不靠删除旧账本完成。
5. 仅使用`CREATE TABLE IF NOT EXISTS`、幂等迁移；字段与Mapper匹配，必要FK/索引按查询设计；**禁止DROP/TRUNCATE/UPDATE旧表**，不向原V1表写虚假seed。

A1验收：隔离数据库先执行原V1 SQL，再执行V11 SQL两次，列出表/索引一致性与旧fg_principal/fg_config_release数量及校验和不变；失败回填真实原因，不能宣称迁移无损。

### A2｜管理API与明确RBAC（提交2：`feat(v11-a): expose secured developer and plan catalog`）

建议新增文件：
- `flowgate-control/.../controller/{DeveloperController,ApplicationController,PlanController,SubscriptionController}.java`（可以合并成2~3个紧凑的领域Controller，**不要一个泛化万能Controller隐藏领域验证**）；
- `flowgate-control/.../service/{DeveloperApplicationService,PlanSubscriptionService}.java`，可用少量DTO record同文件组织；
- **修改** `flowgate-control/.../security/AdminAuthInterceptor.java` 中新增业务前缀的授权白名单；原V1路径原授权不变。

固定只读/写接口：
- `GET /admin/developers?afterId=0&limit=20`（1..100）与`GET /admin/developers/{id}`；`POST /admin/developers`、`PATCH /admin/developers/{id}`。
- `GET /admin/apps?developerId=&afterId=0&limit=20` 与`GET /admin/apps/{id}`；`POST /admin/apps`、`PATCH /admin/apps/{id}`。
- `GET /admin/plans?afterId=0&limit=20` 与`GET /admin/plans/{id}`；`POST /admin/plans`、`PATCH /admin/plans/{id}`。
- `GET /admin/subscriptions?applicationId=&afterId=0&limit=20` 与`GET /admin/subscriptions/{id}`；`POST /admin/subscriptions`（A阶段不开放任意PATCH订阅区间以免绕过互斥；若必须暂停，用一个明确的ADMIN专用状态变更动作并同样串行）。
- **占位** `POST /admin/apps/{id}/credentials`：已登录ADMIN、应用存在时返回**409 `FEATURE_NOT_READY`**，绝不调用IdentityService创建Caller、绝不写任何`fg:auth:app:`或`fg:auth:caller:`。匿名401、非ADMIN403。
- DTO不包含`passwordHash/token/session/internalKey`、隐藏SQL细节；`code`统一只允许`[A-Za-z0-9_-]`和长度限制，`name`非空合理长度；enabled由status枚举替代；ID正数；重复code统一409 `DUPLICATE_CODE`。

**路径ACL矩阵**（Java集成测试必检）：`/admin/developers/**`、`/admin/apps/**`、`/admin/plans/**`、`/admin/subscriptions/**`所有GET允许ADMIN/OPERATOR/VIEWER（Token必须是管理Session）；所有POST/PATCH/PUT/DELETE仅ADMIN，其他403。旧`/admin/services`等OPERATOR旧写权限保持，`/admin/callers`仍ADMIN。路由Method不允许时不可产生DB写入。

A2验收：四域API至少有1个真实GET+POST+PATCH/受限接口测试、409重复/404不存在/400非法、ADMIN/OPERATOR/VIEWER/Caller角色矩阵；审计成功写入，失败不伪造成功动作。

### A3｜订阅互斥与UTC期间状态（提交3：`feat(v11-a): enforce subscription schedule serialization`）

1. 在`PlanSubscriptionService`的`@Transactional`方法按`application_id`对`fg_application`执行真正行锁`SELECT ... FOR UPDATE`；**锁应用行后**查询所有当前/未来候选订阅，在同一事务内判断区间`[start,end)`不得重叠，不允许两个并发操作者都提交。错误返回409 `SUBSCRIPTION_CONFLICT`。
2. 当前首版只能1个有效区间与1个下月UTC首日开始的排队变更；区间端点`effectiveAt/expiresAt`使用ISO8601 UTC时刻（`Z`），严禁以本机时区LocalDateTime解释；下月00:00生效/月底23:59:59.999边界定向测试。
3. 套餐额度创建后不可改；`planVersionSnapshot`随订阅创建冻结；暂停应用/开发者可改状态但**不能删除历史订阅或清零旧消费**。A阶段未引入QuotaBalance，不声称已支持月度扣减。
4. 失败必回滚，审计与订阅写同事务；父Developer不存在/停用、App所属关系无效、Plan停用、历史月回填/重叠、时间倒置均有错误码和测试。

A3测试：两并发数据库事务在同一app创建重叠订阅，**恰好一个201、一个409**；不同app成功并行。跨月/UTC/月末边界独立断言；不依赖Sleep碰运气，使用Barrier/受控锁交错。

### A4｜产品后台交付（提交4：`feat(console): manage v11 developers apps plans subscriptions`）

精确改动：
- `flowgate-control/src/main/resources/static/console/app.js`：在已有分组侧栏新增“客户与应用”“套餐与订阅”（或四个清晰子视图），保留所有V1页面；
- **新增或扩展** `console/openapi-business.js`：真实客户/应用/套餐/订阅列表、按名称关联下拉、状态标签、新建/编辑/分页和错误提示，使用现有`api.js/ui.js`、原生JS ES modules；
- `docs/guides/product-console-guide.md`、`scripts/build-console-guide.py` 和生成的`static/console/guide.html`：补充“客户→应用→套餐→订阅”的完整中文上手路线。

P0界面契约：全部列表真实API，查询失败不显示0；空态可点击新建，保存只显示“已保存/订阅已创建”，不写“额度生效”；选择开发者/套餐使用名称及code组合，不要求输入内部数字ID；凭证栏明确显示`A阶段尚未启用，B阶段上线前禁止签发`，点击后见409 `FEATURE_NOT_READY`，没有虚构Token文本框；VIEWER/OPERATOR只读写按钮禁用且服务器仍403；表单375px/1280px适配，现有旧`/test-console/`不删。

### A5｜定向JDK/Node/真HTTP证据（提交5：`test(v11-a): verify isolation rbacs and subscription conflicts`）

1. JDK17 `mvn -B -ntp -pl flowgate-control -am test package`；若迁移涉及Contract则受影响Contract一起测。新增JUnit：领域状态校验、代码唯一409、状态转换、行锁互斥、错误体、ACL、凭证占位未写Redis，审计。
2. `node scripts/product-console-test.mjs`旧测试保留通过；**新增** `scripts/v11-a-console-test.mjs` 真实ES模块契约/模拟DOM：创建客户/应用/套餐/订阅、权限、异常状态、跳转、表单回填与不展示明文凭证。DOM模拟不得冒充真实浏览器。
3. **新增** `scripts/v11-a-smoke.py` 复用旧隔离Lab或最小补充，独占MySQL8.4、Redis7、随机回环端口。按A1 DDL两次+2客户2应用2套餐+1现行1次月订阅、三角色+Caller测试；旧Caller签发/吊销和V1路由至少一条真实200/401，证明未破坏。
4. `docs/progress/evidence/v11-a-smoke.json`仅在真的运行后提交**脱敏摘要**（路径/status/断言结果/版本/SHA，不含Token/密码/Body/内网密钥）；完整原始调试数据仅本地`.local/acceptance/`。

### A6｜审核门槛与交付说明（提交6：`docs(v11-a): record implementation and test evidence`）

真实填写：新增/修改文件清单、接口返回JSON示例（脱敏）、实际DDL/约束、全部命令/退出码/用例通过失败跳过、SHA和错误修复记录、V1回归事实、`README.md`和产品指南。依次提交/推送到`feat/v11-a-business`；不得创建/合并B实现、不得提交Kafka/QuotaBalance/Outbox/Gateway代码；下一阶段B必须等代码审核通过且负责人确认。

## A-07 停止条件（优先于赶进度）

- 发出可用APP Token、写入任何新增app Redis会话或改变旧Caller凭证语义 → **立即停止并修复**（A只能FEATURE_NOT_READY）。
- 同一应用并发写出重叠订阅、允许OPERATOR写新业务对象、旧V1受保护路由权限发生改变 → **不能验收**。
- 导航只做图片/静态表单、没有真实列表/增改API，或新产品页变成手填JSON → **不能验收**。
- SQL涉及用户个人数据库/Docker volume、执行历史表删除或无法证明迁移幂等 → **停止与报告**。
- 当前没有`docs/acceptance/v11-final-results.md`是正确的；完整总结留到D阶段，A只记录真实定向测试，不虚报Kafka/月底扣量已经实现。
