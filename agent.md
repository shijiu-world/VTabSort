# VTabSort agent 备忘

- 定位：Velocity 端 Tab 按子服排序，走 1.21.2+ `listOrder` 协议字段（`UPDATE_LIST_ORDER`），不碰 scoreboard team。解决「关掉 TAB scoreboard-teams（让小游戏藏名字）之后失去排序」的空缺。
- 🔴 依赖必须 velocity-api **4.x**（`setListOrder` 3.4.0 才有；VWhisper/Vmessage 用的 3.2.0 没有）。编译 release **25**（velocity 4.x 代理本体 class 69）。CI 的 setup-java 也是 25。
- 顺序值 = 优先级×1e6 + 组内序号；优先级封顶 2000、序号封顶 999_999 防溢出。listOrder 必须非负（unknownFirst 时已列服整体 +1 错开，别用 -1）。
- 事件全量重算 + `refresh-delay-ms`（默认 200ms）延迟补发：tab 条目由 TAB global-playerlist 创建，事件瞬间可能还没进各 viewer 的 TabList（竞态兜底）。
- `setListOrder` 包 try-catch 单条吞异常：老客户端由 Velocity 默认实现兜底，别让一条失败带崩整轮刷新。
- 与 TAB 共存：TAB 管显示名（UPDATE_DISPLAY_NAME）、本插件管排序（UPDATE_LIST_ORDER），两个独立 action。已知风险：后端 Paper 会在自己的事件里重写条目 listOrder（默认 0），延迟补发能盖住绝大多数；若仍见偶发乱序，考虑加周期性重断言。
- 命令 `/vtabsort`（别名 `/vts`）reload/version；reload 权限 `vtabsort.reload`。主名小写注册（Brigadier 大小写敏感）。
- 构建：Git Bash `JAVA_HOME=D:/Code/Java/zulu25.34.17-ca-jdk25.0.3-win_x64 mvn -B package`（首次需联网拉 velocity-api 4.3.0，之后可 `-o`）。发版改 pom 版本一处即可（BuildConstants templating）。
- 同步测试服：代理插件 → `D:\game\test_velocity\velocity\plugins\`。
