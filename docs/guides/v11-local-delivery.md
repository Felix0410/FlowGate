# FlowGate V1.1 本地完整交付手册

本手册管理独立演示环境。它与您原有的IDE、数据库、Redis、Kafka分别运行，不表示原环境已启用B/C。原V1启动方式仍见README；不要同时占用相同应用端口。

<a id="start"></a>
## 1. 准备与只读检查

需要Java17、Maven、Python3、已启动的Docker及Compose。先在项目根目录构建：

```bash
mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package
python3 scripts/v11-local.py doctor
```

Java版本应为17；必要时先在当前终端设置自己的`JAVA_HOME`。根`.env`必须已有`JAVA_HOME`、`FG_BOOTSTRAP_ADMIN_USERNAME`、`FG_BOOTSTRAP_ADMIN_BCRYPT`、`FLOWGATE_INTERNAL_KEY`。管理员登录密码是生成BCrypt时使用的原始密码，不能把BCrypt字符串当密码。脚本只读`.env`，不会重设账号密码。缺项时参考`.env.example`手工配置；不要把密码、Token或整个`.env`发到日志/提交。

`doctor`默认检查当前配置的V11条件，`--profile v1`只要求V1基础条件，不要求Kafka。`--json`输出脱敏结构。退出0表示所选配置就绪，2表示存在BLOCKED/UNKNOWN，1表示工具自身失败。无权限或事实不足只能UNKNOWN。诊断不登录、不写会话、不迁移数据库、不改开关。IDE/外部进程的真实启动参数无法证明时会保持UNKNOWN。

## 2. 启动独立演示

```bash
python3 scripts/v11-local.py up --isolated
python3 scripts/v11-local.py status --isolated
```

首次启动会建立带独立身份的新Compose项目、MySQL/Redis/Kafka命名卷、新数据库；仅对这个可验证的新库安装V1/A/B/C结构并做四项只读风险预检。Kafka协议就绪后才启动Java服务，异步仅在Control运行。已有库不会因为重启就被覆盖安装。生成的运行文件放在私有`.local/v11-demo/`，不回写根`.env`。

默认打开`http://127.0.0.1:8081/console/`。Control/Gateway/Demo端口分别8081/8080/8085，依赖为13407/16480/19192。登录使用`.env`中的管理账号及您自己的原始密码。**up退出0仅说明服务启动成功；初始V0业务门禁应受阻。**

端口冲突时脚本停止，不杀占用者。可以自行停IDE里旧项目，或一致覆盖端口：

```bash
python3 scripts/v11-local.py up --isolated --instance practice --control-port 18081 --gateway-port 18080 --demo-port 18085 --mysql-port 23407 --redis-port 26480 --kafka-port 29192
python3 scripts/v11-local.py status --isolated --instance practice
```

这时前端为`http://127.0.0.1:18081/console/`，业务调用走18080，Demo上游18085。脚本同步Control URI、Demo白名单、Console Origin与Gateway指标地址。之后必须一直使用相同`--instance`；已存在实例不能用新端口悄悄接管。可用`--config PATH`只读指定其他静态环境文件，不支持执行Shell表达式。

## 3. 先发布公开非计量初版

1. 进入“API接入→服务”，登记演示服务，代码如`demo`，上游`http://127.0.0.1:8085`（覆盖端口后改用实际Demo端口），启用。
2. 在“API”新增GET API：公开路径`/api/practice/public/{id}`，下游路径`/demo/fast/{id}`，选择刚创建的服务，设为公开并启用。
3. 在“路由”关联这个API，填写唯一的路由标识并启用。暂时不要绑定计量规则。
4. 进入“发布中心”，发布当前草稿。等待节点在线、applied版本与当前版本及checksum相同，不只看PERSISTED。
5. 调用`http://127.0.0.1:8080/api/practice/public/1`应成功。首页B状态还会校验完整接流节点名单、最近心跳和`USAGE_QUOTA_V1`能力。它们全部真实满足后才允许发证。

隔离模式只在自己的进程环境显式启用B/C和真实节点名单。不要为了消除提示把根`.env`或`.env.example`全局改成true。

## 4. 客户、应用、套餐、订阅与APP凭证

1. “客户业务→客户”创建客户，打开详情创建其应用；保留应用编号用于后续筛选。
2. “套餐”新增`Free5`，每月准入单位填5。套餐额度创建后不可修改；调整请另建套餐。
3. “应用详情→订阅”新增当前UTC月套餐：生效时刻为当月1日00:00:00Z，结束为次月1日00:00:00Z。区间左闭右开。同一UTC月不能再次选择套餐，409是保护机制；不要删除账本来重试，练习请新建应用。
4. “应用详情→APP凭证→查询与管理APP凭证”，授权范围输入`demo:read`，首次签发并确认。若受阻，按原因检查初版、节点与doctor，而不是跳过门禁。
5. Token仅在本页领取一次，默认遮盖，离页/刷新/退出会清空。妥善临时保管，不放URL、浏览器存储、截图、Git或终端历史。轮换使旧代次失效；撤销用于紧急停用，ADMIN操作。旧Caller不等于APP凭证。

## 5. 发布私有计量API并验证5/6

1. “API接入→API”新增GET：公开路径`/api/practice/private/{id}`，下游`/demo/fast/{id}`，同一Demo服务，设为私有，requiredScope填`demo:read`；新增并启用关联路由。
2. “治理策略→计量规则”新建启用的USAGE_QUOTA规则，范围API，每次单位`unitsPerCall=1`，绑定刚创建的私有API。每个目标只能有唯一生效计量规则。
3. 发布新版本，等待可信节点applied确认。只保存草稿不会改变网关行为。
4. 使用支持隐藏凭证的HTTP客户端，在请求Header添加`Authorization: Bearer <一次性APP Token>`，对`http://127.0.0.1:8080/api/practice/private/1`发6次GET。不要把Token作为URL参数。
5. 前5次200，第6次429且业务码`QUOTA_EXHAUSTED`。“用量与对账→余额与账本”筛选该应用、当前UTC月，应看到总5、余0、5条授权流水。无Token401，范围错误403；旧Caller不能访问这条APP计量路由。

成功Grant即扣量，下游随后失败也不自动退款。余额以MySQL为准，不从异步日报或Redis推导。重复练习请创建新应用/套餐订阅，不能重置历史余额。

## 6. Kafka、日报和历史对账

“运行监控→异步事件”查看Broker协议、Worker最近实际轮询、Consumer分区和失败/积压。SENT只代表投递，不代表已消费；“用量与对账→用量统计”筛选应用及UTC日期，最终应聚合5次/5单位。Kafka故障时账本仍是权威，日报可能滞后；恢复后应处理原Outbox，不要再次调用业务接口模拟补账。失败重试/补投仅ADMIN，并遵守原事件与等待期限制。

对账只能查昨天及更早的UTC日。今天未封账，拒绝查询是正常行为，不能显示虚假的MATCH。新环境没有历史数据时历史空白也正常。本轮自动测试的历史MATCH来自隔离Control专用MySQL会话时钟创建的新Grant，恢复时钟后再对账；没有修改旧Ledger，也没有改变用户数据库全局时钟。常规使用者不用设置测试时钟，第二天可查询昨天真实事件。

## 7. 看懂就绪提示与常见问题

- V1 BLOCKED：先发布非计量初版，检查节点在线、版本/checksum及实际接流名单。
- B BLOCKED：核对开关、完整名单、心跳和能力；不要强行发证/绕过发布门禁。V0不能签发。
- C BLOCKED/UNKNOWN：查看Broker真实协议、Worker/Consumer事实和积压；端口开放或历史成功计数不能证明当前可用。
- 页面“数据库迁移 UNKNOWN”：页面没有全表探针；运行对应环境的只读doctor。已有库缺表需要负责人备份并单独授权迁移，本脚本不修改原库。
- “数据库或会话服务不可用”：先查正确实例的status及依赖容器状态；检查是否访问了旧IDE端口，勿重置密码或删除卷。
- VIEWER不能保存，OPERATOR不能执行ADMIN专属凭证/账务操作；浏览器隐藏按钮之外，后端仍校验权限。
- 换端口后指标链接应使用同一隔离Gateway地址；管理会话不会发送给Gateway。
- 新Jar与已启动实例不一致时先stop本实例再up。不要直接杀端口进程。

## 8. 停止、恢复与验收边界

```bash
python3 scripts/v11-local.py stop --isolated
# practice实例则增加 --instance practice
python3 scripts/v11-local.py up --isolated
```

只停止身份匹配的自有JVM/容器；卷、客户、订阅、Ledger与Kafka offset全部保留。重复up不会重置账号或额度。失败也保留卷，没有自动删卷入口。用户常驻环境不属于这个脚本的接管范围。

本轮自动测试与负责人验收分别记录。请负责人按`docs/progress/v11-e-local-delivery.md`的E6清单在1280px/375px、键盘与真实业务链路手测；未勾选前不表示正式交付通过。不自动合并PR，也不进入D性能阶段。
