package cn.shijiu.vtabsort;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 假条目的皮肤（v3.3 起支持**多份**，按 key 管理）。
 *
 * <p>三类 key：
 * <ol>
 *   <li>{@link #DEFAULT_KEY} —— {@code [layout].default-skin}：填充假人/占位用；</li>
 *   <li>{@link #HEADER_KEY} —— {@code [layout].column-skin}：**列头默认头像**；</li>
 *   <li>{@link #serverKey} —— {@code [servers.skins.<服名>]}：单个子服的列头头像，
 *       没配就退回 {@link #HEADER_KEY}，再没有才退回 {@link #DEFAULT_KEY}。</li>
 * </ol>
 * 列头头像和占位皮肤**互不相干**（服主要求独立）。
 *
 * <p>写法（四种，跟原来一样）：
 * <ol>
 *   <li>{@code player:<名字>} —— 自动去 Mojang API 取（带有效签名），结果落盘缓存；
 *       reload 时重新拉一遍。</li>
 *   <li>纹理哈希（裸十六进制（通常 64 位，偶尔 63 位）/ {@code texture:<哈希>} / textures 完整 URL）</li>
 *   <li>完整的 base64 value（可配 signature）</li>
 *   <li>留空 = 用上一级的皮肤 / 默认 Steve-Alex</li>
 * </ol>
 * 自己拼属性的那几种关键是**不写 signatureRequired**，客户端就不会校验签名
 * （我们没有 Mojang 私钥，空签名会被拒绝、退回默认皮肤）。
 *
 * <p>线程模型：网络请求一律 {@code CompletableFuture.runAsync}，绝不阻塞事件线程；
 * 拿到结果后回调 → 由调用方（VTabSort）调度回主线程重建假条目。
 */
final class SkinCache {

    /** 一份皮肤：property 的 value + 签名（都可以是空串）。 */
    record Skin(String value, String signature) {

        static final Skin NONE = new Skin("", "");

        boolean isEmpty() {
            return value.isEmpty();
        }
    }

    /** 占位/填充假人的皮肤键（[layout].default-skin）。 */
    static final String DEFAULT_KEY = "@default";
    /** 列头默认头像键（[layout].column-skin）。 */
    static final String HEADER_KEY = "@header";

    /** 某个子服的列头头像键。 */
    static String serverKey(final String server) {
        return "server:" + server.toLowerCase(Locale.ROOT);
    }

    private static final Pattern P_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_VALUE = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_SIG = Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]+)\"");
    /**
     * 纹理哈希：十六进制串。
     *
     * <p>🔴 长度**不能写死 64** —— Mojang 那边的条目是按内容寻址的哈希，
     * 偶尔会出现 **63 位**（实测 `632e7a90…d016e` 这种，补 0 成 64 反而 404，
     * 说明 63 才是真长度）。长度卡死 64 会让这类皮肤被判成"不是哈希"、
     * 当 base64 解不开、最终静默退回默认 Steve。宽松取 32~80 位：真人皮肤
     * 哈希是 64 位，base64 value 则是几百位，两者不会误撞。
     */
    private static final String HASH = "[0-9a-fA-F]{32,80}";
    private static final String SKIN_URL = "http://textures.minecraft.net/texture/";
    private static final String PREFIX_PLAYER = "player:";

    /**
     * 🔴 绝不给 HttpClient 塞自己的线程池（.executor） —— 那会让客户端线程随插件
     * 一起死在热卸载里、拖垮后续请求。用默认的执行器即可。
     */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Logger logger;
    private final Path cacheFile;
    /** 玩家名（小写）→ 皮肤；磁盘缓存，起服不用等网络。 */
    private final Map<String, Skin> playerCache = new ConcurrentHashMap<>();
    /** key → 当前生效的皮肤（fakeProfile 直接读它）。 */
    private final Map<String, Skin> resolved = new ConcurrentHashMap<>();
    /** key → 配置原文（异步取回来后要照它重算）。 */
    private final Map<String, String> specs = new ConcurrentHashMap<>();
    /** key → 签名原文。 */
    private final Map<String, String> sigs = new ConcurrentHashMap<>();
    /** 正在拉的玩家名（防重复打 API —— Mojang 有限流）。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** 皮肤到位后的回调（由调用方调度回主线程重建）。 */
    private volatile Runnable onChange;

    SkinCache(final Logger logger, final Path dataDirectory) {
        this.logger = logger;
        this.cacheFile = dataDirectory.resolve("skin-cache.properties");
        load();
    }

    /** 取某个 key 的皮肤；没配/没拉到就是 {@link Skin#NONE}。 */
    Skin get(final String key) {
        return resolved.getOrDefault(key, Skin.NONE);
    }

    /**
     * 列头头像：该子服自己配的 → 默认列头头像 → 占位皮肤。
     *
     * <p>🔴 三级回退是服主要求的语义：「不设置就用默认的列名头像（也可配置）」，
     * 默认列头也没配才兜到占位皮肤（v3.3 之前列头和占位是同一张皮，
     * 所以旧配置升级上来观感不变）。
     *
     * @param server 子服名（小写）；null/空 = 直接走默认列头
     */
    Skin columnSkin(final String server) {
        if (server != null && !server.isEmpty()) {
            final Skin own = get(serverKey(server));
            if (!own.isEmpty()) {
                return own;
            }
        }
        final Skin header = get(HEADER_KEY);
        return header.isEmpty() ? get(DEFAULT_KEY) : header;
    }

    /**
     * reload 时调用：按配置重算所有 key。
     *
     * <p>同步阶段：非 {@code player:} 的写法直接算出来；{@code player:} 先用磁盘
     * 缓存顶上（起服瞬间就有皮肤，不用等网络），然后后台拉一次。
     *
     * @return 有没有任何一份皮肤发生变化（变了就得重建假条目 ——
     *         Velocity 的 TabListEntry 没有 setProfile，改不了已发出的条目）
     */
    boolean refresh(final Map<String, String> newSpecs, final Map<String, String> newSigs,
                    final Runnable onChange) {
        this.onChange = onChange;
        specs.clear();
        specs.putAll(newSpecs);
        sigs.clear();
        sigs.putAll(newSigs);
        boolean changed = false;
        for (final Map.Entry<String, String> e : newSpecs.entrySet()) {
            final Skin target = resolveSync(e.getValue(), newSigs.getOrDefault(e.getKey(), ""));
            final Skin prev = resolved.put(e.getKey(), target);
            if (!Objects.equals(prev, target)) {
                changed = true;
            }
        }
        // 配置里删掉的 key 要同步清掉，否则旧皮肤会一直挂着
        for (final String key : new ArrayList<>(resolved.keySet())) {
            if (!newSpecs.containsKey(key)) {
                resolved.remove(key);
                changed = true;
            }
        }
        logSummary(newSpecs);
        return changed;
    }

    /**
     * 把「哪份皮肤认出来了、带不带签名、哪份写错了」打到日志里。
     *
     * <p>🔴 皮肤不生效是**最难排查**的一类问题：配错了不报错，客户端只是默默
     * 显示默认 Steve/Alex，看日志一片干净。v3.3.3 之前就是这样 —— 线上
     * 「颜色头像全是默认皮肤」查了半天才发现是配置里贴的是无签名写法。
     * 宁可在 reload 时多一行 INFO，也不要让人再去猜。
     */
    private void logSummary(final Map<String, String> newSpecs) {
        final StringBuilder ok = new StringBuilder();
        final StringBuilder bad = new StringBuilder();
        int signed = 0;
        for (final Map.Entry<String, String> e : newSpecs.entrySet()) {
            final String raw = e.getValue() == null ? "" : e.getValue().trim();
            if (raw.isEmpty()) {
                continue; // 留空 = 有意不配，不算问题
            }
            final Skin skin = resolved.getOrDefault(e.getKey(), Skin.NONE);
            if (skin.isEmpty()) {
                if (raw.toLowerCase(Locale.ROOT).startsWith(PREFIX_PLAYER)) {
                    ok.append(' ').append(e.getKey()).append("=待联网");
                } else {
                    bad.append(' ').append(e.getKey()).append("=[").append(head(raw)).append(']');
                }
                continue;
            }
            if (!skin.signature().isEmpty()) {
                signed++;
                ok.append(' ').append(e.getKey()).append("=签名✓");
            } else {
                ok.append(' ').append(e.getKey()).append("=无签名");
            }
        }
        if (ok.length() > 0) {
            logger.info("[vtabsort] 皮肤已就绪（" + signed + " 份带 Mojang 签名）:" + ok);
        }
        if (bad.length() > 0) {
            logger.warn("[vtabsort] 这些皮肤写法认不出来，对应位置会显示默认皮肤:" + bad
                    + " —— 哈希要 32~80 位十六进制；贴完整 base64 时签名要写在 "
                    + "<名称>-signature 里");
        }
    }

    /** 日志里别把一长串 base64 全打出来。 */
    private static String head(final String s) {
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }

    /** 同步能算出来多少算多少；{@code player:} 顺手起一个后台请求。 */
    private Skin resolveSync(final String spec, final String signature) {
        final String s = spec == null ? "" : spec.trim();
        if (s.isEmpty()) {
            return Skin.NONE;
        }
        if (s.toLowerCase(Locale.ROOT).startsWith(PREFIX_PLAYER)) {
            final String name = s.substring(PREFIX_PLAYER.length()).trim();
            if (name.isEmpty()) {
                return Skin.NONE;
            }
            final Skin cached = playerCache.get(name.toLowerCase(Locale.ROOT));
            fetchAsync(name);
            return cached == null ? Skin.NONE : cached;
        }
        final String value = normalize(s, !signature.isEmpty());
        return value.isEmpty() ? Skin.NONE : new Skin(value, signature);
    }

    /** 后台取玩家皮肤（失败就用旧缓存/没皮肤，绝不把插件拖住）。 */
    private void fetchAsync(final String name) {
        if (!inFlight.add(name.toLowerCase(Locale.ROOT))) {
            return; // 这名字已经在拉了
        }
        CompletableFuture.runAsync(() -> {
            try {
                final Skin skin = lookup(name);
                if (skin == null) {
                    logger.warn("[vtabsort] 没拿到 " + name + " 的皮肤（名字不存在或 API 限流）"
                            + " —— 沿用" + (playerCache.containsKey(name.toLowerCase(Locale.ROOT))
                            ? "缓存" : "默认皮肤"));
                    return;
                }
                playerCache.put(name.toLowerCase(Locale.ROOT), skin);
                save();
                // 把这个名字对应的所有 key 重新算一遍（可能多个服都配了同一个人）
                boolean changed = false;
                for (final Map.Entry<String, String> e : specs.entrySet()) {
                    if (!isPlayerSpec(e.getValue(), name)) {
                        continue;
                    }
                    final Skin prev = resolved.put(e.getKey(), skin);
                    if (!Objects.equals(prev, skin)) {
                        changed = true;
                    }
                }
                logger.info("[vtabsort] 已获取 " + name + " 的皮肤并缓存（头像生效）。");
                if (changed) {
                    final Runnable cb = onChange;
                    if (cb != null) {
                        cb.run();
                    }
                }
            } catch (final Exception e) {
                logger.warn("[vtabsort] 获取 " + name + " 的皮肤失败：" + e.getMessage());
            } finally {
                inFlight.remove(name.toLowerCase(Locale.ROOT));
            }
        });
    }

    /** 这段配置是不是 {@code player:<这个名字>}（忽略大小写）。 */
    private static boolean isPlayerSpec(final String spec, final String name) {
        if (spec == null || !spec.toLowerCase(Locale.ROOT).startsWith(PREFIX_PLAYER)) {
            return false;
        }
        return spec.substring(PREFIX_PLAYER.length()).trim().equalsIgnoreCase(name.trim());
    }

    /** 名字 → uuid → profile → textures（value + 签名）。 */
    private Skin lookup(final String name) throws Exception {
        final String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        final String idJson = get(URI.create(
                "https://api.mojang.com/users/profiles/minecraft/" + encoded));
        final Matcher mId = P_ID.matcher(idJson);
        if (!mId.find()) {
            return null;
        }
        final String uuid = mId.group(1);
        final String profile = get(URI.create(
                "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid
                        + "?unsigned=false"));
        final Matcher mValue = P_VALUE.matcher(profile);
        if (!mValue.find()) {
            return null;
        }
        final Matcher mSig = P_SIG.matcher(profile);
        return new Skin(mValue.group(1), mSig.find() ? mSig.group(1) : "");
    }

    private static String get(final URI uri) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        final HttpResponse<String> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    // ------------------------------------------------------------------
    // 非 player: 写法的归一化
    // ------------------------------------------------------------------

    /**
     * 纹理哈希 / texture: / URL / 完整 base64 → property 的 value。
     *
     * <p>哈希类由本插件拼 JSON（不含 signatureRequired，客户端不校验签名）；
     * base64 类：给了签名就原样用，没给就把 signatureRequired 关掉 —— 留着 true
     * 而没签名，客户端会直接丢弃纹理，皮肤等于白配。
     */
    private static String normalize(final String raw, final boolean hasSignature) {
        final String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        String hash = null;
        if (s.startsWith("texture:")) {
            hash = s.substring("texture:".length()).trim();
        } else if (s.startsWith("http://") || s.startsWith("https://")) {
            hash = s.substring(s.lastIndexOf('/') + 1).trim();
        } else if (s.matches(HASH)) {
            hash = s;
        }
        if (hash != null) {
            if (!hash.matches(HASH)) {
                return ""; // 不是合法纹理哈希：宁可不设皮肤，也别塞坏数据给客户端
            }
            final String json = "{\"timestamp\":0,\"profileId\":"
                    + "\"00000000000000000000000000000000\",\"profileName\":\"VTabSort\","
                    + "\"textures\":{\"SKIN\":{\"url\":\"" + SKIN_URL + hash + "\"}}}";
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
        // 不是哈希类：当完整 base64 value 用。解不开/不像皮肤数据就宁可留空 ——
        // 把随便一段文字塞进 textures 属性会让客户端解析失败（还不好排查）。
        if (!looksLikeSkinValue(s)) {
            return "";
        }
        return hasSignature ? s : stripSignatureRequired(s);
    }

    /** 像不像一段皮肤属性：base64 能解开，且里面有 textures 段。 */
    private static boolean looksLikeSkinValue(final String s) {
        if (!s.matches("[A-Za-z0-9+/=\\s]+")) {
            return false;
        }
        try {
            final String json =
                    new String(Base64.getMimeDecoder().decode(s), StandardCharsets.UTF_8);
            return json.contains("\"textures\"");
        } catch (final Exception e) {
            return false;
        }
    }

    private static String stripSignatureRequired(final String value) {
        try {
            final String json =
                    new String(Base64.getMimeDecoder().decode(value), StandardCharsets.UTF_8);
            final String patched = json.replaceAll("\"signatureRequired\"\\s*:\\s*true",
                    "\"signatureRequired\":false");
            return patched.equals(json) ? value
                    : Base64.getEncoder().encodeToString(patched.getBytes(StandardCharsets.UTF_8));
        } catch (final Exception e) {
            return value; // 解不开就原样用，最多皮肤不显示，别把配置搞坏
        }
    }

    // ------------------------------------------------------------------
    // 磁盘缓存（铁律 1：先读全再替换 + 临时文件 ATOMIC_MOVE）
    // ------------------------------------------------------------------

    private void load() {
        if (!Files.exists(cacheFile)) {
            return;
        }
        try (InputStream in = Files.newInputStream(cacheFile)) {
            final Properties p = new Properties();
            p.load(in);
            for (final String key : p.stringPropertyNames()) {
                final String v = p.getProperty(key, "");
                final int bar = v.indexOf('|');
                if (bar < 0) {
                    continue;
                }
                playerCache.put(key, new Skin(v.substring(0, bar), v.substring(bar + 1)));
            }
        } catch (final IOException e) {
            logger.warn("[vtabsort] 皮肤缓存读不出来（将重新拉取）：" + e.getMessage());
        }
    }

    private void save() {
        final Properties p = new Properties();
        for (final Map.Entry<String, Skin> e : playerCache.entrySet()) {
            p.setProperty(e.getKey(), e.getValue().value() + "|" + e.getValue().signature());
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            final Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "VTabSort skin cache: player name -> base64 value | signature");
            }
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            logger.warn("[vtabsort] 皮肤缓存写不进去：" + e.getMessage());
        }
    }
}
