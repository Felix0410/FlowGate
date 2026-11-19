# V12-M实施记录

当前M0通过、M1/M2兼容版迁移演练通过；最终Kafka-free清退与验收尚在执行，不称正式验收。

- 设计基线`48dc05abf33c7bab7e5c1a90bc306d79f82aef3f`，B远端`c857d3b8532ae98dcfb208d10dd140efa0a3037b`。本地B业务代码无差异，远端缺失的B原始证据从本地`a12bddb`恢复。B8负责人手测与独立复审没有补写通过。
- M0：Java17构建exit0，全新owner/随机端口重跑B/A/V11主链，双Control/双Gateway、100付款、100重复开通、DB/Rabbit故障及退款竞争通过；封账UTC日MATCH，旧Ledger/Outbox/Consumed/Daily6。见`evidence/v12-m0-baseline.json`。
- M1/M2：仅一张运维控制表；双Control的单路由KAFKA/FROZEN/RABBIT栅栏、离线ADMIN会话/全实例退出/租约检查/epoch CAS/事务审计。兼容版真实回滚后再切Rabbit，NEW/FAILED自然及受控恢复、SENT真实300秒节点冷却后原事件补投。11 Ledger=11 Outbox=11 Consumed=11 Daily；Ledger内容前后不变。重复100次不双计；坏JSON/权威冲突隔离。见`v12-m1-legacy-rabbit.json`与`v12-m2-cutover.json`。
- M3：旧REST与权限保留，Rabbit主/Retry/Poison真实计数、栅栏mode/epoch与采样时间替代分区诊断。ADMIN补投前校验原事件/Ledger/指纹与多事件冲突；SENT双300秒保护。原生ESM9入口exit0；浏览器将在最终版本验证。
- Java定向：LegacyRabbitContractTest、V11事件/Publisher/Listener/完整性及V12RabbitReliabilityTest运行exit0。具体最终计数另记录，不继承旧95为本轮成绩。
- 失败保留：首次测试编译漏更新构造器；第一次迁移脚本CONTROL_PORT重复赋值；第二次启动双ConnectionFactory歧义；第三次迁移检查Redis缺Compose归属参数和日期UNION字符集冲突。均修复并实际重跑；私有原始日志`.local/v12-m*-failed-*`保留，脱敏摘要另存。

用户根.env、IDE/用户JVM和常驻容器/卷均未修改；Docker Desktop启动时常驻服务按其既有restart策略恢复。本轮不改main、不修改PR#17、不进入C/D。
