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
