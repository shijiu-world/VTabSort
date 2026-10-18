# VTabSort

Velocity 代理端 Tab 展示插件：**layout 固定网格**（Hypixel 式，列头 = 子服名，假人补位）或 **flat 前缀列表**，两种模式一键切换。

## 为什么会有它

装了 VelocityScoreboardAPI v2 之后 TAB 的 team 排序终于能用，但代价是代理 team 优先级压过后端（小游戏藏不了名字）、team 前后缀清不干净。关掉 `scoreboard-teams` 后 Tab 失去排序。而 MC **1.21.2+** 的 tab 协议本身就有 `listOrder` 字段（客户端原生排序、不经过 scoreboard team），Velocity API 直接支持；现成插件没有一个走这条路（TAB 普通排序/Velocitab 都只用 team；TAB Layout 是写死 80 格的固定网格，列头/分列不可配），所以自研。

## 两种模式（`[display].mode`，`/vts reload` 切换）

### layout —— 固定网格

- 每列头顶 = 子服名（`header-format`，默认 `&e子服名 &7· 在线数`）；
- 子服数超过 `max-columns` 时**自动合并**：人数最多的 (max-1) 个独占列，
  其余全部进共享列（分节按人数降序、人多的在上、分节间空一行）；
- **网格行数自适应**：人数最多的子服几人就是几+1 行；下限是「让客户端开出
  N 列的最小行数」（4 列 ≥ 16 行），上限 `rows`（客户端行数上限，原版 20）。
  原理：客户端折行规则是 列数=ceil(总条目/rows)、行数=ceil(总条目/列数)，
  所以总条目恒等于 列数×行数 时渲染出精确网格——空槽用假条目补满；
- 单列内容超过 `rows` 上限时折叠成「…及N人」（`truncate`，可关＝允许溢出）；
  所有服都并进一列仍超高时按服主规则允许溢出；
- 假条目：确定性 UUID（`nameUUIDFromBytes("vtabsort-slot-N")`）、可配皮肤
  （`default-skin` 填 textures 的 base64）与伪装 ping（`blank-ping`）。

### flat —— 普通列表

- 名字前缀子服名（`prefix-format`，默认 `&7[&e大厅&7] `）；
- 可选按子服排序（`[flat].sort`，复用 v1 的优先级×1e6 算法）；
- 空服自然没条目、某服人多自然续行（原版列表的弹性）。

两模式共同点：不碰 scoreboard team（小游戏藏名字照常）、真实玩家条目由 TAB
global-playerlist 创建本插件只调 `listOrder`/`listed`/显示名。老客户端（<1.21.2）
自动走原版行为，无害。

## 配置要点

```toml
[display]
mode = "layout"            # layout | flat

[layout]
max-columns = 4
rows = 20                  # 客户端每列行数上限；实测列数不对就改（如 15）
header-format = "&e#name# &7· #count#"

[flat]
prefix-format = "&7[&e#name#&7] "
sort = true

[servers]
order = ["lobby", "survival", "survival2", "industry", "starvalley", "minigame", "bedwars", "killer"]

[servers.names]
lobby = "大厅"
survival = "生存净土"
# ……
```

改完 `/vtabsort reload`（或开 `advanced.auto-reload`）。

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/vtabsort reload`（别名 `/vts`） | 重载配置并立即重排 | `vtabsort.reload` |
| `/vtabsort layout` | 打印当前分列模型（无客户端也能核对布局） | `vtabsort.reload` |
| `/vtabsort version` | 看版本 | 无 |

## 构建

- Java 25（class 69，对齐 velocity-api 4.x 与线上 4.2 代理本体）；
- `mvn -B package`，产物在 `target/vtabsort-<版本>.jar`；
- 🔴 依赖必须是 velocity-api **4.x**：`setListOrder` 是 3.4.0 才加的，
  VWhisper/Vmessage 用的 3.2.0 里没有。

## 架构说明

- 事件（进代理/切服/退出）→ 全量重算 LayoutModel（纯数据）→ 对每个 viewer
  diff 应用（没变化不发一个包）；`refresh-delay-ms` 延迟补发兜 TAB 建条目竞态，
  `reassert-seconds` 周期校准兜后端 Paper 重置 listOrder / TAB 覆写显示名；
- flat 排序值 = `子服优先级 × 1_000_000 + 组内序号`（双封顶防溢出）；
- 假条目按槽位确定性生成 UUID，跨重算稳定（避免拔了又加的包抖动）；
  网格缩短后按 512 槽位扫描半径清理残留；
- `Player#getTabList` 是 per-viewer 的：每个在线玩家的 TabList 都要设一遍；
- ⚠️ 别用 PlugManX 热卸载本插件（假条目会永久留在玩家 Tab 里），重启代理再换。
