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
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VTabSort —— Tab 列表按子服排序（纯代理端，零依赖，子服什么都不用装）。
 *
 * <p>原理：MC 1.21.2+ 的 tab 协议有 {@code listOrder} 字段（UPDATE_LIST_ORDER），
 * 客户端原生按它排序、完全不经过 scoreboard team。本插件只做这一件事：
 *
 * <ul>
 *   <li>不碰 team → 后端小游戏用 {@code nametagVisibility} 藏名字照常生效，
 *       VelocityScoreboardAPI 的代理 team 优先级问题与它无关；</li>
 *   <li>不改显示名 → 名字格式（前后缀、颜色）继续归 TAB / 后端管，
 *       UPDATE_DISPLAY_NAME 和 UPDATE_LIST_ORDER 是两个独立 action，互不覆盖；</li>
 *   <li>空服自然没条目、超员自然续行 —— 原版列表的弹性全保留。</li>
 * </ul>
 *
 * <p>老客户端（&lt;1.21.2）没有这个字段，自动走原版顺序，无害兜底。
 */
@Plugin(
        id = "vtabsort",
        name = "VTabSort",
        // ⚠️ 版本号必须是编译期常量 —— 走 BuildConstants 间接引过来（templating-maven-plugin 生成）。
        // 🔴 别改回字面量：注解处理器会按这个值重写 velocity-plugin.json。
        version = BuildConstants.VERSION,
        description = "Tab 列表按子服排序：用 1.21.2+ 的 listOrder 协议字段，不碰 scoreboard team，不影响小游戏藏名字。",
        authors = {"拾玖世界"}
)
public final class VTabSort {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Sorter sorter;

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
        this.config = Configuration.defaults();
    }

    @Subscribe
    public void onProxyInitialization(final ProxyInitializeEvent event) {
        reload(null);
        registerCommands();
        startAutoReload();
        // 起服时一般没玩家，这发是给「热重载插件 / 重启代理但玩家还在」的场合兜底
        sorter.refreshAll(config);
        logger.info("[vtabsort] VTabSort 已就绪 —— Tab 按子服排序（listOrder 协议字段，不碰 team）。"
                + " 子服顺序：" + String.join(" > ", config.serverOrder()));
    }

    // ------------------------------------------------------------------
    // 事件：任何影响顺序的变化都全量重刷（人数 < 几百，重算是 O(n log n)，随便刷）
    // ------------------------------------------------------------------

    /** 进代理：既是新条目、也是新 viewer，两头都要刷。 */
    @Subscribe
    public void onPostLogin(final PostLoginEvent event) {
        final Player player = event.getPlayer();
        sorter.trackJoin(player.getUniqueId());
        sorter.refreshViewer(player, config); // 新 viewer 先把自己看得见的都排好
        refreshAllSoon();                     // 别人的列表里也多了这个新条目
    }

    /** 切子服：顺序基数变了。 */
    @Subscribe
    public void onServerConnected(final ServerConnectedEvent event) {
        refreshAllSoon();
    }

    /** 退代理：同服其他人的组内序号会顺移。 */
    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        sorter.forget(event.getPlayer().getUniqueId());
        refreshAllSoon();
    }

    /**
     * 立即刷一发 + 按 {@code advanced.refresh-delay-ms} 延迟再补一发。
     *
     * <p>为什么要补：tab 条目是别的插件（TAB 的 global playerlist 等）建的，
     * 事件触发那一瞬间新玩家的条目可能还没塞进各 viewer 的 TabList —— 立即那发
     * 会漏掉它，延迟这一发能兜住。条目还没建好就再漏（delay 配 0 可关）。
     */
    private void refreshAllSoon() {
        sorter.refreshAll(config);
        final int delay = config.refreshDelayMs();
        if (delay <= 0 || !delayedRefreshPending.compareAndSet(false, true)) {
            return;
        }
        proxy.getScheduler().buildTask(this, () -> {
            delayedRefreshPending.set(false);
            sorter.refreshAll(config);
        }).delay(delay, TimeUnit.MILLISECONDS).schedule();
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
                case "version", "info" -> send(source, "VTabSort " + version()
                        + " —— Tab 按子服排序（listOrder，不碰 team）");
                default -> send(source, config.message("usage"));
            }
        }

        @Override
        public List<String> suggest(final Invocation invocation) {
            if (invocation.arguments().length <= 1) {
                return List.of("reload", "version");
            }
            return List.of();
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
        config = result.config();
        lastModified = configMtime();
        sorter.refreshAll(config); // 顺序表可能变了，立刻按新顺序刷一遍
        logger.info("[vtabsort] 配置已加载 —— 子服顺序：" + String.join(" > ", config.serverOrder())
                + "；未列出的服排" + (config.unknownFirst() ? "最前" : "最后")
                + "；同服内按" + (config.sameServerByJoin() ? "加入顺序" : "名字") + "排。");
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
