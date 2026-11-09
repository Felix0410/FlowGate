# V1.1-E 本地交付实施

基线 `docs/v11-e-local-delivery-closeout` / `a42b852`，功能分支`feat/v11-e-local-delivery`。权威合同`CODEX_V11_E_LOCAL_DELIVERY_PROMPT.md`、`docs/progress/v11-e-local-delivery.md`。

## E0 当前事实与安全盘点

- 用户工作区最初干净。现有flowgate-local MySQL/Redis/Kafka容器ID分别991bea342391/97de4e0045c1/9f702bcb9815，三个对应持久卷不得修改或删除；.env摘要与权限只保存在本机.local/v11-e-assets-before.json，未输出配置内容。此次仅盘点，不改数据/进程。
- 当前三Jar路径仍为各模块target，运行器run-local.py只覆盖V1基本启动，不迁移V11，不改变其契约。
- SQL共22表（V1十、A/B十、C二）。V1初始化有INSERT IGNORE配置V0；三份DDL幂等，但只允许脚本新建、身份可验证的隔离库初始化；已有库一律只读。四项preflight为同月多订阅、跨月、冻结定义不符、冻结月另订阅，任何非零不得激活。
- B门禁：Control激活开关、完整trafficNodes、每个节点15秒内真实心跳/能力、当前版本与checksum一致、USAGE_QUOTA_V1。必须V0→先发布非计量V1→节点真实能力→签发APP→计量V2，不能跳过。凭证只ADMIN可签发/轮换；紧急撤销仍可在开关关闭时执行。
- C仅Control使用Kafka。Broker协议可达不是Listener/日报追平；SENT仅ACK，不等已消费。余额/Ledger/Outbox同事务权威，日报异步；今天不能生产MATCH。
- 既有只读接口：/status；认证后/admin/releases/current、/admin/nodes、/admin/quota/status、/admin/async-events/summary、/admin/usage、/admin/reconciliation。CLI不得借页面Token或用登录写会话冒充只读doctor；无权限/不可证实运行配置为UNKNOWN。
- 用户应用端口可能由IDE占用，不能杀进程。E隔离将配置独立端口与唯一Compose项目/数据库/卷；复制不可变Jar、自有PID+起始身份+owner验证；stop只停自有进程和容器并保留卷。允许显式端口覆盖时同步Console/Gateway/Demo白名单及指标来源，不新增敏感探针。

## 命令合同

`python3 scripts/v11-local.py doctor [--json] [--profile v1|v11]`只读；0就绪、2阻塞/未知、1工具错误。
`up --isolated`、`status --isolated`、`stop --isolated`仅自己的演示环境；可显式覆盖六个端口。默认应用8081/8080/8085，冲突停止；默认依赖13407/16480/19192。不支持删除卷命令。根.env只读，必需管理员哈希/账号和内部密钥，不生成默认管理员明文。

E0已完成代码/配置审阅，git diff --check通过；未运行E新脚本/Java/HTTP/浏览器，不预填通过。E1～E5待实现，E6仅负责人真人勾选；不进入D、不合并。

## E1 只读doctor

新增v11-local.py doctor [--profile v1|v11] [--json]与标准库共用探测。静态解析export配置，不source、不执行命令；数据库会话READ ONLY事务、四项风险预检；仅exec与配置端口/Compose服务/镜像身份一致的容器。Kafka以协议元数据和实际消费组状态检查，不以TCP开放冒充READY。外部IDE归属和无法认证的运行开关保持UNKNOWN，不登录写Redis会话。输出schemaVersion=1，状态与原因/下一动作，返回0/2/1；原始异常/URL凭证不输出。

`python3 scripts/test_v11_local.py`退出0，8项夹具测试覆盖V1不依赖Kafka、缺B/C表、无可信节点、Kafka不可用、全部事实齐备、UNKNOWN、脱敏及配置不执行。初次导入发现括号语法错误，修正后才运行通过。实际`python3 scripts/v11-local.py doctor --json`退出2（当前常驻环境未被认证为完整V11），脱敏JSON保存在.local/v11-e-doctor-baseline.json；根.env摘要比较未变。没有运行迁移、启动或写业务记录。git diff --check通过。

## E2 隔离启动与安全停机

新增compose.v11-demo.yml、v11_local_runtime.py，扩展up/status/stop --isolated及六端口/实例覆盖。独立project/owner标签、0600身份/环境、0700目录、PID起始身份与实际监听者、命名卷归属、串行文件锁；只初始化工具首次创建且有连续凭据的新库，四项风险全零后启动；协议和实际Stable消费组校验，不伪造V0节点能力。stop不删容器卷；失败仅停本次新启的自有进程/依赖。动态runtime-config.js覆盖只在隔离目录，Gateway指标/CORS/Control URI/Demo白名单一致，不改根.env或run-local.py。

实际e2-check项目flowgate-v11-demo-c2d1d8236a0b，六端口55955/55956/55957/55958/55959/55960。三个命名卷同project前缀，mysql-data/redis-data/kafka-data。首次up退出0但业务门禁BLOCKED（V0）；重复up PID不变；stop0→up0后管理员数量与Ledger数量不变，V0仍不能当计量就绪；占用端口返回2且未生成state/数据库/卷；最后stop0并保留三卷。原始脱敏证据.local/v11-e-e2-smoke.json。

第一轮重启验收失败，随后用真实自有TCP连接复现TIME_WAIT造成误拒绝；SO_REUSEADDR兼容已关闭连接，同时LISTEN/PID/Docker身份校验仍在。新增回归先真实失败后修复通过。`python3 scripts/test_v11_local.py`现12项退出0；临时py_compile曾因macOS外部缓存目录受限失败，改用内存编译/实际导入，不算测试通过。E2仅执行package -DskipTests准备Jar，不计Java测试结果。git diff --check通过。用户原三容器/卷未动；没有删除演示卷命令。

## E3 真实后台就绪诊断

工作台/发布中心复用小型四行就绪区，仍7域20能力。V1核对版本/checksum/节点；B的既有认证GET /admin/quota/status新增只读资格字段，复用requireReady同一完整门禁，无新端点/写语义。C既有summary新增框架Listener实际运行/分配/暂停事实及Worker最近完成轮询事实，Broker协议/Worker/Consumer/积压分别判断，不用历史计数或SENT冒充已统计。数据库迁移明确UNKNOWN并指向只读doctor。

应用凭证界面需要本次后端门禁READY才提供签发/轮换；未知或受阻显示原因，ADMIN紧急撤销仍不受新签发门禁阻挡。离页清密和角色/后端复验不变。新增本地离线交付手册与生成器--delivery支持，不新增第八个菜单或敏感探针。

`mvn -B -ntp -pl flowgate-control -am test package` Java17退出0：Contract10+Control35=45，新增5项检查OFF不碰表/V0不发证/缺可信节点/缺证据UNKNOWN/Worker心跳不等历史计数。E就绪Node及原B Node通过，--delivery --check和git diff --check通过。B旧测试补入真正的就绪API夹具，没有删除原权限/撤销/回执清理预期。全三模块、真实B/C/浏览器在E4/E5执行。

## E4 B/C真实闭环

`JAVA_HOME=<Java17> python3 scripts/v11-e-smoke.py`退出0；实际证据.local/v11-e-acceptance/20261109t125623-347a/result.json。使用E正式up入口、新随机端口/项目/卷，V0签发409、非计量初版及可信节点后才签发，真实5次200+第6次429，余额5/0、Ledger5、Outbox5、日报5；401/Scope403/旧Caller隔离/同月409/VIEWER写403/撤销旧代次拒绝/旧V1两路200。自有Kafka暂停期间1条新Grant积压，恢复只消费原事件；重复Kafka事件未双计。今日查询400。仅对自有Control的MySQL连接注入昨天会话时钟产生新Grant，恢复真实时钟后历史MATCH；没有修改历史Ledger/Outbox或全局数据库时钟。最终doctor READY，重复doctor前后账本计数与根.env相同。finally停机并保留三卷。

首轮20261109t125235-7818失败：时钟注入环境字段绑定导致Control启动失败，整轮未通过，已停自有实例且保留卷。改为SPRING_APPLICATION_JSON精确属性后整轮重跑通过，未删除失败记录。Chromium/旧B/C回归留到E5，不冒充已运行。

## E5 最终实测与指南（2026-11-09）

| 阶段 | 已完成本地提交 | 状态 |
| --- | --- | --- |
| E0 | 967f1d6 | 审计完成 |
| E1 | 392d890 | 只读doctor完成 |
| E2 | b7e9729 | 隔离启动/保留卷完成 |
| E3 | 46859ed | 真实认证就绪状态完成 |
| E4 | 1fae31f | B/C真实闭环完成 |
| E5 | 本节随阶段提交，完整SHA见git log与远端映射 | 定向自动验证完成 |
| E6 | 无 | 待负责人手工验收，未代签 |

实际命令与结果：

- `JAVA_HOME=<本机JDK17> mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package`退出0，75项、失败0、跳过0（Contract10/Control35/Gateway26/Demo4）。最终指南同步后额外`package -DskipTests`退出0只重打资源，未把跳过测试算作测试通过。
- 原8个Node入口加`v11-e-console-test.mjs`共9个，逐项`node scripts/<入口>`退出0；完整命令见证据JSON。首次错误使用本机Node不支持的experimental-default-type参数，退出9；改为既有plain node命令后全通过，未更改旧断言。
- `python3 scripts/test_v11_local.py`14项退出0。夹具覆盖JSON0/2/1、权限UNKNOWN、风险/表缺失/Kafka/节点缺证据、不执行.env表达式、不接管其他PID/卷、TIME_WAIT重启；真实E2另验证二次up PID不变、占用端口拒绝且未建业务资源、stop/up保留卷和账本。
- `JAVA_HOME=<JDK17> FLOWGATE_ASYNC_ENABLED=false python3 scripts/v11-b-smoke.py`退出0，23项原B检查（含双Gateway、并发配额和旧二进制门禁），未改旧预期。
- 同环境`python3 scripts/v11-c-smoke.py`退出0，原26组C真实Kafka/MySQL/双Gateway回归，最终109 Grant=Outbox=Consumed=日报单位；未改旧预期。
- `JAVA_HOME=<JDK17> python3 scripts/v11-e-smoke.py --browser`最终退出0，run=20261109t131204-18e5。新项目flowgate-v11-demo-86e83e1b712d，六端口59101/59102/59103/59104/59105/59106，mysql-data/redis-data/kafka-data三卷停止后仍保留。新库V0发证409→非计量V1可信节点→APP→计量V2，5个200+429，余额5/0、Ledger5、Outbox5、日报5；Kafka停/恢复、重复幂等、今日400及受控真实历史MATCH均通过。最终两次doctor READY且账本计数未改。
- 真实Chromium同一TaskSpace12，1280/375截图已查看，七域、无横向溢出、真实V1/B/C与schema UNKNOWN、键盘Enter/Escape焦点恢复、应用深链接、一次性凭证遮盖/不入存储、离页与前进后退清除、Gateway自定义指标来源且不发管理员Authorization、刷新丢弃会话、VIEWER写按钮禁用、手机导航退出均实测通过。截图仅脱敏业务数据，保存在.local/v11-e-acceptance/20261109t131204-18e5/，JSON内记录摘要。浏览器检查不是负责人真人验收。
- `python3 scripts/build-console-guide.py --product --check`、`--delivery --check`、`git diff --check`退出0。扩展详细中文手册和同源HTML，README加入单一E入口。

浏览器失败记录未抹除：130126标题/按钮重名选择器；130428刷新正确恢复监控深链接但测试等待首页；130650指南已更新导致doctor正确拒绝旧Jar；130950手机退出前测试未先展开侧栏。修正测试交互并打包后整轮重跑通过，没有放宽产品权限断言；VIEWER检查明确要求至少存在一个禁用写按钮。失败实例全部停止、卷保留。

`docs/progress/evidence/v11-e-local-delivery.json`为实际运行后的脱敏汇总。无Token/密码/BCrypt、无原始异常响应。根.env SHA256与权限对比初始私有快照完全相同；原MySQL/Redis/Kafka三个容器ID、运行时长及原命名卷仍在，未执行其迁移/写API/重启。没有删除E失败或成功环境卷。PID不可读时UNKNOWN并拒绝接管，新增回归。

关键Java类和新增复杂脚本/前端汇总逻辑已人工检查中文注释与1/2/3步骤；无新框架/表/依赖，无本轮Plus源码复制，复用现有Spring/Actuator/MyBatis/Kafka与原生ES modules。历史B/C smoke只读源码保持不变。

## E6 停止点与风险

E0～E5自动实施完成，E6任务书所有负责人勾选仍未填写。未运行D正式性能、10万SQL、全故障矩阵；未激活或迁移用户已有环境；未合并main或旧Draft PR。不把本次隔离测试等同用户常驻部署或正式交付验收。Mac上实际验证，Linux路径有实现但本轮未在独立Linux主机执行。卷有意保留会占磁盘，删除需另行明确授权。Ego Lite提示可更新，本轮未升级。

## 远端交付对照

原生`git push -u origin feat/v11-e-local-delivery`实际退出128/HTTP403，未推送main或强推。改用已连接GitHub工具逐阶段创建Git对象；每个远端tree SHA与本地实测tree完全一致，保留阶段父链。因服务端作者/提交时间不同，commit SHA不同，下表同时保留。新的Draft PR只以docs/v11-e-local-delivery-closeout为base，旧PR不修改。

| 阶段 | 本地实测提交 | GitHub提交 | 相同tree |
| --- | --- | --- | --- |
| E0 | 967f1d6606f9854dff2c230070b70d3c5bb0e738 | 9adeabcaa0b18cb609bf8baa4dc5d584834fef72 | 8ededeb773cf0747c79be84fbf8ea348789d801f |
| E1 | 392d890168577af97c6370cd3841f4010142b415 | 4a35ac860cd1fe3cd355779c052f919a212f6422 | 49f57d7215414cff5e221f4b4ca71dd85d09912e |
| E2 | b7e97293925c970827d4905d5545e71572cd18f3 | b8f37e3aeef974d17f49fe5db015fd200bc4784a | 32bf091f8e0eec56343347681683379cf86dad1e |
| E3 | 46859ed21311e8442b6330e29212d27984d71429 | 152195bf11f2766dfce5d8bd02c716522cd4a5cf | 4990a3aadeb46f383cf51f20652abd12d77bc2a7 |
| E4 | 1fae31fa17554bf7689fa4c66d39c6e31015a962 | 7c97f887f03203f9e4f8018295116f07f207c7dc | 61f0ee1c70f5a28e48752b7a3f6d0584c7960f2a |
| E5 | 38b67f8dae7866a581ca02ebb07148a61377037f | 1f27a394ba71df0e2721c682e5d95320a0d8472d | 670990c8e8d56eed5574c996f25c84721530bb88 |

本地原生历史保留为独立local-history分支，规范E分支跟踪远端；没有reset/rebase/force或重写共享历史。此交付记录自身另作docs提交，其源码树同样核对。PR链接及最终head以实际GitHub结果汇报，不预填合并或E6通过。
