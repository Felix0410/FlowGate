# V12-M 旧用量Rabbit迁移（兼容实施中）

旧V11 APP月配额与V12 APP/API月配额、Commerce订单仍是三套独立权威模型。旧用量新增`flowgate.usage.legacy`主/5秒Retry/Poison quorum队列，原eventId与八字段不变。消息只是至少一次传输，MySQL事务和唯一键负责幂等。

兼容版只在数据库栅栏KAFKA时允许旧传输，在RABBIT时允许新传输，FROZEN暂停统计，Gateway仍持久写Ledger与Outbox。缺表/缺行/数据库不可用时停止投递。不能只靠环境开关迁移。

迁移工具`scripts/v12-m-cutover.py`限定本机自有隔离实例；只读检查后必须全部Control退出、租约耗尽、ADMIN会话有效、全量源事件与Ledger/Consumed/Daily健康才允许epoch CAS并审计。未知IDE/Control阻断，不能强制绕过。它不操作用户常驻环境。

SENT只是Broker确认，未必已聚合；保留原事件，经过事件和节点各300秒保护，由ADMIN执行原重排路径。Poison必须先检查源数据，禁止直接删消息或补扣额度。单节点quorum不等于多节点HA。
