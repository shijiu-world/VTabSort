package cn.shijiu.vtabsort;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 排序核心：算出每个玩家在 Tab 里的 {@code listOrder} 并写进所有在线玩家的 TabList。
 *
 * <p>顺序值 = 子服优先级 × 1_000_000 + 同服内序号。客户端（1.21.2+）拿这个字段原生排序，
 * 完全不经过 scoreboard team —— 这就是本插件存在的全部意义：
 * 不抢 team（小游戏藏名字照常）、不改显示名（TAB 继续管格式）。
 *
 * <p>溢出保护：优先级封顶 2000（2000×1e6 + 999_999 = 2_000_999_999 &lt; Integer.MAX_VALUE），
 * 序号封顶 999_999 —— 一个子服里百万人以内的排序都无损。
 */
final class Sorter {

    /** 同服内序号的上限（排序值的低位段）。 */
    private static final int RANK_CAP = 999_999;
    /** 子服优先级上限（防溢出用，实际用不到这么大）。 */
    private static final int PRIORITY_CAP = 2000;

    private final ProxyServer proxy;

    /** 进代理的先后序号（PostLogin 时发号）。same-server-order = "join" 时用它排。 */
    private final Map<UUID, Long> joinSeq = new ConcurrentHashMap<>();
    private final AtomicLong seqCounter = new AtomicLong();

    Sorter(final ProxyServer proxy) {
        this.proxy = proxy;
    }

    /** 玩家进代理：发个号（join 模式排序用）。 */
    void trackJoin(final UUID uuid) {
        joinSeq.put(uuid, seqCounter.incrementAndGet());
    }

    /** 玩家退代理：把号收回来。 */
    void forget(final UUID uuid) {
        joinSeq.remove(uuid);
    }

    /**
     * 全量重算并下发：把所有在线玩家的 listOrder 写进**每个**在线玩家的 TabList。
     *
     * <p>条目找不到（还没被 TAB 之类的插件建出来）就跳过 —— 调用方负责稍后补一发
     * （见 VTabSort 的延迟刷新）。
     */
    void refreshAll(final Configuration config) {
        final Map<UUID, Integer> orders = computeOrders(config);
        for (final Player viewer : proxy.getAllPlayers()) {
            applyToViewer(viewer, orders);
        }
    }

    /** 只给一个玩家（通常是刚进代理的新 viewer）补全量排序。 */
    void refreshViewer(final Player viewer, final Configuration config) {
        applyToViewer(viewer, computeOrders(config));
    }

    private void applyToViewer(final Player viewer, final Map<UUID, Integer> orders) {
        final TabList tabList = viewer.getTabList();
        for (final Map.Entry<UUID, Integer> e : orders.entrySet()) {
            tabList.getEntry(e.getKey())
                    .filter(entry -> entry.getListOrder() != e.getValue())
                    .ifPresent(entry -> setOrder(entry, e.getValue()));
        }
    }

    /**
     * setListOrder 单独抽一层：老协议的客户端（&lt;1.21.2）没有这个 action，
     * Velocity 内部会自己兜底（默认实现按版本判断），但万一实现抛异常也别让它
     * 把整个刷新循环带崩 —— 单个条目失败只丢那一个。
     */
    private void setOrder(final TabListEntry entry, final int order) {
        try {
            entry.setListOrder(order);
        } catch (final Exception ignored) {
            // 极老客户端 / 极端实现：跳过这一个
        }
    }

    // ------------------------------------------------------------------
    // 计算顺序值
    // ------------------------------------------------------------------

    private Map<UUID, Integer> computeOrders(final Configuration config) {
        // 1. 按子服分组（服名小写；还没进任何服的算 "" 组）
        final Map<String, List<Player>> byServer = new LinkedHashMap<>();
        for (final Player player : proxy.getAllPlayers()) {
            final String server = player.getCurrentServer()
                    .map(s -> s.getServerInfo().getName())
                    .orElse("")
                    .toLowerCase(Locale.ROOT);
            byServer.computeIfAbsent(server, k -> new ArrayList<>()).add(player);
        }

        // 2. 每个子服一个优先级基数 —— 每个服（含未列出的）拿到的基数必须互不相同，
        //    否则两个子服的玩家会按各自组内的序号交叉混排。
        //      已列出的：按 order 里的位置；
        //      未列出的：按服名升序分到已列出服的前面（unknownFirst）或后面的连续区间。
        //    全部非负（协议要求 non-negative），统一 Math.min 截到 PRIORITY_CAP ——
        //    只有 order 列了 2000+ 个服才会触发截断（实际不可能）。
        final List<String> order = config.serverOrder();
        final Map<String, Integer> base = new HashMap<>();
        final List<String> unknownServers = new ArrayList<>();
        for (final String server : byServer.keySet()) {
            final int idx = order.indexOf(server);
            if (idx >= 0) {
                base.put(server, idx);
            } else {
                unknownServers.add(server);
            }
        }
        java.util.Collections.sort(unknownServers); // 服名升序（都是小写）
        final int unknownCount = unknownServers.size();
        final int offset = config.unknownFirst() ? unknownCount : 0;
        for (final Map.Entry<String, Integer> e : base.entrySet()) {
            e.setValue(Math.min(offset + e.getValue(), PRIORITY_CAP));
        }
        for (int i = 0; i < unknownCount; i++) {
            final int b = config.unknownFirst()
                    ? i
                    : Math.min(order.size(), PRIORITY_CAP) + i;
            base.put(unknownServers.get(i), Math.min(b, PRIORITY_CAP));
        }

        // 3. 组内排序 + 拼顺序值
        final Map<UUID, Integer> out = new HashMap<>();
        for (final Map.Entry<String, List<Player>> group : byServer.entrySet()) {
            final List<Player> players = group.getValue();
            // join 模式：按进代理的先后。热加载插件后才进服的玩家拿不到序号
            // （PostLogin 早已过去）→ MAX_VALUE 兜底，它们按名字排在同服老人后面。
            final Comparator<Player> cmp = config.sameServerByJoin()
                    ? Comparator.comparing(p -> joinSeq.getOrDefault(p.getUniqueId(), Long.MAX_VALUE))
                    : Comparator.comparing(p -> p.getUsername().toLowerCase(Locale.ROOT));
            players.sort(cmp.thenComparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER));
            final int b = base.get(group.getKey());
            for (int i = 0; i < players.size(); i++) {
                final Player p = players.get(i);
                out.put(p.getUniqueId(), b * 1_000_000 + Math.min(i, RANK_CAP));
            }
        }
        return out;
    }
}
