> **最终阶段移交（2026-11-23）**：此文件记录V12-C已实现功能及Codex原始测试证据，不改写历史结果。下一次**唯一执行合同**是[V12-D Final D0～D8](v12-d-final-delivery.md)与根`CODEX_V12_D_FINAL_EXEC_PROMPT.md`，将已知`PromotionService` FAILED达到8次不能自动/人工恢复的P1、C #20/#21同Tree审查、5k/20k HTTP请求阶梯、Rabbit重试/300s、真实M/C验收一起处理，不再新开C收尾阶段。本页“正式验收未完成”事实继续有效，D文档创建不等于P1已修。

# V12-C实施记录

候选已交付 [Draft PR #21](https://github.com/Felix0410/FlowGate/pull/21)，base为C设计分支。原生push403，GitHub App逐段交付，native fetch独立核对四段tree与有序parent全部相符；完整本地/远端SHA映射见`evidence/v12-c-delivery.json`。未改main/B/M ref。独立源码复审和负责人真人验收待完成。

负责人明确授权完成C，按现有M候选继续；M8独立复审和负责人真人验收没有改写为通过。以C设计分支最新HEAD建立`feat/v12-c-promo-cache`，旧PR/main不变。

C0：Java17现有94项一次通过、Node9入口通过，全新owner/随机端口/新卷的双Control/双Gateway B/M关键链路通过，真实证据见`evidence/v12-c0-baseline.json`和`v12-c0-e2e.json`。用户常驻环境/.env未动。后续集中于C缓存、限量库存和购买闭环，完整历史故障矩阵不重跑。

C1 `dd82c18`：公开分类/首页/详情/SKU/活动Cache-Aside，代次CAS、提交后失效、TTL抖动、短负缓存、有界单飞和回源。两Control50次热读无新增公开投影DB回源，新SKU提交跨节点失效、404负缓存与真实Redis暂停已验证。

C2～C6：五表与原Order来源CHECK单次原子ALTER扩展、Intent FK，真实中断续跑/重复迁移/旧订单未变。ADMIN冻结活动、Lua仅候选、MySQL原子预留与202、独立促销quorum/Retry/Poison、异步PROMOTION订单、B付款/取消/超时/退款库存hook、Portal与Console已实施。`v12-c2-schema-promotion.json`、`v12-c4-c6-promotion-e2e.json`为真实证据。首次活动联调暴露无自增stock表误用insert取ID，已改为update并实测通过；只编译不足以证明可运行。

C3/C7恢复：隔离Redis FLUSHDB实际503阻断，无持久预留；重建epoch推进，SCRIPT FLUSH回退；Rabbit真实stop后仍持久202、Intent期限到释放stock/slot、恢复晚到事件IGNORED_TERMINAL；旧隔离API Ledger笔数/单位不变，MySQL pause/unpause恢复。首次恢复脚本缺SQL分号导致观察失败，Lab统一补终止符后对原事件继续核验；未伪造首次PASS。见`v12-c3-c7-recovery.json`。RR有限对账核对Intent/Order冻结字段、收据、付款Outbox、促销Consumed、Slot和Grant/Subscription/Balance，超过1000明确UNKNOWN，今日只PROVISIONAL。

C5实际ACK窗口：新事务建单COMMIT后故意关闭真实Channel丢ACK，Broker真实redelivery仅一Order/Consumed；独立promo retry实际5秒TTL、坏JSON实际留promo Poison。见`v12-c5-rabbit-intent.json`。未展开12次重试耗尽及300秒SENT补投实等矩阵；代码保留这两项门槛。

C8按负责人“快和准，不要过多测试”缩为必要真实样本：120个明确标注的隔离DB/Redis身份fixture（不是120次真实注册），双Control真实HTTP恰100个202/100唯一Intent/Order，重复请求同Intent，10收据/10 Grant，采样时库存100=reserved90+sold10+available0。1000次24并发公开缓存读实际全200且公开投影DB_LOAD增量0。QPS/分位值仅本机样本，计数不等于所有SQL；5k/20k阶梯NOT_EXECUTED、20k QPS NOT_REACHED。见`v12-c8-performance.json`。

已有Java17实施快照一次94项0失败/错误（最后队列诊断/页面微调后另编译及真实HTTP核验，未重复全量Java），Node9入口、Python14通过。默认沙箱中Mockito attach/socket bind受限的失败日志保留于.local，原生重跑通过；不是业务失败被跳过。C9浏览器与最终交付记录在下方追加。完整历史B23/C26、大压测、M8/C独立源码复审、负责人真人验收未执行；不称正式验收，不进入D。

C9：真实Chromium通过ADMIN表单选真实SKU创建/发布“限时套餐体验”；Portal新建本人APP→202排队→异步订单→模拟支付→FULFILLED。1280/375实际innerWidth与document.scrollWidth一致；键盘Tab核验，窄屏按钮微调后截图已复查。截图见`evidence/v12-c-browser/`，详细`v12-c9-regression-browser.json`。促销已付订单实际双Gateway各200，已用退款409且2条Ledger保留；Console后端实际返回独立promo Poison1/消费者4，见`v12-c9-final-http.json`。模拟付款始终不扣真钱。

本地提交：C0 `2369f07`，C1 `dd82c18`，C2～C7业务/UI `35f1cbf`。业务分段证据生成时工作树未提交，以当时sourceCommit与controlJarSha256共同定位；不能把旧HEAD误称为当时未提交源码已在该commit内。最终候选源码树/本地与远端提交映射另记交付记录。GitHub Draft和C/M8真人验收是独立状态。

中文产品页/Portal/Console帮助、README/AGENTS/路线图已同步。`v12-c-preview.py start|status|stop`仅恢复C0 owner隔离候选，根.env/flowgate-local/旧Kafka卷未改。初次新隔离实例可显式选择C开关安装增量，已有实例不自动补迁移。为遵守本轮合同，交付时停止本轮测试实例并保留全部卷、历史账本和私有账号指引，不把C默默部署到8081。

一键预览已实际stop→start恢复双Control/双Gateway并复用旧C数据，随后最终stop；三卷保留，日常M Control仍为PID68665/8081。最终状态及截图/命令退出码见v12-c9-regression-browser.json。
