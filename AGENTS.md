# FlowGate 当前交接：V12-A源码复审后P1轻量修补（2026-11-10）

> 真正已实现：PR #15 `feat/v12-a-portal`含Portal、免费API权益、独立RabbitMQ V12用量与定向联调；V11-C Kafka保留。ChatGPT只读远端源码复审报告：`docs/reviews/v12-a-rabbitmq-static-review-2026-11-10.md`。**A0～A6真实代码和提交的功能证据已存在，A7仍待P1-A01～A03修补、真实Rabbit故障小矩阵与负责人真人手测**。未重跑全模块Java或用户环境，别把文档复审当完整实机验收。
>
> **下一份唯一待执行Codex合同**：`CODEX_V12_A_RABBIT_REVIEW_FIX_PROMPT.md`。只修坏JSON错误分类/无限重试、真实Rabbit持久死信恢复验证、V12默认OFF旧Console市场待就绪提示，及必需文档/定向测试；**不得自动执行旧`CODEX_V12_A_EXEC_PROMPT.md`重做A**、不得启动B/C订单/秒杀、D压力测试、迁移全项目Kafka、合并任何旧PR/main。保护未提交本地文件、用户.env/IDE/MySQL/Redis/Kafka/Rabbit卷。


> 负责人2026-11-10授权完成V12-A，功能优先、避免过度测试，并明确选择“V12改用RabbitMQ，保留V11 Kafka”。执行合同 `CODEX_V12_A_EXEC_PROMPT.md`、详细合同 `docs/progress/v12-a-portal-free-subscription.md`；消息边界以 `docs/design/v12-architecture-decisions.md` ADR-13为准。
> 独立功能分支 `feat/v12-a-portal` 基于最新设计分支 `docs/v12-platform-business-redesign@e77624f`。已实施Portal、真实市场/免费SKU、自助应用/APP Token、APP/API/月免费唯一权益、V12计量与RabbitMQ专线、Console市场入口及中文指南。进度及真实定向证据见 `docs/progress/v12-a-implementation.md`。
> Java82个不同测试分次运行通过（不是Rabbit接入后完整82项重跑），Node3入口、Python14、隔离双Gateway 5次200/第6次429、8并发唯一领取、RabbitMQ去重汇总、真实Chromium已执行；大矩阵与负责人手测未执行。A7/正式验收未通过，PR保持Draft。
> 不进入V12-B订单/C活动或V11-D性能，不合并main/旧PR。根.env、用户旧数据库/卷/IDE/旧Ledger继续保护；新功能默认OFF，仅新隔离实例迁移，所有测试卷保留。
> 下列V11/C/E“当前任务”为历史记录，不重执行。

---

# 当前状态：V1.1-E 自动实施完成，等待复审与E6负责人手测

> E0～E5已在独立功能分支完成编码和定向实测：Java75、Node9入口、Python14、原B23项/C26组、新E真实5/6+Kafka+历史MATCH和1280/375Chromium。详见`docs/progress/v11-e-local-delivery-implementation.md`及证据JSON。**E6负责人手测未执行，不能称正式交付验收通过。**
> 任务基线为 `feat/v11-console-workbench`；本轮**唯一执行入口**为 `CODEX_V11_E_LOCAL_DELIVERY_PROMPT.md`，唯一验收任务书为 `docs/progress/v11-e-local-delivery.md`。
> 先 E0基线与资产审计 → E1只读doctor → E2隔离V11全链路演示 → E3真实后台就绪诊断 → E4 B/C权威账本/消息闭环 → E5实测和指南 → E6负责人手测。此轮不开展D的10万条SQL/四组性能实验，不重做已完成的A/B/C或UX。
> **安全门槛**：根 `.env`、用户已有MySQL/Redis/Kafka/卷、IDE进程、旧Ledger均不可动；不使用 `down -v`、DROP/TRUNCATE、hard reset/clean。只对新隔离数据库迁移、启动/停止由E脚本自己管理的进程；所有真实启用条件必须来自后端可信事实，绝不假设ready。
> 基于当前docs分支另建 `feat/v11-e-local-delivery` 交给本地Codex编码，阶段commit与真实测试证据入新Draft PR，**不改main、不合并、不自动进入D**。当前PR #1～#11仍Draft。
> 以下UX/C各阶段记录是历史交付说明，请勿再当作当前执行任务。

---

# 当前补充任务：V1.1业务工作台UX重构已实施，等待复审与真人验收

用户2026-11-09明确授权执行 `CODEX_CONSOLE_WORKBENCH_UX_PROMPT.md`，详细设计 `docs/design/v11-console-workbench-ux-rework.md`。功能分支 `feat/v11-console-workbench` 基于独立设计分支；UX-0～UX-5实施及定向自动检查见 `docs/progress/console-workbench-ux-rework.md`。7业务域完整保留20能力，不新增后端语义/框架/迁移。40 Java、8 Node、独立真实HTTP与1280/375真实浏览器已执行；真人体验未验收。不继续扩展、不进入D、不合并main/旧PR；用户.env、IDE与常驻数据保持原样。

以下C阶段记录保留为历史上下文，新工作台任务不重跑历史C修复合同。

# FlowGate｜当前阶段：V11-C技术复审通过，等待V11-D设计/验收授权（2026-11-09）

> 2026-11-09 ChatGPT已核对PR #9实际修复源码和Codex提交的Java70项/7套Node/新13组故障/原C6 26组/独立Kafka持卷测试证据，P1-C01～C03三项均**通过源码+证据复审**；完整报告`docs/reviews/v11-c-review-fix-verification-2026-11-09.md`。**不是本轮独立重跑，也不等于用户真实Kafka已部署、V1/V1.1真人浏览器验收或main已合并。**
>
> **当前没有新增C编码任务**。停止执行历史`CODEX_V11_C_REVIEW_FIX_PROMPT.md`（仅作为旧修复合同保留）；等待负责人明确指令再编写V11-D设计/编码任务。不可自动开始D、Kafka常驻新卷/真实数据库迁移、合并main/旧Draft PR。仍保护用户未提交文件、IDE、.env、MySQL/Redis/Kafka卷。


> **V11-C的C0～C6 Java/Kafka/SQL/前端与实际测试证据已经真实提交GitHub**，PR #9仍Draft。Codex记录JDK17 **67 JUnit**、**26组真实Kafka/HTTP故障场景**及109 Ledger=Outbox=Consumed=每日授权数，ChatGPT已审读远端源码与证据但**未独立运行本机Java/Kafka/MySQL或真人浏览器**。目前不是从头开发C，也不是开始D。
>
> **当前唯一执行合同：`CODEX_V11_C_REVIEW_FIX_PROMPT.md`**；源码审核报告`docs/reviews/v11-c-static-review-2026-11-08.md`；真实历史实施`docs/progress/v11-c-async.md`的C-09/C-10。旧`CODEX_V11_C_EXEC_PROMPT.md`为已完成C0～C6历史阶段合同，不再按原路线从头重写。

## 本轮已完成的三个边界

1. **P1-C01已修**：官方镜像UID1000可写的/var/lib/kafka/data独立Kafka命名卷，真Compose down/up持卷保留消息与offset；ADMIN审计/CAS/300秒等待期的原SENT未消费补投，DB权威不重复扣。
2. **P1-C02已修**：包装异常分类、标准DefaultErrorHandler 1～30秒封顶自动重试，实际MySQL pause/unpause后不重启Control/Listener追平；永久毒消息仅暂停问题分区不ACK，其他分区继续。
3. **P1-C03已修**：只读RR有界完整性诊断，孤儿Ledger/反向孤儿/重复Outbox明确INTEGRITY_GAP，不伪造日归属或放大expected，业务页/指南同步。

## 实测、Git、安全

- 目标分支`feat/v11-c-async-usage`，base`feat/v11-b-quota`，PR #8/#9继续Draft。先`git status -sb`、`git fetch origin`、`git log -1`核对已提交工作；**绝不reset-hard/clean-fd/强推/merge main**。
- 必须使用随机端口/新容器及新的Kafka测试卷，禁止操作用户现有MySQL/Redis/Kafka持久卷、.env、IDE/旧JVM。新增Kafka卷是Compose配置变更，不能不经负责人允许重启用户已有Kafka并误当迁移完成。
- Java17 Spring Boot4/MySQL8.4/Redis7/Kafka4.1.1（Control-only Kafka），原生ES modules；**不加Kafka Streams、CDC、Seata、Nacos、额外Java服务、Vue/React/npm**。关键长逻辑中文注释按1/2/3逐项写。
- 按修复合同扩展JUnit、Node旧新产品和两Gateway真实Kafka/MySQL故障测试；**保留原C0～C6 26组与67JUnit的历史证据**，新`docs/progress/evidence/v11-c-review-fix-smoke.json`只有实测后才生成。进度记录完整SHA、命令/退出码、通过失败跳过数以及原C测试回归。
- 完成后停在PR #9 Draft并交ChatGPT复审与负责人真人1280px/375px/键盘测试。**不开始V11-D、不合并main或所有Stacked PR**。

**当前真实状态：三项复审修复与定向验证已完成（Java70、7套Node、新增13组、独立卷重建、原C6 26组）；见进度C-10与v11-c-review-fix-smoke.json。源码通过不等于常驻部署；启动新Kafka卷需明确负责人授权，.env及用户数据库未自动修改。到此等待复审和真人浏览器验收，不自行进入D或合并。远端交付以实际推送结果为准。**
