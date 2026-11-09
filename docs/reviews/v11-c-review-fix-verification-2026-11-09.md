# FlowGate V11-C｜P1-C01/C02/C03补丁远端复审结论（2026-11-09）

> **复审代码提交**：`0240936728cea7dc5eae1736d4acac062edbce57`，分支最终文档HEAD `23f33bd9f73303276cd08ecf737396b795adcc64`，Draft PR #9 → `feat/v11-b-quota`。
> **方法与证据边界**：本轮ChatGPT通过GitHub读取实际源代码、真实隔离故障脚本、Codex脱敏测试摘要和进度日志；**没有在负责人本机独立启动Java17、MySQL8.4、Redis7、Kafka4.1.1、Gateway或浏览器**。因此“通过”指**静态源码复审与提交的可复核集成证据相互匹配**，不是ChatGPT新一轮实机测试。前次报告`docs/reviews/v11-c-static-review-2026-11-08.md`的历史问题描述保留，不删除或覆写。

## 复审最终结论

| 缺口 | 已有修复代码 | 新增真实证据 | 复审 |
|---|---|---|---|
| P1-C01 Kafka丢记录/SENT未消费无法恢复 | `compose.local.yml`官方Kafka4.1.1`kafka-data:/var/lib/kafka/data`；`AsyncUsageReadMapper.replay`仅SENT/未Consumed/超过等待期CAS→NEW；`AsyncOperationsService.replay`ADMIN操作审计同事务、仅保留原eventId/payload、默认300秒、进程启动保护 | `v11-c-volume-smoke.py`随机Compose只Kafka：容器删除/重建但保留新测试卷，topic消息offset0及已提交位点1仍在；`v11-c-review-smoke.py`实际另建无记录Broker、旧SENT/0Consumed、双ADMIN 200/409、补投后1Ledger/1Consumed/1Daily且余额不变、OPERATOR/VIEWER403 | **通过** |
| P1-C02 MySQL瞬断却永久暂停Kafka分区 | `UsageConsumerErrorHandler`按异常cause分类，`DefaultErrorHandler`指数1/2/4/.../30秒封顶的同offset自动重试；永久ApiFailure/协议坏消息仍seek/只暂停问题分区、不ACK；低基数transientRetry/poisonPause | 新Java`V11ConsumerRecoveryTest`；真实`v11-c-review-smoke.py`用数据库锁等待→MySQL pause/unpause，Control PID/Listener不重启，2Ledger→2Consumed/2Daily，重试数≥1；毒消息及内容冲突仍暂停原分区、其他分区继续 | **通过** |
| P1-C03 Ledger/Outbox异常被INNER JOIN漏报 | `LedgerIntegrityMapper`在`AsyncOperationsService.query`只读RepeatableRead快照按日期/应用/API做有界样本检测；缺Ledger/缺Outbox/同charge多个Outbox/缺时间→`INTEGRITY_GAP`；日报仍以Outbox eventTime为准，expected每charge最多1份，后台中文异常列表 | 新Java`V11ReconciliationIntegrityTest`；真实MySQL注入Ledger无Outbox、反向孤儿、多Outbox和事件时间缺失，旧INNER JOIN确为0，新查询报警，恢复后PENDING→MATCH，余额/历史Ledger不变；DOM/Node补测 | **通过** |

## 可核查的实测记录（来自本地Codex）

- `docs/progress/evidence/v11-c-review-fix-smoke.json`标记`passed=true`，**新增13组**真实故障/HTTP检查、独立Kafka持卷重建和原C6 **26组**回归。
- `mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package`：Codex记录Java17.0.18、Contract10 + Control30 + Gateway26 + Demo4 = **70项**，失败0、错误0、跳过0。
- Node新旧产品脚本**7套**均记录退出0，产品离线指南`python3 scripts/build-console-guide.py --product --check`退出0。
- 既有C6真实故障回归仍守恒：`109 Ledger=109 Outbox=109 ConsumedEvent=109 UsageDaily.grantCount`。新补测的1、2、3笔是不同隔离场景，不与109相加当成生产业务数量。
- Codex记录第一次并行重打包造成隔离Jar类加载失败、前端`replaceChildren(null)`暴露空态问题，随后用隔离不可变Jar和正确空态重跑通过；失败历史已在`docs/progress/v11-c-async.md`的C-10.4原样保留。
- 以前Codex`git push` HTTP403只是当时失败记录；现在GitHub真实HEAD `23f33bd...`，补丁提交`0240936...`和证据都存在。PR #9仍Draft，未合并main。

## 安全性与业务语义再确认

1. **Ledger/Balance仍是每次授权的MySQL权威事实**，Kafka只异步交付和读模型；Outbox SENT仅表示Broker ACK，不代表已消费。补投严格重用原eventId，不重新产生chargeId、Ledger或余额变更。
2. **原有错误安全性未退步**：APP Token独立命名空间，Gateway旧Caller隔离、Quota失败关闭，新的ADMIN补投使用V11ManagementFilter/Interceptor鉴权，OPERATOR/VIEWER写操作403。
3. **Kafka单节点演示有边界**：命名卷防普通容器重建丢日志，但不提供多Broker高可用/磁盘灾备；受控SENT补投可修复部分丢记录情况，却不替代离线备份/生产运维。
4. **对账诊断不是全历史审计**：日期/应用/API候选区间与最多100条异常样本在API与页面说明；JSON字段联接和Outbox累计读性能仍需V11-D的10万行与EXPLAIN ANALYZE检验。不能用C6的109条推导大规模P95。
5. **没有自动启用真实用户环境**：`FLOWGATE_ASYNC_ENABLED`仍默认OFF；用户.env/原MySQL、Redis、Kafka卷/IDE未迁移。本次检验不等于负责人常驻Kafka新卷部署授权。
6. **人工产品与主线尚未验收**：负责人需实际浏览器检查`/console/` PC1280/手机375、ADMIN/OPERATOR/VIEWER、键盘与一次性Token显示等；旧V1/Stacked PR #1～#9均Draft，main未合并。

## 阶段判定与下一步

**V11-C技术功能及本轮三项P1审查整改：有条件通过源码+提交的真实集成证据复审。** 当前**没有发现尚需立即返工的C级阻断项**。可进入**V11-D的设计与验收规划**：单独设计10万级SQL/索引、四组公平性能、双节点长时间稳定性、故障恢复、前端真人验收、Git/README/部署手册收口。**本次审核未授权自动执行D源码、自动开启Kafka/迁移真实数据库或合并main**；D的具体方案/执行仍需负责人明确安排。
