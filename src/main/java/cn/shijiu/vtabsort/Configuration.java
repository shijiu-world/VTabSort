package cn.shijiu.vtabsort;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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

    /**
     * 客户端 Tab 能渲染的条目上限（硬编码在客户端里，配不了）。
     *
     * <p>26.2 的 {@code PlayerTabOverlay#getPlayerInfos()}：
     * <pre>
     *   connection.getListedOnlinePlayers().stream().sorted(PLAYER_COMPARATOR).limit(80L)
     * </pre>
     * 先按 listOrder 降序（值大的在前）排，再取前 80 条 —— 第 81 条起<b>直接不渲染</b>，
     * 而且客户端拿这 80 条反推网格形状（80 → 4 列 × 20 行），插件按更大的网格
     * 发的顺序就会整张表错位。80 是<b>条目数</b>：列头、分节空行、补满整列的空白
     * 假条目全都算，所以真人位实际只有 70 多个。
     */
    static final int CLIENT_MAX_ENTRIES = 80;

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

    /**
     * auto 模式的落地：在线人数 ≤ rows 走 flat，否则走网格；但人数超过客户端
     * 上限（80 条）时不管 rows 多大都必须回 flat —— 网格每格一个人也装不下，
     * 硬画只会被客户端截断 + 整张表错位。
     *
     * <p>纯函数，和 proxy 无关，方便单测边界（20 / 21 / 80 / 81）。
     *
     * @param online 在线人数（含 demo 虚拟玩家）
     * @param rows   {@code [layout].rows}，也是 auto 的切换阈值
     */
    static Mode autoLanding(final int online, final int rows) {
        if (online > CLIENT_MAX_ENTRIES) {
            return Mode.FLAT;
        }
        return online <= rows ? Mode.FLAT : Mode.LAYOUT;
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
    /**
     * layout 模式下<b>玩家名字</b>的前缀（{@code [layout].prefix-format}，默认空）。
     *
     * <p>和 flat 的 {@code [flat].prefix-format} 各管一段：这个只作用于网格里的
     * 玩家名（列头仍由 {@link #headerFormat} 管）。留空 = 完全不碰显示名
     * —— 显示名归 TAB / 原版管（单写者铁律：TAB 也写 tablist-name-formatting 时
     * 两边会互相覆盖，出现前缀来回闪）。所以装了 TAB 就保持空。
     */
    private final String layoutPrefix;
    private final boolean separator;
    private final int blankPing;
    /**
     * 列头（子服名那一行）右边那个信号图标显示的延迟（{@code [layout].header-ping}）。
     *
     * <p>列头是假条目，没有真实玩家，延迟只能给个死值。以前跟填充假人共用
     * {@code blank-ping}，服主想把列头单独调成「满信号」或「红叉」就得连
     * 填充假人一起改 —— 现在拆成两个全局值（不做按子服单独配，那个没用）。
     * 没写这个键时沿用 {@code blank-ping}，老配置观感不变。
     */
    private final int headerPing;
    /**
     * 皮肤<b>第二层</b>（帽子层 / 1.21.4+ 协议里的 showHat）要不要渲染。
     *
     * <p>🔴 假条目/补位条目由本插件创建，Velocity 建的条目 showHat 默认是 false
     * —— 皮肤的第二层（猫耳、帽子、头发外轮廓）就白了画。占位皮肤的猫耳
     * 全在第二层，不打开等于看不见。真人的条目也一并置成 true（原版默认就是
     * 显示两层）。
     */
    private final boolean showHat;
    private final String skinSpec;
    private final String skinSignature;
    /** 列头默认头像（[layout].column-skin）—— 与占位皮肤 default-skin 互不相干。 */
    private final String columnSkin;
    private final String columnSkinSignature;
    /** 每个子服的列头头像（[servers.skins]，键已转小写）。 */
    private final Map<String, String> serverSkins;
    /** 每个子服列头头像的签名（[servers.skins.<服名>-signature]，键已转小写）。 */
    private final Map<String, String> serverSkinSignatures;
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
    /**
     * 隐藏「正在加载中」的玩家（{@code [display].hide-loading}）。
     *
     * <p>判定只有一条：{@code Player#getCurrentServer()} 为空 —— 刚连上代理还没进
     * 任何子服、或者正在切服的那一瞬。这种人在客户端里就是半成品（皮肤可能还没
     * 到位、延迟未知），放在网格里还会因为「没有子服分节」而无处安放。
     *
     * <p>layout 早期是**写死**隐藏的；现在提成开关，flat 也一起支持（flat 以前
     * 照旧显示，因为它们会被塞进 {@code ""} 组排到最后）。
     */
    private final boolean hideLoading;

    private Configuration(final Mode mode, final int maxColumns, final int rows,
                          final String headerFormat, final String layoutPrefix,
                          final boolean separator,
                          final int blankPing, final int headerPing,
                          final boolean showHat, final String skinSpec,
                          final String skinSignature,
                          final String columnSkin, final String columnSkinSignature,
                          final Map<String, String> serverSkins,
                          final Map<String, String> serverSkinSignatures,
                          final String flatPrefix, final boolean flatSort,
                          final Map<String, String> serverNames,
                          final boolean sameServerByJoin,
                          final Map<String, String> messages,
                          final boolean autoReload, final int autoReloadIntervalSeconds,
                          final int refreshDelayMs, final int reassertSeconds,
                          final boolean headerFooterEnabled,
                          final List<String> headerLines, final List<String> footerLines,
                          final boolean hideLoading) {
        this.mode = mode;
        this.maxColumns = maxColumns;
        this.rows = rows;
        this.headerFormat = headerFormat;
        this.layoutPrefix = layoutPrefix;
        this.separator = separator;
        this.blankPing = blankPing;
        this.headerPing = headerPing;
        this.showHat = showHat;
        this.skinSpec = skinSpec;
        this.skinSignature = skinSignature;
        this.columnSkin = columnSkin;
        this.columnSkinSignature = columnSkinSignature;
        this.serverSkins = serverSkins;
        this.serverSkinSignatures = serverSkinSignatures;
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
        this.hideLoading = hideLoading;
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
        messages.put("usage", "用法：/vtabsort reload | mode <layout|flat|auto> | layout | demo | version");
        return new Configuration(Mode.LAYOUT, 4, 20,
                "&e#name# &7· #count#", "", true,
                0, 0, true, "", "", "", "", Map.of(), Map.of(),
                "&7[&e#name#&7] ", true,
                names, false, messages,
                false, 3, 200, 3,
                true,
                List.of("&e欢迎来到 &6拾玖世界", "&7在线 &f%online% &7人 · 当前 &f%server%"),
                List.of("&7延迟 &#9adbc5%ping% &7ms"),
                true);
    }

    /**
     * 配置里写的模式（可能是 auto）。
     *
     * <p>⚠️ 别直接拿它当"现在用哪个模式"——{@code /vts mode} 有运行时覆盖，
     * 而且 auto 还要按在线人数翻面。落地判定统一在 {@code VTabSort#effectiveMode}。
     */
    public Mode mode() {
        return mode;
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

    /**
     * layout 模式下玩家名字的前缀（默认空 = 不碰显示名）。{@code #name#} 换成
     * 该玩家所在子服的显示名；其余写法同 flat 前缀（&amp; 码 / &amp;#hex / 渐变）。
     */
    public String layoutPrefix() {
        return layoutPrefix;
    }

    public boolean separator() {
        return separator;
    }

    public int blankPing() {
        return blankPing;
    }

    /** 列头（子服名那一行）右边信号图标显示的延迟（毫秒）；没配时等于 blank-ping。 */
    public int headerPing() {
        return headerPing;
    }

    /** 皮肤第二层（帽子层）是否渲染。 */
    public boolean showHat() {
        return showHat;
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

    /** 列头默认头像的皮肤配置原文（留空 = 退回占位皮肤）。 */
    public String columnSkin() {
        return columnSkin;
    }

    public String columnSkinSignature() {
        return columnSkinSignature;
    }

    /**
     * 各子服的列头头像（服名小写 → 皮肤配置原文）。
     * 没配的服走 {@link #columnSkin()}，再没有才用 {@link #skinSpec()}。
     */
    public Map<String, String> serverSkins() {
        return serverSkins;
    }

    /**
     * 各子服列头头像的签名（服名小写 → 签名）。
     * 只有直接粘贴完整 base64 value（里面 {@code signatureRequired: true}）才需要。
     */
    public Map<String, String> serverSkinSignatures() {
        return serverSkinSignatures;
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

    /**
     * 「加载中」那一组玩家的显示名（{@code hide-loading = false} 时 layout 会单开
     * 一节放他们）。
     *
     * <p>名字取 {@code [servers.names].loading}，没配就用「加载中」—— 用同一个
     * 配置段是为了让配色/文案跟别的子服一样好改（比如写
     * {@code loading = "&#8f8f8f&l加载中"}）。
     */
    public String loadingDisplayName() {
        return serverNames.getOrDefault("loading", "加载中");
    }

    /** 隐藏「还没进任何子服」的玩家（{@code [display].hide-loading}）。 */
    public boolean hideLoading() {
        return hideLoading;
    }

    /**
     * 已知子服清单（[servers.names] 的键，已排序）——demo 模拟的玩家池。
     *
     * <p>🔴 排除 {@code loading}：它不是真子服，只是「加载中」那一节的显示名；
     * 混进 demo 池会在网格里凭空多出一列「加载中」。
     */
    public List<String> knownServers() {
        final List<String> out = new ArrayList<>();
        for (final String s : serverNames.keySet()) {
            if (!"loading".equals(s)) {
                out.add(s);
            }
        }
        return List.copyOf(out);
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

    /**
     * 把 {@code [display].mode} 写回 config.toml（{@code /vtabsort mode} 用）。
     *
     * <p>只替换那一行（值换掉，行尾注释原样保留），文件里其它内容一个字符都不动
     * —— 服主手写的注释/配色不能被插件覆盖掉。换行符按原文件的风格（CRLF/LF）。
     *
     * @return null = 写成功；非 null = 失败原因（命令里直接回报给服主）
     */
    public static String writeMode(final Path dataDirectory, final Mode mode) {
        final Path file = dataDirectory.resolve("config.toml");
        try {
            final String text = Files.readString(file, StandardCharsets.UTF_8);
            final String eol = text.contains("\r\n") ? "\r\n" : "\n";
            final List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
            final String fresh = "mode = \"" + mode.key() + "\"";
            boolean inDisplay = false;
            int insertAt = -1;
            boolean done = false;
            for (int i = 0; i < lines.size() && !done; i++) {
                final String t = lines.get(i).trim();
                if (t.startsWith("[") && t.endsWith("]")) {
                    inDisplay = "[display]".equalsIgnoreCase(t);
                    if (inDisplay) {
                        insertAt = i + 1; // [display] 段里没有 mode 行就插在这
                    }
                    continue;
                }
                if (!inDisplay || t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                final int eq = t.indexOf('=');
                if (eq <= 0 || !"mode".equalsIgnoreCase(t.substring(0, eq).trim())) {
                    continue;
                }
                final int hash = t.indexOf('#', eq + 1);
                lines.set(i, hash >= 0 ? fresh + "   " + t.substring(hash) : fresh);
                done = true;
            }
            if (!done) {
                if (insertAt < 0) {
                    lines.add("");
                    lines.add("[display]");
                    insertAt = lines.size();
                }
                lines.add(insertAt, fresh);
            }
            Files.writeString(file, String.join(eol, lines), StandardCharsets.UTF_8);
            return null;
        } catch (final IOException | RuntimeException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    private static Configuration fromMap(final Map<String, Object> raw) {
        final Mode mode = Mode.parse(TomlLite.string(raw, "display.mode", "layout"));
        // 还没进子服的玩家（刚连上 / 正在切服）默认不显示 —— layout 早期就是这么做的
        final boolean hideLoading = TomlLite.bool(raw, "display.hide-loading", true);

        final int maxColumns = (int) clamp(TomlLite.integer(raw, "layout.max-columns", 4), 1, 10);
        final int rows = (int) clamp(TomlLite.integer(raw, "layout.rows", 20), 5, 40);
        final String headerFormat = TomlLite.string(raw, "layout.header-format",
                defaults().headerFormat());
        final String layoutPrefix = TomlLite.string(raw, "layout.prefix-format",
                defaults().layoutPrefix());
        final boolean separator = TomlLite.bool(raw, "layout.separator", true);
        // 信号延迟：-1 = 无信号（客户端画红叉），0..60000 = 正常延迟。
        // 🔴 下限必须留 -1：以前夹到 0，写 -1 会被悄悄改成满格，红叉配不出来。
        final int blankPing = (int) clamp(TomlLite.integer(raw, "layout.blank-ping", 0), -1, 60_000);
        // 列头的信号延迟单独配（不写就跟着 blank-ping 走，老配置观感不变）
        final int headerPing = (int) clamp(
                TomlLite.integer(raw, "layout.header-ping", blankPing), -1, 60_000);
        final boolean showHat = TomlLite.bool(raw, "layout.show-hat", true);
        // 皮肤：这里只留原文，解析/联网/缓存交给 SkinCache
        final String skinSig = TomlLite.string(raw, "layout.default-skin-signature", "").trim();
        final String skinSpec = TomlLite.string(raw, "layout.default-skin", "").trim();
        final String columnSig = TomlLite.string(raw, "layout.column-skin-signature", "").trim();
        final String columnSkin = TomlLite.string(raw, "layout.column-skin", "").trim();

        // [servers.skins.<服名>] = 该子服列头的头像；没配的服退回 column-skin。
        // 签名写成 [servers.skins.<服名>-signature]（只有粘贴完整 base64 才需要）。
        final Map<String, String> serverSkins = new TreeMap<>();
        final Map<String, String> serverSigs = new TreeMap<>();
        final String prefix = "servers.skins.";
        final String sigSuffix = "-signature";
        for (final Map.Entry<String, Object> e : raw.entrySet()) {
            final String key = e.getKey();
            if (!key.startsWith(prefix) || e.getValue() == null) {
                continue;
            }
            final String rest = key.substring(prefix.length()).trim().toLowerCase(Locale.ROOT);
            final String spec = String.valueOf(e.getValue()).trim();
            if (rest.isEmpty() || spec.isEmpty()) {
                continue;
            }
            if (rest.endsWith(sigSuffix)) {
                final String server = rest.substring(0, rest.length() - sigSuffix.length());
                if (!server.isEmpty()) {
                    serverSigs.put(server, spec);
                }
                continue;
            }
            serverSkins.put(rest, spec);
        }

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

        return new Configuration(mode, maxColumns, rows, headerFormat, layoutPrefix, separator,
                blankPing, headerPing, showHat, skinSpec, skinSig,
                columnSkin, columnSig, serverSkins, serverSigs,
                flatPrefix, flatSort,
                names, byJoin, messages,
                autoReload, interval, delay, reassert,
                hfEnabled, header, footer, hideLoading);
    }

    private static long clamp(final long v, final long min, final long max) {
        return Math.min(Math.max(v, min), max);
    }

    /** 消息里的 #xxx# 换成实参。 */
    public static String fill(final String template, final String key, final String value) {
        return template == null ? "" : template.replace("#" + key + "#", value);
    }
}
