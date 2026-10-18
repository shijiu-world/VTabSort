# VTabSort agent 备忘（v2.0.0 起）

- 定位：Velocity 端 Tab 展示双模式插件。layout = Hypixel 式固定网格（列头子服名 + 假条目补满 + 动态合并分列 + 自适应行数）；flat = 前缀列表 + 可选排序。底层仍是 1.21.2+ `listOrder` 协议字段，不碰 scoreboard team。
- 🔴 依赖必须 velocity-api **4.x**（`setListOrder` 3.4.0 才有；VWhisper/Vmessage 用的 3.2.0 没有）。编译 release **25**。假条目用 `TabListEntry.builder().tabList(...).profile(...).displayName(...).latency(...).gameMode(0).listed(true).listOrder(...)` + `TabList#addEntry/removeEntry/containsEntry/getEntry`。
- 🔴 **客户端折行算法（1.19.3+ 实测 2026-10-18）**：列数 = ceil(总条目 ÷ 行数上限 20)，行数 = ceil(总条目 ÷ 列数)，是**平衡列**不是固定 20 填充（27 人 → 2 列×14 行）。推论：网格总条目必须精确 = 列数×行数；行数自适应取 max(内容高度, ceil((rows×(C-1)+1)/C))，上限 rows。4 列最少 16 行。
- 分列规则（服主定）：每服一列起步；子服数 > max-columns 时人数最多 (max-1) 个独占、其余全进共享列（分节人数降序、分节间空行、列头=分节头带 #name#·#count#）；所有服并进一列仍超高 → 允许溢出不截断。
- 假条目：UUID = nameUUIDFromBytes("vtabsort-slot-N")，name = "vts_sNNN"；显示名空白槽用 `Component.empty()`；残留清理扫描半径 FAKE_RANGE=512（网格缩短后）。`setListed(false)` 藏截断溢出玩家和连接中（还没进服）的玩家——后者不藏会把折行挤歪。
- 三层兜底：事件直刷 → refresh-delay-ms(200) 延迟补发（TAB 建条目竞态）→ reassert-seconds(3) 周期校准（Paper 重置 listOrder=0、TAB 覆写显示名）。全 diff 应用，无变化零发包。
- 模式切换（reload 检测）：flat→layout 先 `resetDisplayNames()`（清前缀）；layout→flat 先 `stripAll()`（拔假条目）。flat 模式显示名归本插件管，layout 模式不碰显示名（归 TAB）。
- 颜色：自带 30 行 Colors 解析器（& 代码 + &#hex）。🔴 不能复用 Vmessage 的 ChatColors——插件 ClassLoader 隔离（铁律 2）。
- 命令 `/vtabsort`（别名 `/vts`）reload/layout/version；`layout` 子命令打印当前分列模型（无客户端核对布局用）。权限 `vtabsort.reload`。主名小写注册。
- ⚠️ 别用 PlugManX 热卸载（假条目会永久残留玩家 Tab），重启代理换 jar。
- 构建：Git Bash `JAVA_HOME=D:/Code/Java/zulu25.34.17-ca-jdk25.0.3-win_x64 mvn -B -o package`（velocity-api 4.3.0 已进本地 m2）。发版改 pom 版本一处（BuildConstants templating）。Java 输出 GBK：`> log && iconv -f GBK -t UTF-8` 看错误。
- 同步测试服：代理插件 → `D:\game\test_velocity\velocity\plugins\`，旧 jar 进 `plugins/_old/`，产物优先用 Release 那份。
- 待实测确认：① `setListed(false)` 的条目是否不计入客户端折行的总条目数（若计入，隐藏玩家会多开一列——届时改 removeEntry 方案）；② 测试服实测 4×20/自适应行数渲染是否精确。
