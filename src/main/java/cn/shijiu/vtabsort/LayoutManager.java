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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * layout 模式引擎：把所有子服的玩家排进固定网格（列头 = 子服名，假人补满）。
 *
 * <p>客户端折行规则（1.19.3+ 实测）：列数 = ceil(总条目 ÷ 行数上限)，
 * 行数 = ceil(总条目 ÷ 列数)。所以网格行数自适应：
 * <pre>
 *   natural = 最高的那列内容高度（头 + 玩家 + 分节空行）
 *   rMin    = 让客户端开出 C 列的最小行数（4 列 ≥ 16 行）
 *   行数 R  = max(natural, rMin)
 * </pre>
 * 总条目恒等于 C×R，客户端就渲染出精确的 C 列 × R 行。
 *
 * <p><b>v3 分列规则</b>（不再有服主排的 order，也不再折叠）：
 * <ol>
 *   <li><b>0 人的子服整个不出现</b>（连列头都没有），因此也不会有空列；
 *       网格随人数变化收缩/扩张；</li>
 *   <li>子服按<b>人数降序</b>排（平手按服名升序），列的左右顺序就是这个顺序；</li>
 *   <li>一个子服装不下一列（人数 &gt; rows-1）就<b>续开一列</b>，
 *       续的列紧跟在它后面、列头文字完全一样（含总人数）；</li>
 *   <li>列预算（max-columns）不够时，排在后面的小服<b>并进共享列</b>
 *       （分节按人数降序流式往下排，分节间固定空一格；列头落在行末就换新列，
 *       一个服放不完就续到下一列并重复列头），共享列永远在<b>最后</b>；</li>
 *   <li>共享列塞不下也不截断、不折叠 —— 允许溢出（网格变形但不丢人）。</li>
 * </ol>
 */
final class LayoutManager {

    /**
     * 假条目槽位扫描半径。rows/max-columns 改小、或自适应行数缩短后，
     * 超出当前网格的旧假条目要按这个半径清掉（UUID 确定，扫一圈很便宜）。
     */
    private static final int FAKE_RANGE = 512;

    /** {@link #fakeUuid} 的缓存（下标 = 槽位号）。纯函数结果，只加速不改变语义。 */
    private static final UUID[] FAKE_IDS = new UUID[FAKE_RANGE];

    private final ProxyServer proxy;
    private final Sorter sorter;
    private final SkinCache skins;
    private final org.slf4j.Logger logger;
    /**
     * 上一轮实际用掉的槽位数。清理残留假条目时按「本轮和上一轮的较大值」扫，
     * 保证网格缩小时不留尾巴；初值取 FAKE_RANGE，顺便覆盖上任实例的可能残留。
     */
    private volatile int lastSlotCount = FAKE_RANGE;
    /** 上一轮网格形状（列数/行数）。一变就整体重建，见 {@link #applyAll}。 */
    private volatile int lastColumns = -1;
    private volatile int lastRows = -1;
    /**
     * 我们替真人补建的条目（真 UUID + 真人 profile）。
     *
     * <p>🔴 没有 TAB 时，客户端的 Tab 列表里**只有同一个后端服的玩家**
     * （Velocity 不合成全局列表，条目是后端发的）。其它子服的真人没有条目 →
     * 网格缺一格 → 整列错位（v3.0.1 的老问题）。以前补的是"占位皮肤的假条目"，
     * 于是别人看他就变成占位皮肤了；现在改成**用他自己的 GameProfile 补**
     * —— 真 UUID + 真皮肤，客户端看到的和真人一模一样，而且以后端/代理
     * 真把它建出来时是同一个 UUID，不会变成两条。
     *
     * <p>这个 set 只用来在玩家下线时收尾（见 {@link #forget}）。
     *
     * <p>⚠️ 粒度注意：集合是<b>全局</b>的（UUID 一份），而条目是<b>每个 viewer 一份</b>。
     * {@code applyViewer} 里第一个 viewer 发现"真条目已经在了"就会把这个 UUID 移出集合，
     * 之后其它 viewer 里我们补的那条就没标记了 —— 那人掉线时 {@code forget} 收不到。
     * 所以这里只是"尽力早收"，**真正的兜底是 {@link #audit} 和
     * FlatManager#purgeGhosts**（按「UUID 不在线」清）。别把本集合当作准确性依据。
     */
    private final Set<UUID> standins = ConcurrentHashMap.newKeySet();

    /** 「还没进子服」那组的内部键（空串；显示名走 {@code [servers.names].loading}）。 */
    private static final String LOADING_KEY = "";

    /**
     * 每个玩家最后一次**已知**的延迟（毫秒）。
     *
     * <p>🔴 {@code Player#getPing()} 在延迟还没测出来时返回 <b>-1</b>（Velocity 的
     * 「未知」约定）。而我们把这个值原样写进 TabListEntry 时，客户端会因为
     * {@code latency < 0} 画成「无信号」——就是玩家名字右边那个**红叉**。
     * 刚进服 / 刚切服的那几秒最容易撞上，而且我们补建的条目以前从不刷新延迟，
     * 一旦写进 -1 就永远挂着红叉。
     *
     * <p>所以这里缓存最后一个有效值：拿不到就用它顶，一个都没有才用 0。
     */
    private final Map<UUID, Integer> lastPing = new ConcurrentHashMap<>();

    /**
     * 每个 viewer 上一次「清掉多余条目」告警的时间（毫秒）。
     *
     * <p>有插件持续往后端发条目时，{@code audit} 每轮都会删、每轮都想 WARN
     * （reassert 默认 3 秒一轮）→ 日志洪水。这里做一分钟限流。
     */
    private final Map<UUID, Long> auditWarnedAt = new ConcurrentHashMap<>();
    /** 同一个人多久最多提醒一次「清掉多余条目」。 */
    private static final long AUDIT_WARN_INTERVAL_MS = 60_000L;

    LayoutManager(final ProxyServer proxy, final Sorter sorter, final SkinCache skins,
                  final org.slf4j.Logger logger) {
        this.proxy = proxy;
        this.sorter = sorter;
        this.skins = skins;
        this.logger = logger;
    }

    // ------------------------------------------------------------------
    // 建模
    // ------------------------------------------------------------------

    LayoutModel build(final Configuration cfg) {
        return build(cfg, null);
    }

    /**
     * @param demo demo 虚拟玩家（服名 → 身份列表）；null/空 = 没开模拟。
     *             虚拟玩家与真人混进同一套分节/排序/填槽逻辑，走的全是真实代码路径。
     */
    LayoutModel build(final Configuration cfg, final Map<String, List<TabUser>> demo) {
        // 1) 玩家按子服分组；还没进任何服的（连接中）按 hide-loading 决定藏还是单独一节
        final Map<String, List<TabUser>> byServer = new LinkedHashMap<>();
        final List<Player> noServer = new ArrayList<>();
        for (final Player p : proxy.getAllPlayers()) {
            final String s = serverOf(p);
            if (s.isEmpty()) {
                if (cfg.hideLoading()) {
                    noServer.add(p);
                    continue;
                }
                // hide-loading=false：塞进 "" 组，单开一节「加载中」放在最后
                byServer.computeIfAbsent(LOADING_KEY, k -> new ArrayList<>())
                        .add(new TabUser(p.getUniqueId(), p.getUsername(),
                                sorter.joinSeqOf(p.getUniqueId())));
                continue;
            }
            byServer.computeIfAbsent(s, k -> new ArrayList<>())
                    .add(new TabUser(p.getUniqueId(), p.getUsername(), sorter.joinSeqOf(p.getUniqueId())));
        }
        // 1.5) demo 虚拟玩家注入（uuid=null，渲染走假条目路径）
        //      0 人的服整段跳过 —— 否则会多出一个空列头，和「0 人服不出现」的规矩冲突
        if (demo != null) {
            for (final Map.Entry<String, List<TabUser>> e : demo.entrySet()) {
                if (e.getValue().isEmpty()) {
                    continue;
                }
                byServer.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
            }
        }

        // 2) 子服清单：**只要有人在线的**。0 人的子服整个不出现（连列头都没有），
        //    所以也不会有空列 —— 网格会随着人走空而收缩，这是服主定的规矩。
        //    [servers.names] 此时只负责提供显示名 + demo 的玩家池，不再决定谁占分节。
        final List<String> servers = new ArrayList<>();
        for (final Map.Entry<String, List<TabUser>> e : byServer.entrySet()) {
            if (!e.getValue().isEmpty()) {
                servers.add(e.getKey());
            }
        }
        Collections.sort(servers); // 先按服名排，给下面的人数平手兜一个稳定序

        final Comparator<TabUser> pc = sorter.tabUserComparator(cfg);
        final List<Sec> secs = new ArrayList<>();
        for (final String s : servers) {
            final List<TabUser> ps = new ArrayList<>(byServer.get(s));
            ps.sort(pc);
            // 加载中那一节用 [servers.names].loading 的文案（默认「加载中」）
            secs.add(new Sec(s, s.isEmpty() ? cfg.loadingDisplayName() : cfg.serverDisplayName(s), ps));
        }
        // 人数降序（平手按服名升序）——这就是列的左右顺序。
        // 🔴 「加载中」那一节（server == ""）强制排最后：它的键是空串，字典序比谁
        //    都小，人数一多就会飘到**第一列**，跟注释里说的"放最后"正好相反
        //    （2026-10-24 review 发现）。用一个排不出正常服名的大字符顶替排序键。
        secs.sort(Comparator.comparingInt((Sec s) -> -s.count())
                .thenComparing(s -> s.server.isEmpty() ? "\uffff" : s.server));

        // 3) 分列（含同一子服跨多列的续列 + 尾部共享列）
        final int cap = Math.max(2, cfg.rows());
        final Plan plan = allocate(secs, Math.max(1, cfg.maxColumns()), cap, cfg.separator());
        final List<Col> cols = plan.cols();

        // 4) 自适应行数
        final int c = Math.max(1, cols.size());
        int natural = 0;
        for (final Col col : cols) {
            natural = Math.max(natural, col.height(plan.separator()));
        }
        // ceil((cap*(c-1)+1) / c)：客户端开出 c 列的最小行数
        final int rMin = (cap * (c - 1) + 1 + c - 1) / c;
        final int r = Math.max(Math.max(natural, rMin), 1);

        // 5) 填槽（列主序，槽位号 = 建模顺序，即期望的显示顺序）
        //    🔴 客户端对 listOrder 是**降序**排（官方 1.21.2 changelog：
        //    "sorted highest to lowest"，值越大越靠前）——所以条目的顺序值
        //    一律取镜像：order = 总槽数-1-槽位号。demo 实测镜像过一次才定案。
        final List<LayoutModel.Slot> slots = new ArrayList<>();
        final Map<UUID, Integer> orders = new HashMap<>();
        final List<LayoutModel.ColumnDesc> colDescs = new ArrayList<>();
        for (final Col col : cols) {
            fillColumn(col, r, plan.separator(), cfg, slots, colDescs);
        }
        final int total = slots.size();
        for (int i = 0; i < total; i++) {
            final UUID u = slots.get(i).player;
            if (u != null) {
                orders.put(u, total - 1 - i);
            }
        }

        // 6) 还没进服的（连接中）藏起来——不然它们会以原版顺序挤进网格把折行挤歪
        final Set<UUID> hidden = new LinkedHashSet<>();
        for (final Player p : noServer) {
            hidden.add(p.getUniqueId());
        }
        // 🔴 客户端硬上限：PlayerTabOverlay#getPlayerInfos 里 `.limit(80)`，
        //    超过 80 条的会被直接丢掉 → 网格只剩前 80 格，后面整段错位。
        //    ⚠️ 这里不 WARN：每轮都会 build，打日志会刷屏。超限的处理和提示
        //    统一在 VTabSort（auto 自动退回 flat / 显式 layout 提示调小），
        //    见 VTabSort#decide 和 #warnOverflowOnce。
        return new LayoutModel(slots, orders, hidden, colDescs, r, !plan.fitted());
    }

    /**
     * 列预算分配。secs 已按人数降序排好。
     *
     * <p>每列一行要给列头，所以一列能放 cap-1 个人；人更多的子服按这个
     * 容量续开列（续列紧跟其后、列头文字不变）。列预算不够时，排在后面的
     * 小服并进共享列（永远在最后）。
     *
     * <p>分节空行不再自动牺牲：共享列里「上一个服」和「下一个列头」之间
     * 一定隔一格（服主定的规矩），只有配置 separator=false 才不空。
     */
    private Plan allocate(final List<Sec> secs, final int max, final int cap,
                          final boolean separator) {
        if (secs.isEmpty()) {
            return new Plan(List.of(), separator, true);
        }
        return tryAllocate(secs, max, cap, separator);
    }

    /**
     * 一趟分配：前 k 个子服独占列（人多的自己续列），其余并进尾部的共享列。
     * k 从大到小试 —— 优先让更多子服独占（更贴合「按人数降序分列」），
     * 第一个能塞进 max 列的就是答案。
     *
     * <p>全都塞不下（内容真的超过 max 列 × cap 行）：k=0 全共享兜底，
     * **守住行数上限、让列数溢出** —— 行数是客户端硬约束（超了就被折成
     * 别的形状），列预算只是服主的期望值，两者只能保一个。
     */
    private Plan tryAllocate(final List<Sec> secs, final int max, final int cap,
                             final boolean separator) {
        final int perColumn = Math.max(1, cap - 1); // 一列能放的玩家数（扣掉列头）
        List<Col> allShared = null; // k=0 那趟的结果，兜底直接复用（省一次重算）
        for (int k = secs.size(); k >= 0; k--) {
            final List<Col> cols = new ArrayList<>();
            for (final Sec s : secs.subList(0, k)) {
                cols.addAll(spill(s, need(s.count(), perColumn)));
            }
            final List<Sec> tail = secs.subList(k, secs.size());
            if (!tail.isEmpty()) {
                final List<Col> shared = packShared(tail, cap, separator);
                if (k == 0) {
                    allShared = shared;
                }
                cols.addAll(shared);
            }
            if (cols.size() <= max) {
                return new Plan(cols, separator, true);
            }
        }
        // 只有一个服时它不算「共享」，照独占续列的规矩均分（不流式挤成 19/19/…/5）
        final List<Col> fallback = secs.size() == 1
                ? spill(secs.get(0), need(secs.get(0).count(), perColumn))
                : allShared;
        return new Plan(fallback, separator, false);
    }

    /**
     * 把共享的子服按顺序<b>流式</b>装进若干列，每列高度 ≤ cap：
     * <ul>
     *   <li>同一列里，下一个服的列头和上一个服之间空一格（separator 开时）；</li>
     *   <li>空完这一格后剩不到 2 行（列头会落在行末、底下放不了人）→ 换新列，
     *       新列顶上直接是列头，不再空格；</li>
     *   <li>一个服在当前列放不完就接着续到下一列，续的那段重复列头（文字不变）。</li>
     * </ul>
     */
    private List<Col> packShared(final List<Sec> shared, final int cap, final boolean separator) {
        final List<List<Part>> raw = new ArrayList<>();
        final Map<Sec, Integer> partsOf = new HashMap<>();
        List<Part> cur = new ArrayList<>();
        int used = 0;
        for (final Sec s : shared) {
            int at = 0;
            while (at < s.count()) {
                int gap = !cur.isEmpty() && separator ? 1 : 0;
                // 列头 + 至少 1 个人要 2 行；不够 = 列头会落在行末，开新列
                if (cap - used - gap < 2 && !cur.isEmpty()) {
                    raw.add(cur);
                    cur = new ArrayList<>();
                    used = 0;
                    gap = 0;
                }
                final int take = Math.min(Math.max(1, cap - used - gap - 1), s.count() - at);
                final int no = partsOf.merge(s, 1, Integer::sum);
                cur.add(new Part(s, at, at + take, no, 0));
                used += gap + 1 + take;
                at += take;
            }
        }
        if (!cur.isEmpty()) {
            raw.add(cur);
        }
        // 第二趟补上每个服一共分了几段（part/parts 给 /vts layout 看）
        final List<Col> cols = new ArrayList<>(raw.size());
        for (final List<Part> ps : raw) {
            final List<Part> fixed = new ArrayList<>(ps.size());
            for (final Part p : ps) {
                fixed.add(new Part(p.sec(), p.from(), p.to(), p.part(), partsOf.get(p.sec())));
            }
            cols.add(new Col(List.copyOf(fixed)));
        }
        return cols;
    }

    /** 一个子服摊成 n 列：人数均分（余数给前面的列），每列一个 Part。 */
    private static List<Col> spill(final Sec sec, final int n) {
        final List<Col> out = new ArrayList<>(n);
        final int base = sec.count() / n;
        final int rem = sec.count() % n;
        int at = 0;
        for (int i = 0; i < n; i++) {
            final int take = base + (i < rem ? 1 : 0);
            out.add(new Col(List.of(new Part(sec, at, at + take, i + 1, n))));
            at += take;
        }
        return out;
    }

    /** n 个人按每列 perColumn 个，需要几列（至少 1，空服也要有列头）。 */
    private static int need(final int n, final int perColumn) {
        return Math.max(1, (n + perColumn - 1) / perColumn);
    }

    /** 把一列填成恰好 rows 高：分节头、玩家、分节空行、假人补位。 */
    private void fillColumn(final Col col, final int rows, final boolean separator,
                            final Configuration cfg,
                            final List<LayoutModel.Slot> slots,
                            final List<LayoutModel.ColumnDesc> colDescs) {
        final List<LayoutModel.SectionDesc> sds = new ArrayList<>();
        int placed = 0;
        for (int i = 0; i < col.parts().size(); i++) {
            final Part part = col.parts().get(i);
            final Sec sec = part.sec();
            // 列头：#count# 恒为该子服总人数（续列的列头也一样，"列名不变"）。
            // 🔴 server 字段带上：列头要用**这个服的列头头像**皮肤（可单独配）。
            // 列头统一用 header-format；「加载中」那一节只是 #name# 换成
            // [servers.names].loading 的显示名，写法不单独配（多余的开关）。
            slots.add(new LayoutModel.Slot(null, textComponent(cfg.headerFormat()
                    .replace("#name#", sec.display)
                    .replace("#count#", String.valueOf(sec.count()))), null, sec.server));
            placed++;
            for (int k = part.from(); k < part.to(); k++) {
                final TabUser u = sec.players.get(k);
                // 玩家前缀（[layout].prefix-format）。没配 = null = 不碰显示名
                final Component prefixed = playerDisplay(cfg, sec, u.name());
                if (u.virtual()) {
                    // demo 虚拟玩家：走假条目路径，显示名 = 名字（配了前缀就一起带上，
                    // 和真人的观感一致）
                    slots.add(new LayoutModel.Slot(null,
                            prefixed != null ? prefixed : Component.text(u.name()), u.name()));
                } else {
                    slots.add(new LayoutModel.Slot(u.uuid(), prefixed, u.name()));
                }
                placed++;
            }
            sds.add(new LayoutModel.SectionDesc(sec.server, sec.display, sec.count(),
                    part.to() - part.from(), part.part(), part.parts()));
            if (i < col.parts().size() - 1 && separator) {
                slots.add(new LayoutModel.Slot(null, Component.empty(), null));
                placed++;
            }
        }
        // 假人补满到整列高度（placed > rows 只会发生在共享列溢出，那时不补）
        for (int i = placed; i < rows; i++) {
            slots.add(new LayoutModel.Slot(null, Component.empty(), null));
        }
        colDescs.add(new LayoutModel.ColumnDesc(sds));
    }

    /**
     * layout 模式下玩家名字的显示组件（{@code [layout].prefix-format}）。
     *
     * @param sec 这个人所在的子服分节（{@code #name#} 换成它的显示名）
     * @return <b>null = 没配前缀</b>，调用方据此「不碰显示名」（归 TAB / 原版管）。
     *         这是单写者铁律：TAB 也写 tablist-name-formatting 的话，两边同时写
     *         会让玩家看到前缀来回闪。
     */
    private static Component playerDisplay(final Configuration cfg, final Sec sec, final String name) {
        final String format = cfg.layoutPrefix();
        if (format.isEmpty()) {
            return null;
        }
        return Colors.prefixed(format.replace("#name#", sec.display), name);
    }

    // ------------------------------------------------------------------
    // 应用到 viewer（全 diff：没变化不发一个包）
    // ------------------------------------------------------------------

    /**
     * 显示名守卫（每 0.5 秒一次，只写不一致的，不动顺序、不建条目）。
     *
     * <p>🔴 为什么需要（2026-10-24 服主截图：一列里有两三个人名字前少一个空格）：
     * 显示名是<b>单写者字段</b>，而"别人"不只是 TAB —— 后端（Paper 自己的 tab 名
     * 同步）、PlayerTitle、CMI 这类插件在推玩家信息时都会把自己的显示名重新发
     * 一遍，我们的前缀就被顶掉了。周期性校准（reassert）默认 3 秒一轮，空窗期
     * 足够被截图逮到几个漏网的。
     *
     * <p>这里做最便宜的检查：槽位里有真人、本轮该有显示名 → 和条目现值比，
     * 不一样就写回去。几十个人也就几千次对象比较，比整轮重建便宜得多。
     */
    void guardNames(final LayoutModel model, final Configuration cfg) {
        for (final Player viewer : proxy.getAllPlayers()) {
            final TabList tabList = viewer.getTabList();
            for (final LayoutModel.Slot slot : model.slots) {
                if (slot.player == null || slot.display == null) {
                    continue;
                }
                final TabListEntry entry = tabList.getEntry(slot.player).orElse(null);
                if (entry == null
                        || Objects.equals(entry.getDisplayNameComponent().orElse(null), slot.display)) {
                    continue;
                }
                // 静默写回：这条会每 500ms 跟后端/别的插件"抢"显示名，属于正常
                // 拉锯，不刷日志。要排查是谁在抢就临时把它调成 Info。
                setDisplay(entry, slot.display);
            }
        }
    }

    void applyAll(final LayoutModel model, final Configuration cfg) {
        // 🔴 网格「形状」变了就整体重建。逐格 diff 在换形状时容易漏下没人认领的
        //    假条目（槽位号一移位就没人清），而客户端是**按条目总数**推网格形状的
        //    （见 audit）——多一条整张表就错位一格。整体拔掉重发最省心：几十条，
        //    代价可控，换来的是「形状永远精确等于 C×R」。
        if (model.columnCount != lastColumns || model.rows != lastRows) {
            stripAll();
            lastColumns = model.columnCount;
            lastRows = model.rows;
        }
        // 清理上界取「本轮和上一轮槽位数」的较大值：网格缩短（人变少 / 配置改小）
        // 后，超出当前网格的旧假条目要按这个半径清掉。
        // 🔴 不能写死常数：demo 塞几百个虚拟玩家、或把 rows 调小时，槽位数能到
        // 几千，写死就清不干净（旧假条目永远杵在 Tab 里）。
        final int cleanTo = Math.max(model.slots.size(), lastSlotCount);
        for (final Player viewer : proxy.getAllPlayers()) {
            applyViewer(viewer, model, cfg, cleanTo);
        }
        lastSlotCount = model.slots.size();
    }

    void applyViewer(final Player viewer, final LayoutModel model, final Configuration cfg,
                     final int cleanTo) {
        final TabList tabList = viewer.getTabList();
        // 🔴 客户端 listOrder 降序（值越大越靠前）：显示顺序 = 建模顺序，
        //    顺序值 = 总槽数-1-槽位号（见 build 第 5 步）。
        final int total = model.slots.size();
        // 1) 网格槽位
        for (int i = 0; i < total; i++) {
            final int order = total - 1 - i;
            final LayoutModel.Slot slot = model.slots.get(i);
            final UUID fakeId = fakeUuid(i);
            if (slot.player != null) {
                final TabListEntry entry = tabList.getEntry(slot.player).orElse(null);
                // 玩家前缀：null = 没配（不动显示名）——见 playerDisplay
                final Component wantName = slot.display;
                if (entry != null) {
                    // 真条目在：它自己（或我们补的那条）占着这格，补位记录清掉
                    standins.remove(slot.player);
                    removeFake(tabList, fakeId); // 老版本可能在这格留了占位假条目
                    applySlotState(entry, order, cfg.showHat());
                    if (wantName != null
                            && !Objects.equals(entry.getDisplayNameComponent().orElse(null), wantName)) {
                        setDisplay(entry, wantName);
                    }
                    applyPing(viewer, entry, slot.player);
                } else {
                    removeFake(tabList, fakeId);
                    // 🔴 真条目还没建出来（其它子服的玩家 / 事件竞态）：
                    //    优先用**他自己的 profile**（真 UUID + 真皮肤）补一条，
                    //    客户端看到的就是本人；实在拿不到（人刚好下线）才退化成
                    //    同名假条目占位 —— 那格不补的话总条目少 1，客户端折行
                    //    重算会把整列挤歪。
                    final Component standInName = wantName != null
                            ? wantName : Component.text(slot.name == null ? "" : slot.name);
                    if (!addStandIn(viewer, tabList, slot.player, standInName, order,
                            cfg.showHat())) {
                        addFake(tabList, i, order, standInName, cfg, null, cfg.blankPing());
                    }
                }
            } else {
                // 列头槽（server != null）用 [layout].header-ping；其余假条目
                // （demo 虚拟玩家 / 分节空行 / 补满的空白格）用 blank-ping。
                final int wantPing = slot.server != null ? cfg.headerPing() : cfg.blankPing();
                final TabListEntry entry = tabList.getEntry(fakeId).orElse(null);
                // 🔴 列头会换服：人数一变，列的左右顺序就变，同一个槽位下标可能
                //    从「大厅的列头」变成「生存的列头」。显示名能 setDisplayName 改，
                //    皮肤改不了（TabListEntry 没有 setProfile）—— 只能拔了重建，
                //    不然列头头像会一直挂着上一个服的皮。
                if (entry == null || !skinMatches(entry, slot.server)) {
                    removeFake(tabList, fakeId);
                    // 列头的信号延迟单独配（[layout].header-ping），不跟填充假人共用
                    addFake(tabList, i, order, slot.display, cfg, slot.server, wantPing);
                } else {
                    if (!Objects.equals(entry.getDisplayNameComponent().orElse(null), slot.display)) {
                        setDisplay(entry, slot.display);
                    }
                    applySlotState(entry, order, cfg.showHat());
                    // 改 header-ping / blank-ping 后 reload 立即生效，不用等条目重建
                    applyFakePing(entry, wantPing);
                }
            }
        }
        // 2) 网格缩短后的残留假条目（自适应行数变化、配置改小、demo 收摊）
        for (int i = model.slots.size(); i < cleanTo; i++) {
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
        // 4) 对账：清掉「不该存在」的条目（多一条客户端就整体错位，见 audit 的注释）
        audit(viewer, model);
    }

    /**
     * 条目对账 —— 把客户端列表里「本轮不该有」的条目清掉。
     *
     * <p>🔴 为什么必须对账（26.2 客户端源码 `PlayerTabOverlay#extractRenderState`）：
     * <pre>
     *   int rows = slots = playerInfos.size();   // 只数它自己列表里的 listed 条目
     *   int cols = 1;
     *   while (rows &gt; 20) rows = (slots + ++cols - 1) / cols;
     *   col = i / rows; row = i % rows;          // 列主序
     * </pre>
     * 网格形状<b>完全由条目总数</b>推出来，插件这边算好的「C 列 × R 行」只有在
     * 总数正好等于 C×R 时才成立。只要客户端多一条（历史残留的假列头、早就下线
     * 但没被收掉的条目），rows 就会 +1，<b>整张网格重排</b>：列头跑到上一列末尾、
     * 后面每个列的内容整体错一格 —— 这就是「位置错误」。
     *
     * <p>清两类，都是「本来就不该在」的：
     * <ol>
     *   <li>我们自己的假条目（profile 名 {@code vts_sNNN}）但不在本轮槽位表里
     *       —— 老版本/上一轮布局的残留（槽位号变了就没人认领）；</li>
     *   <li>UUID 不是在线玩家、也不是本轮槽位的条目（下线的残影）。</li>
     * </ol>
     * 真实在线玩家的条目一律不动（归后端/代理管，我们只排序）。
     */
    private void audit(final Player viewer, final LayoutModel model) {
        final TabList tabList = viewer.getTabList();
        final Set<UUID> expected = new HashSet<>();
        for (int i = 0; i < model.slots.size(); i++) {
            final UUID player = model.slots.get(i).player;
            expected.add(player != null ? player : fakeUuid(i));
        }
        expected.addAll(model.hidden);
        int removed = 0;
        for (final TabListEntry entry : new ArrayList<>(tabList.getEntries())) {
            final UUID id = entry.getProfile().getId();
            if (expected.contains(id) || proxy.getPlayer(id).isPresent()) {
                continue; // 本轮要的 / 别人家的真人
            }
            try {
                tabList.removeEntry(id);
                removed++;
            } catch (final Exception ignored) {
            }
        }
        if (removed > 0) {
            // 限流：某个插件持续往后端发条目时，audit 会每轮都清、每轮都想 WARN
            // （reassert 默认 3 秒一轮）→ 一分钟内同一个 viewer 只说一次。
            final long now = System.currentTimeMillis();
            final Long last = auditWarnedAt.get(viewer.getUniqueId());
            if (last == null || now - last >= AUDIT_WARN_INTERVAL_MS) {
                auditWarnedAt.put(viewer.getUniqueId(), now);
                logger.warn("[vtabsort] " + viewer.getUsername() + " 的 Tab 里有 " + removed
                        + " 条多余条目（历史残留），已清掉；本轮网格 " + model.slots.size() + " 格");
            }
        }
    }

    /**
     * 延迟的显示值。
     *
     * <p>🔴 {@code getPing()} 在「还没测出来」时返回 <b>-1</b>，直接写进条目
     * 客户端会画成无信号图标（名字右边那个红叉）。拿不到就退回上一个已知值，
     * 一次都没有才用 0（0ms 在客户端是满格，至少不吓人）。
     */
    private int pingOf(final Player p) {
        final int raw = (int) Math.round(p.getPing());
        if (raw < 0) {
            return lastPing.getOrDefault(p.getUniqueId(), 0);
        }
        lastPing.put(p.getUniqueId(), raw);
        return raw;
    }

    /**
     * 把延迟同步到条目上 —— 但只碰「该我们管」的，避免和后端打架。
     *
     * <p>同一后端服的玩家，条目延迟由后端自己发（我们改也会被覆盖，还可能来回抖）；
     * 只有两种情况需要动手：① 当前值是负的（红叉，必须修）；② 对方在别的子服
     * —— 那种条目是本插件补的，没有别人会去更新它，所以永远停在刚建时的值。
     */
    private void applyPing(final Player viewer, final TabListEntry entry, final UUID uuid) {
        final Player target = proxy.getPlayer(uuid).orElse(null);
        if (target == null) {
            return;
        }
        final int want = pingOf(target);
        if (entry.getLatency() == want) {
            return;
        }
        final boolean sameServer = serverOf(target).equals(serverOf(viewer));
        if (sameServer && entry.getLatency() >= 0) {
            return; // 后端的值说了算
        }
        try {
            entry.setLatency(want);
        } catch (final Exception ignored) {
        }
    }

    /**
     * 假条目（列头 / demo 虚拟玩家 / 填充空白）的延迟校准。
     *
     * <p>假条目的延迟只有本插件会写，没有别人覆盖；但只要条目已存在，重建分支
     * 就不会走 —— 改 {@code header-ping} 后 reload 会看起来"没生效"。每轮比一下，
     * 不一样就推一次（{@code -1} = 无信号，客户端画红叉）。
     */
    private static void applyFakePing(final TabListEntry entry, final int ping) {
        if (entry.getLatency() == ping) {
            return;
        }
        try {
            entry.setLatency(ping);
        } catch (final Exception ignored) {
        }
    }

    /** 拔掉某一格上可能残留的占位假条目（换用真人 profile 之后要清）。 */
    private static void removeFake(final TabList tabList, final UUID fakeId) {
        if (tabList.containsEntry(fakeId)) {
            try {
                tabList.removeEntry(fakeId);
            } catch (final Exception ignored) {
            }
        }
    }

    /**
     * 槽位的公共状态：顺序 + 可见 + 双层皮肤。全 diff，没变化一个包都不发。
     *
     * <p>🔴 showHat 必须显式打开：1.21.4 起协议里多了一个「要不要渲染皮肤第二层」
     * 的字段，Velocity 建的条目默认 false —— 占位皮肤的猫耳（画在第二层）就
     * 全丢了。真人的条目也一并置 true（原版默认就是两层都显示）。
     */
    private static void applySlotState(final TabListEntry entry, final int order,
                                       final boolean showHat) {
        if (entry.getListOrder() != order) {
            setOrder(entry, order);
        }
        if (!entry.isListed()) {
            setListed(entry, true);
        }
        // 🔴 双向写：以前只处理 showHat=true 的方向，于是把配置改成 false 之后
        //    已存在的条目第二层（猫耳/帽子）会一直画到下一条网格重建，
        //    reload 看起来"没生效"（2026-10-24 review）。
        if (entry.isShowHat() != showHat) {
            setShowHat(entry, showHat);
        }
    }

    /**
     * 用真人自己的 profile 补一条 Tab 条目（真 UUID + 真皮肤），显示名由调用方给
     * （layout 带玩家前缀、flat 带子服前缀）。
     *
     * <p>🔴 为什么必须这么做：没装 TAB 时，客户端的 Tab 里只有「同一个后端服」
     * 的玩家（Velocity 不合成全局列表，条目都是后端发的）。其它子服的真人
     * 没有条目 → 网格缺一格 → 客户端折行重算 → 整列错位。以前补的是"占位皮肤
     * 的同名假条目"，结果别人看他全变成占位皮肤了。用真人 profile 补：
     * ①皮肤就是他自己的；②UUID 也是真的，后端/代理之后真把条目建出来时是
     * 同一个 UUID，不会变成两条重名。
     *
     * @return 补成功了没（false = 人已经不在了，调用方退化成假条目占位）
     */
    boolean addStandIn(final Player viewer, final TabList tabList, final UUID uuid,
                       final Component display, final int order, final boolean showHat) {
        final Player target = proxy.getPlayer(uuid).orElse(null);
        if (target == null) {
            return false; // 人刚好下线：下一轮自己会收掉这格
        }
        try {
            final TabListEntry entry = TabListEntry.builder()
                    .tabList(tabList)
                    .profile(target.getGameProfile())
                    .displayName(display == null ? Component.text(target.getUsername()) : display)
                    .latency(pingOf(target))
                    .gameMode(0)
                    .listed(true)
                    .listOrder(order)
                    .showHat(showHat)
                    .build();
            tabList.addEntry(entry);
            standins.add(uuid);
            return true;
        } catch (final Exception ignored) {
            return false; // 单个 viewer 出问题不影响别人
        }
    }

    /**
     * 玩家下线：把我们替他补的条目收掉。
     *
     * <p>🔴 只收「我们补的」：用真 UUID 建的条目，一旦后端自己建出来了就是
     * 同一个条目，那时 {@code standins} 里已经没有它了，不会被误删。
     */
    void forget(final UUID uuid) {
        lastPing.remove(uuid);
        auditWarnedAt.remove(uuid);
        if (!standins.remove(uuid)) {
            return;
        }
        for (final Player viewer : proxy.getAllPlayers()) {
            final TabList tabList = viewer.getTabList();
            if (tabList.containsEntry(uuid)) {
                try {
                    tabList.removeEntry(uuid);
                } catch (final Exception ignored) {
                }
            }
        }
    }

    private void addFake(final TabList tabList, final int slot, final int order,
                         final Component display, final Configuration cfg, final String server,
                         final int ping) {
        try {
            final TabListEntry entry = TabListEntry.builder()
                    .tabList(tabList)
                    .profile(fakeProfile(slot, server))
                    .displayName(display)
                    .latency(ping)
                    .gameMode(0)
                    .listed(true)
                    .listOrder(order)
                    .showHat(cfg.showHat())
                    .build();
            tabList.addEntry(entry);
        } catch (final Exception ignored) {
            // 单个假条目失败（极老客户端等）不影响其它槽
        }
    }

    /** 模式切走 / 清场：拔掉所有 viewer 的假条目。 */
    void stripAll() {
        for (final Player viewer : proxy.getAllPlayers()) {
            stripViewerFakes(viewer);
        }
    }

    /**
     * 热重载自愈：新实例起手清上一任的痕迹 —— 拔假条目 + 恢复所有真实玩家
     * 可见（上一任藏掉的人不能让它永远隐身到重登）。冷启动没玩家，等于免费；
     * 清完紧接着 reload→rebuild 就把新状态建回来了。
     *
     * <p>显示名刻意不重置：layout 模式下显示名归 TAB 管，乱清会把 TAB 的
     * 格式打回裸名（flat 前缀的残留由模式切换逻辑 / TAB 自己的事件愈合）。
     */
    void resetAll() {
        // 先把上任替真人补的条目收掉（UUID 是真人的，重开会按新配置再补一次）
        for (final UUID u : new ArrayList<>(standins)) {
            forget(u);
        }
        // 快照一次就够：内层循环每轮重取 getAllPlayers() 是 N² 次调用（review 后改）
        final List<Player> players = new ArrayList<>(proxy.getAllPlayers());
        for (final Player viewer : players) {
            stripViewerFakes(viewer);
            for (final Player p : players) {
                final TabListEntry entry = viewer.getTabList().getEntry(p.getUniqueId()).orElse(null);
                if (entry != null && !entry.isListed()) {
                    setListed(entry, true);
                }
            }
        }
    }

    private void stripViewerFakes(final Player viewer) {
        final TabList tabList = viewer.getTabList();
        final int to = Math.max(FAKE_RANGE, lastSlotCount);
        for (int i = 0; i < to; i++) {
            final UUID fakeId = fakeUuid(i);
            if (tabList.containsEntry(fakeId)) {
                tabList.removeEntry(fakeId);
            }
        }
    }

    /**
     * 槽位 → 假条目 UUID：确定性生成，跨重算稳定（避免拔了又加的包抖动）。
     *
     * <p>结果缓存起来：`UUID.nameUUIDFromBytes` 每次都是一次 MD5，而清理残留假条目
     * 要按 {@link #FAKE_RANGE} 扫一整圈（几十个 viewer × 几百格），每轮 rebuild 都现算
     * 纯属浪费。纯函数，缓存永远不会过期。
     */
    static UUID fakeUuid(final int slot) {
        if (slot >= 0 && slot < FAKE_IDS.length) {
            final UUID cached = FAKE_IDS[slot];
            if (cached != null) {
                return cached;
            }
        }
        final UUID id = UUID.nameUUIDFromBytes(
                ("vtabsort-slot-" + slot).getBytes(StandardCharsets.UTF_8));
        if (slot >= 0 && slot < FAKE_IDS.length) {
            FAKE_IDS[slot] = id;
        }
        return id;
    }

    /**
     * 假条目的 profile。
     *
     * @param server 列头槽位所属子服（null = 填充/占位槽，用占位皮肤）
     */
    private GameProfile fakeProfile(final int slot, final String server) {
        final SkinCache.Skin skin = server == null
                ? skins.get(SkinCache.DEFAULT_KEY)
                : skins.columnSkin(server);
        if (skin.value().isEmpty()) {
            return new GameProfile(fakeUuid(slot), slotName(slot), List.of());
        }
        // ⚠️ signature 不能传 null：GameProfile.Property 构造器 checkNotNull 会 NPE。
        //    空串是安全的 —— 不带签名的写法（哈希/URL/base64）在 SkinCache 归一化时
        //    已经把 signatureRequired 关掉了，客户端不会去校验（见 SkinCache.normalize）。
        final List<GameProfile.Property> props =
                List.of(new GameProfile.Property("textures", skin.value(), skin.signature()));
        return new GameProfile(fakeUuid(slot), slotName(slot), props);
    }

    /**
     * 这条已存在的假条目，皮肤是不是这一格**现在**该用的那张。
     *
     * <p>网格一重排（人数变了、列序变了），同一个槽位下标可能换了主人：
     * 上一轮是「大厅」的列头，这一轮成了「生存」的列头。皮肤是 profile 里的
     * 属性，改不了 —— 只能拔条目重建，所以这里做 profile 级 diff，
     * 不一样才重建（一样就一个包都不发）。
     */
    private boolean skinMatches(final TabListEntry entry, final String server) {
        final SkinCache.Skin want = server == null
                ? skins.get(SkinCache.DEFAULT_KEY)
                : skins.columnSkin(server);
        return want.value().equals(texturesOf(entry.getProfile()));
    }

    /** profile 里的 textures 属性值；没有就是空串。 */
    private static String texturesOf(final GameProfile profile) {
        for (final GameProfile.Property p : profile.getProperties()) {
            if ("textures".equals(p.getName())) {
                return p.getValue() == null ? "" : p.getValue();
            }
        }
        return "";
    }

    /** 假条目的 profile 名字：≤16 字符且互不相同；没配皮肤就是默认 Steve/Alex。 */
    private static String slotName(final int slot) {
        return String.format(Locale.ROOT, "vts_s%03d", slot);
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

    private static void setShowHat(final TabListEntry entry, final boolean value) {
        try {
            entry.setShowHat(value);
        } catch (final Exception ignored) {
        }
    }

    /**
     * 写显示名。
     *
     * @return 写成功了没 —— 条目已经被后端/代理收走时 setDisplayName 会抛，
     *         这时别当成"已修好"，下一轮守卫还会再取一遍
     */
    private static boolean setDisplay(final TabListEntry entry, final Component value) {
        try {
            entry.setDisplayName(value);
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    /** 一个子服分节：显示名 + 分好序的玩家（真人或 demo 虚拟玩家）列表。 */
    private static final class Sec {
        final String server;
        final String display;
        final List<TabUser> players;

        Sec(final String server, final String display, final List<TabUser> players) {
            this.server = server;
            this.display = display;
            this.players = players;
        }

        int count() {
            return players.size();
        }
    }

    /** 某一列里的一个分节片段：sec 的 [from, to) 这些人，第 part/parts 段。 */
    private record Part(Sec sec, int from, int to, int part, int parts) {
    }

    /**
     * 一次分配的结果。
     *
     * @param cols      物理列（左右顺序 = 人数降序，共享列在尾部）
     * @param separator 这一趟实际有没有用分节空行
     * @param fitted    有没有塞进 max-columns（false = 列数溢出了，行数守住）
     */
    private record Plan(List<Col> cols, boolean separator, boolean fitted) {
    }

    /** 一个物理列：一个片段（独占/续列）或多个片段（共享列）。 */
    private record Col(List<Part> parts) {

        /** 内容高度 = 每段（列头 + 人）+ 段间空行。 */
        int height(final boolean separator) {
            int h = 0;
            for (final Part p : parts) {
                h += 1 + (p.to() - p.from());
            }
            if (separator && parts.size() > 1) {
                h += parts.size() - 1;
            }
            return h;
        }
    }
}
