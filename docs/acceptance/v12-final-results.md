# V12 Final 实际结果（2026-11-24）

最终候选状态：**CONDITIONALLY_READY / PENDING_REVIEW / PENDING_OWNER**。P1与核心业务可靠性已实测；下列性能/可视化范围仍有限，不能称全部合同逐项PASS或正式验收。唯一交付为[Draft PR #22](https://github.com/Felix0410/FlowGate/pull/22)，head `feat/v12-d-final-delivery`，base `feat/v12-c-promo-cache`。常驻环境没有升级，main/旧PR未改。

| 包 | 实际结论 | 本地实现提交 |
|---|---|---|
| D0 | PASS：新owner/基线/PR树审计 | 72166df |
| D1 | PASS：耗尽恢复/双300秒/竞态/迟到终态 | fe37d42 |
| D2 | 核心PASS；额外全机离线切换未执行 | d4c9626、658810a |
| D3 | 有条件完成：两套10万账本及索引对照 | 31db0ab |
| D4 | 有条件完成：两轮固定50rps fast/slow | 6fb36dd |
| D5 | PASS：1k/5k/20k请求目标达成，非QPS目标 | 720261a |
| D6 | 自动浏览器及业务PASS；完整错误样式/真人未执行 | 851632b |
| D7 | 回归/最终Jar持卷恢复/安全停机PASS | c572509 |
| D8 | 实现者自审完成，外部/负责人待验 | 本结果所在最终文档提交 |

## 原事件补投与权威闭环

原eventId `394c30c4-ee3c-4ad5-a319-d77f4be8cecc`：NEW0→FAILED1…FAILED8（界面RETRY_EXHAUSTED），Rabbit真实故障8次；自然等待310秒后双ADMIN一200一409、审计1条。保留原事件，恢复confirm及Consumed一次，迟到IGNORED_TERMINAL、订单0、reserved0/sold0，100条实际重复消息不制造订单。原SENT `f1d8a791-2ff5-41bc-bee2-e89d9823fc43`亦受控恢复。坏JSON/FAILED_PERMANENT不可自动解封。详见[v12-d1](../progress/evidence/v12-d1-outbox-recovery.json)。

V11双Gateway Free5为5×200/95×429（100并发）；V12免费、普通订单/模拟付款/唯一Grant/退款、促销全链在全新D owner运行，非复用历史数据。四条独立quorum main/retry/poison，实际NACK/mandatory Return、12次5秒重试至Poison、100次消息去重、MySQL故障回滚、COMMIT后ACK丢失均有真实证据。瞬态消费回调注入和真实Broker投递明确区分；队列空不是验收。

## SQL与公平性能

独立schema V11 Ledger/Outbox/Consumed各100005，V12各100012，审计100051。created_at+id时间游标服务端EXPLAIN前117/115ms、后0.0497/0.0437ms，20行范围覆盖扫描；客户端docker往返p95另列。现有后台id游标本来稳定，推荐索引未自动用于生产/常驻库。订单/活动10万、索引写放大未测。

Gateway每组100请求、两轮、固定50rps/max16inflight、fast与slow10ms，所有样本200、shed0。表内区间是两轮的各自p95范围，**不是把百分位再次聚合**。

| 路径 | fast实际QPS范围 | fast p95 ms范围 | slow p95 ms范围 |
|---|---:|---:|---:|
| DIRECT | 50.14–50.44 | 9.90–14.14 | 15.35–22.23 |
| PROXY | 50.31–50.32 | 9.40–10.81 | 29.74–32.79 |
| GOVERNED | 50.19–50.31 | 12.71–16.90 | 28.38–29.74 |
| METERED_V11 | 49.80–50.01 | 26.98–34.23 | 38.34–43.37 |
| METERED_V12 | 49.93–49.99 | 23.03–31.35 | 32.46–37.75 |

CPU累计/RSS、DB锁/Questions/连接池与Rabbit快照在原JSON；quotaHop另有每路由每Gateway新20请求诊断窗口（v12-d4-quota-hop.json），不是本表混合全局p95。compute/DBheavy、GC直方图、极限持续吞吐未测。

## 100库存请求阶梯

| 实际HTTP数 | 并发 | 完成/客户端丢弃 | HTTP202/409 | QPS | p95 ms | 唯一Intent/订单 |
|---:|---:|---|---|---:|---:|---|
| 1000 | 24 | 1000/0 | 100/900 | 454.3 | 172.5 | 100/100 |
| 5000 | 64 | 5000/0 | 100/4900 | 701.4 | 214.5 | 100/100 |
| 20000 | 128 | 20000/0 | 400/19600 | 960.8 | 330.7 | 100/100 |

每档stock100、零超卖、最终库存/订单/权益对账零缺口。20k档5000匿名fixture重复4轮，相同请求重复202，所以202400仍仅100订单；不是2万用户或2万QPS。1k档10付款10Grant；后两档实际付款0，完整付款证明来自1k和D6/D7。热公开投影1000查询DB_LOAD增量0，全库Questions+80（后台SQL仍存在）。Redis真慢Reader/代次竞态与断连/重建/NOSCRIPT见独立证据，D5逐档完整CPU/RSS未采齐。

## 验证、运行与限制

Java17 `mvn -B -ntp verify` exit0：97测试、零失败/错误/跳过；Node9入口exit0，Python20项exit0。最后仅中文静态说明更新后`-DskipTests package` exit0，不重复全量测试。真实浏览器1280/375无横向溢出，注册/免费/普通付款/促销付款/四链诊断/Tab/Escape/慢网络/401/404已观察，截图与sha在[v12-d6证据](../progress/evidence/v12-d6-product-and-diagnostics.json)。完整浏览器403/409/429/503/504样式矩阵及耗尽事件现场截图未执行，API相应故障另有证据。

最终持卷启动双Control/双Gateway后对账成功，随后全部D自有进程STOP、三卷保留；根.env不变、常驻三个JVM身份不变、8081/status200。详见[v12-d7-lifecycle](../progress/evidence/v12-d7-lifecycle.json)。运行：`python3 scripts/v12-d-preview.py start`；Portal/Console及私有账号在gitignored `.local/v12-d-access.md`，中文[runbook](../guides/v12-d-final-runbook.md)。默认开关仍OFF；当前日常flowgate-local未升级D。

额外旧M离线FROZEN/CAS脚本要求全机Control退出，常驻实例受保护且无停机授权，**NOT_EXECUTED**。首次包装器部分停止D后断言，已恢复D并修复完整预检；未切换CAS。M8不得由历史证据或本次自审冒充通过。独立复审PENDING_REVIEW、负责人真人PENDING_OWNER；旧PR关闭/合并及main/tag未操作，不另派E阶段。

首次失败与修复记录在[v12-d7-full-regression](../progress/evidence/v12-d7-full-regression.json)，各证据记录测试时父SHA与运行Jar hash。测试工作树含当时未提交实现，不能把父SHA当作完整源码版本；逐包提交归档对应源码，最终Jar见D7。原始CSV按原字节gzip保存，[索引与SHA](evidence/v12-d-raw-index.json)，没有重算性能原始行。GitHub App交付若重新生成提交SHA，则以本地树SHA及同顺序父链核验，不强推旧分支。

历史B/C场景的Rabbit替代与未执行范围见[场景映射](../reviews/v12-d-legacy-scenario-map.md)；源码自审见[最终自审](../reviews/v12-d-final-source-review.md)。

远端已通过原生fetch核验：候选HEAD `09c007339efcda7002a2dd877ac8c8d7ab63e74e`（之后仅追加本交付记录），逐包Tree与本地相同、父链顺序一致；原生push403后走GitHub App，映射见[GitHub交付记录](../progress/evidence/v12-d8-github-delivery.json)。本地保留原实现提交历史，远端App提交SHA不同，勿盲目reset/rebase/force push。
