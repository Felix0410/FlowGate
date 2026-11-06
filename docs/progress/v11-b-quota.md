# FlowGate V1.1-B：累计配额账本、可信准入与一致性（Codex 编码合同）

> 状态：未开始。前置 A 已完成且负责人确认。先阅读 docs/design/openapi-platform-v1.1.md 第 3–6、9 节及现有 CallerAuthFilter / AdmissionGlobalFilter / ReleaseService / PolicyRules；本块不引入 Kafka，先完成单数据库权威闭环。

## B-00 准入语义固定

- 免费套餐示例每 UTC 月 5 个单位，Pro 20 个；受保护 API 通过 USAGE_QUOTA 策略显式开启计量，unitsPerCall=1..有限上限。请求成功获 quota grant **即消耗额度**，不依赖下游 HTTP 结果，不自动退还；不是按成功响应/金额收费。
- 每个新代理尝试由 Gateway 生成内部 chargeId；**同一次内部 HTTP 重试必须使用同一 chargeId**；用户新请求是新尝试。没有授权的请求绝不能消费额度。
- 旧已发布配置默认不计量，旧 Caller/API 不迁移；新应用凭证只可用于计量路线，避免停用后通过非计量路线逃逸。
- Control/DB 不可用仅新计量路由拒绝 503；429 RATE_LIMITED 和 429 QUOTA_EXHAUSTED 要区分原因；原有速率准入维度不产生退款。

## B-01 SQL 与服务

1. 扩展增量 SQL：fg_quota_balance(id,application_id,period_yyyymm CHAR(6),total_units,remaining_units,version,UNIQUE(app,period))；fg_quota_ledger(id,charge_id CHAR(36) UNIQUE,application_id,principal_id,api_id,route_id,period_yyyymm,units,created_at)；fg_outbox_event(id,event_id UNIQUE,event_type,payload_json,status,next_attempt_at,attempt_count,created_at)。关键查询索引按 Mapper 设计；不用默认在每个字段建索引。
2. Control 事务：校验 Caller principalId 属于 ACTIVE app、ACTIVE Developer、有当前生效订阅与合法 Scope；确认请求的 API/route/units 与**已发布**策略一致，不能信任网关传入字段直接扣其他 app 额度。按数据库 UTC 月份初始化余额（INSERT IGNORE + 行锁保护竞争），保证总额冻结；按条件 UPDATE 余额 remaining_units>=units 扣减。插入 chargeId 唯一 Ledger 与 Outbox，三者必须同一事务成败。
3. 重复 chargeId 同 payload 返回第一次的消费结果（不重复扣减）；同 chargeId 不同 payload 返回 409。并发第一次到达时要正确处理唯一冲突/事务回滚，不能吞掉一次多扣。chargeId 是否重试相同 payload 必须包括 appId、apiId、routeId、period、units 等稳定字段。
4. 当事务成功但 HTTP 响应丢失，Gateway 限次重试相同 chargeId；若状态始终未知，计量请求 503 而不是放行，历史授权已扣配额可在账本查询；按“授予的配额”语义解释，不声称必然到达下游。
5. 余额查询读 MySQL 权威；套餐元数据可通过 Redis Cache Aside 缓存用于界面/目录非授权读。数据库先提交后删缓存，失败删除写补偿和短 TTL；**最终额度/权限判断不依赖缓存值**。
6. 幂等日志、审计不含 Token、授权 Header、请求 Body，不允许用户提交 chargeId 覆盖内部值。

## B-02 Gateway 与快照

1. 在既有 PolicyRules 中最小增加 USAGE_QUOTA（仅 API/ROUTE 绑定、non-public、每路由最多 1），协议字段回读旧快照时不失败；RouteDefinitionLocator 冻结计量标识/units/appliedVersion。不要把 USAGE_QUOTA 强行塞进 EffectivePolicyResolver.Rule 的 capacity/refill 默认值而触发 NPE；可定义单独 QuotaPlan/metadata。
2. Gateway 认证后仅使用 CallerAuthFilter 验证过的 principalId；原 Redis RATE_LIMIT 与本地并发照旧（包括 complete/error/cancel 许可归还）；计量调用使用 Reactor WebClient、超时上限、内部身份、同 chargeId 限次重试与不确定结果 fail-closed。没有配置计量的路线不额外访问 Control。
3. 回滚到旧配置后必须停止新计量规则；刷新/轮询错误继续保留最后有效快照；网关不同版本期间使用请求所匹配的同版本 Route metadata。禁止在每请求路径做 JDBC、block()、同步 Redis。
4. 与熔断先后需做实际测试：已经确定 OPEN 的路由应在配额调用前拒绝；如果由于框架 CB 路由过滤器位置难以在不破坏原 V1 的条件下做到，明确记录 OPEN 状态下是否会消耗授予额度，禁止夸大“只对真正发到下游的请求计量”。此项为评审阻断条件，必要时收窄计量路由范围。

## B-03 内部接口和安全

B 阶段必须与 app-only 网关访问限制**同一版本**上线 POST /admin/apps/{id}/credentials 的正式签发/轮换与吊销；不得让新应用的活跃 Caller Token 访问无 USAGE_QUOTA 的旧受保护路由。检查 app status/订阅为 DB 权威条件，暂停后拒绝所有计量授权，现有旧 Caller 使用规则不变。

新增 POST /internal/quota/charge：仅 Gateway 可信内部调用，可由环境配置的服务身份 + 网络隔离校验；在 Control 与 Gateway 同网段内对抗伪造/重放，密钥不得打印或直接写入脚本；公网 /admin、API Route 和 Caller 均不能调用它。对于同一 chargeId，校验身份与业务字段的一致性。业务码区分 403 无订阅/停用、429 配额耗尽、409 幂等冲突、503 内部依赖不可用。

## B-04 最低验收

- 两 Gateway、一个 Control、同一 MySQL/Redis：同应用两网关并发 100 次，Free 额度 5 单位时**只允许 5 个唯一 grant**，余额 0、Ledger 5、Outbox 5；其他请求 429。不同应用互不混账。
- 同一 chargeId 100 次并发重复只扣 1；更换 payload 409；先 DB commit 后断 HTTP 模拟重试仍不重复。
- 停用 app、超期订阅、Scope 不足、未发布计量规则均不会扣账；Redis 限速拒绝不扣月度额度；失联 Control / MySQL 计量 503、原未计量 API 继续成功。
- 真实请求验证 Gateway 不触发 block() / JDBC、Old snapshot 回滚仍可用、提交/取消/超时现有许可归还。
- 复测现有 V1 受影响 Java、HTTP/鉴权/发布/回滚链路，构建本块最小 HTTP 证据与中文产品指南；记录平均/尾延迟的**新增配额 Control hop 成本**。
- 本块不运行完整长压测，留 D 块做四组实验；只提交脱敏结果，不提交凭证。

## B-05 完成标准

数据库守恒与唯一性实测通过 + Gateway 真实跨节点测试 + 异常与身份边界通过 + 前端显示额度与拒绝原因 + 负责人确认后才进入 C。


## B-06 设计审核后的强制门槛与实施拆分（不可提前编码）

> 审查依据：`docs/reviews/v11-design-review-2026-11-06.md` R01～R05，`docs/design/openapi-platform-v1.1.md` 第12节。**本块未开始**；只有A代码和真实定向验收通过、负责人确认后才派给Codex。

| 子任务 | 必须实际改动 | 拦截/验收 |
|---|---|---|
| **B0** | 复核A的DDL/订阅/角色和V1最新基线；冻结route/API/身份/版本与错误码合同 | 旧Caller不能因appToken设计失效 |
| **B1** | `sql/flowgate-v11.sql`只新增余额/ledger/outbox表，Outbox预留`lease_owner,lease_until,last_error_code`；Entity/Mapper/事务Service | 隔离MySQL重复DDL；冻结`subscription_id/plan_version`，余额不负、Ledger/Outbox同事务 |
| **B2** | `PolicyRules`、`ReleaseService`、`SnapshotRouteDefinitionLocator`、`EffectivePolicyResolver`接入独立`USAGE_QUOTA`策略、同版metadata与可信节点`supportedFeatures`检查 | **混合旧/新Gateway不允许激活计量**；历史checksum/rollback完全兼容 |
| **B3** | 应用Token独立`fg:auth:app:{digest}`和`APP_CALLER`类型；`IdentityService`与`CallerAuthFilter`新增受限用途且不污染旧Caller；`POST /admin/apps/{id}/credentials`签发/轮换/吊销 | 旧Gateway读不到APP Token；新Gateway非计量私有路由403；状态停用DB拒绝；A占位409变真正接口必须与隔离同提交 |
| **B4** | `POST /internal/quota/charge` + MySQL原子授权、业务校验、chargeId幂等、DB UTC、旧发布版本校验、限时WebClient；在认证/速率/并发与熔断边界中放置单次计量授权 | 100并发Free5仅5grant/5Ledger/5Outbox；重复100次仅扣1；跨月同chargeId不扣第二月；409不同payload；丢回包有限重试fail-closed |
| **B5** | `/console/`额度/账本真实列表、错误提示和用户指南；旧页面保留 | 用户看得到授权消耗vs成功处理、下月套餐状态；非管理员不能修改 |
| **B6** | JUnit、Node、隔离双Gateway+Control+MySQL/Redis真HTTP与故障注入、脱敏证据 | Control/DB故障被计量503而原非计量路线仍旧行为；Redis/401/403/429短路不扣额；实际filter顺序证据 |

**特别注意**：
1. **不是只给新Gateway的`CallerAuthFilter`增加一个检查就完成App隔离**。旧Gateway只识别`fg:auth:caller:`，应用新Token必须存入**不同命名空间**；新用途`APP_CALLER`不作为普通CALLER Session返回。混合版本测试是P0。
2. 不能仅用`/admin/nodes`的`appliedVersion`作为能力检查。节点须有经过受信上报的`supportedFeatures`，由受控接流清单判断，所有可以接计量流量的Gateway尚未升级时**拒绝发布或激活USAGE_QUOTA**。
3. 配额内部接口以历史`ConfigRelease(appliedVersion)`核验同版策略绑定、API隐私/Scope和units；旧版合法滞后网关不能被误拒，伪造不存在/不匹配版本不扣账。
4. **同chargeId跨UTC月重试**查询先前Ledger原始月份和参数，比对后返回先前grant，不创建新月Ledger。若停用应用应拒绝新的代理授权，不通过“历史重复成功”绕过状态；旧grant账本保留。测试用固定可注入UTC Clock、同一chargeId两种月份、当前停用三种可控场景。
5. 首个授权Grant之后，下游失败/取消/超时仍保留单位扣减。若框架过滤器实际无法保证熔断OPEN在quota前拒绝，必须通过集成测试证明并在业务合同与UI明确限制，或收窄首版计量API范围，**不可偷偷将语义写成“真正到达Demo才扣”**。
6. 新计量路由只对App专用身份生效；旧普通Caller仍按V1授权。Public route无鉴权承诺不能被误描述为App token被禁止访问，真正需要保护的端点必须是private metered。
7. A/B/C都是定向测试和每块独立提交；直到D才进行全量长压测。B不提前集成Kafka broker；Outbox写MySQL即可。
