package cn.shijiu.vtabsort;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * layout 模式引擎：把所有子服的玩家排进固定网格（列头 = 子服名，假人补满）。
 *
 * <p>客户端折行规则（1.19.3+ 实测）：列数 = ceil(总条目 ÷ 行数上限)，
 * 行数 = ceil(总条目 ÷ 列数)。所以网格行数自适应：
 * <pre>
 *   natural = 最高的那列内容高度（头 + 玩家 + 分节空行）
 *   rMin    = 让客户端开出 C 列的最小行数（4 列 ≥ 16 行）
 *   行数 R  = natural ≤ rows 上限 ? max(natural, rMin) : rows 上限（截断）
 * </pre>
 * 总条目恒等于 C×R，客户端就渲染出精确的 C 列 × R 行。
 *
 * <p>分列规则（服主定的）：每个子服先各占一列；子服数超过 max-columns 时，
 * 人数最多的 (max-1) 个独占列，**其余全部进一个共享列**（分节按人数降序、
 * 人多的在上、分节间空一行）。共享列装下所有服仍超高时允许溢出，不截断。
 */
final class LayoutManager {

    /**
     * 假条目槽位扫描半径。rows/max-columns 改小、或自适应行数缩短后，
     * 超出当前网格的旧假条目要按这个半径清掉（UUID 确定，扫一圈很便宜）。
     */
    private static final int FAKE_RANGE = 512;

    private final ProxyServer proxy;
    private final Sorter sorter;

    LayoutManager(final ProxyServer proxy, final Sorter sorter) {
        this.proxy = proxy;
        this.sorter = sorter;
    }

    // ------------------------------------------------------------------
    // 建模
    // ------------------------------------------------------------------

    LayoutModel build(final Configuration cfg) {
        // 1) 玩家按子服分组；还没进任何服的（连接中）稍后隐藏
        final Map<String, List<Player>> byServer = new LinkedHashMap<>();
        final List<Player> noServer = new ArrayList<>();
        for (final Player p : proxy.getAllPlayers()) {
            final String s = serverOf(p);
            if (s.isEmpty()) {
                noServer.add(p);
                continue;
            }
            byServer.computeIfAbsent(s, k -> new ArrayList<>()).add(p);
        }

        // 2) 子服清单：order 里全部（0 人也占分节，网格稳定）+ 未列出的在线服（按名排序）
        final List<String> servers = new ArrayList<>(cfg.serverOrder());
        final List<String> unknown = new ArrayList<>();
        for (final String s : byServer.keySet()) {
            if (!servers.contains(s)) {
                unknown.add(s);
            }
        }
        Collections.sort(unknown);
        servers.addAll(unknown);

        final Comparator<Player> pc = sorter.playerComparator(cfg);
        final List<Sec> secs = new ArrayList<>();
        for (final String s : servers) {
            final List<Player> ps = new ArrayList<>(byServer.getOrDefault(s, List.of()));
            ps.sort(pc);
            secs.add(new Sec(s, cfg.serverDisplayName(s), ps));
        }

        // 3) 分列
        final int max = Math.max(1, cfg.maxColumns());
        final List<List<Sec>> columnsRaw = splitIntoColumns(secs, max);

        // 4) 自适应行数
        final int cap = cfg.rows();
        final int c = Math.max(1, columnsRaw.size());
        int natural = 0;
        for (final List<Sec> col : columnsRaw) {
            int h = 0;
            for (final Sec sec : col) {
                h += 1 + sec.count();
            }
            if (col.size() > 1 && cfg.separator()) {
                h += col.size() - 1;
            }
            natural = Math.max(natural, h);
        }
        // ceil((cap*(c-1)+1) / c)：客户端开出 c 列的最小行数
        final int rMin = (cap * (c - 1) + 1 + c - 1) / c;
        final boolean trunc = natural > cap;
        final int r = trunc ? cap : Math.max(natural, rMin);
        // 溢出策略：truncate=false（配置）→ 全局允许溢出（网格会变形，注释已写明）；
        // 服主规则：所有服并进一列仍超高 → 允许溢出（不截断）。
        final boolean allMerged = columnsRaw.size() == 1 && columnsRaw.get(0).size() > 1;
        final boolean allowExceed = !cfg.truncate() || !trunc || allMerged;

        // 5) 填槽（列主序，槽位号 = 全局序号，与客户端折行一致）
        final List<LayoutModel.Slot> slots = new ArrayList<>();
        final Map<UUID, Integer> orders = new HashMap<>();
        final Set<UUID> hidden = new LinkedHashSet<>();
        final List<LayoutModel.ColumnDesc> colDescs = new ArrayList<>();
        for (final List<Sec> col : columnsRaw) {
            fillColumn(col, r, allowExceed, cfg, slots, hidden, colDescs);
        }
        for (int i = 0; i < slots.size(); i++) {
            final UUID u = slots.get(i).player;
            if (u != null) {
                orders.put(u, i);
            }
        }

        // 6) 还没进服的（连接中）藏起来——不然它们会以原版顺序挤进网格把折行挤歪
        for (final Player p : noServer) {
            hidden.add(p.getUniqueId());
        }
        return new LayoutModel(slots, orders, hidden, colDescs, r);
    }

    /**
     * 分列：≤max 各占一列；超了 → 人数降序排名，前 (max-1) 名独占，
     * 其余全进共享列（ranked 本身就是人数降序，平手按 order 位置）。
     * 列的左右顺序 = 各列最靠前的 order 成员的位置。
     */
    private List<List<Sec>> splitIntoColumns(final List<Sec> secs, final int max) {
        final List<List<Sec>> out = new ArrayList<>();
        if (secs.size() <= max) {
            for (final Sec s : secs) {
                out.add(List.of(s));
            }
            return out;
        }
        final Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < secs.size(); i++) {
            pos.put(secs.get(i).server, i);
        }
        final List<Sec> ranked = new ArrayList<>(secs);
        ranked.sort(Comparator.comparingInt((Sec s) -> -s.count()).thenComparingInt(s -> pos.get(s.server)));

        record Col(List<Sec> secs, int key) {
        }
        final List<Col> cols = new ArrayList<>();
        for (int i = 0; i < max - 1; i++) {
            final List<Sec> own = List.of(ranked.get(i));
            cols.add(new Col(own, pos.get(ranked.get(i).server)));
        }
        final List<Sec> shared = new ArrayList<>(ranked.subList(max - 1, ranked.size()));
        int sharedKey = Integer.MAX_VALUE;
        for (final Sec s : shared) {
            sharedKey = Math.min(sharedKey, pos.get(s.server));
        }
        cols.add(new Col(shared, sharedKey));
        cols.sort(Comparator.comparingInt(Col::key));
        for (final Col col : cols) {
            out.add(col.secs);
        }
        return out;
    }

    /** 把一列填成恰好 rows 高：分节头、玩家、分节空行、假人补位。 */
    private void fillColumn(final List<Sec> col, final int rows,
                            final boolean allowExceed, final Configuration cfg,
                            final List<LayoutModel.Slot> slots, final Set<UUID> hidden,
                            final List<LayoutModel.ColumnDesc> colDescs) {
        int remaining = rows;
        final List<LayoutModel.SectionDesc> sds = new ArrayList<>();
        for (int i = 0; i < col.size(); i++) {
            final Sec sec = col.get(i);
            if (remaining <= 0 && !allowExceed) {
                // 截断模式下容量耗尽：整节藏（极端情况，自适应行数下很少走到）
                for (final Player p : sec.players) {
                    hidden.add(p.getUniqueId());
                }
                sds.add(new LayoutModel.SectionDesc(sec.server, sec.display, sec.count(), 0, sec.count()));
                continue;
            }
            slots.add(new LayoutModel.Slot(null, textComponent(cfg.headerFormat()
                    .replace("#name#", sec.display)
                    .replace("#count#", String.valueOf(sec.count())))));
            if (remaining > 0) {
                remaining--;
            }
            final int capacity = Math.max(0, remaining);
            final int shown;
            if (allowExceed || sec.count() <= capacity) {
                shown = sec.count();
            } else {
                shown = Math.max(0, capacity - 1); // 留一格给「…及N人」
            }
            for (int k = 0; k < shown; k++) {
                slots.add(new LayoutModel.Slot(sec.players.get(k).getUniqueId(), null));
            }
            final int hiddenCount = sec.count() - shown;
            if (hiddenCount > 0 && !allowExceed) {
                if (capacity >= 1) {
                    slots.add(new LayoutModel.Slot(null, textComponent(cfg.overflowFormat()
                            .replace("#count#", String.valueOf(hiddenCount)))));
                }
                for (int k = shown; k < sec.count(); k++) {
                    hidden.add(sec.players.get(k).getUniqueId());
                }
                remaining = 0; // 本节已把列吃满，后面的分节只能整节藏
            } else {
                remaining -= shown;
            }
            sds.add(new LayoutModel.SectionDesc(sec.server, sec.display, sec.count(), shown, hiddenCount));
            if (i < col.size() - 1 && (remaining > 0 || allowExceed) && cfg.separator()) {
                slots.add(new LayoutModel.Slot(null, Component.empty()));
                if (remaining > 0) {
                    remaining--;
                }
            }
        }
        // 假人补满到整列高度
        while (remaining > 0) {
            slots.add(new LayoutModel.Slot(null, Component.empty()));
            remaining--;
        }
        colDescs.add(new LayoutModel.ColumnDesc(sds));
    }

    // ------------------------------------------------------------------
    // 应用到 viewer（全 diff：没变化不发一个包）
    // ------------------------------------------------------------------

    void applyAll(final LayoutModel model, final Configuration cfg) {
        for (final Player viewer : proxy.getAllPlayers()) {
            applyViewer(viewer, model, cfg);
        }
    }

    void applyViewer(final Player viewer, final LayoutModel model, final Configuration cfg) {
        final TabList tabList = viewer.getTabList();
        // 1) 网格槽位
        for (int i = 0; i < model.slots.size(); i++) {
            final LayoutModel.Slot slot = model.slots.get(i);
            final UUID fakeId = fakeUuid(i);
            if (slot.player != null) {
                // 该槽是真实玩家：之前占着这格的假条目要拔掉
                if (tabList.containsEntry(fakeId)) {
                    tabList.removeEntry(fakeId);
                }
                final TabListEntry entry = tabList.getEntry(slot.player).orElse(null);
                if (entry != null) {
                    if (entry.getListOrder() != i) {
                        setOrder(entry, i);
                    }
                    if (!entry.isListed()) {
                        setListed(entry, true);
                    }
                }
            } else {
                final TabListEntry entry = tabList.getEntry(fakeId).orElse(null);
                if (entry == null) {
                    addFake(tabList, i, slot.display, cfg);
                } else {
                    if (!Objects.equals(entry.getDisplayNameComponent().orElse(null), slot.display)) {
                        setDisplay(entry, slot.display);
                    }
                    if (entry.getListOrder() != i) {
                        setOrder(entry, i);
                    }
                    if (!entry.isListed()) {
                        setListed(entry, true);
                    }
                }
            }
        }
        // 2) 网格缩短后的残留假条目（自适应行数变化、配置改小）
        for (int i = model.slots.size(); i < FAKE_RANGE; i++) {
            final UUID fakeId = fakeUuid(i);
            if (tabList.containsEntry(fakeId)) {
                tabList.removeEntry(fakeId);
            }
        }
        // 3) 该藏的：截断溢出 + 还没进服的
        for (final UUID u : model.hidden) {
            final TabListEntry entry = tabList.getEntry(u).orElse(null);
            if (entry != null && entry.isListed()) {
                setListed(entry, false);
            }
        }
    }

    private void addFake(final TabList tabList, final int slot, final Component display,
                         final Configuration cfg) {
        try {
            final TabListEntry entry = TabListEntry.builder()
                    .tabList(tabList)
                    .profile(fakeProfile(slot, cfg))
                    .displayName(display)
                    .latency(cfg.blankPing())
                    .gameMode(0)
                    .listed(true)
                    .listOrder(slot)
                    .build();
            tabList.addEntry(entry);
        } catch (final Exception ignored) {
            // 单个假条目失败（极老客户端等）不影响其它槽
        }
    }

    /** 模式切走 / 清场：拔掉所有 viewer 的假条目。 */
    void stripAll() {
        for (final Player viewer : proxy.getAllPlayers()) {
            final TabList tabList = viewer.getTabList();
            for (int i = 0; i < FAKE_RANGE; i++) {
                final UUID fakeId = fakeUuid(i);
                if (tabList.containsEntry(fakeId)) {
                    tabList.removeEntry(fakeId);
                }
            }
        }
    }

    /** 槽位 → 假条目 UUID：确定性生成，跨重算稳定（避免拔了又加的包抖动）。 */
    static UUID fakeUuid(final int slot) {
        return UUID.nameUUIDFromBytes(("vtabsort-slot-" + slot).getBytes(StandardCharsets.UTF_8));
    }

    private static GameProfile fakeProfile(final int slot, final Configuration cfg) {
        final String skin = cfg.defaultSkin();
        final List<GameProfile.Property> props = skin == null || skin.isEmpty()
                ? List.of()
                : List.of(new GameProfile.Property("textures", skin, ""));
        // 名字 ≤16 字符且互不相同；没配皮肤就是默认 Steve/Alex
        return new GameProfile(fakeUuid(slot), String.format(Locale.ROOT, "vts_s%03d", slot), props);
    }

    private static String serverOf(final Player p) {
        return p.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse("")
                .toLowerCase(Locale.ROOT);
    }

    private static Component textComponent(final String legacy) {
        return Colors.parse(legacy);
    }

    // 单条失败只丢那一个，绝不让一个 entry 把整个刷新循环带崩
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

    /** 一个子服分节：显示名 + 分好序的玩家列表。 */
    private static final class Sec {
        final String server;
        final String display;
        final List<Player> players;

        Sec(final String server, final String display, final List<Player> players) {
            this.server = server;
            this.display = display;
            this.players = players;
        }

        int count() {
            return players.size();
        }
    }
}
