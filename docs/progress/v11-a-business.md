# FlowGate V1.1-A：开发者、应用、套餐与订阅（Codex 编码合同）

> 状态：未开始；先读 docs/design/openapi-platform-v1.1.md 与 CODEX_V11_OPENAPI_PROMPT.md；绝不能把设计写成已完成。基线为已完成 V1 的 Block4 工作树，先确认已有未提交改动/PR。此阶段**不添加 Kafka、不扣月度额度、不修改网关请求链路**。

## A-00 范围与业务流程

用户在正式 /console/：以 ADMIN 登录 → 建立客户 → 建立客户所属应用 → 建立 Free/Pro 套餐 → 为应用绑定一个有效订阅 → 为应用安全创建 Caller 凭证并一次性展示 → 按 app 查询 Scope/状态/订阅。VIEWER 只能查看，OPERATOR 不允许更改套餐与凭证（首版固定）；旧 /admin/callers 创建和吊销 API 仍不受影响。

## A-01 数据层任务

1. 新建 sql/flowgate-v11.sql，CREATE IF NOT EXISTS，仅新增：fg_developer(id,code UNIQUE,name,status)、fg_application(id,developer_id,code UNIQUE,name,status)、fg_app_principal(principal_id UNIQUE,application_id,created_at)、fg_api_plan(id,code UNIQUE,name,monthly_units,version,status)、fg_subscription(id,application_id,plan_id,effective_at,expires_at,status,version,created_at)。加外键/匹配查询索引，使用 UTC 时区。沿用 fg_principal 的 CALLER 身份及现有 Redis 摘要会话，不存明文 Token。
2. 有效订阅互斥采用**明确的事务协议**：在数据库事务内按应用行 SELECT FOR UPDATE，再校验时间窗与现有有效/排队订阅，拒绝重叠。不可用“status=ACTIVE 唯一索引”来假设 MySQL 支持部分唯一索引。首次版本只允许一个当前订阅和一个下月待生效变更；同一 UTC 月中不改写已锁定额度。约束在服务方法和集成测试中验证。
3. 主体唯一 code 长度、套餐 monthly_units>0 与上限、日期 start<end、状态枚举、应用归属都要验证；按唯一约束转换 409 错误，不泄露 SQL 堆栈。

## A-02 Control 任务

1. 新增 Entity/Mapper/Service/Controller，路由 /admin/developers、/admin/apps、/admin/plans、/admin/subscriptions。采用真实分页查询或合理上限；查询输出关联名称与稳定 ID，支持状态、创建时间。写操作审计含操作者、对象和摘要；不上传完整请求凭证。
2. 新增 POST /admin/apps/{id}/credentials，复用现有 IdentityService 生成一次性凭证、存储 Redis 摘要；先校验 app 未停用、角色 ADMIN、Scope 不允许冒用 /admin。将 principalId 与 appId 持久关联；若任一步骤失败，数据库事务回滚并尽力清理 Redis 新凭证。明文仅这次响应，之后只展示凭证尾号（若需要，持久化不可逆尾号而非明文）；凭证轮换、旧 Token 删除失败需返回明确失败，不得假装成功。
3. 新增暂停/恢复应用，暂停立即标记数据库状态并触发关联 Token 吊销；清理失败如实记录为异常/待补偿，不要声称跨系统原子。A 块阶段只保证基础安全与可见状态，**正式 METERED 调用还未启用**，B 块再由持久校验闭环保障停用。
4. 权限按 ADMIN 写/VIEWER 读/OPERATOR 只读客户套餐，复用既有 AdminAuthInterceptor；Caller 本身不得访问 /admin。

## A-03 正式 /console/ 页面

新增“客户与应用”“套餐与订阅”两个产品导航。业务化新建/编辑表单、客户列表、按客户查看应用、订阅到期/待生效状态、一次性 Token 模态框、空态/失败与字段帮助。用客户/套餐中文名下拉选项，不让用户手填数据库数字 ID；原有服务/API/路由/发布/监控页面保留，旧 /test-console/ 不删。产品使用指南同步更新。

## A-04 定向测试与通过条件

- Java17 Maven 对新增 Control/Contract 及受影响模块运行 test/package；真实 MySQL8.4+Redis7 隔离实例。
- 从空 V1 数据库执行 V1 再执行增量 DDL 两次无破坏；已有 fg_principal、路由、发布快照不变。
- 两客户同名 code/重复 app code 返回 409；无效套餐额度/日期 400；订阅重叠失败；月末日期按 UTC；VIEWER/OPERATOR 写 403；ADMIN 写 201/200；Caller 访问 /admin 401/403。
- Token 明文仅首次可见、数据库无明文；暂停后新计量验证将在 B 块覆盖，A 块需验证已有 Redis Token 被撤销及审计记录。
- 浏览器 1280px 与 375px 真实可操作，Tab 焦点与中文提示可用；脚本模拟 DOM 不能替代负责人体验。
- 最小真实业务：新建 2 客户+2 应用+Free/Pro 套餐与订阅+有效 Scope 凭证，保留脱敏 JSON 证据。
- 把实际 SHA、文件、命令、通过/失败/跳过计数和阻断问题追加本文件末尾；**本文件当前没有任何已完成测试**。完成后由负责人确认，再开始 B。

## A-05 明确不做

不新增“注册支付”、不做独立开发者前端、不提前实现用量统计、不让应用凭证访问未开启计量业务的额外路由、不把内部个人数据暴露给 API 调用方、不改旧发布链路。
