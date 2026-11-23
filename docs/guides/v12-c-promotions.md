# 套餐缓存与限时活动

Portal 导航“限时套餐”查看已发布活动、真实价格/单位及固定 UTC 权益月份。登录后选择自己的应用提交。202 表示已持久受理并排队创单，不代表付款或权益已开通；页面会查询“我的抢购”，订单就绪后进入订单详情模拟支付。断网后先查询原记录，勿更换请求号反复抢购。每人每场只能受理一次，已取消/超时也不恢复本人参与资格。

同一应用/API/月的免费权益、普通订单和促销互斥。创单超时、未付款取消或付款超时释放预留；已付款计入累计已售，模拟退款不返售。权益有实际授权流水后不可退款。下月权益不提前生效，无真实扣款。

Console“客户业务→API市场→读取活动与库存”：管理员从真实已上架付费SKU创建草稿，填写整数分/库存/UTC时间；发布后价格/名额/月份冻结。可暂停、结束、恢复或按数据库重建Redis。只读角色可查库存/对账/消息，写操作由服务端限ADMIN。库存与消息详情展示实际reserved/sold、Redis软候选、Rabbit消费者/重试/死信数及原事件投递状态。Poison不自动认定成功，不提供改价或伪造来源的恢复入口；原SENT未消费事件及节点均满300秒后才可ADMIN审计补投原eventId。

公开分类/首页/详情/SKU/活动使用Redis缓存，短暂延迟不改变提交时的MySQL价格和购买资格。个人账户、应用凭证、余额和订单不进公共缓存。Console“读取缓存状态”展示当前实例真实累计计数；公开投影DB_LOAD不包括身份/就绪检查等所有SQL。Redis失忆时抢购503，已有意向与订单仍可从MySQL查询；重建先REBUILDING推进epoch，核对数据库库存后再开放候选。

## 本机运行

常驻`flowgate-local`仍是原M候选；本轮不迁移日常数据库、不改根.env或IDE。C候选使用本轮独立owner/随机端口/新卷，验收前按合同停机保留所有数据。当前机器可用以下命令恢复/停止这份保留实例：

```sh
python3 scripts/v12-c-preview.py start
python3 scripts/v12-c-preview.py status
python3 scripts/v12-c-preview.py stop
```

地址与测试账号见本机私有`.local/v12-c-access.md`（不进Git）。已发布演示活动有真实结束时间；过期后管理员新建活动，不回填旧月或改历史库存。缓存和活动默认OFF：`FLOWGATE_V12_CATALOG_CACHE_ENABLED=false`、`FLOWGATE_V12_PROMOTION_ENABLED=false`。

其他机器：先按`docs/guides/v11-local-delivery.md`准备新的隔离配置和owner实例；仅在该新配置显式打开A/B及以上C开关。`v11-local.py up --isolated`首次建库会安装C五表、兼容CHECK/FK；已有隔离实例先停所有Control，再显式运行`python3 scripts/v12-c-migrate.py --instance-home <该隔离实例目录>`。这些脚本拒绝常驻flowgate-local，无自动用户库ALTER。

实测与未执行范围见`docs/progress/v12-c-implementation.md`。20k QPS未达到，5k/20k阶梯未执行；独立源码复审与负责人真人验收待完成。
