# FlowGate V1.1-B：累计配额账本、可信准入与一致性（Codex 编码合同）

> **当前状态（2026-11-08）**：B0～B6本地实现与定向验收已完成，等待复审；当前代码/命令/证据见B-16、B-17。B-01～B-15保留2026-11-07设计时的历史状态，不能代替下方实际交付记录。远端推送状态另记，不自动合并、不进入C/D/Kafka。

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


## B-07 2026-11-07 经负责人调整：B0吸收V11-A两项P0（本节及B专用设计优先）

> **更新后的权威合同**：`docs/design/v11-b-quota-execution.md`；本地Codex入口`CODEX_V11_B_EXEC_PROMPT.md`。负责人已明确将A遗留问题并入B，因此原本“B之前必须先在A分支修复”的文字现改为：**B0先修并真实复测，不通过绝不能启动B1～B6；无须回写旧A代码分支或覆盖A历史结果**。A的PR #7仍Draft且历史问题未神奇消失；B继承A全部源码后修复。

**阶段总门槛**：
1. B0只动月度订阅规则/连接UTC时区/定向测试，单独`fix(v11-b)`提交，通过隔离MySQL后才允许继续。
2. B1/B2/B3先完成策略发布与身份/数据库权威模型；安全能力门禁必须早于真实凭证签发，**不得先放出APP Token再补扣额**。
3. B4完成实际WebFlux阻断链路后才开放B2凭证API对用户可用，遵循“功能完整才可签发”的安全部署规则；开发过程中可用测试服务与受控内部数据验证，不对正式业务放行。
4. B5每完成一项后端业务同步在`/console/`真实表单显示，不将测试台做成用户界面。
5. B6以两Gateway/MySQL/Redis完整验证后才能记录B完成；Kafka消费仍待C，V11-D负责正式长压测。

## B-08 B0｜修补月度锁定与时区（第一提交，P0阻断）

**实际文件**：`SubscriptionSchedule.java`、`PlanSubscriptionService.java`、`ApplicationEntryMapper.java`、`application.yml`、`V11BusinessTest.java`、`scripts/v11-a-smoke.py`追加B0专用测试（或新`scripts/v11-b0-smoke.py`；保留原脚本历史快照）。

1. 先在单元测试里复现旧Free`[2026-11-01,2026-11-10)` + 当前UTC 11月20日 + 新Pro`[11月11日,12月1日)`原代码错误放行；新的校验必须409。**锁定“一个App每UTC月最多一个套餐”**，旧已到期ACTIVE行仍计当月占用；禁止跨计费月的单条订阅时间段（以B设计9/首版可解释规则为准），相邻下月可排队。
2. 继续保留应用行`FOR UPDATE`、候选当前读；SQL真实并发一201一409；不同应用同计划可并行。
3. 对Control全部Hikari连接明确设置会话UTC（如`connection-init-sql: SET time_zone = '+00:00'`），配合Connector/J映射测试；`nowUtc()`不能仅依赖系统JVM或迁移SQL的会话。**必须在非UTC MySQL server/JVM时区实测回读TIMESTAMP(3)**，保证11/30末最后毫秒与12/1首毫秒。
4. 复测V1审计、节点时间/发布快照/旧Caller；有问题最小修补，不改全局MySQL时区或破坏原V1数据。所有实测只用脚本自己创建的MySQL8.4/Redis7容器。
5. 运行受影响的Java17 Maven、V11-A Node、产品离线指南检查、隔离MySQL订阅真实HTTP；记录修复前失败/修复后通过的证据`docs/progress/evidence/v11-b0-smoke.json`，**不得覆盖旧`v11-a-smoke.json`**。成功提交`fix(v11-b): enforce utc-month plan and database sessions`。

## B-09 B1｜新计量策略、节点能力与发布防降级

**目标文件**：`flowgate-contract/PolicyRules.java`、`flowgate-control/service/{CatalogService,ReleaseService}.java`、`ReleaseController.java`、`Gateway config/SnapshotPollingService.java`、新增`QuotaPolicyResolver.java`/能力表Mapper。

1. `USAGE_QUOTA`参数仅`unitsPerCall`（整数1..100），绑定仅API/ROUTE、private/启用、有真实API关联；同一路由最多一个quota，无其他 scope。
2. `PolicyRules`独立执行标志`ENFORCED_QUOTA_V1`，`ReleaseService.build`按类型赋值；旧`ENFORCED_BLOCK2`与`ENFORCED_BLOCK3`语义不变。旧快照`schemaVersion=1`、`RouteSpec`结构、checksum校验和旧rollback不修改。
3. **单独`QuotaPlan`metadata**（`routeId,apiId,units,policyId,appliedVersion,snapshotChecksum`）随路由冻结；绝不能把quota添加到旧`EffectivePolicyResolver.Rule`导致`capacity/refillPerSecond`空取值NPE。
4. `FLOWGATE_QUOTA_TRAFFIC_NODES`必须显式设置接流节点白名单；默认空拒绝启用Quota。新Gateway在已有节点上报增加`supportedFeatures:["USAGE_QUOTA_V1"]`，Control用新`fg_node_capability`持久记录可信心跳；旧节点未提供capability→未知/不支持，过期/缺失/错checksum→拒绝Quota发布/回滚激活。混合版本1旧1新、2新、1节点失联、回滚无Quota都需测试。
5. **分阶段激活门槛**：Control设置`flowgate.quota-activation-enabled=false`（默认），B1只有协议和节点能力检查时不得真正发布生效；Gateway仅在B4认证+Quota Filter实际完成后上报`USAGE_QUOTA_V1`。B6隔离联调可显式开启，用户持久化部署不自动开启，禁止半成品Gateway接受计量流量。
6. 报告仍保留所有已发布V1快照，不能为新的quota反复覆写历史`fg_config_release.snapshot_json`；Control历史`appliedVersion`不是能力证明。

**验收**：功能门禁可阻止旧节点/缺失节点；只有两受控新Gateway上线+可信报告后才能发布含quota配置，节点应用一致。JDK/真HTTP定向。提交`feat(v11-b): gate metered releases by node features`。

## B-10 B2｜应用专用凭证与安全撤销（与B4完整时才真正开放）

**目标文件**：Control新增`AppCredentialService`/Controller、`flowgate-contract/AppCredentialSession`、Gateway`CallerAuthFilter`、`sql/flowgate-v11.sql`新`fg_app_credential_state`表、产品App页。

1. App Token为`fgapp_`+43随机URL安全字符，独立`fg:auth:app:{digest(fullToken)}`和`fg:auth:app-index:{principalId}`；`fg_principal.principal_type/role=APP_CALLER`。旧V1 Caller仍旧43字符/旧Redis key；两种Token不能在另一种身份模式读取。
2. `fg_app_principal`关联App，`fg_app_credential_state`保存单调`generation`和enabled，不保存明文/摘要Token。签发/轮换/吊销/应用暂停需先DB提交权威代次或状态，再Redis Lua原子替换/删除；Redis失败不承诺旧Token仍安全，**所有真正计量授权必须核对DB代次+状态**。
3. `POST /admin/apps/{id}/credentials`从A的409暂时变真实功能，但**本B施工期间用可配置关闭默认的门禁或完成Gateway能力先测后才暴露**。Token仅一次展示，刷新丢失后仅允许轮换；`GET`管理列表脱敏，不提供任何反查明文。
4. Gateway在计量私有路由只接受App专用Token，在非计量私有路由保持V1普通Caller；现有公开路由行为不变。App校验/Redis故障均在进入下游之前处理。
5. 同App双管理员竞态签发、轮换、撤销、旧Token存活但DB代次已变化、Redis清理失败、Developer/App停用、旧Gateway不接受App Token，全部定向验证。

**验收**：新应用凭证与旧V1 Caller双向隔离；签发/轮换/吊销强制DB代次，安全性不依赖Redis成功清理。提交`feat(v11-b): isolate app credential lifecycle`。

## B-11 B3｜MySQL原子额度+幂等Ledger+Outbox

**目标文件**：`sql/flowgate-v11.sql`新增`fg_quota_balance/fg_quota_ledger/fg_outbox_event`，对应Entity/Mapper、`QuotaChargeService`、`InternalQuotaController`，管理余额/账本的只读Controller。

1. 权威Balance UNIQUE(app,UTCYYYYMM)冻结subscriptionId/planId/planVersion/totalUnits/remainingUnits，原子条件UPDATE不许负数；真实耗额成功才写Ledger和Outbox的事实。
2. Internal POST body`chargeId,principalId,credentialGeneration,apiId,routeId,appliedVersion,snapshotChecksum,units`，通过已验证的内部密钥+回环/内网限制；Control以历史`ConfigRelease`对应快照重校验privateness+quota policy/单位/绑定，不接受请求自带appId作为身份。
3. 计量事务详见权威设计6.2：校验App/Developer/Principal/Scope/订阅和当前UTC月份→冻结余额→Ledger chargeId UNIQUE串行幂等→条件扣减→Outbox写NEW→提交后授权；并发重复如果Unique冲突，**回滚当前事务后在新事务查询已提交的Ledger**，匹配返回duplicate、不匹配409。
4. **跨月幂等**：同chargeId重试必须从已有Ledger返回原月结果，不能误用当前月份再扣一次；同chargeId不同可信上下文409。若重试时App/Token已失效则403不再代理，历史Ledger仍保留。
5. Control/MySQL不可用和超时→被计量路由503；未计量业务不访问quota。单位扣减是**授权准入**，已授予后下游500/取消不退款。
6. 增量表共`fg_app_credential_state`、`fg_node_capability`、`fg_quota_balance`、`fg_quota_ledger`、`fg_outbox_event`五张，不引入Kafka；Outbox预留leaseOwner/leaseUntil/lastErrorCode给C。

**验收**：双Gateway100并发额度5仅5不同grant、5Ledger、5Outbox、remaining0；同chargeId100次只一笔，另payload409，跨UTC月份原归属，MySQL失败不超发。提交`feat(v11-b): persist authoritative quota grants and outbox`。

## B-12 B4｜Gateway非阻塞计量链路与真实过滤器顺序

1. 新`QuotaAdmissionGlobalFilter`候选order=+10（**必须本机验证真实组合链及Spring Gateway 5.0.x，不能只看数字**），目的是处于已有`CallerAuthFilter -100`、`AdmissionGlobalFilter -90`内层，Route CB筛选的短路内侧，但在Netty路由前。原并发许可必须在complete/error/cancel都释放。
2. 内部Quota WebClient设置短且有总上限timeout、inflight上限、同chargeId有限重试；`Mono.defer`/Reactor非阻塞，不得`block()`/JDBC/同步Redis、不重试下游HTTP POST。
3. 触发`CB OPEN`必须验证配额请求不执行；如果组合过滤器无法做到不破坏V1，**先拒绝这类混合治理配置的发布**，或由负责人明确批准不同扣量业务语义，不能假装已解决。
4. Redis速率429、本机并发503在Quota之前短路；Control失联/DB失联仅计量503，未计量路由返回旧V1成功；Grant成功后的下游任何结果仍扣量。
5. 错误体与已有Request ID统一，`QUOTA_EXHAUSTED`独立429，`QUOTA_UNAVAILABLE`503，权限/订阅403；指标只低基数记录，不用appId/chargeId标签。

**验收**：真实Route CB、订单（请求尝试）不等于成功响应，计量只在授权后转发，取消/超时/异常无本机许可泄漏。提交`feat(v11-b): enforce nonblocking metered admission`。

## B-13 B5｜正式管理产品/指南同步

- 更新`/console/`：应用详情签发/轮换/吊销与一次性复制、订阅本月/下月日期选项、余额和本月使用情况、账本游标分页、API/路由USAGE_QUOTA策略表单、发布中心能力门槛与节点生效状态；原服务/API/路由和故障实验保留。
- OPERATOR/VIEWER新增业务写按钮只读，后端真403；状态未知显示“未取得/尚未启用”，**不预设余额或以Outbox NEW称为Kafka已统计**。
- 生成`/console/guide.html`并更新`docs/guides/product-console-guide.md`，以“客户→应用→订阅→签发→计量发布→第六次429→账本”做一条完整中文操作路线。
- 完整功能与正常页面不是单纯改旧`/test-console/`或引新框架；手机375px、PC1280px使用实际浏览器验收待负责人。

## B-14 B6｜实际代码/SQL/HTTP验收与提交格式

**每个小块提交即运行定向测试；本块最终至少**：
- Java17 `mvn -B -ntp -pl flowgate-control,flowgate-gateway -am test package`（Maven reactor模块名在Codex本机验证）、旧Controller/Gateway测试与新`V11Quota*Test`；
- Node`scripts/v11-a-console-test.mjs`、`scripts/product-console-test.mjs`、旧Block2/3控制台合同与新`scripts/v11-b-console-test.mjs`；
- 新`scripts/v11-b-smoke.py`复用隔离Lab/加入两个新Gateway、MySQL8.4/Redis7、固定可注入UTC Clock；验证额5/100并发、幂等100/跨月、混合旧Gateway、客户暂停/凭证轮换、发布/回滚、Rate/CB/Control/DB故障、旧Caller全链路；
- 故障注入不破坏用户持久卷，不放明文Token/数据库内网密钥/HTTP Body到Git；原始记录本机`.local/acceptance/`，仓库仅脱敏摘要`docs/progress/evidence/v11-b-*.json`。
- `docs/progress/v11-b-quota.md`追加每一步实际文件/提交SHA/命令/退出码/通过失败跳过数，不能以A旧17JUnit/155HTTP替代B实测。README、AGENTS、产品指南同步。
- 如果B0失败，**停止**不签发App Token；如果B1发布门禁不能证明全接流节点支持，**停止**不创建计量Route；如果B3账本幂等失败，**停止**不允许真实代理。

## B-15 Git、阶段状态及剩余债务

- B设计/任务已提交`feat/v11-b-quota`，**本阶段尚未开始编码，也未运行B0/B1/B2/B3/B4测试**。新增合同不能被当成P0已修。
- B来源是`feat/v11-a-business`，含A复审报告和完整真实代码，PR #7仍Draft。B只在独立分支按B0先修，再逐提交B1～B6；不改A历史，也不自动合并main。
- **用户本轮已明确将A两项P0纳入B，不再要求A分支先修**；从此B0是实际要做的修复，不需要Codex重新打开A独立修复任务。V1产品真人验收/全量主线收口仍在最终门槛，不能以B完工自动宣告V1.1可发布。

## B-16 本地实施记录（2026-11-07）

### B0 已独立完成并通过，允许继续B1

`SubscriptionSchedule`按目标UTC月与所有既有订阅区间交集锁定套餐（含已到期/暂停行），新订阅仅单个UTC月；保留应用行锁和相邻下月边界。Hikari强制每个业务连接`SET time_zone='+00:00'`，Mapper使用UTC_TIMESTAMP(3)；原V1节点写入/年龄比较统一UTC，测试夹具同步，不让JVM本地时区污染心跳。

实际`mvn -B -ntp -pl flowgate-control -am test package`退出0，Contract7+Control12=19 JUnit通过，0失败/跳过。V11-A与原产品Node两套、产品离线指南一致性退出0。`scripts/v11-b0-smoke.py`真实运行两套隔离Lab：MySQL默认+08:00、JVM分别Asia/Shanghai和America/Los_Angeles。实际Control闲置Hikari连接会话均+00:00，独立JDBC/Hikari+实际MyBatis Mapper probe证明CURRENT=UTC、Instant时钟正确，2026-11-30T23:59:59.999Z与12-01零点精确回读；包含同月非重叠已过期套餐拒绝409、原A全部权限/锁竞争/旧Caller与节点ONLINE回归。

证据`docs/progress/evidence/v11-b0-smoke.json`passed=true，旧A155条历史证据未覆盖。脚本只设置自己新建MySQL容器的启动默认时区，不修改用户数据库全局时区/原有卷；临时probe表仅测试连接可见。两项P0已通过独立实证，本提交之后才编码B1～B6。

### B1 协议与默认关闭发布门禁已实现

新增独立`QuotaPolicy.Plan`与`ENFORCED_QUOTA_V1`，只允许1..100准入单位、私有启用API/Route且一个实际路由仅一条Quota。旧Rate Rule不解析Quota，旧schemaVersion/checksum不改。路由冻结quota metadata，缓存确认同时核对版本计划；无Quota为NONE并保留原缓存/恢复测试兼容。

新增`fg_node_capability`与`QuotaActivationService`，完整接流名单默认空、激活开关默认false；目标快照含Quota的发布和历史回滚都检查当前可信节点版本/checksum/15秒内能力。默认关闭时旧心跳不依赖B新表；启用后不含features的旧报告覆盖为无能力。Gateway此步**尚不上报支持Quota**，应用凭证仍409；仅在B4真实过滤器完成后增加能力报告。含Quota实际发布/双新节点联调留到B6安全闭环，不声称本步已激活真实计量。

实际`mvn -B -ntp -pl flowgate-control,flowgate-gateway -am test package`退出0：Contract10/Control14/Gateway22共46通过，0失败/跳过。含新增3项Quota协议（私有/唯一/旧NONE）及2项完整名单/默认关闭/缺失过期门禁测试；后续收紧私有API必须有明确路由及未来心跳拒绝，Contract/Control定向复测退出0。B1没有发Token或开放计量流量。

### B2 内部凭证生命周期已实现，公开签发仍409

专用`fgapp_`49字符Token、`AppCredentialSession(APP_CALLER)`、`fg:auth:app:`/index与`fg_app_credential_state`代次完全独立。应用行锁串行关联及generation变更，独立事务提交后才Redis Lua发布；Lua拒绝迟到旧代次覆盖新会话，DB再校验后才返回一次性Token。发布/清理失败返回明确503状态，DB代次/撤销仍有效，不假装跨系统原子。

Gateway增加配额私有路由APP解析及代次属性，但只有B4真实QuotaSupport组件存在才允许走此分支；本步尚无实现，无法代理计量流量。旧43字符Caller/旧namespace不改。公开credentials Controller仍A阶段409，不存在可对用户启用的中间态发证接口。

实际受影响Maven test/package退出0，Contract10/Control15/Gateway22=47通过；新增凭证用例证明DB commit先于新namespace Redis发布、APP_CALLER类型、49字符格式及Redis失败不回滚代次。首次测试编译因Redis/MyBatis重载泛型推断失败，补显式List/Principal类型后复跑通过；不改生产语义迎合测试。真实并发轮换/撤销/残留Token安全验证留B6。未启用应用凭证、未扣额度。

### B3 已实现并通过受控真实数据库验证

新增余额/唯一chargeId账本/Outbox三表（B新增总计五表），固定SQL初始化唯一余额、持行锁后条件扣减、流水及Outbox同事务；内部端点只接受受信密钥/本机来源与严格字段，查询指定历史版本校验私有Quota/Scope。授权事实不含Body/Header/Token，管理账本不返回fingerprint等内部Hash。

`QuotaChargeService`使用独立READ_COMMITTED事务及应用/客户/身份共享锁，避免等待撤销后仍用旧RR快照授权；余额初始化唯一键竞争由无损upsert序列化，再当前读识别并发重复。UNIQUE冲突路径在整个事务回滚之后新事务重读。原grant重复先查Ledger，不要求次月新订阅；当前DB停用/代次仍先检查。余额冻结套餐/订阅版本，历史跨月订阅显式拒绝，不截断数据。

实际Control/Contract test package退出0。独立`scripts/v11-b3-smoke.py`在自建Lab通过，调用真实Spring事务Bean（web NONE、无公开发证API、当前Gateway仍V1快照）：100线程5额度恰好5grant/95耗尽、Ledger5/Outbox5/余额0；同chargeId100次仅一条新流水/Outbox；不同合法历史上下文409、停用后重放403。重启测试专用Spring上下文，以DB连接session timestamp切到下一UTC月，重复仍返回原月且没有第二余额行。证据单独保存，未覆盖A/B0历史。公开credentials仍409，B4尚未开始计量代理。

### B4 本地实现与组合过滤器验证（2026-11-08）

新增非阻塞Quota Filter（order=10、单机64个hop、每次2秒/总5秒、仅网络错误同chargeId重试一次），成功Grant才转发；配额拒绝独立异常不算CB失败样本，不进入假降级。Request ID入口删除外部AppId/ChargeId，Route冻结策略和身份属性构成内部请求；指标仅routeId/固定outcome。只有真实QuotaSupport组件存在才上报能力；公开凭证还受默认OFF及全接流名单门禁保护，用户环境未激活。

新增`V11QuotaFilterTest`，使用当前5.0.4官方`SpringCloudCircuitBreakerResilience4JFilterFactory`与实际Resilience4j注册表，按真实Route CB order=1组合Quota order=10。已执行：未计量零Control hop、429无下游且不算CB样本、不假降级、OPEN零新增hop/下游。最终真实双Gateway HTTP组合与故障注入仍在B6验证，不能以此代替B6。定向Maven test/package Contract10+Control15+Gateway24=49通过，失败/跳过0；首次沙箱运行Mockito attach不可用，沙箱外同命令退出0。未运行全项目回归。

### B5 业务控制台与指南（2026-11-08）

新增配额与账本页面，应用名称选择、UTC月、MySQL余额和安全流水游标分页；不存在余额行显示尚未冻结，不冒充0。应用页接真实凭证status、确认签发/轮换/撤销、一次性密码输入回执/复制/清空；离页/退出同时销毁回执与模态DOM，迟到响应按epoch丢弃。新增计量规则表单及发布中心实际开关/完整接流名单/节点能力提示，订阅增本月/下月UTC选项；中文指南生成完成，无Kafka图表或新JS框架。

已执行Node：`v11-a-console-test.mjs`、`product-console-test.mjs`、`block2-console-test.js`、`block3-console-test.js`、`v11-b-console-test.mjs`全部退出0。B新增测试执行真实模块与模拟DOM，覆盖UTC跨年月份、units边界、只读权限、确认签发、一次性回执清空、空余额和读取失败不显示旧事实。曾误用两个旧测试的.mjs后缀，命令MODULE_NOT_FOUND；按实际.js文件名执行通过。1280/375px与键盘真人浏览器验收未执行，留负责人。


## B-17 B6定向验收与交付状态（2026-11-08）

### 代码与提交

| 阶段 | 本地提交 | 结果 |
| --- | --- | --- |
| B0 | `9a4708b` | 同月套餐禁止第二次选择、Hikari会话UTC；先独立真库通过才开始B1 |
| B1 | `a92a709` | 独立Quota协议、冻结Route计划与完整接流节点门禁 |
| B2 | `57721f2` | APP_CALLER专用Token/namespace、持久generation与DB先提交 |
| B3 | `1058467` | MySQL余额/唯一Ledger/Outbox同事务、历史版本重验与幂等 |
| B4 | `1ca5f6f` | 非阻塞WebClient预准入、官方CB顺序/错误边界及受控公开凭证 |
| B5 | `0d5438a` | 原生业务控制台、真实凭证/余额/账本/计量规则及中文指南 |
| B6 | 本节所在提交 | 真HTTP/隔离故障与边界测试、脱敏证据和部署说明 |

B6额外补`V11QuotaCredentialControllerTest`2项验证默认OFF无发证副作用、缺节点禁止签发但不阻碍安全撤销；Gateway测试另补取消释放全部64个hop槽、满载503与身份域/namespace隔离。UI凭证status迟到响应按epoch隔离，ACTIVE只说明DB代次启用，不声称Redis会话可用或未过期。

### 实际执行结果

- `source .env`后Java17运行`mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package`，退出0；Contract10/Control17/Gateway26/Demo4，共**57项、失败0、错误0、跳过0**。这是当前新增/修改及直接受影响模块定向验证，没有执行全项目统一大回归或长期性能压测。
- `node scripts/v11-a-console-test.mjs`、`node scripts/product-console-test.mjs`、`node scripts/block2-console-test.js`、`node scripts/block3-console-test.js`、`node scripts/v11-b-console-test.mjs`均退出0；补UI迟到响应测试后受影响两套再次通过。`python3 scripts/build-console-guide.py --product --check`退出0。Node为真实ES模块/模拟DOM，不是负责人浏览器体验。
- `python3 scripts/v11-b-smoke.py`最终退出0，**20组真实HTTP断言通过**。证据`evidence/v11-b-smoke.json`：MySQL8.4、Redis7、两个实际新Gateway、Control、Demo/受控HTTP下游，均专用随机端口，无用户持久卷。100并发Free5精确5grant/95耗尽；内部100次同chargeId仅1条新Ledger/Outbox；各余额total=remaining+SUM(ledger.units)，Ledger逐笔匹配Outbox，仅NEW。
- 真实HTTP覆盖：49位App格式与旧Caller隔离、轮换/撤销残留Redis仍403、App/Developer停用、并发双发证仅1主体、历史版本/校验和/API/units/payload冲突、当前版本更新后旧版本仍可重试、无Scope/订阅不扣、ADMIN写与OPERATOR/VIEWER只读、低基数实际指标、Control/MySQL不可用计量503且公开非计量200。
- 实际官方Route CB：OPEN零配额调用/零下游；Quota429不假降级、不计失败样本；Rate429/并发503先短路不扣，Grant后500/timeout/cancel仍扣且许可释放。**运行FilteringWebHandler DEBUG排序日志**确认Auth→Admission→官方CB→Quota→Netty，不用手写order代替运行证据。
- 透明HTTP代理在Control提交成功后丢首次回包，实际WebClient重试同chargeId，只有1扣额/1下游POST。验证POST Body/业务Header/201/RequestId真实透传，Authorization及伪造AppId/ChargeId清理。
- 实际启动A基线`befc5d0586fa578d9fe6db398363132d4de3e480`独立历史Jar（`.local/v11-a-legacy-binary`构建，未切换/修改A分支）；单旧+单新时App401、旧Caller200，含Quota发布/历史回滚409；换回新节点并等真实能力心跳才允许计量发布。
- 重启测试Control，用真实Hikari连接session timestamp切到下UTC月：旧charge仍返回原月、无第二余额、没有新月订阅仍可重试；新的charge则因无新月订阅403，证明时钟确已推进。恢复正常时钟后继续。Redis ACL只拒绝Lua时轮换/撤销真实返回503，但DB代次/禁用已提交，恢复Redis后旧会话仍403，绝不恢复旧授权。
- `python3 scripts/v11-a-smoke.py`在当前B代码默认OFF环境重跑退出0，**155条HTTP记录**，重复增量迁移、旧快照/表/索引事实、角色矩阵、同App锁竞争、旧Caller授权/撤销及产品资源兼容；新摘要`evidence/v11-b-a-compat.json`，原A历史证据不覆盖。B3原独立Spring服务并发/跨月实测摘要保存`evidence/v11-b3-smoke.json`；B0证据保留。

### 初次失败与最终收敛

初次沙箱Mockito attach受限；沙箱外定向命令通过。B6夹具初次上游路径没用`/demo/`，后续新增下游没有同步Gateway白名单，均被真实校验拒绝，修正测试配置而非放宽业务规则。混合版本恢复时测试提前读到旧能力，改为等待新节点真实心跳。日志级别环境变量会丢类名大小写，改精确Spring JSON配置后取得真实排序日志。跨月夹具曾用环境别名覆盖Hikari初始化而触发pool sealed启动失败，改用与B3一致的明确命令行初始化参数；最终正反向跨月HTTP均通过。故障注入期间已取消HTTP连接可产生测试代理BrokenPipe日志，最终断言和清理正常；代理现忽略预期断连噪声。失败尝试仍在本机`.local/acceptance/`保留，不冒充通过。

### 依赖、注释、保护与剩余门槛

未增加Maven依赖/npm库/新框架；复用原生WebClient/Reactor、官方Gateway CB与Resilience4j、Micrometer、既有MyBatis-Plus事务和Redis Lua/会话基础。Plus没有整块复制业务代码，也未新增Redisson/Kafka/分布式锁。核心Quota协议/交易/凭证/门禁/过滤器/页面有中文类或流程说明，关键长流程含`// 1.`、`// 2.`、`// 3.`；`git diff --check`通过，新增SQL只有CREATE IF NOT EXISTS。仅新增当前B范围文件。

用户`.env`、IDE配置、原持久容器/卷未修改或停机。所有本轮Lab仅终止自建JVM并清理自建容器；最终Docker只剩原`flowgate-local-mysql-1`与`flowgate-local-redis-1`。部署说明与占位`.env.example`补开关默认false/名单默认空，**用户真实库未迁移，正式环境未激活计量**。需负责人备份后确认增量SQL、升级完整接流节点并显式设置开关/名单，再按中文指南抽查。默认关闭可继续旧V1/Caller。

B完成的是功能分支实现与定向验收，**ChatGPT源码复审、负责人1280px/375px/键盘真人浏览器验收、旧V1/PR正式收口、V11-D长期性能验收均未执行**。不承诺长期吞吐或Kafka投递；Outbox积累与未来消费/清理由C设计处理。阶段到此停止，不进入C/D。
