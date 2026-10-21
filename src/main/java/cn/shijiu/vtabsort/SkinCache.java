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
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 假条目的皮肤（[layout].default-skin）。
 *
 * <p>四种写法：
 * <ol>
 *   <li>{@code player:<名字>} —— 自动去 Mojang API 取该玩家的皮肤（带有效签名），
 *       结果落盘缓存；reload 时重新拉一遍。</li>
 *   <li>纹理哈希（裸 64 位十六进制 / {@code texture:<哈希>} / textures 的完整 URL）</li>
 *   <li>完整的 base64 value（可配 signature）</li>
 *   <li>留空 = 默认 Steve/Alex</li>
 * </ol>
 * 后两种（以及第二种）由本类自己拼属性：关键是**不写 signatureRequired**，
 * 客户端就不会校验签名（我们没有 Mojang 私钥，空签名会被拒绝、退回默认皮肤）。
 * 只有 {@code player:} 走 Mojang 拿到的 value 才带真签名，那时两个都有、原样用。
 *
 * <p>线程模型：网络请求一律 {@code CompletableFuture.runAsync}，绝不阻塞事件线程；
 * 拿到结果后回调 → 由调用方（VTabSort）调度回主线程重建假条目。
 */
final class SkinCache {

    /** 一份皮肤：property 的 value + 签名（都可以是空串）。 */
    record Skin(String value, String signature) {

        static final Skin NONE = new Skin("", "");
    }

    private static final Pattern P_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_VALUE = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_SIG = Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]+)\"");
    /** 纹理哈希：64 位十六进制。 */
    private static final String HASH = "[0-9a-fA-F]{64}";
    private static final String SKIN_URL = "http://textures.minecraft.net/texture/";

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
    /** 名字（小写）→ 皮肤；内存缓存 + 落盘，起服不用等网络。 */
    private final Map<String, Skin> cache = new ConcurrentHashMap<>();
    /** 当前生效的皮肤（fakeProfile 直接读它）。 */
    private volatile Skin current = Skin.NONE;
    /** 防止连续 reload 打出并发请求（后一个丢了也无所谓，前一个会写回缓存）。 */
    private final AtomicBoolean fetching = new AtomicBoolean(false);

    SkinCache(final Logger logger, final Path dataDirectory) {
        this.logger = logger;
        this.cacheFile = dataDirectory.resolve("skin-cache.properties");
        load();
    }

    Skin current() {
        return current;
    }

    /**
     * reload 时调用。
     *
     * <p>同步阶段：非 {@code player:} 的写法直接算出来；{@code player:} 先用磁盘
     * 缓存顶上（起服瞬间就有皮肤，不用等网络）。
     * 然后后台拉一次（仅 {@code player:}），拉到后写缓存 + 触发 onChange。
     */
    void refresh(final String spec, final String signature, final Runnable onChange) {
        final String s = spec == null ? "" : spec.trim();
        if (s.isEmpty()) {
            current = Skin.NONE;
            return;
        }
        if (s.toLowerCase(Locale.ROOT).startsWith("player:")) {
            final String name = s.substring("player:".length()).trim();
            if (name.isEmpty()) {
                current = Skin.NONE;
                return;
            }
            final Skin cached = cache.get(name.toLowerCase(Locale.ROOT));
            if (cached != null) {
                current = cached;
            }
            fetchAsync(name, onChange);
            return;
        }
        current = new Skin(normalize(s, !signature.isEmpty()), signature);
    }

    /** 后台取玩家皮肤（失败就用旧缓存/没皮肤，绝不把插件拖住）。 */
    private void fetchAsync(final String name, final Runnable onChange) {
        if (!fetching.compareAndSet(false, true)) {
            return; // 上一把还没回来，别重复打 API（Mojang 有限流）
        }
        CompletableFuture.runAsync(() -> {
            try {
                final Skin skin = lookup(name);
                if (skin == null) {
                    logger.warn("[vtabsort] 没拿到 " + name + " 的皮肤（名字不存在或 API 限流）"
                            + " —— 沿用" + (cache.containsKey(name.toLowerCase(Locale.ROOT))
                            ? "缓存" : "默认皮肤"));
                    return;
                }
                cache.put(name.toLowerCase(Locale.ROOT), skin);
                save();
                current = skin;
                logger.info("[vtabsort] 已获取 " + name + " 的皮肤并缓存（假人头像生效）。");
                onChange.run();
            } catch (final Exception e) {
                logger.warn("[vtabsort] 获取 " + name + " 的皮肤失败：" + e.getMessage());
            } finally {
                fetching.set(false);
            }
        });
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
                cache.put(key, new Skin(v.substring(0, bar), v.substring(bar + 1)));
            }
        } catch (final IOException e) {
            logger.warn("[vtabsort] 皮肤缓存读不出来（将重新拉取）：" + e.getMessage());
        }
    }

    private void save() {
        final Properties p = new Properties();
        for (final Map.Entry<String, Skin> e : cache.entrySet()) {
            p.setProperty(e.getKey(), e.getValue().value() + "|" + e.getValue().signature());
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            final Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "VTabSort skin cache: name -> base64 value | signature");
            }
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            logger.warn("[vtabsort] 皮肤缓存写不进去：" + e.getMessage());
        }
    }
}
