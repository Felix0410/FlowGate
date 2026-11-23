# V12-D Final实施与证据

唯一分支`feat/v12-d-final-delivery`，同一Draft PR#22，base C功能。按负责人授权执行D0～D8；原root.env/flowgate-local/旧卷只读。不代签负责人或外部独立复审。

D0：Java17 Maven verify一次94测试通过，自动发现Node9入口及Python3个文件均exit0。新随机owner/随机端口/新三卷，双Control/双Gateway真实A/B/M/V11基线通过（非历史报告复用），默认C OFF无表安全，停止自有双Control后幂等安装C再启动。见v12-d0-preflight/e2e/schema.json。两条初始命令的shell通配符/错误Compose文件名失败已记录并使用真实命令重跑。PR#17～#22 native tree/merge-base审计见`docs/reviews/v12-d-pr-stack-audit.md`；没有修改旧PR。

D1：已实施原schema派生RETRY_EXHAUSTED、ADMIN原eventId审计重排、租约/Consumed/事件与节点双300秒/CAS守卫、Console真实恢复时刻。永久错误不解封；真实Rabbit连续8次失败→FAILED8→RETRY_EXHAUSTED，节点/事件自然300秒后双ADMIN一200一409，原eventId恢复Consumed/IGNORED_TERMINAL，100次真消息不重建订单/库存，审计仅一条。新JUnit3项通过；见v12-d1-outbox-recovery.json。


D2：`d4c9626`及补充`658810a`。双Gateway100并发Free5恰5通过/95拒绝；同charge100次仅一Ledger；真实轮换/撤销/暂停、SSRF、CB OPEN/HALF_OPEN、timeout、socket取消许可、旧A二进制混版与快照回滚。实际MySQL pause/unpause、四Rabbit main/retry/poison、NACK/Return；真实12次TTL重试/13次交付进Poison（瞬态业务回调为明确注入）。独立UTC schema事务探针覆盖跨月幂等与日表写失败回滚；独立租约schema双worker100条无重叠、自然31秒后重领、旧owner更新0。真实HTTP代理丢弃Control COMMIT成功回复，Gateway同chargeId重试，Ledger1/下游POST1/201透传。最终COMMIT后丢ACK真Channel恢复见D7。额外M全机离线FROZEN/CAS复验NOT_EXECUTED：受保护的常驻Control不能停；旧包装器先停了部分D Control，已恢复并加完整前置检查；未运行切换CAS，不称M8通过。

D3：`31db0ab`。隔离克隆schema的V11 Ledger/Outbox/Consumed各100005、V12各100012、Audit100051，真实EXPLAIN ANALYZE和同时间戳稳定游标。时间游标推荐索引前服务端约117/115ms，后约0.050/0.044ms；docker客户端耗时单列，非HTTP生产收益。仅生成推荐SQL，没有迁移常驻库。订单/促销表10万规模、物理写放大未执行，D3为有条件完成。

D4：`6fb36dd`。fast/slow10ms、固定50rps/max16inflight、两轮DIRECT/PROXY/GOVERNED/V11及V12 METERED，每组100请求全部200；CPU累计时间/RSS、DB锁/连接池/Questions、Rabbit积压快照均真实保留。增加低基数routeId的quotaHop p95观测，另外新20请求/路由/节点窗口采样，不能冒充公平压测窗口的全局p95。compute/DBheavy上游、极限持续吞吐与GC直方图未执行。

D5：`720261a`。1000/5000/20000请求，24/64/128并发，完成全部请求无clientShed/timeout；实测QPS454.3/701.4/960.8，p95为172.5/214.5/330.7ms，每档100唯一Intent及100订单，100库存无超卖。20k档是5000匿名关系fixture重复4次（202共400，非400个订单），非20k注册用户或20k QPS。1k档10笔实际付款/Grant；5k/20k档抽取首10个身份未命中受理者，实际付款0，不预填付款成功。公开热读1000次投影DB_LOAD增量0，全库Questions增加80另列；真实Redis慢Reader失效代次探针通过。Redis丢失/重建/NOSCRIPT/DB/Rabbit故障最终由D7再验。

D6：`851632b`。真实Chromium1280/375，普通用户注册/三应用/免费/普通付款/活动202到付款；ADMIN真实SKU建立活动14，四链含Poison非零；Tab/Escape恢复焦点、150ms网络、401/404错误和导航，均已实际观察并检查脱敏截图。原普通与促销订单真实Gateway消耗及已用退款409，V11账本不受V12消耗影响；越权404、改价400、四链真实坏JSON各Poison+1且不制造Consumed。全量错误码浏览器样式矩阵、真人签字与耗尽事件现场截图未执行，详见v12-d6-product-and-diagnostics.json。

D7：`c572509`。最终Java17 Maven verify97项/0失败0错误0跳过，Node9入口exit0，Python20项exit0。最终核心促销支付/退款/取消/自然超时、Redis FLUSHDB后503/DB epoch重建、SCRIPT FLUSH fallback、Rabbit停恢复/迟到终态、DB pause恢复/原Ledger不变、真事务COMMIT后Channel在ACK处关闭/重投仍一单一Consumed均通过。末次中文说明变化后仅重新package跳过重复Java测试。最终Jar哈希见v12-d7-full-regression.json，既有各证据sourceCommit是测试时工作树父提交，不能误认为测试时没有未提交新代码；Jar哈希精确标识运行二进制。

持卷STOP→START演练使用最终Jar，双Gateway能力恢复，V11/V12 Ledger=Outbox=Consumed=Daily，活动3/4/5/14对账零缺口。最终再次STOP所有D进程，三卷保留；根.env哈希不变、原三个常驻JVM身份不变、常驻8081/status=200。见v12-d7-lifecycle.json。私有新账号/地址.local/v12-d-access.md（0600），从本仓库python3 scripts/v12-d-preview.py start恢复；没有日常实例D升级授权。

D8：本轮实现者源码自审报告、完整结果/历史场景映射及中文runbook随同一Draft PR#22交付。总体CONDITIONALLY_READY，外部独立复审PENDING_REVIEW、负责人PENDING_OWNER；未签名不写ACCEPTED。原PR#17～#21/main/tag不改，旧PR冗余关闭方案仅供负责人决定。远端App提交如与本地SHA不同，以相同Git Tree和逐包单父链验证交付，实际交付记录见最终结果；不强推、不自合并。

交付前语法检查：Node promotions.js --check exit0；Python py_compile首次因沙箱禁止写系统bytecode缓存而exit1，改为不写缓存的AST解析四入口exit0。git diff --check exit0。此环境错误不计入业务通过数。

远端真实交付：原生push HTTP403/exit128，使用已连接GitHub App在同一分支按序生成10个提交；原生fetch exit0，逐包相同Tree和单父链均已核对。候选远端HEAD 09c007339efcda7002a2dd877ac8c8d7ab63e74e（在交付记录提交之前），本地/远端不同提交SHA但最终树相同。完整映射见v12-d8-github-delivery.json。原PR22 Draft/base C，未改旧PR/main。
