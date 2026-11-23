# 历史B/C场景到当前Rabbit证据映射

不运行旧Kafka脚本，不使用历史109条计数冒充本轮。PASS_EQUIVALENT表示保留业务不变量并使用Rabbit/当前事务替代；PARTIAL与NOT_EXECUTED不能算完整原矩阵通过。常说B23包含B主smoke20组及后续B修复/兼容项；原文件实际20个checks，不能编造23个原check。

| B原序号 | 历史场景 | 本轮 | 证据/差异 |
|---|---|---|---|
| B01 | 双新Gateway真HTTP100并发Free5：5通过/95耗尽，Ledger5=Outbox5/余额0；身份域隔离 | PASS | D2 legacy；D0 A用量 |
| B02 | 内部HTTP同chargeId100并发仅1流水；历史版本可重试，checksum/API/units/版本错误和payload冲突409 | PASS | D2 legacy/UTC |
| B03 | 轮换/撤销后即使Redis残留旧Token仍403；应用/客户暂停不扣额 | PASS | D2 identity |
| B04 | 双管理员并发首次签发仅1主体/1成功/1冲突 | NOT_EXECUTED | 首次签发双ADMIN未单独复跑；D1双ADMIN为事件补投，不冒充凭证竞态 |
| B05 | 真实官方Route CB先于Quota：OPEN零扣额/零下游；Quota429不降级不触发CB，双节点状态独立 | PASS | D2 real CB/Quota；独立节点状态未逐项复验 |
| B06 | Grant后真实500/超时仍扣；Rate429/并发503先拒绝不扣；完成后许可复用 | PASS | D2 timeout/rate/concurrency |
| B07 | 真实客户端取消不退款，本机并发许可可再次使用 | PASS | D2 socket cancel |
| B08 | Control成功提交后丢回包：实际WebClient同chargeId重试，1扣额/1下游POST；Header/Body/201/RequestId透传且伪造身份被清理 | PASS | D2 http-commit-loss |
| B09 | 实际FilteringWebHandler排序日志：Auth → Rate/Concurrency → 官方CB → Quota → Netty | NOT_EXECUTED | 未重新抓FilteringWebHandler排序日志；实际顺序行为有D2验证 |
| B10 | 无订阅/Scope不足不扣；内部配额不接受管理凭证 | PARTIAL | D2内部认证隔离；无订阅/scope全部排列未逐项复验 |
| B11 | 新增凭证API真实HTTP：OPERATOR/VIEWER可读，签发/轮换/撤销全部403 | PASS | D2 credential role writes403 |
| B12 | 实际Quota指标可读且无身份/charge高基数标签 | PASS | D4 routeId/phi.95真实指标；新增标签无身份高基数 |
| B13 | 实际A旧Gateway+新Gateway：App格式401/旧Caller200，含Quota发布及历史回滚409 | PASS | D2 mixed actual A jar |
| B14 | Control停机仅计量503，非计量公开路径200，不新增流水 | NOT_EXECUTED | Control单独停机下的计量拒绝未专门复跑；不能把DB pause冒充 |
| B15 | MySQL失联计量503，旧非计量公开路径200 | PASS | D2 MySQL pause |
| B16 | 真实Control HTTP跨UTC月重复返回原月，不要求新月订阅且无第二余额 | PASS_EQUIVALENT | D2 UTC独立schema真实服务事务；非全HTTP跨月 |
| B17 | Redis Lua发布失败返回503但DB代次已前进，旧Redis Token不可复活 | NOT_EXECUTED | 发布Lua失败的该精确故障未重复；stale generation拒绝已D2实测 |
| B18 | Redis Lua撤销清理真实失败仍DB enabled=false，旧会话403不扣额 | NOT_EXECUTED | 撤销Lua清理失败该精确故障未重复；stale session撤销403已D2实测 |
| B19 | 回滚旧快照后默认OFF凭证409，旧Caller正常/APP不可跨域 | PARTIAL | D2 snapshot rollback/身份隔离；OFF后所有凭证动作未逐项复跑 |
| B20 | 真实HTTP产品入口/新资源/指南可达；全部Ledger与Outbox守恒且仅NEW | PASS_EQUIVALENT | D0默认OFF/C缺表与D7界面，异步最终为Consumed非保持NEW |

B附加三项：冻结余额不匹配、OFF凭证安全撤销、旧A兼容，保留现有源码/JUnit及D0新默认OFF安装，但本轮没有逐项重跑历史C0所有真HTTP脏数据故障；标PARTIAL，不称23/23。

| C原序号 | 历史场景 | 本轮 | 证据/差异 |
|---|---|---|---|
| C01 | 无Kafka/Async OFF双Gateway100并发Free5精确5Grant95耗尽 | PASS | D2 Free5 |
| C02 | App/旧Caller身份隔离且OFF不虚构消费 | PASS_EQUIVALENT | D0 defaultOFF与D2身份；不使用Kafka |
| C03 | Async ON但Broker未启动：B授权200、Outbox积压6、真实探测不可用 | PASS_EQUIVALENT | D0/D7 Rabbit停机授权/202持久化与积压 |
| C04 | 真实封账HTTP：积压PENDING且同日多App复合游标不漏页 | PARTIAL | D0 legacy封账；同日多App深页完整矩阵未逐项复跑 |
| C05 | 专用单KRaft Broker及3分区真实启动 | REPLACED | Rabbit四链quorum main/retry/poison，不启动KRaft/三分区 |
| C06 | 真实Broker ACK后halt23，SENT未写且原租约保留 | PARTIAL | D2真NACK/Return与D1 SENT补投；精确ACK后halt23 sender窗口未再注入 |
| C07 | 真实消费DB提交后halt24未ACK，幂等标记和汇总已落库 | PASS_EQUIVALENT | D7真实COMMIT后Channel在ACK处关闭，不重放Kafka offset |
| C08 | 实际30秒租约到期重发/offset重放后仅6消费6汇总，无第7笔 | PASS_EQUIVALENT | D2 lease100自然31秒与D7重投；新业务计数以本轮为准 |
| C09 | 积压追平后真实封账对账MATCH | PASS_EQUIVALENT | D0封账/四链关联；最终D7 Ledger/Consumed/Daily |
| C10 | 真实Kafka重发同eventId100次，读模型仍6次 | PASS_EQUIVALENT | D0 usage及Commerce、D1 promo实际100重复 |
| C11 | 实际停Broker后B授权继续200/新Outbox积压 | PASS_EQUIVALENT | D0/D7停Rabbit持久化原事件 |
| C12 | Broker恢复重排追平7笔，未重复扣量 | PASS_EQUIVALENT | D0/D7恢复/晚到终态不重扣 |
| C13 | 两Worker真实领取100个不重叠租约，迟到owner更新0 | PASS | D2 lease100独立schema双worker |
| C14 | 100个租约真实到期后恢复发送，107Ledger/Consumed/汇总守恒 | PASS | D2 lease100自然到期/100重领，非历史107计数 |
| C15 | 两管理员真实并发重投仅一次审计，原eventId/Grant总数不变 | PASS_EQUIVALENT | D1原事件双ADMIN一审计/未新增业务Grant |
| C16 | 新运营HTTP只读角色可读、POST/未知PUT/DELETE均403 | PARTIAL | D2只读与POST403；未知PUT/DELETE全排列未复跑 |
| C17 | 真实HTTP读模型差异MISMATCH，Ledger/余额不自动改写 | PARTIAL | D7零缺口/旧诊断代码保留；人为读模型MISMATCH UI未重新注入 |
| C18 | 实际消费日表SQL故障不留下第108个消费标记、不提前确认 | PASS_EQUIVALENT | D2 UTC真daily表rename，Consumed回滚 |
| C19 | 修复DB并重启消费者后原offset重放，恰好108汇总 | PASS_EQUIVALENT | D2 UTC修复后原事件消费；Rabbit无offset |
| C20 | 毒消息实际暂停分区2，未确认原offset，未假发送DLT | REPLACED | 永久坏消息隔离各Poison，四主链继续；不暂停Kafka分区 |
| C21 | 同eventId不同payload实际诊断，不累计读模型 | PASS_EQUIVALENT | D2 UTC source冲突不累计；D1原payload核验 |
| C22 | 无DLT且Broker断联/恢复，毒消息offset仍未前进 | REPLACED | Rabbit Poison留存，D6四链坏JSON消费不增加，无Kafka offset概念 |
| C23 | 实际日期边界拒绝未封账日和超31天范围 | NOT_EXECUTED | 日期封账全部错误范围HTTP未逐项重跑 |
| C24 | 实际产品新资源可达且运营事件无原始payload | PASS_EQUIVALENT | D6真实页面与后台诊断；原payload不向运营返回 |
| C25 | 首次Topic初始化时Broker离线，恢复后自动创建声明Topic并幂等追平109笔 | PASS_EQUIVALENT | D0新空卷拓扑/幂等安装及D7停恢复，无Kafka Topic |
| C26 | 最终109权威Grant/Outbox/消费标记/汇总一致；毒消息没有制造Grant | PASS_EQUIVALENT | D7实际新计数守恒，非历史109 |

证据前缀均指docs/progress/evidence/v12-d*.json，具体文件清单见v12-d7-full-regression.json。该映射本身是源码/证据核对，不是第二次独立实测。额外M全机FROZEN/CAS未执行，见v12-d2-cutover-safety.json；既有独立review与负责人签字继续待办。
