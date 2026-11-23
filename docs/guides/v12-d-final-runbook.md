# V12-D Final 运行与恢复

当前只RabbitMQ：V11旧用量、V12 API用量、普通付款权益、促销创单各有独立quorum main/retry/poison。MySQL授权账本/余额/Intent/订单/库存是权威，Redis缓存/Lua候选或队列为空不代表付款成功。

## 本机隔离候选

本轮D使用全新owner和随机端口。根.env、日常flowgate-local的8081/8080、IDE、原数据库及旧Kafka卷没有升级为D。账号/准确地址在gitignored私有`.local/v12-d-access.md`，不使用或传播真实用户密码。

```sh
python3 scripts/v12-d-preview.py start
python3 scripts/v12-d-preview.py doctor
python3 scripts/v12-d-preview.py status
python3 scripts/v12-d-preview.py stop
```

启动会校验owner、PID启动时间、Jar、端口、卷归属；更新Jar时先停自有进程，再持卷启动，等待双Gateway能力上报。停止只停D owner，保留卷与日志，不删除旧Kafka卷，不prune。运行器拒绝占用端口、身份异常及替换正在运行的其他Jar。交付时D隔离实例按合同停止；start已实测可恢复原D数据。旧C预览脚本只用于旧C owner，不能当作D入口。

其他机器从空数据开始：Java17构建后，执行`python3 scripts/v12-d-final-smoke.py --init`，生成本机私有随机管理员/内部密钥和新的隔离owner/端口/卷，安装B/M及C安全CHECK/FK；不读取用户业务库进行迁移。验证`--core`运行实际促销付款、Redis/DB/Rabbit故障、真实COMMIT后ACK丢失探针。性能和更深故障是各自显式脚本，不在日常start自动运行。Docker镜像需已拉取或有网络权限。

## 用户与运营验收

Console“客户业务→API市场”：管理员引用实际已发布API建立商品与不可变SKU，再建立、发布/暂停活动；选真实SKU填写整数分/UTC时间，不手填内部ID。Portal注册→创建应用→市场免费或新应用普通购买→模拟付款→权益→签发一次性Token→真实Gateway→权益用量。促销提交202表示已持久受理、等待订单，随后仍须付款与权益消费。自然UTC月固定，下月权益不提前调用；授权扣额不因下游失败退款。

Console“消息与业务消费”手动读取四链原Outbox分布/Consumed/Broker ready/retry/poison/消费者/促销RETRY_EXHAUSTED，未知显示UNKNOWN、未装表NOT_READY。Broker Ready不含未ACK，SENT不等于Consumed。订单/活动详情有权威库存、事件和对账。APP/Portal/Caller/ADMIN各自认证，VIEWER/OPERATOR不能写新业务或恢复事件。

## 促销失败恢复

自动投递最多8次；第8次后DB仍FAILED，事件页派生RETRY_EXHAUSTED，不能靠重启假装恢复。先查同一Intent、原Outbox、Consumed、Stock/Slot，恢复Rabbit或下游依赖。

ADMIN仅在事件next_attempt_at之后300秒、当前节点启动300秒、无租约、未Consumed时点击“补投原事件”。界面显示UTC时刻与节点剩余冷却；服务器重新锁行校验/CAS和审计，再开放下一批有界投递。两管理员竞争一方成功，另一方409。保留原eventId/payload/金额/月份，不追加新Intent或HTTP抢购。过期Intent迟到只IGNORED_TERMINAL，库存与槽不重新占用；已有Consumed禁止再重排。FAILED_PERMANENT/坏JSON留Poison检查，不能一键解封或当作已消费。

Redis丢失：新抢购503，先从MySQL查询原Intent；ADMIN“从数据库重建Redis”推进REBUILDING/epoch，核对库存后开放新代次。旧token补偿不得污染新代次；订单支付/取消/退款从DB核验。未付款取消/超时释放reserved；已付款退款sold不返售；已用额度退款409，Ledger保留。

## 日常环境升级建议（本轮未执行）

负责人另行授权维护窗口后：只读诊断/核对源SHA→备份MySQL与配置并演练可恢复→停止所有自有Control/Gateway（IDE/非自有进程人工协调）→离线预检B/M/C表、来源CHECK/FK及MQ单路由栅栏→对授权库显式迁移→默认OFF启动旧业务验证→全节点新能力/版本一致后再启用C→验证四链与账本。不要source用户.env进行压测，不执行DROP/TRUNCATE或down -v。

出现失败时关闭新促销准入，保留DB库存/Intent/Outbox等事实；回退前校验旧版本是否识别PROMOTION Order/C CHECK，不能将新产生的业务事实丢回不支持C的旧二进制。保留备份/原事件，恢复同版本候选后再查Consumed和审计；不回Kafka，不删Ledger、不退款已用单位。

SQL实验在D MySQL内部独立克隆schema，未混入业务演示库。`sql/flowgate-v12-d-indexes.sql`仅推荐；先查information_schema确认同名索引，评估维护锁/备份/空间，再在授权窗口逐个安装。回退只删除本维护新建索引，不碰数据。现有id游标API本来稳定；新增索引实测适用于created_at+id时间游标，不能把实验收益宣称为所有接口或生产性能收益。

## 复验入口与证据

- `v12-d-outbox-recovery.py`：真实8失败与300秒自然等待、双ADMIN、原事件100重复。
- `v12-d-rabbit-reliability.py`：真实NACK/Return、12次5秒TTL重试与Poison。瞬态业务回调是明确故障注入，Broker/confirm/TTL/x-death真实。
- `v12-d-legacy-regressions.py`/`v12-d-mixed-gateway.py`：身份/代次/配额/CB/timeout/cancel/回滚/真实旧二进制混版。
- `v12-d-sql-benchmark.py`：新独立schema、两套100k账本/Outbox/Consumed/Daily与审计、EXPLAIN/游标/索引对照。
- `v12-d-gateway-benchmark.py`：两轮DIRECT/PROXY/GOVERNED/V11和V12 METERED，固定50rps、16inflight，fast/slow10ms；是固定到达率对照，不是极限容量声明。
- `v12-d-catalog-promo-load.py`：1000/5000/20000实际HTTP请求数，最大128inflight，不是20k QPS；5000匿名fixture重复请求明确标注，非真实注册。
- `DCacheRaceProbe.java`：真实Redis慢Reader/代次失效交错。代码需经D Lab类路径/私有env调用，勿把密码放命令行。

结果/未执行范围以`docs/acceptance/v12-final-results.md`及`docs/progress/evidence/v12-d*.json`为准。负责人真人与外部独立复审分别PENDING_OWNER/PENDING_REVIEW，自动截图不能代签；所有PR继续Draft，不合并main/打tag/自动升级日常环境。
