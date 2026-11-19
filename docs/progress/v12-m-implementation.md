# V12-M实施记录

M0～M7候选实施与定向自动验证已完成；M8独立远端源码复审和负责人真人手测尚未完成，保持Draft，不称正式验收。

- 设计基线`48dc05abf33c7bab7e5c1a90bc306d79f82aef3f`，B远端`c857d3b8532ae98dcfb208d10dd140efa0a3037b`。本地B业务代码无差异，远端缺失的B原始证据从本地`a12bddb`恢复。B8负责人手测与独立复审没有补写通过。
- M0：Java17构建exit0，全新owner/随机端口重跑B/A/V11主链，双Control/双Gateway、100付款、100重复开通、DB/Rabbit故障及退款竞争通过；封账UTC日MATCH，旧Ledger/Outbox/Consumed/Daily6。见`evidence/v12-m0-baseline.json`。
- M1/M2：仅一张运维控制表；双Control的单路由KAFKA/FROZEN/RABBIT栅栏、离线ADMIN会话/全实例退出/租约检查/epoch CAS/事务审计。兼容版真实回滚后再切Rabbit，NEW/FAILED自然及受控恢复、SENT真实300秒节点冷却后原事件补投。11 Ledger=11 Outbox=11 Consumed=11 Daily；Ledger内容前后不变。重复100次不双计；坏JSON/权威冲突隔离。见`v12-m1-legacy-rabbit.json`与`v12-m2-cutover.json`。
- M3：旧REST与权限保留，Rabbit主/Retry/Poison真实计数、栅栏mode/epoch与采样时间替代分区诊断。ADMIN补投前校验原事件/Ledger/指纹与多事件冲突；SENT双300秒保护。原生ESM9入口exit0；浏览器将在最终版本验证。
- Java定向：LegacyRabbitContractTest、V11事件/Publisher/Listener/完整性及V12RabbitReliabilityTest运行exit0。具体最终计数另记录，不继承旧95为本轮成绩。
- 失败保留：首次测试编译漏更新构造器；第一次迁移脚本CONTROL_PORT重复赋值；第二次启动双ConnectionFactory歧义；第三次迁移检查Redis缺Compose归属参数和日期UNION字符集冲突。均修复并实际重跑；私有原始日志`.local/v12-m*-failed-*`保留，脱敏摘要另存。

用户根.env、IDE/用户JVM和常驻容器/卷均未修改；Docker Desktop启动时常驻服务按其既有restart策略恢复。本轮不改main、不修改PR#17、不进入C/D。

## 最终Kafka-free验证与交付

- M0提交`ef241a7`；M1/M2兼容提交`f26db25`；M4清退提交`303529d`；M5/M6故障修复提交`bb93377`。M7/最终文档和交付SHA以Git记录为准。每阶段独立保留，未合并旧PR/main。
- M4：当前Java没有Kafka Producer/Consumer/Admin/Topic类；Maven dependency:tree无Kafka客户端，干净Control Jar无Kafka条目。当前Compose仅MySQL/Redis/Rabbit。旧Kafka探针与过渡Smoke移至`docs/history/retired-kafka-tools`，不在当前构建执行路径，历史证据未篡改。
- M5：全新owner、随机端口、全新MySQL8.4/Redis7/Rabbit3.13.7，无Kafka容器/Jar/环境变量，双Control/双Gateway真实V11 Free5、V12 API Free5与B正价订单→模拟付款→唯一权益→Gateway/退款通过。见`v12-m5-kafka-free-e2e.json`。先前M0/M2的Kafka只属于独立迁移演练轮次，不混作最终无Kafka证据。
- M6：Rabbit断开期间真实Gateway200，原Ledger/Outbox持久；DB暂停→持久TTL可见→恢复；100次重复无双计；坏JSON/源冲突/错schema进Poison；实际quorum容量拒绝NACK和mandatory Return；真实MySQL COMMIT后关闭真实Channel丢ACK，Broker redelivered仍唯一消费；固定hostname原命名卷重建保留持久消息和Poison。最终21=21=21=21，封账日MATCH、doctor READY，见`v12-m6-failure-matrix.json`。
- 实测发现DB暂停时栅栏UNKNOWN会导致源队列热重投，已修复为生命周期控制与持久TTL；兼容DB短断不是放宽fence。源冲突409禁止ADMIN恢复，修复仅本轮测试造的payload后原eventId恢复。
- 最终Jar再次验证双ADMIN离线epoch CAS恰好一成功/一冲突；FROZEN下运行Control仍允许真实Gateway200并写原NEW事件，恢复RABBIT消费一次。最终旧Ledger/Outbox/Consumed/Daily各22，栅栏RABBIT/epoch4；A真实API用量和B新订单/模拟付款/Grant/真实调用/已用拒退/未用退款/跨用户404与OPERATOR/VIEWER旧消息读200写403均通过，见`v12-m-final-runtime.json`。
- 最终Java17全reactor **94 tests，0失败/0错误/0跳过，一次全量通过**；是退役后替换了Kafka专用测试并新增Rabbit测试的当前总数，不沿用旧B95。Python原运行器14+新增迁移清单安全2；Node9入口exit0。新测试确认带版本Jar与不透明IDE argfile不会消失于进程清单，权限不足不伪造空清单。见`v12-m-validation.json`。
- M3最终真实ego Chromium：1280/375 Console Rabbit三队列/mode/epoch、VIEWER只读；Portal真实订单/SIM收据/状态、Enter登录/查询和手机导航Enter/Escape。页面无文档级横向溢出，表格独立滚动。截图和浏览器证据见`v12-m3-console.json`；这是自动真实浏览器，不是负责人真人验收。
- M7：README/AGENTS/路线图/配置、产品指南、现行运行器手册与同源HTML、A/B指南及维护Runbook同步。历史文字明确是当时Kafka架构，当前不依赖它。网页手册生成检查两项exit0。
- 清理按本轮用户新授权执行：匹配`.local`清单、owner标签、停止状态和卷/网络独占引用后，共删除 **38个测试容器、56个测试卷、10个测试网络**。保留flowgate-local常驻MySQL/Redis/Kafka三容器、全部用户原卷和其他项目；未删除镜像、未全局prune。最后两个隔离实例已停机和清理，不能再用原端口当作在线演示。见`v12-m-docker-cleanup.json`。

## 失败、未执行及停止线

所有失败与修复摘要见`v12-m-failed-attempts.json`，M6三份失败状态证据保留。除产品缺陷修复外，还纠正了探针未退出、quorum容量允许少量超额、队列计数采样时序等测试工具问题；失败尝试不计为通过。

完整旧V11 B23/C26历史故障矩阵、C/D负载性能、独立Linux主机、多节点Rabbit HA、用户常驻环境迁移、B8/M8独立复审及负责人真人手测均NOT_EXECUTED/PENDING。没有替负责人签验收，没有自动进入C/D，没有改PR#17的head/base，没有合并main。

## 远端交付回执

已通过GitHub App交付[Draft PR #19](https://github.com/Felix0410/FlowGate/pull/19)，base为M设计分支，未改PR#17。原生git push返回403/exit128；App创建同内容Git树并保留六阶段父链。源码检查点本地`702ec47`对应远端`281847a02db37a38286307c8f7d60b1f1b52677c`，树`ddb5a45ffa74346f113857dfec5e293b581165c7`完全一致；该检查点后仅增加本回执文档。各阶段远端tree/parent均实际GET回读核对，映射见`evidence/v12-m-delivery.json`。main与B功能分支SHA未变。兼容回滚本地`f26db25`在远端对应`72cfb593501384f2629d6cf4e69aad8dca30a6d1`。PR待M8独立复审和负责人手测，不正式签验收。
