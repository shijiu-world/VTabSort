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
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VTabSort —— Tab 列表展示（Velocity 代理端，零依赖，子服什么都不用装）。
 *
 * <p>两种模式（config.toml 的 [display].mode，reload 即切换）：
 *
 * <ul>
 *   <li><b>layout</b>：Hypixel 式固定网格。每列头顶子服名（在线数可配），
 *       假条目补满空槽；子服数超过 max-columns 时人数最少的并进共享列
 *       （分节按人数降序、分节间空一行）。网格行数自适应：人数最多的
 *       子服几人就是几+1 行（受「客户端开出 N 列的最小行数」约束，上限 rows）。</li>
 *   <li><b>flat</b>：普通列表。名字前缀子服名（格式可配），可选按子服排序。</li>
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
        description = "Tab 展示：layout 固定网格（子服列头 + 假人补位）/ flat 前缀列表；listOrder 协议字段，不碰 scoreboard team。",
        authors = {"拾玖世界"}
)
public final class VTabSort {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Sorter sorter;
    private final LayoutManager layoutManager;
    private final FlatManager flatManager;

    private volatile Configuration config;
    /** 上一次读到的 config.toml 修改时间，热重载用。 */
    private volatile long lastModified;
    /** 是否已有一个延迟刷新任务在排队（防抖：连发事件只补一发）。 */
    private final AtomicBoolean delayedRefreshPending = new AtomicBoolean(false);

    @Inject
    public VTabSort(final ProxyServer proxy, final Logger logger, @DataDirectory final Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.sorter = new Sorter(proxy);
        this.layoutManager = new LayoutManager(proxy, sorter);
        this.flatManager = new FlatManager(proxy, sorter);
        this.config = Configuration.defaults();
    }

    @Subscribe
    public void onProxyInitialization(final ProxyInitializeEvent event) {
        reload(null);
        registerCommands();
        startAutoReload();
        startReassert();
        logger.info("[vtabsort] VTabSort 已就绪 —— 模式："
                + (config.layoutMode()
                ? "layout 固定网格（列头=子服名，假人补位，行数自适应）"
                : "flat 普通列表（前缀" + (config.flatSort() ? "+排序" : "") + "）")
                + "；子服顺序：" + String.join(" > ", config.serverOrder()));
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

    private void rebuildNow() {
        final Configuration cfg = config;
        if (cfg.layoutMode()) {
            layoutManager.applyAll(layoutManager.build(cfg), cfg);
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
                case "version", "info" -> send(source, "VTabSort " + version()
                        + " —— Tab 展示（layout 网格 / flat 前缀），不碰 scoreboard team");
                default -> send(source, config.message("usage"));
            }
        }

        @Override
        public List<String> suggest(final Invocation invocation) {
            if (invocation.arguments().length <= 1) {
                return List.of("reload", "layout", "version");
            }
            return List.of();
        }
    }

    /** 无客户端也能核对布局：把当前网格的分列模型打给命令发起者。 */
    private void printLayout(final CommandSource source) {
        final Configuration cfg = config;
        if (!cfg.layoutMode()) {
            send(source, "当前是 flat 模式：排序 " + (cfg.flatSort() ? "开" : "关")
                    + "，前缀格式 " + cfg.flatPrefix());
            return;
        }
        final LayoutModel model = layoutManager.build(cfg);
        send(source, "layout 网格：" + model.columnCount + " 列 × " + model.rows
                + " 行（上限 " + cfg.rows() + "），槽位 " + model.slots.size()
                + "，隐藏 " + model.hidden.size());
        for (int i = 0; i < model.columns.size(); i++) {
            final StringBuilder sb = new StringBuilder("列" + (i + 1) + "：");
            for (final LayoutModel.SectionDesc sd : model.columns.get(i).sections) {
                sb.append(sd.display).append('(').append(sd.count);
                if (sd.shown != sd.count) {
                    sb.append(" 显").append(sd.shown);
                }
                if (sd.hidden > 0) {
                    sb.append(" 藏").append(sd.hidden);
                }
                sb.append(") ");
            }
            send(source, sb.toString().trim());
        }
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
        final boolean wasLayout = config.layoutMode();
        config = result.config();
        lastModified = configMtime();
        if (config.layoutMode() != wasLayout) {
            // 模式切换：先把对家留下的痕迹清干净
            if (config.layoutMode()) {
                flatManager.resetDisplayNames(); // flat→layout：清前缀显示名
            } else {
                layoutManager.stripAll();         // layout→flat：拔全部假条目
            }
        }
        rebuildNow();
        logger.info("[vtabsort] 配置已加载 —— 模式 "
                + (config.layoutMode() ? "layout" : "flat")
                + "；子服顺序：" + String.join(" > ", config.serverOrder()));
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
