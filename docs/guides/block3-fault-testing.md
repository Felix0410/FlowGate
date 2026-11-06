# FlowGate 功能块 3｜超时、熔断、降级与恢复演示操作指南

> 创建于 2026-11-06；**这是功能块 3 的测试手册草稿，不表示下述 Block3 接口或按钮已经实现**。Codex 开发完成后，应根据真实页面与测试返回修订本文件并填写“实测结果”；负责人自行进行真实浏览器体验，不由 AI 操作电脑。
> 当前既有测试台使用说明：[test-console-guide.md](test-console-guide.md)。功能块 3 技术契约：[`block-3-resilience.md`](../progress/block-3-resilience.md)。

## 1. 本地环境与启动（现有步骤，可直接使用）

先在仓库根目录检查 Java 17、Docker Desktop、Maven 与本项目独立的 `.env`。不要使用其他项目的同名环境文件，也不要提交真实密码。

```bash
# 首次环境仍需先按 docs/guides/test-console-guide.md 创建 .env。
# 修改 Block3 代码后先打包（只构建相关模块及依赖）
mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am package

# 下面脚本只管理本项目启动的三个 Java 进程，Docker 数据卷保留
python3 scripts/run-local.py start
python3 scripts/run-local.py status
```

浏览器打开 `http://127.0.0.1:8081/test-console/`。Control 8081、Gateway 8080、Demo 8085，MySQL/Redis 使用现有本地 Compose；如果你使用 IDE 启动三个主类，不要再启动脚本的三个 JVM 造成端口冲突。当前五步引导：环境检查、管理员登录、初始化公共 Demo 草稿、发布并确认节点、HTTP 测试。具体初始账号和密码来自**你本地首次设置的** `.env`，不是仓库默认密码。

## 2. 新功能的安全初始化（待 Codex 实现后按 UI 更新）

1. 在“配置与版本”查看已有 Demo 服务/路由；**从现有版本继续，勿假定 expectedVersion=0**。
2. Block3 会新增只在本地开发实验模式下使用的 GET `/api/demo/fault` 路由，映射到 `/demo/fault`，上游仍为 `http://127.0.0.1:8085`。以实际页面的“初始化故障演示”功能或原目录 API 添加，**仅补缺失的记录，不重复创建或覆盖**。
3. 打开“故障保护”页签，选择已存在的路由（推荐 slow / fault）后依次保存策略草稿。**保存不生效**；需确认新版本发布后，等待节点 `appliedVersion` 与最新持久化版本匹配。
4. 准备查询 Gateway Actuator `/actuator/metrics`（只从本机读）和演示下游实际到达计数。不要在浏览器或日志中暴露 `Authorization`、`.env`、`FLOWGATE_INTERNAL_KEY`。

## 3. 场景 A：TIMEOUT（预期 504）

推荐策略：ROUTE 作用域，绑定 slow 路由；`{"timeoutMs":200}`，保存后发布。

| 操作 | 预期 | 判断依据 |
|---|---|---|
| `GET /api/demo/slow?delayMs=100` | 200 | 下游响应早于 200ms |
| `GET /api/demo/slow?delayMs=500` | 504 `UPSTREAM_TIMEOUT` | Gateway 自己计时超时；不是浏览器超时 |
| 同一个未设 TIMEOUT 的 fast 路由 | 200 | 不被其他路由超时策略误影响 |
| 超时后执行新慢请求 | 有可判定的 200/504，无永久 503 | 原 Block2 在途许可不泄漏 |

如果新版本仍处于 PERSISTED 且节点尚未应用，则不能把任何旧 HTTP 响应当作新超时生效的证据。

## 4. 场景 B：熔断 CLOSE → OPEN → HALF_OPEN → CLOSED

策略例子（ROUTE：fault）：

```json
{
  "failureRateThreshold": 50,
  "slidingWindowSize": 10,
  "minimumNumberOfCalls": 5,
  "waitDurationMs": 3000
}
```

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | 发布策略并确认网关节点 applied | 当前熔断状态 CLOSED（仅由真实状态返回确认） |
| 2 | 多次 `GET /api/demo/fault?mode=500` | 真实下游 HTTP 500；达到阈值后熔断转 OPEN |
| 3 | OPEN 时继续请求同一路由 | HTTP 503 `CIRCUIT_OPEN`；Demo 实际到达计数**不增加** |
| 4 | 停止注入故障，等待至少 `waitDurationMs` | 再执行由网关允许的两个有限半开试探 |
| 5 | 连续 `GET /api/demo/fault?mode=ok` | 成功样本使状态恢复 CLOSED，后续请求正常 200 |
| 6 | 半开阶段再次 `mode=500` | 应重新 OPEN，后续短路不进入下游 |

实现时可以记录保护指标、Node 已应用版本、底层 Demo 命中数；**不能仅通过前端根据次数模拟状态**。不同客户端时序可能改变触发请求序号，应以真实断路器事件/状态为准。

## 5. 场景 C：只读 GET 的静态受控降级

在 fault GET 路由的 CIRCUIT_BREAKER 参数中明确设 `fallbackMode:"STATIC_JSON"` 并发布。按任务书约定：

- 触发保护时 **HTTP 503**，JSON `code:"DEGRADED"`、`degraded:true`、`source:"GATEWAY_FALLBACK"`。
- 不返回编造的 `{"id":...,"result":"success"}`，也不冒充实际业务成功；正常访问不触发 fallback 时，原响应仍保留。
- 不可给 POST echo 打开该选项。未认证/无 scope（401/403）、限速（429）、Redis 不可用（503）不会被 GET fallback 绕过或更改成 200。
- 内部 `/__flowgate/fallback` 地址不能被外部直接调用；若直接请求获得可用降级处理，应报告高优先级缺陷。

## 6. 场景 D：Control 与 Redis 断联及节点恢复

**先确认测试数据/容器属于 FlowGate**，不要关闭其他项目运行的 Redis/MySQL。建议使用 Block3 独立脚本创建的隔离实例做故障注入，正式用户持久化容器只用管理员可控、可恢复的方式操作。

- Control 暂不可用：Gateway 对最后成功应用的路由继续按原权限/限速/熔断运行；不伪造更高 appliedVersion。Control 恢复后下一次补拉到最新版本。
- 无可信快照时重启 Gateway：未知动态业务 API 404/拒绝，而不是使用旧块 0 公开通配代理。
- Redis 断联：私有 API 身份校验失败为 503，配置了分布式速率预算的路由 503；公开且未绑定速率的路由依已确认快照继续服务。恢复后重新尝试，凭证失效需要重新登录/签发。
- 回滚到 Block1/2 历史 `PENDING`：不自动开启 Block3 TIMEOUT/CIRCUIT_BREAKER；回滚生成**更大的版本号**，不是降低 `currentVersion`；再次发布新策略后才按 Block3 执行。

## 7. 用户遇到错误时应该看到什么

| HTTP/错误 | 业务解释 | 检查方式 |
|---|---|---|
| 400 `VALIDATION_ERROR` | 参数或作用域不合法 | 改合法超时时间/熔断窗口/绑定对象 |
| 401/403 | Caller 凭证或 Scope 不足 | 回到“身份与登录”，不要错误认作断路器 |
| 409 | expectedVersion 落后 | 重新读取当前版本，检查草稿后决定发布 |
| 429 `RATE_LIMITED` | Redis 速率额度用尽 | 查 Block2 策略，与熔断无关 |
| 503 `CONCURRENCY_LIMITED` | 本实例的在途许可耗尽 | 查看 slow/fast 并发实验 |
| 503 `CIRCUIT_OPEN` | 当前断路器短路，未访问下游 | 查看真实状态与下游到达计数 |
| 503 `DEGRADED` | 只读路由启用静态降级 | 检查 `degraded:true`、`source` |
| 503 `ADMISSION_UNAVAILABLE` | Redis 限速依赖失败 | 检查 Redis，不会自动降级为业务成功 |
| 504 `UPSTREAM_TIMEOUT` | Gateway 主动等待超时 | 核查 timeoutMs、Demo delayMs、版本应用 |

**状态码最终以运行的真实结果为准**；Codex 完成后需要在此文件增加实际版控 SHA、成功命令及一组可复核示例。不把本表当成已经执行的验收证据。

## 8. 完成时由本地 Codex 真实回填

| 验收项目 | 状态 |
|---|---|
| 超时 slow 504 | 待实现/未执行 |
| 真实 500 触发 OPEN，OPEN 不访问 Demo | 待实现/未执行 |
| 半开恢复 CLOSED | 待实现/未执行 |
| GET 静态降级明确 503 degraded | 待实现/未执行 |
| Redis/Control 故障按预期恢复 | 待实现/未执行 |
| 节点/指标真实显示 | 待实现/未执行 |
| 测试台保留旧按钮并有中文引导 | 待实现/未执行 |
| 负责人真实浏览器体验 | 未执行 |

**完成后同步更新** `docs/guides/test-console-guide.md`、`docs/progress/block-3-resilience.md` 与 README。