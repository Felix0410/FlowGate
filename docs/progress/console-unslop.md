# FlowGate 业务前端规范化重构

日期：2026-11-09。独立分支：`feat/v11-console-unslop`。基线：C技术复审通过的`f904e33`；不进入V11-D，不改旧PR base或合并main。

## 依据与设计方向

参考 [heydamir/unslop](https://github.com/heydamir/unslop) 的设计审查、signs与tokens：作者 **Damir Sokolovsky（@heydamir）**，**CC BY 4.0**。参考的是设计方法，不安装该仓库的运行程序或依赖，也不引入React/Vue/npm、远程字体、图标库或图片服务。存在其他同名项目，本轮选择直接面向前端界面重构的此仓库。

五项方向：

- 产品：供业务管理员管理接口接入、流量治理、凭证、配额和用量。
- 性格：克制、准确、易查。
- 内容环境：配置目录和网关运行台；保留既有FlowGate蓝色和本机系统字体。
- 视觉重点：主操作和真实状态；不增加虚构图表、指标、成功率或业务数据。
- 一致细节：草稿目录、发布版本、节点应用的流程线，解释交付状态。

原界面已有合理功能结构和权限合同，主要问题不是缺少装饰，而是侧栏长且整体滚动、组件尺度分散、提示框与数据卡片层级接近、登录页文案偏宣传、缺少每页说明入口。无紫色渐变、emoji图标或动画，因此不声称删除这些原本不存在的内容。

## 交付范围与界面规范

| 层次 | 实施 |
| --- | --- |
| 间距 | 共用4px节奏、24/32px页面边距、24px卡片内边距；控件与同类列表统一尺度 |
| 色彩 | 中性白灰外壳、三个文本角色；蓝色用于主操作与当前导航，绿/黄/红只辅助实际状态文字 |
| 图标 | 保留平面F标识；菜单使用统一线条，无新增装饰图标或第三方资源 |
| 文案 | 登录页说明实际配置链路；保留权限、延迟、未知值和失败边界；每页标题旁直达对应中文手册章节 |
| 排版 | 系统字体、固定字号层级；数值采用等宽数字并右对齐；路径与代码不被拆成难读的碎片 |
| 布局 | 248px侧栏；导航独立滚动并可按名称/分组查找；保留20个功能及原顺序；查询条件与结果分区 |
| 细节 | 共用按钮/输入框/模态半径；表格可键盘聚焦和区域内横向滚动；筛选显示匹配数量 |
| 数据呈现 | 工作台与发布中心分别显示持久化、节点确认；版本0/节点未知/过期或不同步均不标全部成功 |
| 动效 | 仅保留明确的控件颜色反馈，支持prefers-reduced-motion；无循环装饰动画 |

手机与平板导航打开时工作区inert、焦点进入查找框，Tab/Shift+Tab留在导航内；Escape和遮罩关闭后返回菜单按钮；选页后聚焦主要内容。退出清除导航筛选。原生dialog保持焦点约束、Escape关闭和触发焦点恢复。

保持所有原API、角色控制、409不自动重试、只存内存的会话、一次性Token离页清理、未知不冒充0、SENT补投和FAILED重试的区别。没有后端Java/SQL/依赖/容器配置修改；本轮没有变更用户.env、IDE或运行进程。

## 实际验证

| 验证 | 命令/结果 |
| --- | --- |
| Java17定向 | `mvn -B -ntp -pl flowgate-control -am test package`，退出0；Contract10+Control30=40，失败/错误/跳过均0 |
| Node产品 | `node scripts/product-console-test.mjs`，退出0；扩展导航筛选无请求/退出复位、六种交付状态、数值列、表格键盘区域、筛选计数及单页h1 |
| Node兼容 | `node scripts/v11-a-console-test.mjs`、`v11-b-console-test.mjs`、`v11-c-console-test.mjs`、`v11-c-review-console-test.mjs`、`block2-console-test.js`、`block3-console-test.js`，全部退出0；共7个入口 |
| 中文指南 | `python3 scripts/build-console-guide.py --product --check`，退出0；新增quota/async-usage锚点，既有说明保留 |
| 真实产品HTTP | Java17下显式`FLOWGATE_ASYNC_ENABLED=false FLOWGATE_QUOTA_ACTIVATION_ENABLED=false python3 scripts/product-console-smoke.py`，退出0；自有随机端口MySQL/Redis、Demo/Control/双Gateway；静态资源、真实JS创建编辑/绑定/RBAC/发布/调用方吊销/409/回滚通过 |
| 浏览器 | ego-browser独立Chromium空间，1280px/375px各逐页打开20功能，页面无横向溢出，单页一个h1；375px抽屉焦点/回环/Escape/弹窗/表格滚动、VIEWER禁用写入及指南锚点通过 |
| 打包一致性 | 最后HTML源码排版后额外`mvn -B -ntp -pl flowgate-control -am package -DskipTests`仅打包；逐字比对Jar中12项console资源与源码，全部一致；此命令不计新增测试 |
| 交付检查 | `git diff --check`通过；新核心流程有准确中文注释和编号说明；只暂存本次前端/指南/测试/证据文件 |

**证据边界**：浏览器使用独立临时UI夹具，数据不是用户数据库事实，不冒充真实后端业务验收；真实后端HTTP验证另用原有隔离Lab。浏览器检查在界面迭代中进行，最后的HTML排版和技术文本呈现调整另由Node及Jar资源比对验证。当前用户8081未运行，首次浏览器直连拒绝；未接管或重启用户IDE实例。真人业务浏览器验收、全项目Java回归、C Kafka故障链路和D压测均未执行。

失败与修正：首次受限Maven不能为Mockito附加测试代理，Control出现20个测试环境错误；使用允许的原生执行权限后40项全部通过。新增数字列断言起初读取模拟DOM的attrs.class，而实际工厂设置className，修正断言后通过。临时服务首次绑定端口受到沙箱限制，允许本机测试端口后完成浏览器检查。均未将失败尝试算作通过。

脱敏汇总：`docs/progress/evidence/console-unslop.json`。HTTP原始本机记录：`.local/acceptance/product-console-20261109T054408527106.json`。日志：`/tmp/flowgate-console-unslop-maven.log`、`/tmp/flowgate-console-unslop-http.log`；本机截图`/tmp/flowgate-console-login-after.png`、`/tmp/flowgate-console-dashboard-after.png`、`/tmp/flowgate-console-mobile-after.png`（夹具，非业务成功证明）。旧A/B/C证据未覆盖，临时浏览器空间、服务和隔离容器均已结束。

## 本地查看

IDEA重新启动自己的Control运行配置，让其读取更新后的静态资源，再访问`http://localhost:8081/console/`并强制刷新。无需npm、新密码或数据库迁移。若使用Jar，运行本次已打包的Control Jar，避免与IDEA重复占用8081。负责人仍需用自己的真实业务数据抽查和验收。
