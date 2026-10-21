package cn.shijiu.vtabsort;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

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
 * flat 模式的排序计算 + 分节内排序比较器。
 *
 * <p>排序值 = 子服基数 × 1_000_000 + 同服内序号，客户端（1.21.2+）按
 * listOrder 原生排序——🔴 官方 1.21.2 changelog 明确是 "sorted highest to
 * lowest"：<b>值越大越靠前</b>。所以基数和组内序号都按这个方向拼：人多的服
 * 拿大基数、组内排前面的拿大序号（RANK_CAP-idx 镜像）。完全不经过
 * scoreboard team。溢出保护：基数封顶 2000、序号封顶 999_999
 * （2000×1e6+999_999 &lt; Integer.MAX_VALUE）。
 *
 * <p>v3：子服基数不再来自 {@code [servers].order}（已删），改成和 layout
 * 一样按**人数降序**（平手按服名升序）—— 两个模式的子服先后顺序保持一致，
 * auto 模式切换时视觉跳动最小。
 */
final class Sorter {

    private static final int RANK_CAP = 999_999;
    private static final int PRIORITY_CAP = 2000;

    private final ProxyServer proxy;

    /** 进代理的先后序号（PostLogin 发号）。same-server-order = "join" 时用。 */
    private final Map<UUID, Long> joinSeq = new ConcurrentHashMap<>();
    private final AtomicLong seqCounter = new AtomicLong();

    Sorter(final ProxyServer proxy) {
        this.proxy = proxy;
    }

    /** 玩家进代理：发个号。 */
    void trackJoin(final UUID uuid) {
        joinSeq.put(uuid, seqCounter.incrementAndGet());
    }

    /** 玩家退代理：收号。 */
    void forget(final UUID uuid) {
        joinSeq.remove(uuid);
    }

    /**
     * 同一子服内（layout 分节 / flat 组内）的玩家排序比较器。
     *
     * <p>join 模式：按进代理先后。热加载插件后才在线的玩家拿不到序号
     * （PostLogin 早已过去）→ MAX_VALUE 兜底，按名字排在同服老人后面。
     */
    Comparator<Player> playerComparator(final Configuration cfg) {
        final Comparator<Player> cmp = cfg.sameServerByJoin()
                ? Comparator.comparing(p -> joinSeq.getOrDefault(p.getUniqueId(), Long.MAX_VALUE))
                : Comparator.comparing(p -> p.getUsername().toLowerCase(Locale.ROOT));
        return cmp.thenComparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER);
    }

    /** 玩家的进代理序号；没记录的（热加载后才在线）MAX_VALUE 兜底。 */
    long joinSeqOf(final UUID uuid) {
        return joinSeq.getOrDefault(uuid, Long.MAX_VALUE);
    }

    /**
     * TabUser（真人 + demo 虚拟玩家）的排序比较器，语义与 {@link #playerComparator}
     * 一致：join 模式按序号，否则按名字；末位平手按名字大小写不敏感。
     */
    Comparator<TabUser> tabUserComparator(final Configuration cfg) {
        final Comparator<TabUser> primary = cfg.sameServerByJoin()
                ? Comparator.comparingLong(TabUser::joinSeq)
                : Comparator.comparing(u -> u.name().toLowerCase(Locale.ROOT));
        return primary.thenComparing(TabUser::name, String.CASE_INSENSITIVE_ORDER);
    }

    /**
     * flat 排序：算每个玩家的 listOrder。
     *
     * <p>🔴 客户端对 listOrder <b>降序</b>（值越大越靠前，官方 1.21.2 changelog
     * "sorted highest to lowest"）。每个子服的基数互不相同，否则两个子服的
     * 玩家会按各自组内序号交叉混排。基数 = 人数降序排名的<b>倒转</b>
     * （人最多的服基数最大），所以人多的服排最上。组内同理：排第 0 位的玩家
     * 拿 RANK_CAP（组内最大），往后递减。恒非负（协议要求 non-negative）。
     *
     * <p>还没进任何服的玩家（"" 组）基数 0，排在所有服的后面。
     */
    Map<UUID, Integer> computeOrders(final Configuration config) {
        // 1. 按子服分组（还没进任何服的算 "" 组）
        final Map<String, List<Player>> byServer = new LinkedHashMap<>();
        for (final Player player : proxy.getAllPlayers()) {
            final String server = player.getCurrentServer()
                    .map(s -> s.getServerInfo().getName())
                    .orElse("")
                    .toLowerCase(Locale.ROOT);
            byServer.computeIfAbsent(server, k -> new ArrayList<>()).add(player);
        }

        // 2. 基数 = 人数降序排名的倒转（人数降序排名 rank：0 = 人最多 →
        //    基数 = 服数-rank 最大）。"" 组（连接中）钉在最后 → 基数 0。
        final List<String> ranked = new ArrayList<>(byServer.keySet());
        ranked.remove("");
        ranked.sort(Comparator
                .comparingInt((String s) -> -byServer.get(s).size())
                .thenComparing(s -> s));
        final int n = ranked.size();
        final Map<String, Integer> base = new HashMap<>();
        for (int i = 0; i < n; i++) {
            base.put(ranked.get(i), Math.min(n - i, PRIORITY_CAP));
        }
        if (byServer.containsKey("")) {
            base.put("", 0);
        }

        // 3. 组内排序 + 拼顺序值（idx 越小值越大：RANK_CAP-idx 镜像）
        final Comparator<Player> cmp = playerComparator(config);
        final Map<UUID, Integer> out = new HashMap<>();
        for (final Map.Entry<String, List<Player>> group : byServer.entrySet()) {
            final List<Player> players = group.getValue();
            players.sort(cmp);
            final int b = base.get(group.getKey());
            for (int i = 0; i < players.size(); i++) {
                out.put(players.get(i).getUniqueId(),
                        b * 1_000_000 + (RANK_CAP - Math.min(i, RANK_CAP)));
            }
        }
        return out;
    }
}
