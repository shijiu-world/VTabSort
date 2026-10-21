# VTabSort

Velocity 代理端 Tab 展示插件：**layout 固定网格**（Hypixel 式，列头 = 子服名，假人补位）、
**flat 前缀列表**、**auto 按在线人数自适应**，三种模式一键切换。

> **想先看效果？** → <https://shijiu-world.github.io/VTabSort/>
> 纯前端模拟器（也可以直接打开本地的 [`docs/index.html`](docs/index.html)），复刻了同一套分列
> 算法，可以改配置、加退玩家、实时看 Tab 怎么变（和插件输出逐场景核对过）。
> 带 `?mode=auto` / `?mode=flat` 参数能直接开到指定模式。

## 为什么会有它

装了 VelocityScoreboardAPI v2 之后 TAB 的 team 排序终于能用，但代价是代理 team 优先级压过后端（小游戏藏不了名字）、team 前后缀清不干净。关掉 `scoreboard-teams` 后 Tab 失去排序。而 MC **1.21.2+** 的 tab 协议本身就有 `listOrder` 字段（客户端原生排序、不经过 scoreboard team），Velocity API 直接支持；现成插件没有一个走这条路（TAB 普通排序/Velocitab 都只用 team；TAB Layout 是写死 80 格的固定网格，列头/分列不可配），所以自研。

## 三种模式（`[display].mode`，`/vts reload` 切换）

### layout —— 固定网格

- 每列头顶 = 子服名（`header-format`，默认 `&e子服名 &7· 在线数`）；
- **列的左右顺序 = 子服人数降序**（平手按服名升序）。没有「服主排序」这回事了，
  人多的服永远在左边；
- **一个子服装不下一列就续开一列**：人数 > `rows-1` 时按列均分（余数给靠前的列），
  续出来的列紧跟在它后面、**列头文字完全一样**（`#count#` 始终是该服总人数）；
- 列预算（`max-columns`）不够时，排在后面的小服**并进尾部的共享列**
  （分节按人数降序、人多的在上，流式往下排）。同一列里列头和上一个服之间固定空一格；
  列头刚好落在行末（底下放不了人）就换新列；一个服在当前列放不完就续到下一列、
  续段重复列头。共享列也能占多列；
- **0 人的子服整个不出现**（连列头都没有），所以不会有空列；
- 优先让更多子服独占列：分配时从「尽量多独占」往下退，第一个塞得进预算的方案胜出；
- **网格行数自适应**：取「最高那列的内容高度」与「让客户端开出 N 列的最小行数」
  （4 列 ≥ 16 行）的较大值。原理：客户端折行规则是
  列数=ceil(总条目/rows)、行数=ceil(总条目/列数)，所以总条目恒等于 列数×行数
  时渲染出精确网格——空槽用假条目补满；
- 内容真的塞不进「`max-columns` 列 × `rows` 行」时：**保行数、让列数溢出**。
  行数是客户端硬约束（超了会被折成别的形状），列预算只是期望值，两者只能保一个。
  `/vts layout` 会提示，调大 `max-columns` / `rows` 即可消除；
- 假条目：确定性 UUID（`nameUUIDFromBytes("vtabsort-slot-N")`）、可配皮肤
  （`default-skin` 填 textures 的 base64）与伪装 ping（`blank-ping`）。

### flat —— 普通列表

- 名字前缀子服名（`prefix-format`，默认 `&7[&e大厅&7] `）；
- 可选排序（`[flat].sort`）：基数 = **子服人数降序排名**，和 layout 的列顺序一致
  （auto 模式切换时视觉跳动最小）；算法还是优先级×1e6；
- 空服自然没条目、某服人多自然续行（原版列表的弹性）。

### auto —— 自适应

在线人数 **≤ `[layout].rows`**（客户端拆出第二列的阈值，原版 20）时按 flat 来，
超过就按 layout 来。人少的时候不铺一屏假人，人多的时候才上网格。
落地模式每次重算都重新判定，翻面时会自动清掉对家的痕迹（假条目 / 前缀显示名）。

三种模式共同点：不碰 scoreboard team（小游戏藏名字照常）、真实玩家条目由 TAB
global-playerlist 创建本插件只调 `listOrder`/`listed`/显示名。老客户端（<1.21.2）
自动走原版行为，无害。

## 配置要点

```toml
[display]
mode = "layout"            # layout | flat | auto

[layout]
max-columns = 4            # 列预算（续列和共享列都算在内）
rows = 20                  # 客户端每列行数上限，同时是 auto 的切换阈值；实测列数不对就改（如 15）
header-format = "&e#name# &7· #count#"
separator = true           # 共享列里分节之间空一行（固定空，不会自动放弃）

[flat]
prefix-format = "&7[&e#name#&7] "
sort = true                # 按子服人数降序

# 只提供显示名 + 充当 /vts demo 的模拟玩家池。
# 列的左右顺序一律按人数降序算，0 人的子服整个不出现（连列头都没有）。
# 没列出但有人在线的服照样显示，用服名原文当显示名。
[servers.names]
lobby = "大厅"
survival = "生存净土"
# ……
```

改完 `/vtabsort reload`（或开 `advanced.auto-reload`）。

> **v2 → v3 删掉的配置**（留着也只是被忽略）：`[servers].order`、`[servers].unknown`
> （列顺序改成按人数降序算）、`[layout].truncate`、`[layout].overflow-format`
> （单列装不下改成续开一列，不再折叠成「…及N人」）。

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/vtabsort reload`（别名 `/vts`） | 重载配置并立即重排 | `vtabsort.reload` |
| `/vtabsort layout` | 打印当前分列模型（无客户端也能核对布局） | `vtabsort.reload` |
| `/vtabsort demo` | 查看虚拟玩家模拟状态 | `vtabsort.reload` |
| `/vtabsort demo lobby:5 survival:12` | 固定各服虚拟人数，真实客户端里看网格效果 | `vtabsort.reload` |
| `/vtabsort demo flow [总人数]` | 流动模拟（默认 30 人，每 2 秒随机进出/换服，0 人服的列自动消失） | `vtabsort.reload` |
| `/vtabsort demo off` | 关掉模拟并清掉虚拟玩家 | `vtabsort.reload` |
| `/vtabsort version` | 看版本 | 无 |

### demo 虚拟玩家模拟（v3.0.0+）

想在**真实客户端**里看玩家进出时网格怎么动，用 `/vts demo`：虚拟玩家没有连接，
在布局引擎里与真人共用同一套分节/排序/续列代码路径，Tab 里观感与真人一致
（默认皮肤、可配 ping）。auto 模式的翻面判定也把虚拟人数算进去。
只在 layout/auto（人数超阈值）下有视觉效果；flat 模式会提示先切模式。
顺带一提：GitHub Pages 上还有一个纯前端的[分列算法模拟器](docs/index.html)，不用起服就能调参预览。

## 构建

- Java 25（class 69，对齐 velocity-api 4.x 与线上 4.2 代理本体）；
- `mvn -B package`，产物在 `target/vtabsort-<版本>.jar`；
- 🔴 依赖必须是 velocity-api **4.x**：`setListOrder` 是 3.4.0 才加的，
  VWhisper/Vmessage 用的 3.2.0 里没有。

## 架构说明

- 事件（进代理/切服/退出）→ 全量重算 LayoutModel（纯数据）→ 对每个 viewer
  diff 应用（没变化不发一个包）；`refresh-delay-ms` 延迟补发兜 TAB 建条目竞态，
  `reassert-seconds` 周期校准兜后端 Paper 重置 listOrder / TAB 覆写显示名；
- flat 排序值 = `子服人数降序排名 × 1_000_000 + 组内序号`（双封顶防溢出）；
- layout 分列是「试 k 退 k」：k = 独占列的子服个数，从最大往下退，
  第一个塞得进 `max-columns` 的方案胜出；带/不带分节空行各试一趟，取独占更多的那个；
- 假条目按槽位确定性生成 UUID，跨重算稳定（避免拔了又加的包抖动）；
  网格缩短后按 512 槽位扫描半径清理残留；
- `Player#getTabList` 是 per-viewer 的：每个在线玩家的 TabList 都要设一遍；
- ⚠️ 热重载（卸载 + 立刻装新 jar）是安全的：新实例起手会 `resetAll()` 自愈
  （拔掉上一任的假条目 + 恢复被藏玩家 + 重建布局；假条目 UUID 确定性生成，
  本来就能被重新认领）。**只卸载不装**才会残留（Velocity 没有卸载回调，
  插件在被卸载瞬间一行代码都不执行）——那种情况重装一次任意版本或重启代理即可。
