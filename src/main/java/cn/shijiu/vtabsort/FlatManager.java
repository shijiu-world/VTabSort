package cn.shijiu.vtabsort;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * flat 模式：普通列表 + 名字前缀子服名 + 可选按子服排序。
 *
 * <p>显示名归本插件管（layout 模式下不碰显示名，归 TAB）——TAB 偶尔会
 * 覆写回来（reload/切服事件），靠周期性重断言拉回来（advanced.reassert-seconds）。
 */
final class FlatManager {

    private final ProxyServer proxy;
    private final Sorter sorter;
    /** 补条目复用 layout 那套（真 UUID + 真皮肤），别再写第二份。 */
    private final LayoutManager standIns;
    private final org.slf4j.Logger logger;

    FlatManager(final ProxyServer proxy, final Sorter sorter, final LayoutManager standIns,
                final org.slf4j.Logger logger) {
        this.proxy = proxy;
        this.sorter = sorter;
        this.standIns = standIns;
        this.logger = logger;
    }

    /**
     * 这个人在 flat 里该显示的组件。
     *
     * @return {@code null} = 本轮不显示他（hide-loading 开着且他还没进任何子服）
     */
    private Component nameFor(final Configuration cfg, final Player p) {
        final String s = serverOf(p);
        if (!s.isEmpty()) {
            return prefixed(cfg, s, p.getUsername());
        }
        if (cfg.hideLoading()) {
            return null;
        }
        return Colors.prefixed(cfg.flatPrefix().replace("#name#", cfg.loadingDisplayName()),
                p.getUsername());
    }

    /**
     * 显示名守卫（每 0.5 秒一次）—— 同 {@link LayoutManager#guardNames}，
     * 只是 flat 的显示名是现算的。只写不一致的，不动顺序、不建条目。
     */
    void guardNames(final Configuration cfg) {
        final List<Player> players = new ArrayList<>(proxy.getAllPlayers());
        for (final Player viewer : players) {
            final TabList tabList = viewer.getTabList();
            for (final Player p : players) {
                final Component want = nameFor(cfg, p);
                if (want == null) {
                    continue;
                }
                final TabListEntry entry = tabList.getEntry(p.getUniqueId()).orElse(null);
                if (entry == null
                        || Objects.equals(entry.getDisplayNameComponent().orElse(null), want)) {
                    continue;
                }
                // 静默写回（同 layout 的守卫）：不刷日志
                setDisplay(entry, want);
            }
        }
    }

    void applyAll(final Configuration cfg) {
        final Map<UUID, Integer> orders = cfg.flatSort() ? sorter.computeOrders(cfg) : null;
        // 前缀显示名：同前缀对所有人一样，算一份全局复用
        final Map<UUID, Component> names = new HashMap<>();
        // 「加载中」（还没进任何子服）的那批：hide-loading 开着就藏起来
        final Set<UUID> loading = new HashSet<>();
        // 在线 UUID（含连接中/切服瞬间没子服的）—— 幽灵条目清理用，别误删活人
        final Set<UUID> online = new HashSet<>();
        for (final Player p : proxy.getAllPlayers()) {
            online.add(p.getUniqueId());
            final String s = serverOf(p);
            if (s.isEmpty()) {
                if (cfg.hideLoading()) {
                    loading.add(p.getUniqueId());
                } else {
                    // hide-loading=false：照样显示，但前缀挂「加载中」的显示名。
                    // 🔴 以前这里直接 continue → names 里没有他 → 条目保持裸名、
                    //    listOrder 也没人设 → 排在所有人后面，看起来就是个幽灵
                    //    （2026-10-24 截图里的 Ku_Gu_Gu）。和 layout 单开一节的
                    //    做法对齐：让人一眼看出是「还没进服」，而不是"没前缀的神秘人"。
                    //    前缀就是普通的那份（[flat].prefix-format），只是 #name# 换成
                    //    [servers.names].loading，不再另开一个键。
                    names.put(p.getUniqueId(), nameFor(cfg, p));
                }
                continue;
            }
            names.put(p.getUniqueId(), prefixed(cfg, s, p.getUsername()));
        }
        int ghosts = 0;
        for (final Player viewer : proxy.getAllPlayers()) {
            final TabList tabList = viewer.getTabList();
            for (final Player p : proxy.getAllPlayers()) {
                if (loading.contains(p.getUniqueId())) {
                    // 藏：条目留着但不能显示（flat 没有网格可言，直接 listed=false 即
                    // 从列表里消失；等它进了子服下一轮自然恢复）
                    final TabListEntry le = tabList.getEntry(p.getUniqueId()).orElse(null);
                    if (le != null && le.isListed()) {
                        setListed(le, false);
                    }
                    continue;
                }
                TabListEntry entry = tabList.getEntry(p.getUniqueId()).orElse(null);
                if (entry == null) {
                    // 🔴 没条目 = 这个人在别的子服（Velocity 不合成全局列表，条目都是
                    // 后端发的）。flat 以前在这里 continue，于是没装 TAB 时只能看见
                    // 本服的人，整个列表看起来就是原版 —— auto 模式人少走 flat 时
                    // 最容易撞上。补一条真 UUID + 真皮肤的条目即可。
                    final int initial = orders == null ? 0 : orders.getOrDefault(p.getUniqueId(), 0);
                    if (!standIns.addStandIn(viewer, tabList, p.getUniqueId(),
                            names.get(p.getUniqueId()), initial, cfg.showHat())) {
                        continue; // 人刚好下线
                    }
                    entry = tabList.getEntry(p.getUniqueId()).orElse(null);
                    if (entry == null) {
                        continue;
                    }
                }
                if (orders != null) {
                    final int want = orders.getOrDefault(p.getUniqueId(), 0);
                    if (entry.getListOrder() != want) {
                        setOrder(entry, want);
                    }
                } else if (entry.getListOrder() != 0) {
                    setOrder(entry, 0); // 不排序：回到原版顺序
                }
                if (!entry.isListed()) {
                    setListed(entry, true); // 把 layout 藏起来的（连接中）恢复可见
                }
                final Component want = names.get(p.getUniqueId());
                if (want != null && !Objects.equals(entry.getDisplayNameComponent().orElse(null), want)) {
                    setDisplay(entry, want);
                }
            }
            ghosts += purgeGhosts(viewer, online);
        }
        if (ghosts > 0) {
            logger.info("[vtabsort] flat 清了 " + ghosts + " 条幽灵条目（这些 UUID 已经不在线了，"
                    + "正常断开本该由后端/代理收走）");
        }
    }

    /**
     * 清「幽灵条目」：UUID 已经不在在线列表里的 Tab 条目。
     *
     * <p>🔴 为什么必须清（2026-10-24 现场截图）：flat 只遍历在线玩家 → 掉线/
     * 被踢的人如果在别人列表里留了条目，就再没人认领它 —— 不改名、不排序，
     * 裸名挂在最后，还能挂好几分钟（截图里那个 Ku_Gu_Gu，当时是 3.3.4）。
     * 代理正常断开会清一次，但半开连接、后端异常断开、以及我们自己补的
     * standin 条目都不一定有人管；layout 有 {@code audit()} 兜底，flat 以前没有。
     *
     * <p>只删「UUID 不在线」的 —— 在线玩家（含刚连上、切服瞬间没有子服的）
     * 一律不碰，那是后端/代理的条目。
     */
    private int purgeGhosts(final Player viewer, final Set<UUID> online) {
        final TabList tabList = viewer.getTabList();
        int removed = 0;
        // getEntries() 是集合视图，边遍历边删会炸 —— 先复制一份
        for (final TabListEntry entry : new ArrayList<>(tabList.getEntries())) {
            final UUID id = entry.getProfile().getId();
            if (online.contains(id)) {
                continue;
            }
            try {
                tabList.removeEntry(id);
                removed++;
            } catch (final Exception ignored) {
            }
        }
        return removed;
    }

    /** flat → layout 切换：把前缀显示名还原成裸名字（TAB 之后会按自己的格式重设）。 */
    void resetDisplayNames() {
        for (final Player viewer : proxy.getAllPlayers()) {
            final TabList tabList = viewer.getTabList();
            for (final Player p : proxy.getAllPlayers()) {
                final TabListEntry entry = tabList.getEntry(p.getUniqueId()).orElse(null);
                if (entry != null) {
                    setDisplay(entry, Component.text(p.getUsername()));
                }
            }
        }
    }

    /** 前缀与名字做兄弟节点挂空父组件：前缀的颜色不会传染名字。 */
    private static Component prefixed(final Configuration cfg, final String server, final String username) {
        return Colors.prefixed(cfg.flatPrefix().replace("#name#", cfg.serverDisplayName(server)), username);
    }

    private static String serverOf(final Player p) {
        return p.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse("")
                .toLowerCase(Locale.ROOT);
    }

    private static void setOrder(final TabListEntry entry, final int value) {
        try {
            entry.setListOrder(value);
        } catch (final Exception ignored) {
        }
    }

    private static void setListed(final TabListEntry entry, final boolean value) {
        try {
            entry.setListed(value);
        } catch (final Exception ignored) {
        }
    }

    /** 写显示名；@return 成功了没（条目被收走时 setDisplayName 会抛）。 */
    private static boolean setDisplay(final TabListEntry entry, final Component value) {
        try {
            entry.setDisplayName(value);
            return true;
        } catch (final Exception e) {
            return false;
        }
    }
}
