package cn.shijiu.vtabsort;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VTabSort —— Tab 列表展示（Velocity 代理端，零依赖，子服什么都不用装）。
 *
 * <p>三种模式（config.toml 的 [display].mode，reload 即切换）：
 *
 * <ul>
 *   <li><b>layout</b>：Hypixel 式固定网格。每列头顶子服名（在线数可配），
 *       假条目补满空槽；列的左右顺序按子服人数降序；一个子服装不下一列
 *       就续开一列（列头不变）；列预算（max-columns）不够时排在后面的
 *       小服并进最后一列。网格行数自适应（受「客户端开出 N 列的最小行数」
 *       约束，目标上限 rows）。</li>
 *   <li><b>flat</b>：普通列表。名字前缀子服名（格式可配），可选按人数降序排序。</li>
 *   <li><b>auto</b>：在线人数 ≤ rows（客户端拆第二列的阈值）时按 flat 来，
 *       超过就按 layout 来 —— 人少的时候不铺一屏假人，人多的时候才上网格。</li>
 * </ul>
 *
 * <p>两条不变式：①不碰 scoreboard team → 后端小游戏藏名字照常；
 * ②真实玩家的 tab 条目由 TAB（global playerlist）创建，本插件只调
 * listOrder / listed /（flat 模式）显示名，条目不存在的就等下一轮
 * （事件直刷 + refresh-delay-ms 延迟补发 + reassert 周期校准三层兜底）。
 */
@Plugin(
        id = "vtabsort",
        name = "VTabSort",
        // ⚠️ 版本号必须是编译期常量 —— 走 BuildConstants 间接引过来（templating-maven-plugin 生成）。
        // 🔴 别改回字面量：注解处理器会按这个值重写 velocity-plugin.json。
        version = BuildConstants.VERSION,
        description = "Tab 展示：layout 固定网格（子服列头 + 假人补位）/ flat 前缀列表 / auto 按人数自适应；listOrder 协议字段，不碰 scoreboard team。",
        authors = {"拾玖世界"}
)
public final class VTabSort {

    private final ProxyServer proxy;
    private final Logger logger;
    /** 上一轮 layout 实际用的模型（显示名守卫要按它比对，不重算）。 */
    private volatile LayoutModel lastModel;
    private final Path dataDirectory;
    private final Sorter sorter;
    private final SkinCache skins;
    private final LayoutManager layoutManager;
    private final FlatManager flatManager;
    private final HeaderFooter headerFooter;

    private volatile Configuration config;
    /** 上一次读到的 config.toml 修改时间，热重载用。 */
    private volatile long lastModified;
    /** 是否已有一个延迟刷新任务在排队（防抖：连发事件只补一发）。 */
    private final AtomicBoolean delayedRefreshPending = new AtomicBoolean(false);
    /**
     * 上一轮实际落地的模式（auto 会随在线人数翻面）。null = 还没刷过。
     * 用它判断「要不要清对家痕迹」，比在 reload 里比配置更准
     * —— auto 模式下配置没变但落地模式会变。
     */
    private final AtomicReference<Configuration.Mode> appliedMode = new AtomicReference<>();
    /**
     * {@code /vts mode} 切出来的运行时模式（null = 用 config.toml 里的）。
     *
     * <p>为什么要有它：写文件之后如果还等文件 mtime 触发重载，中间会有一瞬间
     * 用的还是旧模式；而且文件万一写不进去（只读/占用），命令也不该白敲。
     * 覆盖只在本次运行有效 —— {@link #reload} 会清掉它（文件才是唯一真相，
     * 命令成功时已经把新值写回文件了）。
     */
    private volatile Configuration.Mode modeOverride;
    /**
     * 上一轮是否已经就「超过客户端 80 条上限」提示过。
     *
     * <p>rebuild 每秒都在跑，超限状态在一条 log 里只需要说一次 —— 状态变化
     * （超限 ↔ 不超限）才打，否则日志会被刷满。
     */
    private volatile boolean overflowWarned;
    /** demo 虚拟玩家模拟器（/vts demo）。 */
    private final DemoSim demo = new DemoSim();
    /** 流动模拟的定时任务（/vts demo off 时取消）。 */
    private volatile ScheduledTask demoFlowTask;

    @Inject
    public VTabSort(final ProxyServer proxy, final Logger logger, @DataDirectory final Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.sorter = new Sorter(proxy);
        this.skins = new SkinCache(logger, dataDirectory); // 读磁盘缓存，起服就有皮肤
        this.layoutManager = new LayoutManager(proxy, sorter, skins, logger);
        this.flatManager = new FlatManager(proxy, sorter, layoutManager, logger);
        this.headerFooter = new HeaderFooter(proxy);
        this.config = Configuration.defaults();
    }

    @Subscribe
    public void onProxyInitialization(final ProxyInitializeEvent event) {
        // 热重载自愈：Velocity 没有「卸载回调」，上一任实例塞的假条目/藏掉的玩家
        // 只能由新实例起手清掉（冷启动没玩家，这步免费）。清完立刻 reload→rebuild 重建。
        layoutManager.resetAll();
        reload(null);
        registerCommands();
        startAutoReload();
        startReassert();
        startNameGuard();
        logger.info("[vtabsort] VTabSort 已就绪 —— " + describeMode(config));
    }

    // ------------------------------------------------------------------
    // 事件：任何影响布局的变化都全量重算（人数 < 几百，重算是毫秒级）
    // ------------------------------------------------------------------

    @Subscribe
    public void onPostLogin(final PostLoginEvent event) {
        sorter.trackJoin(event.getPlayer().getUniqueId());
        refreshAllSoon(); // 新 viewer 要补假条目，别人的网格里也多了这个人
    }

    @Subscribe
    public void onServerConnected(final ServerConnectedEvent event) {
        refreshAllSoon(); // 换列/换分节，行数也可能跟着变
    }

    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        sorter.forget(event.getPlayer().getUniqueId());
        headerFooter.forget(event.getPlayer().getUniqueId()); // 缓存的渲染结果跟着收走
        layoutManager.forget(event.getPlayer().getUniqueId()); // 替他补的 Tab 条目
        refreshAllSoon();
    }

    /** 立即刷一发 + 延迟补一发（兜 TAB 建条目的竞态），全 diff 无变化不发包。 */
    private void refreshAllSoon() {
        rebuildNow();
        final int delay = config.refreshDelayMs();
        if (delay <= 0 || !delayedRefreshPending.compareAndSet(false, true)) {
            return;
        }
        proxy.getScheduler().buildTask(this, () -> {
            delayedRefreshPending.set(false);
            rebuildNow();
        }).delay(delay, TimeUnit.MILLISECONDS).schedule();
    }

    /**
     * 重算并应用。
     *
     * <p>auto 模式每轮都按当前在线人数重新判定落地模式，所以模式可能在
     * 玩家进出的瞬间翻面 —— 翻面时要先把对家留下的痕迹清掉（假条目 /
     * 前缀显示名），否则 flat 列表里会残留假人、layout 网格里会残留前缀。
     */
    private void rebuildNow() {
        final Configuration cfg = config;
        final Decision decision = decide(cfg);
        final Configuration.Mode effective = decision.mode;
        final Configuration.Mode previous = appliedMode.getAndSet(effective);
        if (previous != null && previous != effective) {
            if (effective == Configuration.Mode.LAYOUT) {
                flatManager.resetDisplayNames(); // flat→layout：清前缀显示名
            } else {
                layoutManager.stripAll();         // layout→flat：拔全部假条目
            }
        }
        warnOverflowOnce(cfg, decision);
        if (effective == Configuration.Mode.LAYOUT) {
            layoutManager.applyAll(decision.model, cfg);
            lastModel = decision.model;
        } else {
            flatManager.applyAll(cfg);
            lastModel = null;
        }
        // header/footer 与展示模式无关：每轮都刷（%ping%/%online%/%server% 会变），
        // 内部全 diff —— 渲染结果没变就不发包。
        headerFooter.applyAll(cfg, demo.virtualCount(), demo.countsByServer());
    }

    // ------------------------------------------------------------------
    // 模式解析（config.toml + /vts mode 的运行时覆盖）
    // ------------------------------------------------------------------

    /** 在线人数（含 /vts demo 的虚拟玩家）—— auto 翻面判定和 %online% 用的是同一个数。 */
    private int online() {
        return proxy.getPlayerCount() + demo.virtualCount();
    }

    /** 配置里写的模式，被 {@code /vts mode} 覆盖时以覆盖为准。 */
    private Configuration.Mode configuredMode(final Configuration cfg) {
        final Configuration.Mode override = modeOverride;
        return override != null ? override : cfg.mode();
    }

    /** 真正落地的模式：auto 按在线人数翻面，其余原样。 */
    private Configuration.Mode effectiveMode(final Configuration cfg, final int onlineCount) {
        final Configuration.Mode mode = configuredMode(cfg);
        if (mode != Configuration.Mode.AUTO) {
            return mode;
        }
        // 阈值 + 客户端上限（80 条）的判定都在 autoLanding 里，见它的注释
        return Configuration.autoLanding(onlineCount, cfg.rows());
    }

    /**
     * 一轮的落地判定：模式 + （要走网格时）已经建好的模型。
     *
     * <p>为什么不能只看人数：客户端上限 80 是<b>条目数</b>，列头、分节空行、
     * 补满整列的空白假条目都算。4 列 × 20 行 = 80 格里，每列一个列头 → 真人位
     * 只有 76 个；5 个子服都有人时，第 77 个人进服就得开第 5 列（5 × 17 = 85 格）
     * —— 人数没到 80，网格却已经超了。所以 auto 在建完模型后还要再比一次格数。
     *
     * <p>显式配 layout 的人不弹（他要的就是网格），由 {@link #warnOverflowOnce} 提示。
     */
    private Decision decide(final Configuration cfg) {
        final Configuration.Mode mode = effectiveMode(cfg, online());
        if (mode != Configuration.Mode.LAYOUT) {
            return new Decision(mode, null);
        }
        final LayoutModel model = layoutManager.build(cfg, demo.snapshot());
        final boolean tooMany = model.slots.size() > Configuration.CLIENT_MAX_ENTRIES;
        if (tooMany && configuredMode(cfg) == Configuration.Mode.AUTO) {
            return new Decision(Configuration.Mode.FLAT, model); // 模型留着，日志/命令要说清超了多少
        }
        return new Decision(Configuration.Mode.LAYOUT, model);
    }

    /**
     * 超过客户端 80 条上限时提示一次（状态变化才打，别刷屏）。
     *
     * <p>auto 的情况已经自动退回 flat 了，这里只是把原因和数字讲清楚；
     * 显式 layout 的情况没有退路，得让服主知道会错位、该调哪个键。
     */
    private void warnOverflowOnce(final Configuration cfg, final Decision decision) {
        final boolean auto = configuredMode(cfg) == Configuration.Mode.AUTO;
        final int online = online();
        final boolean overflow = decision.mode == Configuration.Mode.FLAT
                ? auto && (decision.model != null || online > Configuration.CLIENT_MAX_ENTRIES)
                : decision.model != null && decision.model.slots.size() > Configuration.CLIENT_MAX_ENTRIES;
        if (overflow == overflowWarned) {
            return;
        }
        overflowWarned = overflow;
        if (!overflow) {
            return;
        }
        final String what = decision.model != null
                ? "网格 " + decision.model.columnCount + " 列 × " + decision.model.rows + " 行 = "
                        + decision.model.slots.size() + " 格"
                : "在线 " + online + " 人（含模拟）";
        final String limit = "客户端上限 " + Configuration.CLIENT_MAX_ENTRIES
                + " 条（PlayerTabOverlay 的 limit(80)）";
        if (auto) {
            logger.warn("[vtabsort] " + what + "，超过" + limit + "：auto 已自动退回 flat。"
                    + "flat 里排不下的人客户端同样不显示，但不会像网格那样整张表错位；"
                    + "想继续用网格请调小 [layout].max-columns / rows。");
        } else {
            logger.warn("[vtabsort] " + what + "，超过" + limit + "：多出来的条目客户端会直接丢掉，"
                    + "整张网格会错位。请调小 [layout].max-columns / rows，"
                    + "或把 [display].mode 改成 auto（超限时自动退回 flat）。");
        }
    }

    /**
     * 一次判定结果。
     *
     * <p>{@code model} 只在「本来要画网格」时非 null —— 哪怕因为超 80 条被弹回
     * flat，也把模型带出来，日志和 {@code /vts layout} 才能说清是几列几行超的。
     */
    private static final class Decision {
        final Configuration.Mode mode;
        final LayoutModel model;

        Decision(final Configuration.Mode mode, final LayoutModel model) {
            this.mode = mode;
            this.model = model;
        }
    }

    /**
     * {@code /vts mode [layout|flat|auto]}：切排列模式。
     *
     * <p>两件事一起做：①运行时覆盖 → 立刻生效（rebuildNow 会顺手清掉对家留下的
     * 假条目/前缀）；②把 {@code [display].mode} 写回 config.toml —— 重启、热重载
     * 之后还是这个模式。写文件只动那一行，注释和其它配置原样保留。
     */
    private void switchMode(final CommandSource source, final String raw) {
        if (raw == null || raw.isEmpty()) {
            final Configuration cfg = config;
            final Decision decision = decide(cfg);
            final boolean auto = configuredMode(cfg) == Configuration.Mode.AUTO;
            send(source, "当前模式：" + configuredMode(cfg).key()
                    + (auto
                            ? "（在线 " + online() + " 人 → 实际 " + decision.mode.key()
                                    + (decision.mode == Configuration.Mode.FLAT && decision.model != null
                                            ? "，网格 " + decision.model.slots.size() + " 格超上限 "
                                                    + Configuration.CLIENT_MAX_ENTRIES
                                            : "")
                                    + "）"
                            : "")
                    + "；切换：/vts mode layout|flat|auto");
            return;
        }
        final Configuration.Mode want = switch (raw.toLowerCase(Locale.ROOT)) {
            case "layout" -> Configuration.Mode.LAYOUT;
            case "flat" -> Configuration.Mode.FLAT;
            case "auto" -> Configuration.Mode.AUTO;
            default -> null;
        };
        if (want == null) {
            send(source, "没有这个模式：" + raw + "（只有 layout / flat / auto）");
            return;
        }
        modeOverride = want;
        rebuildNow(); // 立刻生效（模式翻面时清对家痕迹的逻辑在 rebuildNow 里）
        final String error = Configuration.writeMode(dataDirectory, want);
        if (error == null) {
            lastModified = configMtime(); // 自己写的，别让自动重载当成外部改动再读一遍
            send(source, "已切到 " + want.key() + "，并写回 config.toml（重启后仍然是这个模式）");
        } else {
            send(source, "已切到 " + want.key() + "，但 config.toml 没写进去（" + error
                    + "）—— 本次运行有效，重启或 reload 后会回到文件里的模式。");
        }
    }

    // ------------------------------------------------------------------
    // 命令
    // ------------------------------------------------------------------

    private void registerCommands() {
        final CommandManager manager = proxy.getCommandManager();
        // ⚠️ 主名一律小写：Brigadier literal 大小写敏感，注册大写会导致小写敲不出来。
        final CommandMeta meta = manager.metaBuilder("vtabsort")
                .aliases("vts")
                .plugin(this)
                .build();
        manager.register(meta, new VTabSortCommand());
    }

    private final class VTabSortCommand implements SimpleCommand {

        @Override
        public void execute(final Invocation invocation) {
            final CommandSource source = invocation.source();
            final String[] args = invocation.arguments();
            final String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reload" -> {
                    if (!source.hasPermission("vtabsort.reload")) {
                        send(source, config.message("no-permission"));
                        return;
                    }
                    reload(source);
                }
                case "layout" -> {
                    if (!source.hasPermission("vtabsort.reload")) {
                        send(source, config.message("no-permission"));
                        return;
                    }
                    printLayout(source);
                }
                case "mode" -> {
                    if (!source.hasPermission("vtabsort.reload")) {
                        send(source, config.message("no-permission"));
                        return;
                    }
                    switchMode(source, args.length >= 2 ? args[1] : "");
                }
                case "verify", "check" -> {
                    if (!source.hasPermission("vtabsort.reload")) {
                        send(source, config.message("no-permission"));
                        return;
                    }
                    verifyTab(source, args.length >= 2 ? args[1] : "");
                }
                case "demo" -> {
                    if (!source.hasPermission("vtabsort.reload")) {
                        send(source, config.message("no-permission"));
                        return;
                    }
                    handleDemo(source, args);
                }
                case "version", "info" -> send(source, "VTabSort " + version()
                        + " —— Tab 展示（layout 网格 / flat 前缀），不碰 scoreboard team");
                default -> send(source, config.message("usage"));
            }
        }

        @Override
        public List<String> suggest(final Invocation invocation) {
            if (invocation.arguments().length <= 1) {
                return List.of("reload", "mode", "layout", "verify", "demo", "version");
            }
            if (invocation.arguments().length == 2
                    && "mode".equalsIgnoreCase(invocation.arguments()[0])) {
                return List.of("layout", "flat", "auto");
            }
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // demo：虚拟玩家模拟（看 layout 网格随人数变化的效果）
    // ------------------------------------------------------------------

    /**
     * <pre>
     * /vts demo                        查看状态
     * /vts demo off                    关掉并清理
     * /vts demo lobby:5 survival:12    固定各服人数
     * /vts demo flow [总人数]           流动模拟（默认 30；每 2 秒随机进出/换服）
     * </pre>
     */
    private void handleDemo(final CommandSource source, final String[] args) {
        final String op = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        // flat 模式下开模拟没意义（网格都看不到），但「off」必须随时能用
        // —— 否则切到 flat 之后就再也关不掉一个正在跑的流动模拟了。
        if (configuredMode(config) == Configuration.Mode.FLAT && !"off".equals(op)) {
            send(source, "当前是 flat 模式，网格看不到 —— 先切 layout/auto 再 demo。");
            return;
        }
        if (args.length < 2) {
            send(source, "demo 状态：" + demo.describe());
            send(source, "用法：/vts demo off | /vts demo lobby:5 survival:12 | /vts demo flow [总人数]");
            return;
        }
        switch (op) {
            case "off" -> {
                stopDemoFlow();
                demo.clear();
                refreshAllSoon();
                send(source, "demo 已关，虚拟玩家清掉了。");
            }
            case "flow" -> {
                int total = 30;
                if (args.length >= 3) {
                    try {
                        total = Integer.parseInt(args[2]);
                    } catch (final NumberFormatException e) {
                        send(source, "总人数得是数字：" + args[2]);
                        return;
                    }
                }
                total = Math.max(1, Math.min(total, 300));
                stopDemoFlow();
                final List<String> pool = config.knownServers();
                demo.startFlow(pool, total);
                demoFlowTask = proxy.getScheduler().buildTask(this, () -> {
                    demo.tick(pool);
                    refreshAllSoon();
                }).delay(2, TimeUnit.SECONDS).repeat(2, TimeUnit.SECONDS).schedule();
                refreshAllSoon();
                send(source, "流动模拟开始（基数 " + total + " 人，池子 "
                        + pool.size() + " 个服）——Tab 里看着，每 2 秒有人进出/换服。");
            }
            default -> {
                // server:count 串
                final Map<String, Integer> counts = new LinkedHashMap<>();
                for (int i = 1; i < args.length; i++) {
                    final int colon = args[i].lastIndexOf(':');
                    if (colon <= 0 || colon == args[i].length() - 1) {
                        send(source, "看不懂这截：" + args[i]
                                + "（要写成 服名:人数，如 lobby:5）");
                        return;
                    }
                    final String server = args[i].substring(0, colon).toLowerCase(Locale.ROOT);
                    final int count;
                    try {
                        count = Integer.parseInt(args[i].substring(colon + 1));
                    } catch (final NumberFormatException e) {
                        send(source, "人数得是数字：" + args[i]);
                        return;
                    }
                    counts.put(server, Math.max(0, count));
                }
                stopDemoFlow();
                demo.setFixed(counts);
                refreshAllSoon();
                send(source, "固定模拟开始：" + demo.describe());
            }
        }
    }

    private void stopDemoFlow() {
        final ScheduledTask task = demoFlowTask;
        if (task != null) {
            task.cancel();
            demoFlowTask = null;
        }
    }

    /**
     * 皮肤异步拉取回来后的回调（在 HttpClient 的线程里）：调度回主线程，
     * 拔掉旧假条目重建 —— 已发出去的条目改不了 profile，只能重建。
     */
    private void rebuildWithNewSkin() {
        proxy.getScheduler().buildTask(this, () -> {
            layoutManager.stripAll();
            rebuildNow();
        }).schedule();
    }

    /** 无客户端也能核对布局：把当前网格的分列模型打给命令发起者。 */
    private void printLayout(final CommandSource source) {
        final Configuration cfg = config;
        final int online = online();
        final Decision decision = decide(cfg);
        if (configuredMode(cfg) == Configuration.Mode.AUTO) {
            send(source, "auto 模式：在线 " + online
                    + " 人（含模拟），阈值 " + cfg.rows() + " → 当前落地 " + decision.mode.key()
                    + (decision.mode == Configuration.Mode.FLAT && decision.model != null
                            ? "（网格 " + decision.model.columnCount + " 列 × " + decision.model.rows
                                    + " 行 = " + decision.model.slots.size() + " 格，超客户端上限 "
                                    + Configuration.CLIENT_MAX_ENTRIES + " → 自动退回 flat）"
                            : online > Configuration.CLIENT_MAX_ENTRIES
                                    ? "（人数超客户端上限 " + Configuration.CLIENT_MAX_ENTRIES
                                            + " 条 → 自动退回 flat）"
                                    : ""));
        }
        if (demo.isActive()) {
            send(source, "demo 模拟中：" + demo.describe());
        }
        if (decision.mode != Configuration.Mode.LAYOUT) {
            send(source, "当前是 flat 展示：排序 " + (cfg.flatSort() ? "开" : "关")
                    + "，前缀格式 " + cfg.flatPrefix());
            return;
        }
        final LayoutModel model = decision.model;
        send(source, "layout 网格：" + model.columnCount + " 列 × " + model.rows
                + " 行（行数上限 " + cfg.rows() + "，列预算 " + cfg.maxColumns()
                + (model.exceedsColumns ? "，内容塞不下已超列预算" : "")
                + "），槽位 " + model.slots.size() + "，隐藏 " + model.hidden.size()
                + (cfg.layoutPrefix().isEmpty() ? "" : "，玩家前缀 " + cfg.layoutPrefix()));
        if (model.slots.size() > Configuration.CLIENT_MAX_ENTRIES) {
            send(source, "⚠️ 槽位 " + model.slots.size() + " 超过客户端上限 "
                    + Configuration.CLIENT_MAX_ENTRIES + " 条：多出来的格子客户端不渲染、整张网格会错位。"
                    + "调小 [layout].max-columns / rows，或改用 [display].mode = \"auto\"。");
        }
        for (int i = 0; i < model.columns.size(); i++) {
            final StringBuilder sb = new StringBuilder("列" + (i + 1) + "：");
            for (final LayoutModel.SectionDesc sd : model.columns.get(i).sections) {
                sb.append(sd.display).append('(').append(sd.count);
                if (sd.parts > 1) {
                    sb.append(" 第").append(sd.part).append('/').append(sd.parts)
                            .append("段 本列").append(sd.here);
                }
                sb.append(") ");
            }
            send(source, sb.toString().trim());
        }
    }

    /**
     * {@code /vts verify [玩家名]}：打印这个玩家客户端里**实际**的 Tab 列表。
     *
     * <p>26.2 客户端的网格形状完全由「它自己列表里 listed 条目数」推出来
     * （{@code PlayerTabOverlay#extractRenderState}：{@code rows = slots = size}，
     * 再 while 推 cols，列主序）。所以位置不对时先看这里：客户端条目数和插件
     * 模型格数不一样，就是多了/少了条目 —— 多一条整张网格就重排一格。
     * 同时打到控制台，方便整段贴出来排查。
     */
    private void verifyTab(final CommandSource source, final String name) {
        final Configuration cfg = config;
        final Player target;
        if (name == null || name.isEmpty()) {
            if (!(source instanceof Player self)) {
                send(source, "控制台要带玩家名：/vts verify <玩家名>");
                return;
            }
            target = self;
        } else {
            target = proxy.getPlayer(name).orElse(null);
            if (target == null) {
                send(source, "找不到在线玩家：" + name);
                return;
            }
        }
        final Decision decision = decide(cfg);
        if (decision.mode != Configuration.Mode.LAYOUT) {
            send(source, "注意：当前落地模式不是 layout（flat 没有网格），verify 只对 layout 有意义。"
                    + (decision.model != null
                            ? "（网格 " + decision.model.slots.size() + " 格超过客户端上限 "
                                    + Configuration.CLIENT_MAX_ENTRIES + "，auto 已自动退回 flat）"
                            : ""));
        }
        final LayoutModel model = decision.model != null
                ? decision.model : layoutManager.build(cfg, demo.snapshot());
        final List<String> lines = layoutManager.verify(target, model);
        send(source, "== " + target.getUsername() + " 的 Tab 实况 ==");
        for (final String line : lines) {
            send(source, line);
            logger.info("[vtabsort][verify] " + line);
        }
    }

    /** 起服/重载/切模式时打的那行模式说明。 */
    private String describeMode(final Configuration cfg) {
        final int online = online();
        return switch (configuredMode(cfg)) {
            case LAYOUT -> "模式 layout（固定网格，列头=子服名，人数降序分列，超高续列"
                    + (cfg.layoutPrefix().isEmpty() ? "）" : "，玩家前缀 " + cfg.layoutPrefix() + "）");
            case FLAT -> "模式 flat（前缀列表" + (cfg.flatSort() ? "，按人数降序排序" : "，原版顺序") + "）";
            case AUTO -> "模式 auto（≤" + cfg.rows() + " 人走 flat，超过走 layout；"
                    + "网格超过客户端上限 " + Configuration.CLIENT_MAX_ENTRIES + " 格时也退回 flat）"
                    + "；当前在线 " + online + " → " + effectiveMode(cfg, online).key();
        };
    }

    // ------------------------------------------------------------------
    // 重载
    // ------------------------------------------------------------------

    /** 重新读 config.toml。出错时保留旧配置 —— 宁可用旧的，也不让插件变成半成品。 */
    public void reload(final CommandSource feedback) {
        final Configuration.LoadResult result = Configuration.load(dataDirectory, logger);
        if (result.error() != null) {
            logger.warn("[vtabsort] config.toml 没读出来，继续用旧配置：" + result.error());
            // ⚠️ 读失败也要把 mtime 记账往前推：不然自动重载每 3 秒重试一次、
            //    每 3 秒一条 WARN，把容错变成日志洪水。文件改好了 mtime 自然会变。
            lastModified = configMtime();
            if (feedback != null) {
                send(feedback, Configuration.fill(config.message("reload-failed"), "reason", result.error()));
            }
            return;
        }
        final Configuration previous = config;
        config = result.config();
        // 文件是唯一真相：/vts mode 成功时已经把新值写回文件了，覆盖到这里作废。
        modeOverride = null;
        // 配置换了，超限警告重新算一遍（改了 max-columns/rows 之后该提示就提示）
        overflowWarned = false;
        lastModified = configMtime();
        // layout 玩家前缀改了（加了或删了）：先把显示名打回裸名，让下面重建时
        // 干净地写一遍 —— 显示名能改，但删前缀之后旧前缀会一直挂在条目上。
        if (!previous.layoutPrefix().equals(config.layoutPrefix())) {
            flatManager.resetDisplayNames();
        }
        // 皮肤：三档 —— 占位皮肤（填充假人）/ 默认列头头像 / 各子服列头头像。
        // 非 player: 写法同步算出来；player: 先用磁盘缓存顶上、后台再拉一次
        // （拉到后回调 → 拔掉旧假条目重建，让新皮肤立刻生效）。
        final Map<String, String> specs = new LinkedHashMap<>();
        final Map<String, String> sigs = new LinkedHashMap<>();
        specs.put(SkinCache.DEFAULT_KEY, config.skinSpec());
        sigs.put(SkinCache.DEFAULT_KEY, config.skinSignature());
        specs.put(SkinCache.HEADER_KEY, config.columnSkin());
        sigs.put(SkinCache.HEADER_KEY, config.columnSkinSignature());
        for (final Map.Entry<String, String> e : config.serverSkins().entrySet()) {
            final String key = SkinCache.serverKey(e.getKey());
            specs.put(key, e.getValue());
            sigs.put(key, config.serverSkinSignatures().getOrDefault(e.getKey(), ""));
        }
        if (skins.refresh(specs, sigs, this::rebuildWithNewSkin)) {
            // 换皮肤必须重建假条目：Velocity 的 TabListEntry 没有 setProfile，
            // 已发出去的条目改不了皮肤 —— 不重建的话改了配置得重启代理才生效。
            layoutManager.stripAll();
        }
        // 模式切换的清场交给 rebuildNow（它比对的是「上一轮实际落地的模式」，
        // auto 模式下配置没变也可能翻面，统一在那里处理不会漏）。
        rebuildNow();
        logger.info("[vtabsort] 配置已加载 —— " + describeMode(config));
        if (feedback != null) {
            send(feedback, config.message("reloaded"));
        }
    }

    private long configMtime() {
        try {
            return Files.getLastModifiedTime(dataDirectory.resolve("config.toml")).toMillis();
        } catch (final Exception e) {
            return -1L;
        }
    }

    /** 改了配置不想敲命令就用这个（advanced.auto-reload = true）。按修改时间判断，没变不读。 */
    private void startAutoReload() {
        proxy.getScheduler().buildTask(this, () -> {
            if (!config.autoReload()) {
                return;
            }
            final long now = configMtime();
            if (now > 0 && now != lastModified) {
                logger.info("[vtabsort] 发现 config.toml 变了，自动重载。");
                reload(null);
            }
        // 周期取自当前的 advanced.auto-reload-interval-seconds：只在起服时读一次，
        // 中途改了要重启代理才生效；开关（auto-reload）则是每次跑 task 都看，随时可改。
        }).delay(3, TimeUnit.SECONDS)
                .repeat(Math.max(1L, config.autoReloadIntervalSeconds()), TimeUnit.SECONDS)
                .schedule();
    }

    /**
     * 显示名守卫：每 0.5 秒把「被别人改掉的显示名」写回来。
     *
     * <p>🔴 为什么单独起一个高频任务：显示名是单写者字段，后端（Paper 自己的
     * tab 名同步）/ PlayerTitle / CMI 都会在推玩家信息时重新发一遍自己的显示名，
     * 把我们的前缀顶掉。周期性校准（reassert，默认 3 秒）空窗期太长 —— 服主
     * 截图里一列 16 个人有 2 个名字前少了那个空格，就是被逮在空窗期里。
     * 这里只比对显示名（不建条目、不发顺序包），成本极低。
     */
    private void startNameGuard() {
        proxy.getScheduler().buildTask(this, this::nameGuard)
                .delay(500, TimeUnit.MILLISECONDS)
                .repeat(500, TimeUnit.MILLISECONDS)
                .schedule();
    }

    private void nameGuard() {
        final Configuration.Mode mode = appliedMode.get();
        if (mode == null) {
            return;
        }
        final Configuration cfg = config;
        if (mode == Configuration.Mode.LAYOUT) {
            final LayoutModel model = lastModel;
            if (model != null) {
                layoutManager.guardNames(model, cfg);
            }
        } else {
            flatManager.guardNames(cfg);
        }
    }

    /**
     * 周期性校准（advanced.reassert-seconds，0=关）：后端 Paper 会把自己玩家的
     * listOrder 重置回 0、TAB 也可能在事件里覆写显示名/重新 listed —— 全量
     * 重算 + diff 应用，没变化一个包都不发，有变化几秒内拉回来。
     *
     * <p>⚠️ 周期在起服时定死（读一次 config）：改 reassert-seconds 要重启代理
     * 才生效（和 auto-reload-interval-seconds 一样），开关语义只有 0/非 0。
     */
    private void startReassert() {
        final int seconds = Math.max(0, config.reassertSeconds());
        if (seconds == 0) {
            return;
        }
        proxy.getScheduler().buildTask(this, this::rebuildNow)
                .delay(seconds, TimeUnit.SECONDS)
                .repeat(seconds, TimeUnit.SECONDS)
                .schedule();
    }

    // ------------------------------------------------------------------
    // 小接口
    // ------------------------------------------------------------------

    private void send(final CommandSource source, final String text) {
        source.sendMessage(Component.text(text));
    }

    private String version() {
        return proxy.getPluginManager().fromInstance(this)
                .map(container -> container.getDescription().getVersion().orElse("unknown"))
                .orElse("unknown");
    }
}
