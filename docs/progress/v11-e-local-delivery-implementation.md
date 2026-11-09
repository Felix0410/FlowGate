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
