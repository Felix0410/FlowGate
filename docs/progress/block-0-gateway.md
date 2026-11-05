# 功能块 0｜基础网关与请求代理（交给本地 Codex）

> **状态**：已实现、本地定向验证通过，待 ChatGPT 和项目负责人审核（2026-11-05）
> **唯一执行依据**：本文件是功能块 0 的实施与进度记录；README 负责索引，`docs/stage-0-audit.md` 负责项目背景与源码审计。旧文档第十二节相似方案仅作参考，出现冲突**以本文件为准**。  
> **分支**：`feat/block0-gateway-foundation`；不得直接修改或合并 main。  
> **约束**：本轮做完一个完整业务块就停，不自动进入功能块 1；只测新增、修改和直接受影响内容。

## 1. 业务目标、明确交付

实现客户端经 `FlowGate Gateway` 访问独立 `Demo` 服务，**真正使用 Spring Cloud Gateway WebFlux/Netty 转发**，而非自写 HTTP 代理。需要最小静态路由、请求方法/Body/Header/Query/响应码透传、统一追踪 ID 和基础网关指标。无原点评业务。

交付四项：① 可构建、可独立启动的双服务 Maven 工程；② 快速查询/POST Echo/边界内慢请求；③ Gateway 代理和 Trace/Metric；④ 相关定向测试、提交 SHA 和进度状态。

## 2. 固定技术、依赖和工程文件

- Java 17；`spring-boot-starter-parent:4.0.9` 作为 Maven parent；在父 `pom.xml` 的 `dependencyManagement` 导入 `org.springframework.cloud:spring-cloud-dependencies:2025.1.4`；Gateway 5.0.4 由 BOM 管理，不手动重写网关版本。
- 父工程 `<packaging>pom</packaging>`，`<modules>` 只有 `flowgate-gateway`、`flowgate-demo`；坐标 `com.flowgate:flowgate`，子模块继承父工程。
- Gateway 依赖：`org.springframework.cloud:spring-cloud-starter-gateway-server-webflux`、`org.springframework.boot:spring-boot-starter-actuator`、测试 Starter。**禁止**依赖 Web MVC、JDBC、同步 Redis、Kafka 等。
- Demo 依赖：Boot 4 正式模块名 `org.springframework.boot:spring-boot-starter-webmvc`（不要使用弃用的 `spring-boot-starter-web`）、测试 Starter。可使用 Jackson（MVC 自带的 JSON 转换机制），不额外引入框架。
- 只使用 JDK UUID、Spring WebFlux `WebFilter`、Spring Cloud Gateway 内置路由/指标。**不添加** Control、Contract 空模块、MySQL、Redis、Lua、Redisson、Kafka、Nacos、Docker 或前端。
- `src/main/java` 使用包名前缀 `com.flowgate.gateway` 与 `com.flowgate.demo`。建立相应 `FlowGateGatewayApplication`、`FlowGateDemoApplication` 启动类；Demo 的 `DemoController`；Gateway 的 `RequestIdWebFilter`。其他类只在真正需要时添加。

最低文件布局：

```text
pom.xml
.gitignore
flowgate-gateway/
  pom.xml
  src/main/java/com/flowgate/gateway/FlowGateGatewayApplication.java
  src/main/java/com/flowgate/gateway/filter/RequestIdWebFilter.java
  src/main/resources/application.yml
  src/test/java/com/flowgate/gateway/...
flowgate-demo/
  pom.xml
  src/main/java/com/flowgate/demo/FlowGateDemoApplication.java
  src/main/java/com/flowgate/demo/controller/DemoController.java
  src/main/resources/application.yml
  src/test/java/com/flowgate/demo/...
```

不要拆出很多 Controller/Service/Port/Adapter 或配置类；它们在此时没有业务价值。

## 3. 固定 HTTP 契约（不要由 Codex 重新设计）

| 外部 Gateway 路径 | 转发给 Demo 路径 | 方法 | 成功行为 |
|---|---|---|---|
| `/api/demo/fast/123?tag=abc` | `/demo/fast/123?tag=abc` | GET | 200 JSON：`{"id":"123","tag":"abc","requestId":"<实际 X-Request-Id>"}` |
| `/api/demo/echo` | `/demo/echo` | POST + JSON | 201 JSON：`{"body":<传入 JSON 对象>,"demoHeader":"<X-Demo-Header>","requestId":"<X-Request-Id>"}` |
| `/api/demo/slow?delayMs=100` | `/demo/slow?delayMs=100` | GET | 200 JSON：`{"delayMs":100,"requestId":"<X-Request-Id>"}` |

- 取 `X-Demo-Header` 的值直接回显用于验证；若未传，自行返回空字符串即可。POST Body **必须为 JSON 对象**；非法 JSON 应得到框架默认的 400，不需要封装复杂的 Result。
- `delayMs` 默认 100，范围 `0..2000`（含边界）。超过范围和非数值应为 400；仅 Demo MVC 服务可做有界 `Thread.sleep`，Gateway 不能阻塞事件循环。
- 只为 `/api/demo/**` 配置代理，其他如 `/admin/**` 不可路由；`/unknown` 正常 404。Gateway 暂不实现身份系统（功能块 1 开始）。
- Demo Controller 只用于演示 HTTP 行为，不访问数据库、不复活商户/券/订单。没有必要为 Response 创建多个包及抽象。
- 端口：Gateway `8080`，Demo `8085`；端口可用环境变量覆盖。默认**仅本地开发**运行，网络监听与 Actuator 暴露范围必须不允许在公网部署时未经保护地可访问。

## 4. 固定路由和 Trace 实现

Gateway 的 `application.yml` 使用 Gateway 5 的正确属性前缀，不使用旧版 `spring.cloud.gateway.routes`。以如下配置为基础：

```yaml
server:
  port: ${GATEWAY_PORT:8080}
spring:
  application:
    name: flowgate-gateway
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: demo-route
              uri: ${DEMO_UPSTREAM_URI:http://127.0.0.1:8085}
              predicates:
                - Path=/api/demo/**
              filters:
                - StripPrefix=1
management:
  endpoints:
    web:
      exposure:
        include: health,metrics
```

- 这个组合把 `/api/demo/fast/123` 变成 `/demo/fast/123`，保持 Query、Body 和 HTTP Method。
- `DEMO_UPSTREAM_URI` 是**部署方环境变量**，不能从来访请求的参数/Header 得到；允许的开发地址是受控 Demo，不得提供任意 URL 代理。此阶段只允许在隔离的本地开发环境使用。
- `RequestIdWebFilter` 使用 `UUID.randomUUID().toString()` 生成 ID；**始终覆盖**来访请求中伪造的 `X-Request-Id`，写入转发后的请求 Header 和 Gateway 响应 Header；用 `ServerWebExchange` 的非阻塞请求变更 API，不能依赖 Servlet/ThreadLocal。
- 过滤器应让未匹配路由的 404 尽可能也带 ID；不为了它重造异常处理链。尽量小而清晰；防止因响应提交时机导致 Header 写入失败。
- 依赖 Actuator 复用框架 `spring.cloud.gateway.requests` 计时器。若需要显式启用，应优先核对 `spring.cloud.gateway.metrics.enabled`（官方 5.0 文档），**不要直接把旧文档里的 `spring.cloud.gateway.server.webflux.metrics.enabled` 当成已验证属性**。
- 指标 Path 标签默认关闭，避免动态路径导致高基数；管理端点只暴露 `health,metrics`，在本地开发环境访问，不能打开 Gateway Actuator 动态路由写入 API。
- 不采集或打印完整请求 Body、Authorization、Cookie、Token。Gateway 代理失败时不可返回伪造 200；暂时采用框架默认 5xx，具体错误码以实际测得结果记录。

## 5. 定向测试，仅针对本块

**不做**全库回归、Docker 压测、Kafka/MySQL/Redis 联调和复杂第三方测试框架。

1. Maven：在仓库根运行 `mvn -pl flowgate-demo,flowgate-gateway -am test`。若本地 Maven/网络环境阻塞，记录具体错误，不声称成功。
2. 应用：分别运行 `mvn -pl flowgate-demo spring-boot:run` 和 `mvn -pl flowgate-gateway spring-boot:run`，确认 8085/8080 监听并能访问（请根据实际构建插件配置调整运行命令）。
3. GET：`curl -i 'http://127.0.0.1:8080/api/demo/fast/123?tag=abc'`；比较直连 8085 的 JSON 业务字段。
4. POST：`curl -i -X POST 'http://127.0.0.1:8080/api/demo/echo' -H 'Content-Type: application/json' -H 'X-Demo-Header: hello' -d '{"name":"flowgate"}'`；检查 201、JSON Body、Header/ID。
5. 慢请求：`/api/demo/slow?delayMs=100` 200；`delayMs=-1`、`2001`、`abc` 应 400。
6. Trace：两次请求 ID 不同；带客户端伪造 ID 后响应与 Demo 收到的都应是新 ID；未匹配 404 应尽力带 ID。
7. 错误：`/unknown` 是 404；停止 Demo 后代理请求返回明确非 200 的网关错误。
8. 指标：`GET /actuator/health` 正常；`GET /actuator/metrics` 可访问；发起网关代理请求后观察是否出现 `spring.cloud.gateway.requests`。若缺失，核对 5.0.4 的官方配置与 Starter，再做最小修复，不增加额外指标系统。
9. 注释与依赖：代码审查检查重要类中文注释，长逻辑 1、2、3 分点；`mvn dependency:tree` 或 POM 检查 Gateway 没有 MVC/JDBC/同步 Redis 依赖。

测试只需当前功能涉及的单测+上述小规模冒烟；实际运行记录不能改写成“预期通过”。

## 6. 本地 Codex 执行边界

- 开始前 `git status`，确认工作目录有无未提交修改，不能覆盖用户工作。读取 README 与本任务书，不用自己重新规划项目或擅自增加新技术。
- 从最新 main 建议创建 `feat/block0-gateway-foundation`。按本文件完整实现一个功能块；遇到 Boot 4 API 名称不兼容可在此块内查官方文档做**最小**适配，说明实际变更。
- 需要用户动作的远端推送或 PR 使用既有授权；不推送/合并 main；不做功能块 1 的控制面/认证/动态路由。
- 代码必须提供充分、准确的中文注释；**长业务方法和逻辑块按 `// 1. ...`、`// 2. ...`、`// 3. ...` 编号**，不为注释制造复杂抽象。
- 每块只测新增/修改/直接受影响逻辑。不要因为本块完成就做完整项目大测试。

## 7. 进度记录（Codex 完工后原地更新本节）

| 项目 | 当前状态 |
|---|---|
| 业务功能 | 双应用、三类 API、静态代理、请求标识、基础指标已完成 |
| Git 分支/PR | 本地 `feat/block0-gateway-foundation`；推送被 GitHub HTTP 403 拒绝，远端分支/PR 尚未建立，未合并 main |
| 实际提交 SHA | 实现提交 `6c43db2b8276fea54bbedd446f57576cc2b1ea8d`；本进度记录另行提交 |
| Maven 构建/启动 | Java 17.0.18 / Maven 3.9.13；依赖解析与打包成功；8080 Netty / 8085 MVC 独立 JVM 启动通过 |
| 定向测试 | 3 个 JUnit 测试通过，0 失败/错误/跳过；真实双进程 HTTP 冒烟全部通过 |
| 中文注释/依赖审查 | 关键类/方法及路由配置有中文说明；Filter/慢请求按 1/2/3 分点；Gateway 无 MVC/JDBC/Redis/Kafka |
| 未解决问题 | 无本块阻塞；上游断联使用框架默认 500；生产保护及性能验证不在本块范围 |
| 最终人工/ChatGPT 审核 | 待审核，未宣称已验收；未进入功能块 1 |

完成后填写：实际新增/修改文件、最终依赖版本、Git 分支/Commit/PR、**测试命令与真实结果**、未完成/阻塞事项、中文注释检查、和本块验收清单。**只在真实验证后**将状态改为“待审核”；由项目负责人/ChatGPT 审核后改为“已验收”。

### 2026-11-05 实际交付记录

**主要文件**：根 `pom.xml` 和 `.gitignore`；两个模块的 `pom.xml`、启动类和 `application.yml`；`RequestIdWebFilter`、`DemoController`；`RequestIdWebFilterTest`、`DemoHttpTest`；`scripts/block0-smoke.py`；README 启动说明和本节。原 `.idea/` 未覆盖、未提交；未创建 Control/Contract，未修改后续功能块文件或历史审计内容。

**依赖与复用**：父 POM Boot 4.0.9、Cloud BOM 2025.1.4 均实际解析成功，Gateway 5.0.4 由 BOM 提供；Micrometer 1.16.8 随 Actuator 引入。Gateway 仅直接引入 WebFlux Gateway、Actuator 和测试 Starter；Demo 仅直接引入 WebMVC 和测试 Starter。HTTP 客户端测试使用 JDK，冒烟使用 Python 标准库，无额外测试平台。未复制 Plus 源码：当前业务不需要其 Servlet/数据库/Redis 业务封装，只沿用 MVC 和 Actuator 使用经验，代理直接复用官方 Gateway。

**最小配置修正**：实际检查 `spring-cloud-gateway-server-webflux-5.0.4.jar` 中的 `META-INF/spring-configuration-metadata.json`，确认指标开关是 `spring.cloud.gateway.server.webflux.metrics.enabled`（默认 false），因此显式设为 true；同时设置 `spring.cloud.gateway.server.webflux.metrics.path-tags.enabled=false`。没有采用本任务文件第 4 节中待核对的旧属性猜测。真实计时器输出确认该配置生效。

**实际执行命令**（均在根目录；先 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`）：

```bash
git fetch origin
git switch -c feat/block0-gateway-foundation origin/main
mvn -U -pl flowgate-gateway,flowgate-demo -am dependency:resolve
mvn -B -ntp -pl flowgate-demo,flowgate-gateway -am test package
python3 scripts/block0-smoke.py
mvn -B -ntp -pl flowgate-gateway dependency:tree
git diff --check
```

依赖解析、构建打包、3 个测试、冒烟和差异格式检查均通过。冒烟脚本实际启动命令为 `java -jar flowgate-demo/target/flowgate-demo-0.0.1-SNAPSHOT.jar` 和 `java -jar flowgate-gateway/target/flowgate-gateway-0.0.1-SNAPSHOT.jar`，结束后清理了自己的两个 JVM。README 的 `spring-boot:run` 替代启动方式未另行执行，不列为实测通过项。

| 验收项 | 实际结果 |
|---|---|
| 两进程/本地监听 | `127.0.0.1:8080` Gateway Netty、`127.0.0.1:8085` Demo MVC 独立运行，无数据库/Redis/Kafka |
| GET/直连对照 | `/api/demo/fast/123?tag=abc` 200；JSON `id=123,tag=abc` 与直连一致 |
| POST | JSON 含中文/嵌套/数组/null；201，Content-Type 为 application/json；`body` 原样，`demoHeader=hello` |
| 非法 JSON | 数组、null、标量、截断 JSON 为 400（单测和/或冒烟覆盖） |
| 慢请求 | 默认 100、0/100/2000 成功；-1/2001/abc 返回 400，超长整数转换也为 400 |
| ID | GET ID `6dc49350-7582-4542-92d3-1511285b6f88`；POST ID `128cdf16-3be7-476c-9f6b-5a92f90e5cc8`，两者不同；响应头与下游字段一致；客户端伪造被覆盖 |
| 路由与 404 | `/unknown`、`/admin/routes`、直接 `/demo/fast/123` 为 404 且带 ID |
| 下游不可用 | 脚本停止自身 Demo 后，同一已匹配代理请求返回 500，响应带 ID，无伪造成功 |
| Actuator | health 为 UP；metrics 可读；env/beans/heapdump/gateway routes 返回 404 |
| 网关计时器 | `spring.cloud.gateway.requests`，COUNT=12，TOTAL_TIME=2.372198582s，MAX=2.009110791s；包含 200/201/400 状态及 demo-route；无 path 标签 |
| 依赖隔离/注释 | dependency:tree 确认 Gateway 无 MVC、JDBC、Redis、Kafka；核心中文注释与长流程编号人工检查通过 |

以上计时器只是少量功能请求的观测，**不是性能基线或压测结论**。原始本机输出：`/tmp/flowgate-build.log`、`/tmp/flowgate-smoke.log`、`/tmp/flowgate-dependencies.log`；应用日志位于脚本打印的临时目录，未提交日志或敏感请求信息。

**风险/未运行范围**：无全项目最终统一回归、大规模压测、跨阶段故障或多节点测试；未实现认证、动态发布、限流、熔断、生产管理端点保护。默认绑定回环地址，部署方环境变量仍必须只指向可信 Demo。本机 Maven 既有 HTTP Aliyun 镜像在解析插件元数据时出现无 checksum 警告，构建另外有 compilerVersion 弃用警告，均未阻断；没有修改全局 Maven 设置。当前本地功能验证通过不代表远端 CI 或人工验收通过。

**远端交付阻塞**：实际执行 `git push -u origin feat/block0-gateway-foundation`，GitHub 返回 `Permission to Felix0410/FlowGate.git denied to Felix0410` / HTTP 403。本地实现提交与记录提交均保留，远端分支和 PR 尚不存在。待仓库写入权限/当前 Git 凭据恢复后重试同一命令；没有改动 main，也没有改动用户凭据。此问题只阻塞 GitHub 审查入口，不阻塞本地功能验证。

## 8. 完成判定

Gateway 与 Demo 可独立构建并启动；受控路由能真实转发三类 Demo API；GET/POST/Query/Body/Header/状态码、Trace ID、404/上游错误与基础指标均有证据；没有业务范围外技术；注释规范符合要求；进度记录真实。**达标后停止，等待下一个功能块任务。**

**关键官方参考**：[Boot 4 Starter 清单](https://docs.spring.io/spring-boot/4.0/reference/using/build-systems.html)、[Spring Cloud 2025.1.4](https://spring.io/blog/2026/10/23/spring-cloud-2025-1-4-has-been-released/)、[Gateway 5 WebFlux Starter](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/starter.html)、[Gateway 5 Global Filters / Metrics](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/global-filters.html)。
