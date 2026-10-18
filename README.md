# VTabSort

Velocity 代理端小插件：**Tab 列表按子服排序**。

## 为什么会有它

装了 VelocityScoreboardAPI v2 之后 TAB 的 team 排序终于能用，但代价是：

- 代理 team 优先级压过后端 → 小游戏藏不了玩家名字；
- team 前后缀同时渲染在 Tab 列表 + 头顶名牌 → 前缀清不干净。

关掉 `scoreboard-teams` 后 Tab 失去排序。而 MC **1.21.2+** 的 tab 协议本身就有
`listOrder` 字段（`UPDATE_LIST_ORDER`，客户端原生排序、完全不经过 scoreboard team），
Velocity API 直接支持（`TabListEntry#setListOrder(int)`，velocity-api 3.4.0+）。
现成插件没有一个走这条路（TAB 普通排序只用 team；Velocitab 也是 team；TAB Layout
是 80 假人固定网格），所以有了这个 ~300 行的自研插件。

## 效果

- Tab 列表按配置的子服顺序排（大厅 → 生存 → 工业 → …），同服内按名字排（可配按加入顺序）；
- 名字右边的显示、前后缀、header/footer 全归原有插件管（`UPDATE_DISPLAY_NAME` 与
  `UPDATE_LIST_ORDER` 是两个独立 action，互不覆盖）；
- 不碰 team → 小游戏藏名字照常，后端排序照常；
- 原版列表的弹性全保留：空服自然没条目、某服人多自然续行；
- 老客户端（<1.21.2）没有该字段，自动走原版顺序，无害。

## 配置（config.toml）

```toml
[servers]
order = ["lobby", "survival", "survival2", "industry", "starvalley", "minigame", "bedwars", "killer"]
unknown = "after"        # 没列进 order 的服排最后（before = 最前）

[general]
same-server-order = "name"   # 同服内按名字（join = 按加入顺序）
```

改完 `/vtabsort reload`（或开 `advanced.auto-reload`）。

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/vtabsort reload`（别名 `/vts reload`） | 重载配置并立即重排 | `vtabsort.reload` |
| `/vtabsort version` | 看版本 | 无 |

## 构建

- Java 25（class 69，对齐 velocity-api 4.x 与线上 4.2 代理本体）；
- `mvn -B package`，产物在 `target/vtabsort-<版本>.jar`；
- 🔴 依赖必须是 velocity-api **4.x**：`setListOrder` 是 3.4.0 才加的，
  VWhisper/Vmessage 用的 3.2.0 里没有。

## 架构说明

- 顺序值 = `子服优先级 × 1_000_000 + 组内序号`（优先级封顶 2000、序号封顶 999_999，
  拼起来永不溢出 int）；
- 进代理 / 切服 / 退代理都**全量重算**（人数 < 几百，O(n log n) 随便刷，比增量维护省心）；
- 事件触发立即刷一发，再按 `advanced.refresh-delay-ms`（默认 200ms）延迟补一发 ——
  tab 条目是 TAB 等插件建的，事件瞬间可能还没建好；
- `Player#getTabList` 是 per-viewer 的：每个在线玩家的 TabList 都要设一遍。
