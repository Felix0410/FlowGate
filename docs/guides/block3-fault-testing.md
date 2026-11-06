# FlowGate｜故障保护业务页面使用说明

> 2026-11-06：对应块3已实现功能。自动JUnit/HTTP/Node测试已运行；本文点击步骤由负责人自行抽查，Codex没有操作电脑或冒充浏览器人工验收。

## 1. 启动与可选配置

正常业务启动不新增必填配置。Java17构建后，使用现有Docker依赖，分别在IDEA运行Demo、Control、Gateway；Control/Gateway加载**FlowGate**根目录`.env`。不要同时使用脚本和IDEA重复启动同一个应用。

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am package
# 如果用脚本启动，而不是IDEA：
python3 scripts/run-local.py start
```

仅当需要抽查故障API时，在**Demo的运行环境**设置`FLOWGATE_DEMO_FAULT_ENABLED=true`并重启Demo。用脚本时可在本机`.env`添加此变量；用IDEA时让Demo加载该文件或单独填写环境变量。默认关闭，普通fast/echo/slow不受影响。不修改密码、数据库卷或用户已有容器。

打开 **http://127.0.0.1:8081/test-console/**，使用本机已初始化账号登录。

## 2. 业务页面如何操作

- **业务概览**：读取真实服务/API目录，看公开/需授权和草稿状态；顶部区分已发布版本与节点已应用版本。
- **服务/API与发布**：读取可选服务/API，从下拉选择关联对象，保存业务草稿，无需手写JSON。高级JSON编辑保留在折叠区；已有公共Demo初始化入口继续保留。
- **流量策略**：原限速、并发功能保留；已有策略只改参数/启停，不能把未保存的目标切换伪称绑定已更新。
- **故障保护**：读取路由与保护策略，选择已有策略编辑，或选择目标路由新建；已有绑定被锁定。基本表单只做ROUTE范围，SERVICE/API范围的超时仍可使用原高级策略接口。

保存草稿后，点击“发布配置”，确认后持久化；再点“查看节点与熔断状态”。只有节点应用版本等于当前发布版本时，才按该版本规则执行。页面的OPEN/HALF_OPEN/CLOSED来自节点最近真实上报，不能当作没有延迟的实时状态；STALE表示最近心跳已过期。

## 3. 抽查超时

1. “故障保护”读取路由，选择`demo-slow`，类型“响应超时”，名称用唯一值，例如`slow-timeout`，等待时间`200`毫秒。
2. 保存、发布、查看节点，确认应用对应版本。
3. 展开“抽查演示”，点击“请求slow 500ms”，应为**504 UPSTREAM_TIMEOUT**；响应详情中的requestId应与响应Header一致。
4. 回到“接口调用”，slow设`0`或`100`，应为200。若配置了并发保护，超时后仍应可再次准入。

未绑定路由保持框架原全局超时行为。TIMEOUT允许50..10000ms；SERVICE/API/ROUTE多层配置取最小值。后端超时不是浏览器fetch自行计时。

## 4. 抽查熔断和恢复

1. 点击“添加故障演示API草稿”，只补缺失，不覆盖已有API；发布并确认节点。Demo须先开启本机故障演示。
2. 读取路由，在`demo-fault`上新建故障熔断：失败比例50%、窗口10、最少5次、等待3000ms，不可用响应选择“保留故障状态”。保存、发布、确认应用。
3. 点击“上游故障500”若干次。最初为下游500；达到阈值后继续调用应为**503 CIRCUIT_OPEN**，此时不访问Demo。
4. 等待至少3000ms，刷新实际状态。自动转HALF_OPEN后，点击“正常请求”两次，再刷新状态，应恢复CLOSED；半开任一次失败会回OPEN。
5. 超时和上游500/502/503/504会计入失败；认证401/403、限速429、本机并发拒绝503在CB之前返回，不计其失败样本。

无降级时，官方statusCodes Filter会把受控上游5xx转成异常；FlowGate保留原HTTP状态并返回`UPSTREAM_ERROR / source=DOWNSTREAM`的明确来源JSON，区别于OPEN短路。该分支不原样返回上游错误Body；正常业务响应仍由Gateway代理。

## 5. 抽查只读不可用提示

编辑已有GET熔断策略，将不可用响应改为“只读GET返回明确的不可用提示”，保存、发布、确认节点。

- 故障或OPEN时为**503 DEGRADED**，包含`degraded:true`、`source:GATEWAY_FALLBACK`。
- 不伪造业务成功或HTTP200；正常请求仍正常代理。
- POST echo不能启用此模式，发布会400；内部`/__flowgate/fallback`直接访问404。
- 认证/Scope/Redis/限流拒绝不会变成降级成功。

## 6. 状态和常见错误

| 返回 | 含义 |
|---|---|
| 400 | 参数、作用域或只读降级限制不符合要求 |
| 401/403 | 凭证/Scope问题，优先于流控和熔断 |
| 409 | 草稿发布版本已变化，刷新后由用户决定，不自动重试覆盖 |
| 429 RATE_LIMITED | Redis速率预算耗尽 |
| 503 CONCURRENCY_LIMITED | 当前Gateway本机在途容量已满 |
| 503 ADMISSION_UNAVAILABLE / AUTH_UNAVAILABLE | Redis依赖失败，继续拒绝受保护请求 |
| 503 CIRCUIT_OPEN | 官方断路器拒绝，本次不访问下游 |
| 503 DEGRADED | 只读接口的明确静态不可用提示 |
| 504 UPSTREAM_TIMEOUT | Gateway等待下游响应超过受控时间 |

原始HTTP/JSON、耗时和Request ID在操作结果详情中折叠保留；页面以业务表单/列表为主，不作为复杂验收或压测平台。

## 7. 自动化实测证据

```bash
source .env
mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package
node scripts/block3-console-test.js
python3 scripts/block3-smoke.py
```

脚本使用独立临时MySQL/Redis、随机端口与Demo/Control/两个Gateway，不控制浏览器或使用用户业务数据。退出清理自建JVM/容器/匿名卷。末次无凭证HTTP证据在[block3-smoke.json](../progress/evidence/block3-smoke.json)，阶段命令、数字和已知差异在[块3进度第11节](../progress/block-3-resilience.md)。

已实测：旧PENDING历史不执行保护；全局1500ms与路由200/2500ms覆盖；CB不叠加默认1秒TimeLimiter；真实状态转换及短路计数；认证/限速/并发不进入CB；Redis暂停恢复；Control停机与Gateway无快照重启；回滚旧PENDING和恢复已生效历史。原始历史checksum不改写。

负责人浏览器、窄屏/键盘可用性、最终完整回归和正式性能测试**未执行**，不能据本脚本宣布全部项目验收完成。
