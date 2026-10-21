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

    private final ProxyServer proxy;
    private final Sorter sorter;
    private final SkinCache skins;
    /**
     * 上一轮实际用掉的槽位数。清理残留假条目时按「本轮和上一轮的较大值」扫，
     * 保证网格缩小时不留尾巴；初值取 FAKE_RANGE，顺便覆盖上任实例的可能残留。
     */
    private volatile int lastSlotCount = FAKE_RANGE;
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
     */
    private final Set<UUID> standins = ConcurrentHashMap.newKeySet();

    LayoutManager(final ProxyServer proxy, final Sorter sorter, final SkinCache skins) {
        this.proxy = proxy;
        this.sorter = sorter;
        this.skins = skins;
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
        // 1) 玩家按子服分组；还没进任何服的（连接中）稍后隐藏
        final Map<String, List<TabUser>> byServer = new LinkedHashMap<>();
        final List<Player> noServer = new ArrayList<>();
        for (final Player p : proxy.getAllPlayers()) {
            final String s = serverOf(p);
            if (s.isEmpty()) {
                noServer.add(p);
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
            secs.add(new Sec(s, cfg.serverDisplayName(s), ps));
        }
        // 人数降序（平手按服名升序）——这就是列的左右顺序
        secs.sort(Comparator.comparingInt((Sec s) -> -s.count()).thenComparing(s -> s.server));

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
            // 列头：#count# 恒为该子服总人数（续列的列头也一样，"列名不变"）
            slots.add(new LayoutModel.Slot(null, textComponent(cfg.headerFormat()
                    .replace("#name#", sec.display)
                    .replace("#count#", String.valueOf(sec.count()))), null));
            placed++;
            for (int k = part.from(); k < part.to(); k++) {
                final TabUser u = sec.players.get(k);
                if (u.virtual()) {
                    // demo 虚拟玩家：走假条目路径，显示名 = 名字（和真人观感一致）
                    slots.add(new LayoutModel.Slot(null, Component.text(u.name()), u.name()));
                } else {
                    slots.add(new LayoutModel.Slot(u.uuid(), null, u.name()));
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

    // ------------------------------------------------------------------
    // 应用到 viewer（全 diff：没变化不发一个包）
    // ------------------------------------------------------------------

    void applyAll(final LayoutModel model, final Configuration cfg) {
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
                if (entry != null) {
                    // 真条目在：它自己（或我们补的那条）占着这格，补位记录清掉
                    standins.remove(slot.player);
                    removeFake(tabList, fakeId); // 老版本可能在这格留了占位假条目
                    applySlotState(entry, order, cfg.showHat());
                } else {
                    removeFake(tabList, fakeId);
                    // 🔴 真条目还没建出来（其它子服的玩家 / 事件竞态）：
                    //    优先用**他自己的 profile**（真 UUID + 真皮肤）补一条，
                    //    客户端看到的就是本人；实在拿不到（人刚好下线）才退化成
                    //    同名假条目占位 —— 那格不补的话总条目少 1，客户端折行
                    //    重算会把整列挤歪。
                    if (!addStandIn(viewer, tabList, slot.player, slot.name, order)) {
                        final Component placeholder =
                                Component.text(slot.name == null ? "" : slot.name);
                        addFake(tabList, i, order, placeholder, cfg);
                    }
                }
            } else {
                final TabListEntry entry = tabList.getEntry(fakeId).orElse(null);
                if (entry == null) {
                    addFake(tabList, i, order, slot.display, cfg);
                } else {
                    if (!Objects.equals(entry.getDisplayNameComponent().orElse(null), slot.display)) {
                        setDisplay(entry, slot.display);
                    }
                    applySlotState(entry, order, cfg.showHat());
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
        if (showHat && !entry.isShowHat()) {
            setShowHat(entry, true);
        }
    }

    /**
     * 用真人自己的 profile 补一条 Tab 条目（真 UUID + 真皮肤）。
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
    private boolean addStandIn(final Player viewer, final TabList tabList, final UUID uuid,
                               final String name, final int order) {
        final Player target = proxy.getPlayer(uuid).orElse(null);
        if (target == null) {
            return false; // 人刚好下线：下一轮网格自己会收掉这格
        }
        try {
            final TabListEntry entry = TabListEntry.builder()
                    .tabList(tabList)
                    .profile(target.getGameProfile())
                    .displayName(Component.text(name == null ? target.getUsername() : name))
                    .latency((int) Math.round(target.getPing()))
                    .gameMode(0)
                    .listed(true)
                    .listOrder(order)
                    .showHat(cfg.showHat())
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
                         final Component display, final Configuration cfg) {
        try {
            final TabListEntry entry = TabListEntry.builder()
                    .tabList(tabList)
                    .profile(fakeProfile(slot))
                    .displayName(display)
                    .latency(cfg.blankPing())
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
        for (final Player viewer : proxy.getAllPlayers()) {
            stripViewerFakes(viewer);
            for (final Player p : proxy.getAllPlayers()) {
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

    /** 槽位 → 假条目 UUID：确定性生成，跨重算稳定（避免拔了又加的包抖动）。 */
    static UUID fakeUuid(final int slot) {
        return UUID.nameUUIDFromBytes(("vtabsort-slot-" + slot).getBytes(StandardCharsets.UTF_8));
    }

    private GameProfile fakeProfile(final int slot) {
        final SkinCache.Skin skin = skins.current();
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

    private static void setDisplay(final TabListEntry entry, final Component value) {
        try {
            entry.setDisplayName(value);
        } catch (final Exception ignored) {
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
