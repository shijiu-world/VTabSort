package cn.shijiu.vtabsort;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * config.toml 的读取与校验。
 *
 * <p>铁律：**读失败的时候保留上一次的配置**，绝不让插件变成半成品。
 * 所有取值都带默认值，{@link #load} 出错时返回 error 而不是 null 之后裸奔。
 */
public final class Configuration {

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

    /** 子服优先级（全部已转小写）。 */
    private final List<String> serverOrder;
    /** 没列进 serverOrder 的服排最前还是最后。 */
    private final boolean unknownFirst;
    /** 同服内排序方式：true = 加入顺序，false = 名字字母序。 */
    private final boolean sameServerByJoin;
    private final Map<String, String> messages;
    private final boolean autoReload;
    private final int autoReloadIntervalSeconds;
    private final int refreshDelayMs;

    private Configuration(final List<String> serverOrder, final boolean unknownFirst,
                          final boolean sameServerByJoin, final Map<String, String> messages,
                          final boolean autoReload, final int autoReloadIntervalSeconds,
                          final int refreshDelayMs) {
        this.serverOrder = serverOrder;
        this.unknownFirst = unknownFirst;
        this.sameServerByJoin = sameServerByJoin;
        this.messages = messages;
        this.autoReload = autoReload;
        this.autoReloadIntervalSeconds = autoReloadIntervalSeconds;
        this.refreshDelayMs = refreshDelayMs;
    }

    public static Configuration defaults() {
        final List<String> order = new ArrayList<>();
        order.add("lobby");
        order.add("survival");
        order.add("survival2");
        order.add("industry");
        order.add("starvalley");
        order.add("minigame");
        order.add("bedwars");
        order.add("killer");
        final Map<String, String> messages = new LinkedHashMap<>();
        messages.put("reloaded", "配置已重新加载，Tab 排序已刷新");
        messages.put("reload-failed", "配置加载失败，用的还是旧配置，原因：#reason#");
        messages.put("no-permission", "你没有权限这么做");
        messages.put("usage", "用法：/vtabsort reload | version");
        return new Configuration(order, false, false, messages, false, 3, 200);
    }

    public List<String> serverOrder() {
        return serverOrder;
    }

    public boolean unknownFirst() {
        return unknownFirst;
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
        final List<String> order = TomlLite.lowercase(
                TomlLite.list(raw, "servers.order", defaults().serverOrder));
        // 去重（同一个服写两遍只认第一个位置）
        final List<String> dedup = new ArrayList<>();
        for (final String s : order) {
            if (!s.isEmpty() && !dedup.contains(s)) {
                dedup.add(s);
            }
        }
        final boolean unknownFirst = "before".equalsIgnoreCase(
                TomlLite.string(raw, "servers.unknown", "after").trim());
        final boolean byJoin = "join".equalsIgnoreCase(
                TomlLite.string(raw, "general.same-server-order", "name").trim());

        final Map<String, String> messages = new LinkedHashMap<>();
        messages.put("reloaded", TomlLite.string(raw, "messages.reloaded", defaults().message("reloaded")));
        messages.put("reload-failed", TomlLite.string(raw, "messages.reload-failed", defaults().message("reload-failed")));
        messages.put("no-permission", TomlLite.string(raw, "messages.no-permission", defaults().message("no-permission")));
        messages.put("usage", TomlLite.string(raw, "messages.usage", defaults().message("usage")));

        final boolean autoReload = TomlLite.bool(raw, "advanced.auto-reload", false);
        final int interval = (int) Math.max(1L, TomlLite.integer(raw, "advanced.auto-reload-interval-seconds", 3L));
        final int delay = (int) Math.min(Math.max(0L, TomlLite.integer(raw, "advanced.refresh-delay-ms", 200L)), 10_000L);
        return new Configuration(dedup, unknownFirst, byJoin, messages, autoReload, interval, delay);
    }

    /** 消息里的 #xxx# 换成实参。 */
    public static String fill(final String template, final String key, final String value) {
        return template == null ? "" : template.replace("#" + key + "#", value);
    }
}
