# FlowGate V1.1 本地完整交付手册

本手册只管理明确隔离的新演示库，不表示用户原环境已激活B/C。常规V1启动方式保留，不修改根.env、用户库卷或IDE。

<a id="start"></a>
## 1. 先只读检查

运行 `python3 scripts/v11-local.py doctor`，或加 `--json`。0表示所选配置就绪，2表示BLOCKED/UNKNOWN，1表示工具错误。`--profile v1`不要求Kafka；未验证/无权限绝不标READY。

## 2. 显式隔离启动

运行 `python3 scripts/v11-local.py up --isolated`。从已有.env只读取得Java17、管理员账号/BCrypt和内部密钥，不生成管理员默认明文密码。先使用Java17构建三模块Jar。

默认应用8081/8080/8085；冲突会停止且不杀占用进程。可传 `--control-port`、`--gateway-port`、`--demo-port`、`--mysql-port`、`--redis-port`、`--kafka-port` 全链路一致性覆盖。依赖默认13407/16480/19192。`--instance`可建立独立命名实例，后续status/stop必须使用相同实例名。

服务启动成功的up退出0不代表B门禁就绪。初始V0应BLOCKED，先在发布中心创建并发布公开非计量Demo API，再等网关可信能力。工作台“本地功能就绪”逐项说明V1/B/C和下一步，数据库迁移仍需doctor只读确认。

## 3. 业务顺序与停机

客户→应用→Free5套餐→当前UTC月订阅；先发布非计量版本并确认节点，再在应用详情签发APP凭证，建立计量私有API/路由及每次1单位的规则，发布并等待节点应用。前5次授权、第6次429，余额和Ledger以MySQL为准。日报异步、SENT不代表已消费；今天未封账不能假称MATCH。

运行 `python3 scripts/v11-local.py status --isolated` 查看脱敏状态；`python3 scripts/v11-local.py stop --isolated` 只停自有JVM/容器，保留命名卷和账本。重复up不重置密码、订阅或余额；没有删除卷入口。已有库需要迁移时由负责人另行备份并授权。
