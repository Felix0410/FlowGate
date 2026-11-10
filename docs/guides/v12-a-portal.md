# V12-A 开发者Portal与免费API权益

入口 `/portal/`，运营入口 `/console/#business/market`。本阶段仅注册、真实API市场、应用、免费当月权益、短期APP凭证、授权流水与异步日报；订单/支付/活动不在本阶段。

## 本地运行

保护根 `.env` 和用户常驻数据库。先用JDK17构建，再使用**新的独立配置文件、新实例名**运行既有隔离启动器：

```sh
python3 scripts/v11-local.py up --isolated --instance v12-owner --config /绝对路径/v12-owner.env
python3 scripts/v11-local.py status --isolated --instance v12-owner
python3 scripts/v11-local.py stop --isolated --instance v12-owner
```

配置沿用E指南的 `JAVA_HOME`、`FG_BOOTSTRAP_ADMIN_USERNAME`、`FG_BOOTSTRAP_ADMIN_BCRYPT`、`FLOWGATE_INTERNAL_KEY`，另在该**独立文件**明确设：

```sh
FLOWGATE_PORTAL_ENABLED=true
FLOWGATE_V12_QUOTA_ENABLED=true
FLOWGATE_V12_ASYNC_ENABLED=true
```

新实例安装 V1→V11→C→V12-A，叠加 `compose.v12-a.yml`。RabbitMQ使用单独随机端口、随机密码、独立vhost `/flowgate-v12`和新命名卷。V11仍使用Kafka。默认六个E端口若占用需用 `--control-port`、`--gateway-port` 等分别指定空闲端口，不能停止占用端口的用户进程。Rabbit端口由新实例自动选取。

已有E实例不会自动迁移或改变消息中间件，不能拿旧实例名重启当作V12迁移。所有stop均保留卷。隔离运行清单和凭证保存在 `.local/`，不要提交、截图或公开这些文件。

## 运营上架

1. 创建私有API和路由，先发布无计量的初始版本并等待全部接流节点上报可信能力。
2. 治理策略→计量规则→API独立月度计量（`USAGE_QUOTA_V12`），绑定私有API，发布并等所有节点应用。
3. 客户业务→API市场，创建分类和商品，选择实际API，填写公开文档，再创建零元免费SKU（例如Free5）。
4. 上架操作实时检查当前发布、完整接流名单、版本/checksum、心跳、V12能力和新表。保存商品不等于上架；上架不改变API快照。
5. V12用量在市场页独立对账；当天为PROVISIONAL，尚未消费为PENDING，有完整性缺口时不假报MATCH。

## 用户演示

注册→登录→创建应用→市场选择API/免费SKU→选择自己的应用领取→我的应用签发3600秒APP Token→在终端使用详情页的安全curl示例调用→权益与用量查看余额/流水。

免费资格为每用户、每API终身一次；权益池为应用、API、UTC月份唯一。Free5前5次授权成功、第6次429。授权即消耗，下游失败不退款。余额不存在明确显示未开通，不显示伪造0。APP Token只显示本次明文，离页清除；Portal登录Token仅内存保存，刷新需重登。

RabbitMQ日报与MySQL实时账本是不同口径。异步关闭时Outbox保留NEW，空日报不能解释为没有使用。消息确认/重试/死信隔离不会修改原Ledger或重复扣额。

## 必要验证与手动验收

`scripts/v12-a-smoke.py`只建立新隔离项目，做功能主链路和小规模并发，不是性能测试。默认结束停止自有JVM/容器并保留卷；`--keep-running`仅供同轮页面检查，需之后显式stop并停止该轮额外Gateway进程。进度/证据见 `docs/progress/v12-a-implementation.md`。

负责人仍需亲自完成账号注册、市场搜索、应用/权益/凭证操作、调用、375px与键盘体验。自动化通过不代表人工验收、V11 E6或D性能完成。所有PR保持Draft，不合并main。
