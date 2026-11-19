# V12-B普通订单候选交付记录（2026-11-18）

B0～B7已完成候选代码与本地定向验证，B8独立源码复审和负责人真人手测尚未完成，PR必须保持Draft。新功能没有迁移或部署到用户常驻环境；不进入V12-C活动、V11-D性能，也不合并main或旧PR。

## 基线、提交与范围

- 设计父提交：`0a69307672e4492d2482acab0237bb81f16062bd`，分支`docs/v12-b-order-design`；实际实现分支`feat/v12-b-orders`。
- B0：`875bbfff8754491359c1a14d827938836960d9eb`。先实测、提交A三项P1收口，再进行后续订单实现。
- B1～B7：`8c3beb5`。六张B表、价格快照、普通订单/模拟付款/退款、Commerce Rabbit消费者、Portal/Console与隔离验证脚本。
- Git远端交付结果单独记录在`evidence/v12-b-delivery.json`，不把本地提交当成已推送。

开始时fetch尚未返回，曾按旧蓝图写订单草稿；获得远端B合同后立即对齐设计父分支并暂停后续实现，先完成B0真实验证。旧A的82项历史成绩没有被列作本轮95项；证据中的阶段性构建与最终小幅页面调整也分开记录。

## 实际行为

1. B六表只在明确启用的新隔离库安装。`FLOWGATE_V12_COMMERCE_ENABLED`和`FLOWGATE_V12_COMMERCE_ASYNC_ENABLED`均默认false。OFF不查询B表；缺表、Broker或活跃Worker事实不足时不接受新订单。Broker故障期间原订单付款事实、退款与只读查询仍由DB支持。
2. ADMIN创建不可变正价PAID_BASE，支持UTC本月/下月；普通用户选择自己的应用下单。请求只接收应用、SKU和clientRequestId，价格、单位、用户、API与期间由服务端决定。锁序APP→BASE槽→订单，旧免费权益或旧版同月订阅冲突拒绝。
3. 订单、HELD槽和审计同事务；同用户请求号唯一，同参重放返回同单，不同参数409。支付CAS赢后才写一张SIM收据和一个PAYMENT_CONFIRMED Outbox，事务内不调用Broker。15分钟支付截止、月末停售保护和到期关闭均依DB UTC。
4. 独立`flowgate.commerce.v12`与orders/retry/poison quorum队列，复用B0可靠运输：持久消息、confirm且无mandatory return后SENT，MySQL提交后ACK。权威冲突/格式错误进Poison，临时错误确认复制进TTL retry后才ACK，累计最多12次。
5. Grant、APP/API/月订阅和余额、GRANTED槽、FULFILLED与Consumed在同一事务；用量授权仍由Gateway/MySQL决定，不依赖Rabbit。下月权益不能本月提前调用。
6. 模拟退款不反转Ledger。未履约释放本订单HELD；已履约完全未用则SUSPENDED且保留GRANTED，使用过拒绝。APP锁与Gateway、Worker串行化保护余额。
7. Portal订单/收据/来源权益/Token与用量互链，Console提供筛选、详情、业务事件、ADMIN确认原事件重排和有界完整性审计。最多1000对象，超过范围UNKNOWN；今日PROVISIONAL，积压PENDING，缺口INTEGRITY_GAP，不冒充资金结算。

## 命令与真实结果

环境：JDK17（Microsoft17.0.18）、Spring Boot4、MySQL8.4、Redis7、Rabbit3.13.7、旧Kafka4.1.1；全部为本轮新隔离实例。Java分次95个不同测试通过：Contract11、Control54、Gateway26、Demo4。不是最终一次全95重跑。

```sh
JAVA_HOME=/Users/felix/Library/Java/JavaVirtualMachines/ms-17.0.18/Contents/Home \
  mvn -B -ntp -DargLine=-javaagent:/Users/felix/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar test package
# 初次Demo有3项受沙箱socket限制失败；随后Demo4在允许socket环境定向重跑通过。
# 最后后端调整后以相同JDK/agent对Control及其Contract依赖重跑：11+54通过。
for task_test in scripts/*test.mjs; do node "$task_test" || exit; done
PYTHONPYCACHEPREFIX=/tmp/flowgate-v12-b-pycache python3 -m unittest discover -s scripts -p test_v11_local.py
PYTHONPATH=scripts python3 scripts/v12-b-smoke.py --keep-running
PYTHONPATH=scripts python3 scripts/v12-b-final-runtime.py
PYTHONPATH=scripts python3 scripts/v12-b-replay-smoke.py
git diff --check
```

Java最终定向、Node9入口、Python14均exit0。集成与ACK实验最终通过；期间失败尝试未删除：SQL重复列与JSON文本替换的错误、B0 SQL占位符/队列参数/异步统计等待，见`v12-b-failed-*`和`v12-b0-failed-*`。ACK恢复时发现固定Rabbit镜像白名单未更新和重复CONTROL_PORT参数，修复后恢复最终Jar；第二Gateway原子进程未独立会话导致退出，已改start_new_session并复验双节点可信就绪，没有放宽后端门禁。Python安全测试初次socket限制同样在授权环境重跑通过。

| 实际证据 | 覆盖内容 |
|---|---|
| `evidence/v12-b0-rabbit-fixes.json` | A坏JSON/权威冲突Poison、真实DB暂停TTL恢复、Broker停机后Outbox追平、同卷重建保存消息、mandatory return不误SENT、永久坏Outbox不再claim |
| `evidence/v12-b0-console.json`与截图 | 独立旧V11库零V12表，市场DISABLED非500，旧服务/发布200，真实Chromium |
| `evidence/v12-b7-e2e.json` | 六表顺序及重复两次迁移、CHECK/FK拒绝；双Control/双Gateway、20并发同请求1×201+19×200、100次付款唯一收据、100重复消息唯一Grant |
| 同上 | 真实5×200/第6次429，已用退款409、退款/Gateway竞争、未用退款403与槽保留、待开通退款+迟到消息、新holder保护、本月/下月/API隔离 |
| 同上 | 多Control支付/取消一200一409、过期付款拒绝后关闭；B坏消息Poison、MySQL pause TTL恢复；OPERATOR/VIEWER读200写403；V11旧Free5/Kafka账本一致 |
| `evidence/v12-b-commit-ack.json` | 调用生产CommerceWorker/CommerceService，真实提交后ACK通道断开，Rabbit redelivery且Grant/Consumed各1；不是只抛Mock异常 |
| `evidence/v12-b-replay.json` | 受控新收据权威冲突进隔离后修复，SENT自然等待300秒原eventId重排、审计与唯一Grant；退款与自然运行Worker的竞争观测 |
| `evidence/v12-b-browser.json`及6张新截图 | 普通用户UI购买/付款/异步开通、UI签发Token真实Gateway200、使用后无退款入口、另一订单UI未用退款、后台筛选/收据、退出清会话、1280/375无页面横向溢出 |
| `evidence/v12-b-validation.json` | 分次95 Java、Node9入口、Python14、最终Jar SHA256、测试边界 |

浏览器Token验证使用页面签发凭证后由Node发起真实HTTP，不是浏览器跨域CORS验证。代理操作Chromium不等于负责人真人手测；Escape/Tab只做基本焦点验证，没有声称完整无障碍审计。

## 明确保留的验收边界

- B8远端源码独立复审：PENDING；负责人1280/375和完整键盘真人验收：NOT_EXECUTED。
- 旧V11 B23/C26全故障矩阵本轮未重跑；真实旧Free5/双Gateway/Kafka关键链路已跑。A历史证据原样保留。
- 单Broker quorum持卷恢复有证据，未验证多节点HA；B专属拓扑未额外force-recreate，使用B0共享运输的真实同卷重建证据。
- 独立冷启动300秒内Commerce replay拒绝的实机实验未另跑；SENT不足300秒拒绝及满300秒成功另有真实证据。
- 独立Linux主机、用户常驻环境迁移、V11 E6/D性能、C抢购/优惠券、真钱支付退款均未执行。
- 对账为全局有界订单完整性，今日PROVISIONAL；未把它称作历史日资金结算MATCH。

## 安全与停机

根.env未修改；测试脚本仅使用随机端口、新库、新卷与owner验证。自有主进程/两个额外JVM及容器在验证结束后按归属停止，全部测试卷保留。具体停止与卷保留结果见`evidence/v12-b-cleanup.json`；不删除用户服务、旧卷或历史Ledger。

Draft PR：[ #17 ](https://github.com/Felix0410/FlowGate/pull/17)。交付时发现设计分支已前进至`f228cd3`，新增ADR-14与V12-M后续退役Kafka设计；这不改变B实现范围，文档更新随后合入B分支，不执行M。先前命名1280的桌面截图实际为默认1510，另存`v12-b-fulfilled-exact-1280.png`真实1280×900并检查无溢出；375px截图真实有效。
