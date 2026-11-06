# FlowGate 本地测试台｜第一次使用指南

> 更新：2026-11-06。本指南**首先对应功能块 1 已有的五区测试台**，不假装 V2.0 易用性改造已经实现。功能块 2 完成时需同步更新步骤与截图/界面名称。
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

当前五个区域依次是：
- **A 服务状态**：点“探测服务”，查看 Control/MySQL/Redis/Demo/Gateway 是否真的在线。
- **B 基础 HTTP 测试**：在发布 API 后执行 fast/echo/slow；私有 API 则需要 Caller Token。
- **C 管理员与身份**：使用 `.env` 对应的管理员用户名及**你本人设定的原始密码**登录；密码不是 BCrypt 哈希本身。
- **D 目录草稿与发布**：初始化 API 草稿、查看/编辑四种目录、发布、查询节点、回滚。
- **E 策略与审计**：Block1 只能保存策略，**真实限流和熔断仍未执行**。

当前版所有操作的返回都显示在**页面底部“实际 HTTP 结果”**，你需要向下滚动查看；新操作会覆盖上一次结果。这是已确认的易用性缺陷，已列入下一阶段整改。

## 3. 第一次演示：公共 Demo（从空库 V0 开始）

1. 在区域 A 点击“探测服务”，必须先看到相关依赖 UP；若探测失败先查 `.local/run/` 日志。
2. 在区域 C 输入管理员用户名和原始密码，点击“登录”，应显示 ADMIN。页面刷新会丢失临时 Token，需要重新登录，这是当前设计。
3. 在区域 D 点击“初始化公共 Demo 草稿”。这是**只补齐缺失的** Demo 服务、三个公共 API 和路由，不会自动发布。
4. 点击“当前持久化版本”。**不要假设一定是 0**：如果返回 version=0，填写 expectedVersion=0；如果已有版本 Vn，应填 Vn。
5. 点击“发布完整配置”，返回应为 HTTP 200 且 `status=PERSISTED`、`version=n+1`，注意这**还不代表 Gateway 已应用**。
6. 等待/重复点“节点实际应用版本”，看到某个节点 `appliedVersion=n+1` 且 checksum 对得上，才能确认已应用。若没有，检查 Gateway/Control 状态和日志。
7. 在区域 B 点击 GET fast（id=123、tag=abc），预期 200；POST echo（合法 JSON 对象，例如 `{"name":"flowgate"}`）预期 201；GET slow（delayMs=100）预期 200，越界 2001 预期 400。
8. 每次查看底部结果中的 HTTP 状态、JSON 和 X-Request-Id。私有认证不影响已明确发布为公开的三个 Demo 接口。

如果原本已有草稿/版本，建议先查看 `services`、`apis`、`routes` 的实际列表，而不是反复创建重复对象。

## 4. 进阶演示：私有路由与版本回滚

1. 在区域 D 点“添加私有 Demo 草稿”；该按钮只添加 `GET /api/private/fast/{id}`，要求 Scope `demo:read`，仍需显式发布。
2. 先点“当前持久化版本”，把真实 `version` 写入 expectedVersion，点击发布；等待节点 appliedVersion 同步到新版本。
3. 区域 B 的“私有路径”使用 `/api/private/fast/123`，**不带 Caller Token** 点击“请求私有 API”，预期 HTTP 401。
4. 区域 C 中输入 Caller 名称、Scopes=`demo:read`，点击“创建 Caller”。一次性 Token 会暂存在当前页面的密码输入框，默认隐藏；页面会自动填写 Caller Token 和吊销 ID。现在私有请求预期 HTTP 200。
5. 如要测试 Scope 不足，创建一个只有 `other:read` 的不同名称 Caller，私有请求应返回 HTTP 403。不要把真实 Token 粘贴到聊天或仓库。
6. 区域 C 点击“吊销”，再次请求私有 API 应返回 HTTP 401。
7. 在 D 的“发布历史”选择**最初仅含公共 Demo 的版本**（不一定是 V1，取实际值），填写“回滚来源版本”和**当前持久化版本** expectedVersion，点击“回滚为更大新版本”。成功后新版本号必须大于旧版本；等待节点同步。
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
| 429 / 并发限制 503 | 只有功能块 2 真实治理上线后才会出现 | 在旧 Block1 不应把配置成功当成限流已执行 |

## 6. 看哪里能证明操作真正生效？

- **Control 数据库发布成功**：`GET /admin/releases/current`（页面“当前持久化版本”）出现新版本和 `PERSISTED`。
- **Gateway 已实际加载**：页面“节点实际应用版本”中的 appliedVersion 与 checksum 对齐；节点信息不是请求已经成功转发的替代品。
- **业务返回正确**：实际请求 HTTP 200/201/400/401/403/404，Request ID 可以关联行为；别用页面点击成功代替 API 成功。
- **策略生效**：在功能块 1 阶段尚不存在；功能块 2 将新增有界负载请求和真实 429/503 的可视演示。

遇到问题可以把**不含密码/Token 的** HTTP 状态、requestId、接口路径和错误说明交给 Codex 排查。强制检查不要把原始认证头、`.env` 或一次性 Token 放进日志/截图。

## 7. 后续新版界面会怎样变化

功能块 2 计划把上述流程做成可视化的“环境检查→登录→初始化→发布→验证”引导，同时保留进阶目录、原始 JSON 调试和旧功能。新增策略向导、独立请求结果卡与最近 10 条无敏感历史。**这些是下一阶段设计，不代表当前页面已具备。**

改造合同见 `docs/standards/test-console-ux-v2.md`；最小功能实现方案见 `docs/progress/block-2-traffic.md`。