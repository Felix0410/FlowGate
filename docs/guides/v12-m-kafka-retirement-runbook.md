# V12-M旧用量迁移与Kafka退役维护Runbook

本文描述人工维护流程。仓库候选实现和隔离演练通过不代表用户常驻机器已迁移。本轮没有修改根.env、IDE、原数据库、常驻容器或旧用户Kafka卷。正式目标库迁移另需负责人明确授权、备份与维护窗口。

## 1. 维护前只读核验

1. 列明所有Control实例、机器、owner、PID/容器、真实Jar校验和、接流方式、服务管理器及自动重启策略，不能只列在线的一台。旧B二进制不识别fence，数据库锁本身无法阻断它。确认旧服务不会被IDE/守护进程重新拉起，无法确认就停止迁移。
2. 备份旧Balance/Ledger/Outbox/Consumed/Daily与审计；保留原chargeId、eventId、payload指纹和UTC时间。盘点旧Kafka分区暂停、lag和最后排空情况；Broker不可恢复时记UNKNOWN及理由，按MySQL权威源恢复，不按offset导入。
3. 有界日期/API/APP完整性诊断与全迁移范围逐笔检查同时需要。核对Ledger与Outbox一一对应、原八字段合法、Consumed唯一与指纹、UTC日报。孤儿/多event/冲突/多余日报均INTEGRITY_GAP，停止迁移，不自动改账。
4. 验证Rabbit3.13.7固定hostname、独占命名卷、feature flag `stream_queue`及三套quorum拓扑。旧用量exchange `flowgate.usage.legacy`，主`.aggregate`、5秒`.retry`、`.poison`；主/Retry含at-least-once DLX和reject-publish。单节点不等于HA。

## 2. 全实例停止与单路由切换

兼容实现与真实回滚演练固定在本地Git提交`f26db25`；GitHub App等价远端提交为`72cfb593501384f2629d6cf4e69aad8dca30a6d1`，完整映射见`docs/progress/evidence/v12-m-delivery.json`。最终Rabbit-only源码已不含Kafka客户端，不能静默回到旧传输。不要把兼容版用于未知旧Control仍运行的灰度双写。

显式安装`sql/flowgate-v12-m.sql`只新增控制表；首次为KAFKA/epoch1，重复安装不重置。新建且经owner验证为空的隔离库由启动器显式选择RABBIT，已有库永不自动改模式。

隔离工具`scripts/v12-m-cutover.py --home <本轮实例目录>`只读显示模式/epoch、活跃租约及本机Control清单。工具的执行边界为单机新owner隔离实例，不能证明跨机器全实例清单，正式迁移须人工补足上述清单和变更审批。未知IDE/Control、权限不足、存活Control或未过期租约均阻断；没有force开关。

全Control停止并确认无在途Publisher/Consumer及有效租约后，使用有效ADMIN会话的0600 token文件执行：

```text
python3 scripts/v12-m-cutover.py --home <自有隔离目录> --target FROZEN --expect-mode KAFKA --expect-epoch <当前代次> --admin-token-file <0600私有文件>
python3 scripts/v12-m-cutover.py --home <同目录> --target RABBIT --expect-mode FROZEN --expect-epoch <新代次> --admin-token-file <0600私有文件>
```

每一步都先逐笔核验、记录Outbox高水位，然后DB mode/epoch CAS与审计同事务。并发操作只有一个CAS成功，失败不得跳过检查。Gateway授权事务只依赖MySQL；在FROZEN且Control运行时继续持久写Ledger+NEW Outbox，异步显示PENDING。全部Control退出的实际维护窗口内管理端/内部授权接口也会暂停，不能承诺无停机。

只启动同一Rabbit-only协议版本的受控Control集合。旧进程存在或会自动重启时禁止启动新Worker。legacy消费者只在RABBIT启动，DB短断走持久TTL Retry；FROZEN不领取、不消费。V12 API用量和Commerce仍走各自队列。

## 3. 原消息恢复与验收

- NEW正常领取；过期LEASED等原30秒租约清理；尝试上限耗尽为FAILED，不能伪装已发送。
- 以高水位和ID游标每批最多100检查FAILED与SENT未消费，原eventId/chargeId/八字段和Ledger必须一致。仅ADMIN调用原`/admin/async-events/{id}/retry`或`/replay`，不调用扣量业务、不生成新事件。
- SENT必须源事件与当前节点均已满300秒；更改环境变量也不能短于300。恢复前检查无Consumed，状态CAS与审计/指纹同事务；已消费、并发改变、无权威源均409。断点以Outbox id保存，重复执行先重新读取状态。
- 不因Rabbit队列为空就宣告成功。核对旧Ledger/Outbox/Consumed/Daily逐笔与UTC日，再测试V11 Free5、V12 API Free5、真实订单/模拟付款/唯一Grant/退款及两用户权限隔离。今日PROVISIONAL或PENDING，封账日才MATCH。
- 验证Broker断连/持卷重建、DB短断TTL、坏JSON/冲突Poison、真实COMMIT后ACK丢失与重复100次。Poison不自动重注入；先修源数据，由ADMIN审计恢复。

## 4. 失败止损和回滚

切换前失败保留旧路由，不删除任何数据。FROZEN后失败可继续保持冻结并保留MySQL授权事实；修复Rabbit后从原事件恢复。兼容版本允许全实例停止、核验租约/Consumed后审计回退到KAFKA，实际练习已在M2完成，见对应证据。

最终版本仅允许RABBIT↔FROZEN以及初次KAFKA→FROZEN→RABBIT迁移，不提供Kafka目标。故障回滚应使用已验证的Rabbit-only包；回Kafka属于新的受控变更，需明确授权，不能靠旧环境变量启动。旧Kafka容器/卷只读归档，移除Compose服务定义不授权删除用户数据。

## 5. 交接边界

交接提交SHA、Jar哈希、依赖树/Compose/残余扫描、三链HTTP及DB对账、失败日志摘要、全实例清单、备份和待验项。远端源码复审与负责人1280/375/键盘真人验收分别记录。保持Draft，不合并main，不进入C/D。
