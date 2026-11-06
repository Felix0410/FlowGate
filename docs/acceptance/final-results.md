# FlowGate 原Block4基线实测记录（不含新产品后台）

2026-11-06，按df00937任务书本机执行。收尾fetch发现远端追加了产品后台P0门槛（823556f），当前转入PC-00..06。以下保留旧基线真实数据，不能代表新产品后台或新版Block4完成。**负责人真实浏览器体验、ChatGPT复审及main合并仍待执行/授权，不宣称FlowGate V1已最终验收。**

## 1. 源码、环境与数据保护

- 工作分支：`test/block4-final-acceptance`，任务基线`df00937a8403be4912101d2b0344707e2ce5e290`；性能与补充边界脚本提交`d59acf9`。未改变Java业务源码、依赖或数据库schema；正式性能JSON另记录完整Git SHA和脚本SHA256。
- JDK：Microsoft OpenJDK17.0.18+8-LTS；Maven3.9.13；macOS27.0/Darwin27 ARM64；宿主10逻辑CPU、64GiB内存；Docker29.8.0，VM10CPU、8319504384字节内存（约7.75GiB）。未为测试设置额外容器CPU/内存配额或JVM堆上限。
- MySQL8.4、Redis7-alpine各用独立随机名称容器、随机回环端口和全新临时数据；两Gateway、一个Control、一个Demo JVM均自建。正式负载仅经过Gateway0，第二节点只供正确性验证；Demo Tomcat最大8线程、最小空闲2线程。
- 原有IDEA/其他Java服务和`flowgate-local-mysql-1`/`flowgate-local-redis-1`继续运行，未重启或停止；因此这不是独占宿主性能实验。没有覆盖.env、删除用户数据卷、clean工程、改凭据或使用电脑界面控制。
- 只读GitHub核对PR#1–#4均open/draft、未合并，叠加base依次main→Block1→Block2设计→Block2实现；本轮开始远端main为`84a25d5a7b9545dff3556299efec3c310fc321cf`。不修改PR的base或main。

## 2. 实际命令、退出码与自动化结果

所有Maven及JVM均在`source .env`后使用该JAVA_HOME17；没有把shell默认Java21作为测试JDK。长命令日志保留本机，敏感日志不提交。

| 命令 | 退出码 | 真实结果 |
|---|---:|---|
| `mvn -B -ntp verify`（沙箱内首次） | 1 | Contract7通过；Control的Mockito无法self-attach，1 error；其余模块未执行，不是业务断言失败 |
| `mvn -B -ntp verify`（正常本机权限复跑） | 0 | 全部模块成功，JUnit34通过，0失败/错误/跳过 |
| `node --check flowgate-control/src/main/resources/static/test-console/app.js` | 0 | JavaScript语法通过 |
| `node scripts/block2-console-test.js` | 0 | 原导航/权限/清密/版本/请求与错误渲染契约通过 |
| `node scripts/block3-console-test.js` | 0 | 包含块2契约及保护表单/真实路径/504与降级/节点/已有绑定锁定，通过 |
| `python3 scripts/build-console-guide.py --check` | 0 | 14章Markdown与离线HTML一致 |
| `python3 scripts/test-block4-performance.py` | 0 | 最初3项统计/节奏单测通过；补CPU差值检查后4项通过 |
| `PYTHONPYCACHEPREFIX=/tmp/flowgate-block4-pycache python3 -m py_compile scripts/block4-performance.py scripts/block4-boundaries.py` | 0 | 新脚本语法通过 |
| `python3 scripts/block2-smoke.py` | 0 | 4组真实断言通过，含Redis桶自然60秒TTL；未逐HTTP计数，不虚构请求总数 |
| `python3 scripts/block3-smoke.py` | 0 | 191条HTTP记录，passed=true，故障恢复/超时/熔断/降级/旧PENDING回滚通过 |
| `python3 scripts/block4-boundaries.py`（最终复跑） | 0 | 361条HTTP记录，3组断言通过，补身份/代理/五维顺序、取消与网络失败、修订容量与节点恢复 |

JUnit按模块：Contract7、Control1、Gateway22、Demo4。Gateway包含7项真实缓存/版本切换测试，涵盖取消恢复互斥、迟到写回、刷新失败/超时补偿及保护metadata一致性；Contract覆盖checksum及旧PENDING；Admission覆盖complete/error/cancel/timeout释放和版本隔离。未把Node模拟DOM当作真人浏览器验证。

两个新增补充实验的失败记录均保留本机：第一次错误继承Block3实验全局1500ms超时，却期待合法2000ms慢请求成功；改为本脚本专用3000ms。第二次在“一次成功+一次500已达50%”后仍期待第二次500，实际已正确OPEN并503；修正断言。两次均是测试夹具/预期错误，没有修改生产行为来迎合测试。

Block1旧脚本依赖本机固定MySQL socket/13306和8080/8081/8085，**未直接运行**。其核心身份/代理/会话/CAS边界通过新的随机端口Lab补测；既有Block2/3覆盖其余发布/回滚与故障路径。不宣称执行了旧脚本每个断言。

## 3. 实链路与证据

- [本轮块3完整联调](../progress/evidence/block4-functional.json)：191条，HTTP200×121、201×30、400×5、401×4、403×3、404×3、429×1、500×7、503×11、504×6。这些错误码是有意测试预期；轮询次数可能随调度不同，旧块3历史192条证据保留原样。
- [补充边界与容量](../progress/evidence/block4-boundaries.json)：361条，HTTP200×285、201×29、204×2、400×11、401×10、403×3、404×5、409×1、429×5、500×8、503×2；只记录路径/状态/必要运行摘要，不保存Body或认证Header。
- 实测Trace Header/Body一致并覆盖伪造ID、GET编码空格/Query、POST JSON/Header/201、慢请求合法/非法边界、404、管理内部/Actuator写端点拒绝、CORS白名单。
- ADMIN/OPERATOR/VIEWER权限、Caller用途与scope、Redis TTL、原子撤销、管理退出/到期、SSRF上游限制；并发CAS恰好一200一409，数据库历史数量和既有JSON保持正确。
- Redis Lua初始/耗尽/补充/回拨/同Key并发、TTL、两网关共享速率和本机并发独立；补充实验在本Lab专属桶冻结补充时间，逐维实证GLOBAL→SERVICE→API→ROUTE→CALLER，前层扣一次、不退款，拒绝后的层不访问。
- Block3实测OPEN不再触达Demo、半开两次成功关闭/一次失败重新打开、主动504、GET降级503、POST降级配置400、401/403/429/Redis503不降级、低基数指标、Control离线保留/无快照Gateway关闭/恢复追平、PENDING历史双向回滚不变。
- 第二Gateway真正停止后等待超过15秒的新鲜度边界并显示STALE，第一节点继续200；第二节点重启后重新ONLINE且业务200。未用篡改节点时间冒充失联。

## 4. 容量与请求终止边界

20次不同CB参数revision发布，每次等待双节点实际应用并完成真实请求。`jcmd GC.class_histogram`在自建Gateway完整GC后：breaker实例**1→21**、breaker浅层占用56→1176字节；全体活对象堆33152992→33441016字节（增加288024字节）；Gateway RSS449744→437824KiB，Demo RSS253392→253728KiB。RSS受GC/驻留页影响，下降不能证明无泄漏，浅层字节也不代表熔断器全部保留内存。

**已确认限制：旧CB实例由官方注册表和配置映射持有，没有按revision自动淘汰。** 本轮遵循任务书的有界实验，不为清理而误删旧在途请求状态或改写历史快照。V1仅接受本机、低频配置演示场景，是否接受此限制由负责人复审决定；不适用于无界高频发布，不能写“无限长期稳定”。长期使用应另行设计带生命周期的安全淘汰及验证。

真实TCP客户端在2000ms慢请求进入Demo后关闭，Gateway许可可重新进入且原Servlet仍有1个在途，随后Servlet自行归零。停止自建Demo时网关实际返回**500**（网络失败，不是业务500/熔断短路的同一来源），重启Demo后同一并发规则可再入200；超时许可释放由Block3的504后立即再入和JUnit共同验证。不能把Gateway许可归还宣传为强制中断下游Servlet。

## 5. 正式三组对照

正式执行命令（退出码0，passed=true）：

```bash
source .env
python3 scripts/block4-performance.py --seconds 30 --warmup 3 --repeats 2 --fast-rps 8 --slow-rps 8 --output .local/acceptance/block4-performance-run1.json
```

原始2880条请求与观测在本机`.local/acceptance/block4-performance-run1.json`，不自动提交；经脱敏的[正式摘要与校准记录](../progress/evidence/block4-performance-summary.json)包含Git/脚本SHA、六组汇总、资源前后值及限制。预热3秒不混入正式样本，两轮均按DIRECT→PROXY→GOVERNED顺序。所有路由公开；fast无治理，slow=1000ms；治理组仅slow绑定容量2/每秒补2和本机并发2。客户端最大在途48，所有组同一开放式时间表。

先按任务书执行默认8+16/s的5秒×三组短冒烟，退出码0但**passed=false**：DIRECT/PROXY各CLIENT_SHED20（其中fast6、slow14），因此不能作为公平对照。随后三组统一改为8+8/s，新的5秒短冒烟passed=true，再按同一8+8/s执行正式六组；两个短实验JSON均保留，不覆盖。短样本不用于P99结论。

每组预定480次（fast240+slow240），下表分位数只用fast成功样本；成功RPS分母为30秒发送窗口，括号内为包含最终排空的实际墙钟RPS，**不能把发送窗口归一值当稳态实测处理上限**。

| 轮次/模式 | 成功数/预定数 | 成功RPS（含排空） | fast P95/P99 ms | 429/503 | 成功率 | 观测Demo在途峰值 |
|---|---:|---:|---:|---:|---:|---:|

| 1/DIRECT | 480/480 | 16.000（15.438） | 356.507/406.752 | 0/0 | 100.00% | 7 |
| 1/PROXY | 480/480 | 16.000（15.465） | 299.013/323.635 | 0/0 | 100.00% | 7 |
| 1/GOVERNED | 282/480 | 9.400（9.244） | 10.796/21.581 | 179/19 | 58.75% | 3 |
| 2/DIRECT | 480/480 | 16.000（15.439） | 305.333/323.278 | 0/0 | 100.00% | 7 |
| 2/PROXY | 480/480 | 16.000（15.441） | 317.345/341.058 | 0/0 | 100.00% | 7 |
| 2/GOVERNED | 281/480 | 9.367（9.237） | 11.568/18.669 | 179/20 | 58.54% | 2 |

六组CLIENT_SHED=0、NETWORK_ERROR=0，每组fast240/240成功；总HTTP2880，200×2483、429×358、503×39。治理以拒绝slow换取fast尾延迟改善，总成功率和总成功吞吐都下降；不是“所有业务吞吐提高”。Demo在途是全部业务请求采样，治理组可能2个slow加1个fast达到3，不能把它误读成slow并发上限失效。采样0.5秒且观测接口共用Tomcat线程，峰值可能低估。

纯代理与直连P95接近且两轮有交叉，不能从本实验精确推断额外单请求延迟，JIT、顺序与共享宿主噪声均存在。每组fast仅240样本，P99对应最尾约3个样本，只供本机参考；没有可信生产SLA或稳定提升百分比。

### 资源成本

CPU以自有PID累计CPU秒差/采样墙钟计算，100%表示占满一核心；Redis为INFO CPU差值。CPU窗口覆盖资源采样及排空，与30秒发送窗口略有差异；后台200ms快照轮询、Control和第二节点也在运行。RSS和Redis内存只记录前后采样，不是峰值；不能用来证明无泄漏。

| 轮次/模式 | Gateway0 CPU% | Demo CPU% | Redis CPU% | Gateway0 RSS前→后 MiB | Demo RSS前→后 MiB | Redis used_memory前→后 MiB |
|---|---:|---:|---:|---:|---:|---:|
| 1/DIRECT | 4.591 | 4.814 | 0.958 | 429.5→433.7 | 246.7→250.3 | 1.12→1.12 |
| 1/PROXY | 13.316 | 2.631 | 0.784 | 437.1→453.3 | 250.4→255.8 | 1.12→1.12 |
| 1/GOVERNED | 9.354 | 1.434 | 1.133 | 459.8→464.7 | 255.8→256.0 | 1.30→1.30 |
| 2/DIRECT | 2.819 | 2.915 | 1.012 | 464.8→464.3 | 256.1→256.2 | 1.28→1.28 |
| 2/PROXY | 10.436 | 2.721 | 0.968 | 464.4→441.5 | 256.3→258.6 | 1.28→1.28 |
| 2/GOVERNED | 8.557 | 1.992 | 1.166 | 441.8→442.9 | 258.6→258.9 | 1.31→1.31 |

代理组Gateway0的平均单核CPU约10.4–13.3%，治理组约8.6–9.4%，直连组仍有约2.8–4.6%轮询/后台成本；不是空闲为零的纯函数计时。Control/第二Gateway的CPU、RSS和Redis RSS原值也保留在摘要中。整体RSS包含JIT、堆保留、OS驻留页和GC差异，不以两轮数据推导长期容量。

## 6. 前端、文档、安全和未执行项

- 保留全部业务按钮、50个字段帮助、14章同源离线指南；验收说明页继续无JavaScript、无凭证、无读本机报告/发压测能力。同步实际校准命令、CLIENT_SHED含义、吞吐口径和CB历史revision限制。
- 最终`mvn -B -ntp -pl flowgate-control -am package -DskipTests`退出0，仅打包更新静态资源，明确跳过Java测试；Node两套契约、Python4项、py_compile、guide同步与三页HTML链接/锚点/字段检查再次退出0。没有为了静态说明变更反复重跑后端全量回归。
- 没有新增运行依赖/模块/schema；标准库Python与既有Node用于验收。Plus仅沿用块2已注明来源的Lua数学/Redis TIME思路；`THIRD_PARTY_NOTICES.md`与Apache2许可文本保留。Gateway使用Reactive Redis，无JDBC/Servlet/阻塞sleep/ThreadLocal新增。
- 新增Python的关键诊断、节奏、扣减顺序和容量阶段有中文注释及编号。变更与脱敏证据检查不包含本机.env的真实密码、内部Key或BCrypt；不提交原始日志、构建输出、.env或Token。
- **未执行/未批准**：负责人1280px/窄屏/Tab/角色浏览器体验、ChatGPT复审、长时间生产容量/正式生产压测、生产公网部署安全审查、CI、main合并及tag。Stacked PR旧描述有历史状态，以当前本报告和阶段进度为准；未擅自改PR描述、base或合并。

## 7. 本轮结论与Git交付

Block4自动化实施与本机统一测试、正式三组两轮对照已完成；有界容量风险已实证并保留。**等待ChatGPT和项目负责人复审及负责人浏览器验收，FlowGate V1尚未最终验收/合并。** Git最终提交与推送实际结果在阶段进度末尾单独记录，不把早期历史403当成本次必然结果。
