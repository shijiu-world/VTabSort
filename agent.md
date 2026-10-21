# VTabSort agent 备忘（v3 起）

- 定位：Velocity 端 Tab 展示三模式插件。layout = Hypixel 式固定网格（列头子服名 + 假条目补满 + 人数降序分列 + 续列 + 自适应行数）；flat = 前缀列表 + 可选排序；auto = 在线 ≤ rows 走 flat、超过走 layout。底层仍是 1.21.2+ `listOrder` 协议字段，不碰 scoreboard team。
- 🔴 依赖必须 velocity-api **4.x**（`setListOrder` 3.4.0 才有；VWhisper/Vmessage 用的 3.2.0 没有）。编译 release **25**。假条目用 `TabListEntry.builder().tabList(...).profile(...).displayName(...).latency(...).gameMode(0).listed(true).listOrder(...)` + `TabList#addEntry/removeEntry/containsEntry/getEntry`。
- 🔴 **listOrder 是降序**（官方 1.21.2 changelog："a non-negative ordering index that is sorted **highest to lowest**"——值越大越靠前，demo 实测 2026-10-21 镜像翻车定案）。建模槽位下标（0 = 期望显示在最前）≠ 协议顺序值：发包一律取 `总槽数-1-下标` 镜像（LayoutManager.build 第 5 步 / applyViewer / addFake）；flat 的 Sorter.computeOrders 同理——人多的服基数最大、组内 idx 越小值越大（`RANK_CAP-idx`）。没进 orders 表的条目默认 0 = 沉底。
- 🔴 **客户端折行算法（1.19.3+ 实测 2026-10-18）**：列数 = ceil(总条目 ÷ 行数上限 20)，行数 = ceil(总条目 ÷ 列数)，是**平衡列**不是固定 20 填充（27 人 → 2 列×14 行）。推论：网格总条目必须精确 = 列数×行数；行数自适应取 max(内容高度, ceil((rows×(C-1)+1)/C))，上限 rows。4 列最少 16 行。
- 🔴 **v3 分列规则**（删了 [servers].order/unknown、[layout].truncate/overflow-format）：①列的左右顺序 = 子服人数降序（平手服名升序）；②一个服 > rows-1 人就续开列，按列均分、余数给靠前的列，续列列头文字完全一样（#count# 恒为总人数）；③列预算不够时尾部开共享列（可占多列，packShared **流式**装箱：同列分节间固定空一格；空完剩 <2 行＝列头会落行末 → 换新列、新列顶不空格；一个服放不完续到下一列并重复列头）；④**保行数、让列数溢出** —— 行数是客户端硬约束，max-columns 只是期望值；⑤**0 人子服整个不出现**（[servers.names] 只管显示名）。
- 分配算法 = 「试 k 退 k」：k = 独占列的服个数，从 secs.size() 往 0 退，第一个 cols.size() ≤ max 的方案胜出（tryAllocate）。🔴 分节空行**不再自动牺牲**（服主要求共享列列头必须和上个服隔一格），只有 separator=false 才不空。
- auto 落地在 `Configuration.effectiveMode(onlineCount)`，阈值 = `[layout].rows`（客户端拆第二列的点）。🔴 翻面清场放在 `VTabSort.rebuildNow` 里比对 `appliedMode`（AtomicReference），**不能**只在 reload 里比配置——auto 下配置没变也会翻面。
- `docs/index.html` 是纯前端模拟器（单文件、零依赖，GitHub Pages 站点入口 → https://shijiu-world.github.io/VTabSort/，`.github/workflows/pages.yml` 推 main 自动部署），复刻同一套算法；改 LayoutManager 必须同步改它，并用 `/tmp` 下的 Harness（动态代理伪造 Player/ProxyServer 跑真 build）+ node 抽 JS 对跑同场景核对输出一致。
- 假条目：UUID = nameUUIDFromBytes("vtabsort-slot-N")，name = "vts_sNNN"；显示名空白槽用 `Component.empty()`；残留清理扫描半径 FAKE_RANGE=512（网格缩短后）。`setListed(false)` 现在只藏连接中（还没进服）的玩家——不藏会把折行挤歪；v3 没有截断了所以没有「被藏的溢出玩家」。
- 三层兜底：事件直刷 → refresh-delay-ms(200) 延迟补发（TAB 建条目竞态）→ reassert-seconds(3) 周期校准（Paper 重置 listOrder=0、TAB 覆写显示名）。全 diff 应用，无变化零发包。
- 模式切换清场：flat→layout 先 `resetDisplayNames()`（清前缀）；layout→flat 先 `stripAll()`（拔假条目）。flat 模式显示名归本插件管，layout 模式不碰显示名（归 TAB）。
- 颜色：自带 Colors 解析器（`&` 码 + `&#hex` + **渐变 `<#RRGGBB>文字</#RRGGBB>`**（v3.2，起止色逐字线性插值，段内仍认 `&l/&m` 等样式码；段内颜色码无效会被插值覆盖））。🔴 **`&#RRGGBB` 只换颜色不清样式**（原版 `§x` 同理）——否则 TAB 那套 `&l&#ffadbb欢&#f3b0c2迎…` 的加粗会被 hex 吃掉；只有命名颜色码 `&0-&f` 才清样式。不认识的 `%xxx%`、没闭合的 `<#` 原样输出，配错不炸。🔴 不能复用 Vmessage 的 ChatColors——插件 ClassLoader 隔离（铁律 2）。
- **header/footer（v3.2 起，接手 TAB 卸载后的活）**：`[header-footer]` = `enabled` + `header`/`footer` 两个数组（TomlLite 已支持单行/跨行数组）。`HeaderFooter` 每轮（事件直刷 + 200ms 补发 + reassert 3s）按 viewer 渲染：占位符替换 → `Colors.parse`（多行用 `Component.newline()` 拼）→ diff（渲染字符串没变不发包）→ `sendPlayerListHeaderAndFooter`。🔴 关掉时**只清一次**（发空组件），否则会跟别的 header 插件互相擦。占位符 `%online%`（含 demo 虚拟玩家）`%server%`（显示名）`%server-raw%` `%server-online%` `%player%` `%ping%` `%time%` `%date%`；认不出的原样留着（配错能看见）。
- 命令 `/vtabsort`（别名 `/vts`）reload/layout/version；`layout` 子命令打印当前分列模型（含 auto 的落地判定、续列第几段、超列预算/去空行提示）。权限 `vtabsort.reload`。主名小写注册。
- ⚠️ 别用 PlugManX 热卸载（假条目会永久残留玩家 Tab），重启代理换 jar。
- 构建：Git Bash `JAVA_HOME=D:/Code/Java/zulu25.34.17-ca-jdk25.0.3-win_x64 mvn -B -o package`（velocity-api 4.3.0 已进本地 m2）。发版改 pom 版本一处（BuildConstants templating）。Java 输出 GBK：`> log && iconv -f GBK -t UTF-8` 看错误。
- 同步测试服：代理插件 → `D:\game\test_velocity\velocity\plugins\`，旧 jar 进 `plugins/_old/`，产物优先用 Release 那份。
- 待实测确认：① `setListed(false)` 的条目是否不计入客户端折行的总条目数（若计入，隐藏玩家会多开一列——届时改 removeEntry 方案）；② 测试服实测 4×20/自适应行数渲染是否精确；③ 续列的重复列头在真客户端里观感如何（同一个服名连着出现 2-3 次）。
