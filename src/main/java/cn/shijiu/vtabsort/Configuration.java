package cn.shijiu.vtabsort;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * config.toml 的读取与校验（v3：display/layout/flat/servers.names）。
 *
 * <p>铁律：**读失败的时候保留上一次的配置**，绝不让插件变成半成品。
 * 所有取值都带默认值，{@link #load} 出错时返回 error 而不是 null 之后裸奔。
 * 缺行/写歪的键走默认值（fallback 要留），但不做旧版配置迁移（服主定的规矩）。
 *
 * <p>v3 删掉的键（读到也忽略）：{@code [servers].order}、{@code [servers].unknown}
 * （列顺序改成按人数降序，不再由服主排）、{@code [layout].truncate}、
 * {@code [layout].overflow-format}（单列装不下改成续开一列，不再折叠）。
 */
public final class Configuration {

    /** 展示模式。auto = 按在线人数在 flat / layout 之间自己切。 */
    public enum Mode {
        LAYOUT, FLAT, AUTO;

        static Mode parse(final String raw) {
            final String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (s) {
                case "flat" -> FLAT;
                case "auto" -> AUTO;
                default -> LAYOUT; // 写歪了回 layout
            };
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 加载结果：config 可能为旧的（出错时），error 不为空说明这次没能读成。 */
    public static final class LoadResult {
        final Configuration config;
        final String error;

        LoadResult(final Configuration config, final String error) {
            this.config = config;
            this.error = error;
        }

        public Configuration config() {
            return config;
        }

        public String error() {
            return error;
        }
    }

    private final Mode mode;
    private final int maxColumns;
    private final int rows;
    private final String headerFormat;
    private final boolean separator;
    private final int blankPing;
    private final String skinSpec;
    private final String skinSignature;
    private final String flatPrefix;
    private final boolean flatSort;
    private final Map<String, String> serverNames;
    private final boolean sameServerByJoin;
    private final Map<String, String> messages;
    private final boolean autoReload;
    private final int autoReloadIntervalSeconds;
    private final int refreshDelayMs;
    private final int reassertSeconds;
    /** header/footer 总开关（false = 清掉已显示的、本插件不再插手）。 */
    private final boolean headerFooterEnabled;
    private final List<String> headerLines;
    private final List<String> footerLines;

    private Configuration(final Mode mode, final int maxColumns, final int rows,
                          final String headerFormat, final boolean separator,
                          final int blankPing, final String skinSpec,
                          final String skinSignature,
                          final String flatPrefix, final boolean flatSort,
                          final Map<String, String> serverNames,
                          final boolean sameServerByJoin,
                          final Map<String, String> messages,
                          final boolean autoReload, final int autoReloadIntervalSeconds,
                          final int refreshDelayMs, final int reassertSeconds,
                          final boolean headerFooterEnabled,
                          final List<String> headerLines, final List<String> footerLines) {
        this.mode = mode;
        this.maxColumns = maxColumns;
        this.rows = rows;
        this.headerFormat = headerFormat;
        this.separator = separator;
        this.blankPing = blankPing;
        this.skinSpec = skinSpec;
        this.skinSignature = skinSignature;
        this.flatPrefix = flatPrefix;
        this.flatSort = flatSort;
        this.serverNames = serverNames;
        this.sameServerByJoin = sameServerByJoin;
        this.messages = messages;
        this.autoReload = autoReload;
        this.autoReloadIntervalSeconds = autoReloadIntervalSeconds;
        this.refreshDelayMs = refreshDelayMs;
        this.reassertSeconds = reassertSeconds;
        this.headerFooterEnabled = headerFooterEnabled;
        this.headerLines = headerLines;
        this.footerLines = footerLines;
    }

    public static Configuration defaults() {
        final Map<String, String> names = new TreeMap<>();
        names.put("lobby", "大厅");
        names.put("survival", "生存净土");
        names.put("survival2", "生存乐土");
        names.put("industry", "工业基地");
        names.put("starvalley", "星露谷");
        names.put("minigame", "电玩中心");
        names.put("bedwars", "起床战争");
        names.put("killer", "限时游戏");
        final Map<String, String> messages = new LinkedHashMap<>();
        messages.put("reloaded", "配置已重新加载，Tab 已刷新");
        messages.put("reload-failed", "配置加载失败，用的还是旧配置，原因：#reason#");
        messages.put("no-permission", "你没有权限这么做");
        messages.put("usage", "用法：/vtabsort reload | layout | demo | version");
        return new Configuration(Mode.LAYOUT, 4, 20,
                "&e#name# &7· #count#", true,
                0, "", "",
                "&7[&e#name#&7] ", true,
                names, false, messages,
                false, 3, 200, 3,
                true,
                List.of("&e欢迎来到 &6拾玖世界", "&7在线 &f%online% &7人 · 当前 &f%server%"),
                List.of("&7延迟 &#9adbc5%ping% &7ms"));
    }

    /** 配置里写的模式（可能是 auto）。 */
    public Mode mode() {
        return mode;
    }

    /**
     * auto 模式的落地：在线人数 ≤ rows（客户端拆第二列的阈值）走 flat，
     * 超了走 layout。非 auto 模式原样返回。
     *
     * @param onlineCount 当前在线人数（含还在连接中的）
     */
    public Mode effectiveMode(final int onlineCount) {
        if (mode != Mode.AUTO) {
            return mode;
        }
        return onlineCount <= rows ? Mode.FLAT : Mode.LAYOUT;
    }

    public int maxColumns() {
        return maxColumns;
    }

    /** 客户端每列行数上限（网格实际行数自适应，见 LayoutManager）。 */
    public int rows() {
        return rows;
    }

    public String headerFormat() {
        return headerFormat;
    }

    public boolean separator() {
        return separator;
    }

    public int blankPing() {
        return blankPing;
    }

    /**
     * 假条目的皮肤配置原文（哈希 / {@code texture:} / URL / base64 / {@code player:名字}）。
     * 真正的解析与缓存由 {@link SkinCache} 负责（{@code player:} 要联网取）。
     */
    public String skinSpec() {
        return skinSpec;
    }

    /**
     * 皮肤签名（可选）。只有直接粘贴从 Mojang API 拿到的完整 base64 value
     * （里面 {@code signatureRequired: true}）时才需要一起填它的 signature。
     */
    public String skinSignature() {
        return skinSignature;
    }

    public String flatPrefix() {
        return flatPrefix;
    }

    public boolean flatSort() {
        return flatSort;
    }

    /** 子服显示名（键已转小写）；没配的用服名原文。 */
    public String serverDisplayName(final String server) {
        return serverNames.getOrDefault(server, server);
    }

    /** 已知子服清单（[servers.names] 的键，已排序）——demo 模拟的玩家池。 */
    public List<String> knownServers() {
        return List.copyOf(serverNames.keySet());
    }

    public boolean sameServerByJoin() {
        return sameServerByJoin;
    }

    public String message(final String key) {
        return messages.getOrDefault(key, key);
    }

    public boolean autoReload() {
        return autoReload;
    }

    public int autoReloadIntervalSeconds() {
        return autoReloadIntervalSeconds;
    }

    public int refreshDelayMs() {
        return refreshDelayMs;
    }

    public int reassertSeconds() {
        return reassertSeconds;
    }

    /** header/footer 总开关。 */
    public boolean headerFooterEnabled() {
        return headerFooterEnabled;
    }

    /** header 行（原文，占位符还没替换）。 */
    public List<String> headerLines() {
        return headerLines;
    }

    /** footer 行（原文，占位符还没替换）。 */
    public List<String> footerLines() {
        return footerLines;
    }

    // ------------------------------------------------------------------
    // 加载
    // ------------------------------------------------------------------

    public static LoadResult load(final Path dataDirectory, final Logger logger) {
        final Path file = dataDirectory.resolve("config.toml");
        try {
            Files.createDirectories(dataDirectory);
            if (!Files.exists(file)) {
                copyDefault(logger, file);
            }
            final Map<String, Object> raw = TomlLite.parse(file);
            final Configuration config = fromMap(raw);
            return new LoadResult(config, null);
        } catch (final IOException e) {
            // 读挂了不算致命：用默认配置顶上，起服别被一个文件拦住。
            logger.warn("[vtabsort] config.toml 读取失败，先用内置默认配置：" + e.getMessage());
            return new LoadResult(defaults(), e.getMessage());
        }
    }

    private static void copyDefault(final Logger logger, final Path file) throws IOException {
        try (InputStream in = Configuration.class.getClassLoader().getResourceAsStream("config.toml")) {
            if (in == null) {
                throw new IOException("jar 里找不到默认 config.toml");
            }
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        }
        logger.info("[vtabsort] 已生成默认配置文件：" + file.toAbsolutePath());
    }

    private static Configuration fromMap(final Map<String, Object> raw) {
        final Mode mode = Mode.parse(TomlLite.string(raw, "display.mode", "layout"));

        final int maxColumns = (int) clamp(TomlLite.integer(raw, "layout.max-columns", 4), 1, 10);
        final int rows = (int) clamp(TomlLite.integer(raw, "layout.rows", 20), 5, 40);
        final String headerFormat = TomlLite.string(raw, "layout.header-format",
                defaults().headerFormat());
        final boolean separator = TomlLite.bool(raw, "layout.separator", true);
        final int blankPing = (int) clamp(TomlLite.integer(raw, "layout.blank-ping", 0), 0, 60_000);
        // 皮肤：这里只留原文，解析/联网/缓存交给 SkinCache
        final String skinSig = TomlLite.string(raw, "layout.default-skin-signature", "").trim();
        final String skinSpec = TomlLite.string(raw, "layout.default-skin", "").trim();

        final String flatPrefix = TomlLite.string(raw, "flat.prefix-format", defaults().flatPrefix());
        final boolean flatSort = TomlLite.bool(raw, "flat.sort", true);

        final boolean byJoin = "join".equalsIgnoreCase(
                TomlLite.string(raw, "general.same-server-order", "name").trim());

        // [servers.names] 整段：键是服名（转小写），值是显示名
        final Map<String, String> names = new TreeMap<>();
        for (final Map.Entry<String, Object> e : raw.entrySet()) {
            final String key = e.getKey();
            if (key.startsWith("servers.names.") && e.getValue() != null) {
                final String server = key.substring("servers.names.".length()).trim().toLowerCase(Locale.ROOT);
                final String display = String.valueOf(e.getValue());
                if (!server.isEmpty() && !display.isEmpty()) {
                    names.put(server, display);
                }
            }
        }

        final Map<String, String> messages = new LinkedHashMap<>();
        messages.put("reloaded", TomlLite.string(raw, "messages.reloaded", defaults().message("reloaded")));
        messages.put("reload-failed", TomlLite.string(raw, "messages.reload-failed", defaults().message("reload-failed")));
        messages.put("no-permission", TomlLite.string(raw, "messages.no-permission", defaults().message("no-permission")));
        messages.put("usage", TomlLite.string(raw, "messages.usage", defaults().message("usage")));

        final boolean autoReload = TomlLite.bool(raw, "advanced.auto-reload", false);
        final int interval = (int) Math.max(1L, TomlLite.integer(raw, "advanced.auto-reload-interval-seconds", 3L));
        final int delay = (int) clamp(TomlLite.integer(raw, "advanced.refresh-delay-ms", 200), 0, 10_000);
        final int reassert = (int) clamp(TomlLite.integer(raw, "advanced.reassert-seconds", 3), 0, 60);

        // header/footer：TAB 卸载后的接手方案。行内支持 & 码 / &#hex /
        // <#hex>渐变</#hex>；占位符见 HeaderFooter.variables()。
        final boolean hfEnabled = TomlLite.bool(raw, "header-footer.enabled", true);
        final List<String> header = TomlLite.list(raw, "header-footer.header",
                defaults().headerLines());
        final List<String> footer = TomlLite.list(raw, "header-footer.footer",
                defaults().footerLines());

        return new Configuration(mode, maxColumns, rows, headerFormat, separator,
                blankPing, skinSpec, skinSig, flatPrefix, flatSort,
                names, byJoin, messages,
                autoReload, interval, delay, reassert,
                hfEnabled, header, footer);
    }

    private static long clamp(final long v, final long min, final long max) {
        return Math.min(Math.max(v, min), max);
    }

    /** 消息里的 #xxx# 换成实参。 */
    public static String fill(final String template, final String key, final String value) {
        return template == null ? "" : template.replace("#" + key + "#", value);
    }
}
