# FlowGate 本地测试台｜第一次使用指南

> 更新：2026-11-06。对应功能块3简单业务前端；浏览器布局和负责人可用性验收尚未执行。
> 使用范围：本机开发演示，不是公网部署方案；密码、Token、内部密钥不可提交 Git 或发到聊天中。

## 1. 先启动服务（macOS + JDK17 + Docker Desktop）

确认已在 FlowGate 仓库根目录，机器已安装 JDK17、Maven 3.9+、Docker Desktop、Python3，并且 Docker Desktop 正在运行。

首次安装：

```bash
# macOS 选择 JDK17；实际 JDK 路径以本机为准
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am package

# 仅当 .env 不存在时创建，不覆盖已有个人配置
cp -n .env.example .env
chmod 600 .env

# 用隐藏输入生成 BCrypt 哈希（需要 Control Jar 已构建）
sh scripts/hash-password.sh
```

打开根目录 `.env` 编辑，填入：`JAVA_HOME`、`FG_MYSQL_PASSWORD`、`FG_MYSQL_ROOT_PASSWORD`、`FG_REDIS_PASSWORD`、`FLOWGATE_INTERNAL_KEY`、`FG_BOOTSTRAP_ADMIN_USERNAME`、`FG_BOOTSTRAP_ADMIN_BCRYPT`。内部密钥至少 32 字符，可用 `openssl rand -hex 32` 生成；BCrypt 哈希使用**单引号**保留 `$`。配置文件只在本机，不要提交。

```bash
python3 scripts/run-local.py start
python3 scripts/run-local.py status
```

正常应看到 Demo（8085）、Control（8081）、Gateway（8080）就绪，Control 的 mysql/redis 为 UP。首次运行 docker compose 会建立独立 MySQL/Redis 数据卷；已有卷不会因修改 `.env` 自动重建账号密码，**不要为了排错随意删卷**。

停机和重新启动：

```bash
python3 scripts/run-local.py stop       # 只停本项目脚本管理的三个 JVM
python3 scripts/run-local.py restart    # 修改 Java 并重新打包后重启
docker compose -f compose.local.yml stop  # 另外停止容器，数据卷仍保留
```

## 2. 打开网页与快速定位

浏览器打开：**http://127.0.0.1:8081/test-console/**

新版默认打开“业务概览”，顶部导航为环境状态、接口调用、账号与调用方、服务/API与发布、流量策略、故障保护、帮助和最近操作。首次使用五步说明折叠保留，原始HTTP详情也默认折叠；业务页面不承担复杂验收或压测。所有原有操作保留，每项操作在所在分区显示独立结果卡，最近十条仅保留无敏感摘要。顶部状态始终显示依赖、PERSISTED版本和节点appliedVersion；首次检查前不会显示UP。

**IDEA启动**：分别运行 Demo、Control、Gateway 的主类，JRE均选择17。Control/Gateway运行配置的环境文件必须是 **FlowGate** 根目录 `.env`，不要误选 AIGateRe 项目的同名文件。不要同时用脚本和IDEA重复启动；需要切换时先 `python3 scripts/run-local.py stop`。该命令只停止脚本自身的JVM，Docker保留运行。

## 3. 第一次演示：公共 Demo（从空库 V0 开始）

1. 在“环境状态” 点击“探测服务”，必须先看到相关依赖 UP；若探测失败先查 `.local/run/` 日志。
2. 在“账号与调用方” 输入管理员用户名和原始密码，点击“登录”，应显示 ADMIN。页面刷新会丢失临时 Token，需要重新登录，这是当前设计。
3. 在“服务/API与发布” 点击“初始化公共 Demo 草稿”。这是**只补齐缺失的** Demo 服务、三个公共 API 和路由，不会自动发布。
4. 点击“当前持久化版本”。**不要假设一定是 0**：如果返回 version=0，确认自动读取的 expectedVersion=0；如果已有版本 Vn，自动读取后应显示 Vn。
5. 点击“发布完整配置”，返回应为 HTTP 200 且 `status=PERSISTED`、`version=n+1`，注意这**还不代表 Gateway 已应用**。
6. 等待/重复点“节点实际应用版本”，看到某个节点 `appliedVersion=n+1` 且 checksum 对得上，才能确认已应用。若没有，检查 Gateway/Control 状态和日志。
7. 在“接口调用” 点击 GET fast（id=123、tag=abc），预期 200；POST echo（合法 JSON 对象，例如 `{"name":"flowgate"}`）预期 201；GET slow（delayMs=100）预期 200，越界 2001 预期 400。
8. 每次查看就地结果中的 HTTP 状态、JSON 和 X-Request-Id。私有认证不影响已明确发布为公开的三个 Demo 接口。

如果原本已有草稿/版本，建议先查看 `services`、`apis`、`routes` 的实际列表，而不是反复创建重复对象。

## 4. 进阶演示：私有路由与版本回滚

1. 在“服务/API与发布” 点“添加私有 Demo 草稿”；该按钮只添加 `GET /api/private/fast/{id}`，要求 Scope `demo:read`，仍需显式发布。
2. 先点“当前持久化版本”，把真实 `version` 写入 expectedVersion，点击发布；等待节点 appliedVersion 同步到新版本。
3. “接口调用” 的“私有路径”使用 `/api/private/fast/123`，**不带 Caller Token** 点击“请求私有 API”，预期 HTTP 401。
4. “账号与调用方” 中输入 Caller 名称、Scopes=`demo:read`，点击“创建 Caller”。一次性 Token 会暂存在当前页面的密码输入框，默认隐藏；页面会自动填写 Caller Token 和吊销 ID。现在私有请求预期 HTTP 200。
5. 如要测试 Scope 不足，创建一个只有 `other:read` 的不同名称 Caller，私有请求应返回 HTTP 403。不要把真实 Token 粘贴到聊天或仓库。
6. “账号与调用方” 点击“吊销”，再次请求私有 API 应返回 HTTP 401。
7. 在“服务/API与发布” 的“发布历史”选择**最初仅含公共 Demo 的版本**（不一定是 V1，取实际值），填写“回滚来源版本”和**当前持久化版本** expectedVersion，点击“回滚为更大新版本”。成功后新版本号必须大于旧版本；等待节点同步。
8. 再请求 `/api/private/fast/123` 应为 HTTP 404；原三个公开 Demo API 应仍可用。回滚不会擦掉草稿；下一次发布草稿仍可能重新开放私有路由。

## 5. 常见错误和处理

| 网页现象 / HTTP 码 | 常见原因 | 你可以做什么 |
|---|---|---|
| 网页无法打开 | Control 没启动或端口改变 | `python3 scripts/run-local.py status`、检查 8081 和 Control 日志 |
| 依赖未就绪/503 | MySQL、Redis 离线或密码不一致 | 确认 Docker healthy、`.env` 与容器初始配置相符；不删数据卷 |
| 登录 401 | 用户名或原始密码错误，或会话过期 | 确认 ADMIN 是首次初始化的账号；刷新后重新登录 |
| 管理 403 | VIEWER/OPERATOR 没有对应写入或 Caller 管理权限 | 使用具备授权的管理员登录 |
| Demo 404 | API 尚未发布、路径不匹配或回滚删除了私有路由 | 查看当前版本、路由列表与 nodes.appliedVersion |
| 保存草稿 400 | 上游 URI 不在白名单、路径冲突、JSON 格式不对 | 检查对应表单字段与实际白名单 |
| 发布 409 | expectedVersion 已过期或不同管理员已发布 | 重新点“当前持久化版本”，检查草稿后由你决定是否重新发布 |
| Echo 400 | 不是合法 JSON **对象** | 例如填 `{"name":"flowgate"}`，不要直接用数组或字符串 |
| 私有 401/403 | Caller Token 缺失/过期/吊销，或 Scope 不足 | 重新创建合法 Caller 或检查 scope |
| 429 / 并发限制 503 | 策略已真实执行或依赖故障 | 查看响应code及policyCode区分速率耗尽、并发满、Redis不可用 |

## 6. 看哪里能证明操作真正生效？

- **Control 数据库发布成功**：`GET /admin/releases/current`（页面“当前持久化版本”）出现新版本和 `PERSISTED`。
- **Gateway 已实际加载**：页面“节点实际应用版本”中的 appliedVersion 与 checksum 对齐；节点信息不是请求已经成功转发的替代品。
- **业务返回正确**：实际请求 HTTP 200/201/400/401/403/404，Request ID 可以关联行为；别用页面点击成功代替 API 成功。
- **策略生效**：当前版本返回policyStates；ENFORCED_BLOCK2表示新版本的执行契约，仍须节点应用该版本。历史PENDING_BLOCK_2_3不执行。通过真实请求和指标确认结果。

遇到问题可以把**不含密码/Token 的** HTTP 状态、requestId、接口路径和错误说明交给 Codex 排查。强制检查不要把原始认证头、`.env` 或一次性 Token 放进日志/截图。

## 7. 限速和并发演示

1. 在“流量策略”点击“读取策略与目标”，选ROUTE和demo-slow。
2. 新建RATE_LIMIT，代码填唯一值，例如slow-route-rate；capacity=2、refillPerSecond=1，点击“保存并绑定草稿”。
3. 转到“服务/API与发布”，读取当前版本，点击发布并确认；查询节点，等待appliedVersion匹配。
4. 回到策略页，路径`/api/demo/slow?delayMs=0`，请求数6，执行有界请求；独立结果卡呈现真实429。不是所有请求都必定429，取决于容量、补充和发送时刻。
5. 选择已有策略，停用并保存；新建CONCURRENCY，ROUTE=demo-slow、maxConcurrent=1，发布并确认节点。
6. 路径改为`/api/demo/slow?delayMs=500`，请求数6。可观察200和503 CONCURRENCY_LIMITED；请求结束后再次发送应恢复准入。
7. 修改已有策略仅更新参数/启用状态，不改变作用域或重复绑定；原始高级绑定仍在页面保留。相同类型/范围/目标只允许一条启用策略。
8. RATE_LIMIT跨网关共用Redis预算；CONCURRENCY仅单机。先GLOBAL→SERVICE→API→ROUTE→CALLER扣减，再获取服务→路由并发；后层拒绝不退还前层预算。Redis不可用时受限请求503，未绑定限速的公开API不依赖限流Redis。

脚本验证（自动创建独立临时容器、随机端口，退出自动清理，不覆盖本机IDEA和已有数据）：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -B -ntp -pl flowgate-control,flowgate-gateway,flowgate-demo -am test package
node scripts/block2-console-test.js
python3 scripts/block2-smoke.py
python3 scripts/block2-hotspot.py
```

最后一条运行同环境15秒+15秒的小对照，结果写入`docs/progress/evidence/block2-hotspot.json`。Demo观测仅在显式`FLOWGATE_DEMO_OBSERVATION=true`时注册；脚本设置8个共享Tomcat线程，不改变日常启动默认值。普通API尾延迟的改善仅代表此次开发机短实验，不代表正式性能验收。


## 8. 服务/API和故障保护业务页面

“业务概览”刷新即可查看服务/API目录，不用读Java源码。“服务/API与发布”可读取关联服务/API的下拉选项，选择服务、API或路由模板，填入名称、方法与路径后直接保存业务草稿；高级JSON仅在需要时展开，保留原有更新/绑定能力。

“故障保护”提供响应超时、熔断与GET不可用提示表单、发布和节点状态，具体抽查步骤见[故障保护指南](block3-fault-testing.md)。只有演示故障接口时需要在Demo环境开启`FLOWGATE_DEMO_FAULT_ENABLED=true`，正常业务没有新增必填配置。控制台URL暂沿用`/test-console/`，页面定位已调整为简单业务前端。
