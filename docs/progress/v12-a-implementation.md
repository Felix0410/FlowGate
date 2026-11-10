# V12-A 实施与功能交付记录（2026-11-10）

负责人已授权完成V12-A，要求功能优先、避免过度测试；实施过程中明确选择**V12 RabbitMQ，V11 Kafka保留**。最新设计父分支 `docs/v12-platform-business-redesign@e77624f`，独立功能分支 `feat/v12-a-portal`。原C/E合同仅作为历史安全约束，没有重做历史阶段，也未进入B/C订单或D性能阶段。

## 已实施功能

| 段 | 交付内容 | 本轮证据/边界 |
|---|---|---|
| A0 | 14张独立增量表、FK/UNIQUE/CHECK/索引；新随机隔离项目 | V1→V11→C→V12迁移，V12再重复执行两次通过；根.env哈希未变 |
| A1 | Portal独立主体/资料/OWNER工作区、BCrypt、独立Redis摘要会话、限速/Origin/请求体上限 | 两真实用户注册登录；ADMIN/Portal/APP用途隔离；猜他人应用/凭证/余额/流水404 |
| A2 | 分类、商品、不可变零元SKU、上架门禁、真实搜索/分页/纯文本公开文档、Console运营入口 | 实际私有API发布并双节点应用后上架；浏览器恶意HTML按纯文本显示 |
| A3 | 自助APP、B统一凭证签发/轮换/撤销、免费唯一来源/槽/Grant/订阅/余额事务 | 8并发领取1个201+7个409；换APP不可再领同API；不同用户独立领取；3600秒Token、离页清除 |
| A4 | 显式NONE/V11_APP/V12_APP_API、schema2拒绝旧网关、新策略/节点Feature、独立授权端点 | 双Gateway同库5个200+第6个429，余额0；旧余额/Ledger未写入；规则冲突和schema兼容单测 |
| A5 | V12独立RabbitMQ交换机/quorum队列/持久重试/毒消息隔离、Outbox lease、publisher confirm+return、COMMIT后手动ACK、独立日报/只读对账/SENT等待期审计补投 | Ledger=Outbox=Consumed=Daily=5；重复投递两次不放大；今日PROVISIONAL。RabbitMQ 3.13.7；V11 Kafka仍启动并有真实消费组 |
| A6 | 独立Portal和会话、市场/详情/应用/权益/流水/日报、Console原20能力外新增市场、中文指南和隔离入口 | 真实Chromium注册→登录→建APP→搜索→领取→发Token→同Token后台HTTP调用Gateway→余额0/5流水→日报追平→离页清密→退出；1280截图、375 DOM宽度无横溢、键盘Tab |
| A7 | 源码复审与负责人手动验收 | **NOT_EXECUTED，等待复审/负责人手测，PR保持Draft** |

RabbitMQ是按负责人明确决定修改的合同，见ADR-13。同步授权仍为MySQL事务，消息只负责异步读模型；V11 Kafka、原表和原消息协议未替换。不能把本轮称为用户常驻环境部署或正式验收。

## 验证命令与真实结果

- Java17全模块 `mvn -B -ntp -DargLine=-javaagent:<本机mockito-core-5.23.0.jar> -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package`：Contract11、Control37、Gateway26通过；Demo3个HTTP测试被沙箱端口权限阻止，随后仅 `-pl flowgate-demo test package` 在允许随机端口环境重跑4项通过。初版合计**78项**。
- RabbitMQ接入后仅运行 `-Dtest=PortalContractTest,V12RabbitWorkerTest -Dsurefire.failIfNoSpecifiedTests=false -pl flowgate-control -am test package`：**6项通过**，其中Portal2项为重跑，新增Rabbit4项验证COMMIT后ACK、毒消息隔离、确认重试副本后ACK、发布NACK不ACK原件。本轮共**82个不同Java测试**执行通过；不声称Rabbit接入后再重跑全模块82项。
- 最终 `mvn ... -pl flowgate-control,flowgate-gateway,flowgate-demo -am package -DskipTests` 退出0，仅重新打包，不计作测试通过。
- Node定向3个入口：`scripts/v12-a-portal-test.mjs`、`scripts/workbench-console-test.mjs`、`scripts/v11-b-console-test.mjs`，均退出0。工作台保留七业务域、原20能力，增加API市场为第21入口；Node模拟DOM不冒充浏览器。
- Python `python3 -m unittest discover -s scripts -p test_v11_local.py`：**14项通过**。第一次有1个socket测试被沙箱阻止，允许随机端口后重跑通过。Rabbit入口扩展也已由真实隔离启动验证。
- `JAVA_HOME=<JDK17> python3 scripts/v12-a-smoke.py --keep-running` 最终退出0，证据 `evidence/v12-a-functional-smoke.json`。仅8并发及业务主链路，不是100并发或性能实验。
- ego-browser真实Chromium：`evidence/v12-a-browser.json`、`v12-a-portal-1280.png`、`v12-a-portal-keyboard-1498.png`。375px轮次确认innerWidth375、无页面横溢、无凭证明文；下一轮ego恢复窗口1498，故后续截图按真实1498命名，**不伪称保留375截图**。
- 中文后台指南生成/`--product --check`、JSON/语法及`git diff --check`通过。

## 实际失败与修复记录

1. 旧协议测试把schema2当非法；新增V12明确schema2后，把不支持版本断言改为3并增加新/旧模式与双扣冲突测试。旧schema1校验和计算不变。
2. 本机Mockito动态挂载受限，使用显式Java agent；Demo/Python随机端口测试使用允许本机监听的环境。未修改IDE。
3. Docker最初未运行，启动Docker Desktop后仅对本任务新owner项目操作。Rabbit4.1新镜像未能及时拉取，改用已缓存官方 `rabbitmq:3-management`，实际3.13.7；曾有一次隔离启动返回脱敏非零，复启同一自有项目诊断通过，最终全新实例通过，不宣称该首次失败已确定具体根因。
4. 新Smoke的第二Gateway环境参数重复、启动HTTP未等待、Python模块名遮蔽均已修正；独立Rabbit发布探针缺Netty类库也已补齐。没有把失败尝试计作通过。
5. 免费领取首次出现Spring代理字段直接访问空指针，改为调用存储Bean的 `nowUtc()` 方法。免费来源查询使用当前读，交易内复核OWNER和启用状态。
6. 浏览器跨轮次恢复viewport导致截图尺寸改变，检查PNG实际尺寸后修正命名和JSON，保留真实观察范围。

## 未执行与验收边界

依负责人“功能优先、不要过度测试”，本轮未运行：两个Control同时同名注册、100并发完整矩阵、V11 B23/C26真实故障全回归、RabbitMQ停机/恢复/毒消息端到端故障矩阵、历史日期最终MATCH、所有缺表/旧二进制/多API故障组合、Console新增市场的真人体验。它们不能用上述局部证据替代。

负责人手测、ChatGPT源码复审、V11 E6、V11-D正式性能仍未完成；不自动进入V12-B/C或D，不合并main/旧PR。

## 资产与清理

根 `.env` 前后哈希一致。没有迁移用户旧库、操作旧Ledger、重启用户已有容器、停止IDE、删卷或执行DROP/TRUNCATE/down -v/reset/clean。仅启动/停止本任务新随机owner项目与不可变测试Jar。最终两个Gateway、Control、Demo及该轮MySQL/Redis/Kafka/RabbitMQ容器均已停止，**所有测试卷保留**。运行凭证仅 `.local/` 0600文件，没有写入证据/源码/PR。

功能提交 `458be6e7a9f6720ae36753f90de13cb5a5378636`。验证证据独立后续提交；远端推送与Draft PR以真实交付映射为准。


## 2026-11-10 ChatGPT远端源码复审（首次A7静态审计）

本轮ChatGPT已从GitHub PR#15真实提交读取Java、SQL、Portal ESM、Rabbit拓扑、脚本和脱敏证据，形成[完整复审报告](../reviews/v12-a-rabbitmq-static-review-2026-11-10.md)；**没有再次独立运行用户本机Java、Docker或浏览器**。当前裁决：**A0～A6功能源码和提交的真实定向联调证据相匹配；A7仍待定**，不等于正式验收通过。

发现三项需轻量整改：P1-A01 Jackson3坏JSON可能误当暂时故障在Rabbit retry无限回炉，且坏Outbox可能反复重领；P1-A02 没有实际Rabbit断连/持卷重建/TTL重试与quorum Poison故障证据，Mockito+5笔正常消费不能替代；P1-A03 V12默认OFF/旧库缺14表时Console新市场页会读不存在表导致不友好5xx。次要工作：SENT补投进程启动冷却、万级对账查询性能、浮动Rabbit镜像版本。

已给出**只针对本阶段的修补合同**：[CODEX_V12_A_RABBIT_REVIEW_FIX_PROMPT.md](../../CODEX_V12_A_RABBIT_REVIEW_FIX_PROMPT.md)，要求定向修复/隔离真实验证/旧Console正常提示，不要求重写A功能、100并发或旧D公平压测。负责人尚未完成Portal手测；旧E6/V11-D继续留欠。**本次只是审计与文档提交，三项P1仍未编码修复/实测**，不得标成已解决。
