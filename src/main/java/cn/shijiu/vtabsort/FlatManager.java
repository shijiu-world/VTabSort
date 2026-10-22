package cn.shijiu.vtabsort;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import net.kyori.adventure.text.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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

    FlatManager(final ProxyServer proxy, final Sorter sorter, final LayoutManager standIns) {
        this.proxy = proxy;
        this.sorter = sorter;
        this.standIns = standIns;
    }

    void applyAll(final Configuration cfg) {
        final Map<UUID, Integer> orders = cfg.flatSort() ? sorter.computeOrders(cfg) : null;
        // 前缀显示名：同前缀对所有人一样，算一份全局复用
        final Map<UUID, Component> names = new HashMap<>();
        for (final Player p : proxy.getAllPlayers()) {
            final String s = serverOf(p);
            if (!s.isEmpty()) {
                names.put(p.getUniqueId(), prefixed(cfg, s, p.getUsername()));
            }
        }
        for (final Player viewer : proxy.getAllPlayers()) {
            final TabList tabList = viewer.getTabList();
            for (final Player p : proxy.getAllPlayers()) {
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
        }
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
        final Component prefix = Colors.parse(
                cfg.flatPrefix().replace("#name#", cfg.serverDisplayName(server)));
        return Component.text().append(prefix).append(Component.text(username)).build();
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

    private static void setDisplay(final TabListEntry entry, final Component value) {
        try {
            entry.setDisplayName(value);
        } catch (final Exception ignored) {
        }
    }
}
