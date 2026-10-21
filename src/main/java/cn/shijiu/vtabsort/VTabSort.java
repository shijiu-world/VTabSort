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
    private final Path dataDirectory;
    private final Sorter sorter;
    private final SkinCache skins;
    private final LayoutManager layoutManager;
    private final FlatManager flatManager;

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
        this.layoutManager = new LayoutManager(proxy, sorter, skins);
        this.flatManager = new FlatManager(proxy, sorter);
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
        // demo 虚拟玩家计入 auto 的翻面判定（模拟人多时的行为才算数）
        final Configuration.Mode effective = cfg.effectiveMode(proxy.getPlayerCount() + demo.virtualCount());
        final Configuration.Mode previous = appliedMode.getAndSet(effective);
        if (previous != null && previous != effective) {
            if (effective == Configuration.Mode.LAYOUT) {
                flatManager.resetDisplayNames(); // flat→layout：清前缀显示名
            } else {
                layoutManager.stripAll();         // layout→flat：拔全部假条目
            }
        }
        if (effective == Configuration.Mode.LAYOUT) {
            layoutManager.applyAll(layoutManager.build(cfg, demo.snapshot()), cfg);
        } else {
            flatManager.applyAll(cfg);
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
                return List.of("reload", "layout", "demo", "version");
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
        if (config.mode() == Configuration.Mode.FLAT && !"off".equals(op)) {
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
        final Configuration.Mode effective = cfg.effectiveMode(proxy.getPlayerCount() + demo.virtualCount());
        if (cfg.mode() == Configuration.Mode.AUTO) {
            send(source, "auto 模式：在线 " + (proxy.getPlayerCount() + demo.virtualCount())
                    + " 人（含模拟），阈值 " + cfg.rows() + " → 当前落地 " + effective.key());
        }
        if (demo.isActive()) {
            send(source, "demo 模拟中：" + demo.describe());
        }
        if (effective != Configuration.Mode.LAYOUT) {
            send(source, "当前是 flat 展示：排序 " + (cfg.flatSort() ? "开" : "关")
                    + "，前缀格式 " + cfg.flatPrefix());
            return;
        }
        final LayoutModel model = layoutManager.build(cfg, demo.snapshot());
        send(source, "layout 网格：" + model.columnCount + " 列 × " + model.rows
                + " 行（行数上限 " + cfg.rows() + "，列预算 " + cfg.maxColumns()
                + (model.exceedsColumns ? "，内容塞不下已超列预算" : "")
                + "），槽位 " + model.slots.size() + "，隐藏 " + model.hidden.size());
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

    /** 起服/重载时打的那行模式说明。 */
    private String describeMode(final Configuration cfg) {
        return switch (cfg.mode()) {
            case LAYOUT -> "模式 layout（固定网格，列头=子服名，人数降序分列，超高续列）";
            case FLAT -> "模式 flat（前缀列表" + (cfg.flatSort() ? "，按人数降序排序" : "，原版顺序") + "）";
            case AUTO -> "模式 auto（≤" + cfg.rows() + " 人走 flat，超过走 layout）"
                    + "；当前在线 " + proxy.getPlayerCount() + " → "
                    + cfg.effectiveMode(proxy.getPlayerCount()).key();
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
        config = result.config();
        lastModified = configMtime();
        // 皮肤：非 player: 写法同步算出来；player: 先用磁盘缓存顶上、后台再拉一次
        // （拉到后回调 → 拔掉旧假条目重建，让新皮肤立刻生效）。
        final SkinCache.Skin before = skins.current();
        skins.refresh(config.skinSpec(), config.skinSignature(), this::rebuildWithNewSkin);
        if (!before.equals(skins.current())) {
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
