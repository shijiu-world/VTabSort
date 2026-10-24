package cn.shijiu.vtabsort;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tab 上方的 header / 下方的 footer（v3.2，接手 TAB 卸载后的活）。
 *
 * <p>和 TAB 一样的写法：配置里是一组行，行内支持 {@code &} 颜色码、
 * {@code &#RRGGBB}、以及渐变 {@code <#RRGGBB>文字</#RRGGBB>}（见 {@link Colors}）；
 * 占位符在发送前按<b>每个玩家</b>替换（延迟/所在服都是本人的）。
 *
 * <p>🔴 两个省包的规矩：
 * <ol>
 *   <li><b>全 diff</b>：渲染出来的文本和上次一样就不发包（header 里放 %ping%
 *       时数值每几秒才变一次，逐帧重发纯属浪费带宽）；</li>
 *   <li><b>关掉只清一次</b>：enabled=false 时发一次空组件把已显示的擦掉，
 *       之后不再骚扰客户端（不然别人的 header 插件会跟我们打架）。</li>
 * </ol>
 *
 * <p>刷新时机交给 VTabSort：事件直刷 + 延迟补发 + reassert 周期校准
 * —— %ping% / %online% 就跟着一起更新了。
 */
final class HeaderFooter {

    /** 上次发给每个 viewer 的渲染结果（占位符换完的原文），用于 diff。 */
    private final Map<UUID, String> lastHeader = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastFooter = new ConcurrentHashMap<>();
    /** enabled 关掉后是否已经清过一次（避免每轮都发空包）。 */
    private volatile boolean cleared;

    private final ProxyServer proxy;

    HeaderFooter(final ProxyServer proxy) {
        this.proxy = proxy;
    }

    /** 玩家下线：把缓存的渲染结果一起收走（UUID 复用时会串）。 */
    void forget(final UUID uuid) {
        lastHeader.remove(uuid);
        lastFooter.remove(uuid);
    }

    /**
     * @param virtualTotal    demo 虚拟玩家总数（计入 %online%，跟 auto 翻面判定一致）
     * @param virtualByServer demo 虚拟玩家按服统计（计入 %server-online%）
     */
    void applyAll(final Configuration cfg, final int virtualTotal,
                  final Map<String, Integer> virtualByServer) {
        if (!cfg.headerFooterEnabled()) {
            clearAll();
            return;
        }
        cleared = false;
        for (final Player viewer : proxy.getAllPlayers()) {
            apply(viewer, cfg, virtualTotal, virtualByServer);
        }
    }

    private void apply(final Player viewer, final Configuration cfg, final int virtualTotal,
                       final Map<String, Integer> virtualByServer) {
        final String h = render(cfg.headerLines(), viewer, cfg, virtualTotal, virtualByServer);
        final String f = render(cfg.footerLines(), viewer, cfg, virtualTotal, virtualByServer);
        final UUID id = viewer.getUniqueId();
        if (h.equals(lastHeader.get(id)) && f.equals(lastFooter.get(id))) {
            return; // 没变化 —— 一个包都别发
        }
        lastHeader.put(id, h);
        lastFooter.put(id, f);
        try {
            viewer.sendPlayerListHeaderAndFooter(build(h), build(f));
        } catch (final Exception ignored) {
            // 单个 viewer 出问题（客户端断线瞬间等）不影响别人
        }
    }

    /** 关掉时把已经发出去的 header/footer 擦掉（只发一次）。 */
    private void clearAll() {
        if (cleared) {
            return;
        }
        cleared = true;
        lastHeader.clear();
        lastFooter.clear();
        for (final Player viewer : proxy.getAllPlayers()) {
            try {
                viewer.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            } catch (final Exception ignored) {
            }
        }
    }

    /** 多行拼一个组件：行间换行（空行就是空行，跟 TAB 一个观感）。 */
    private static Component build(final String rendered) {
        if (rendered == null || rendered.isEmpty()) {
            return Component.empty();
        }
        final String[] lines = rendered.split("\n", -1);
        final net.kyori.adventure.text.TextComponent.Builder out = Component.text();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append(Component.newline());
            }
            out.append(Colors.parse(lines[i]));
        }
        return out.build();
    }

    /** 把每行里的占位符换成这个玩家的实际值，行间用 \n 连起来（diff 用）。 */
    private String render(final List<String> lines, final Player viewer, final Configuration cfg,
                          final int virtualTotal, final Map<String, Integer> virtualByServer) {
        if (lines.isEmpty()) {
            return "";
        }
        final Map<String, String> vars = variables(viewer, cfg, virtualTotal, virtualByServer);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(fill(lines.get(i), vars));
        }
        return sb.toString();
    }

    /**
     * 支持的占位符：
     * {@code %online%} 全服在线（含 demo）、{@code %server%} 当前子服显示名、
     * {@code %server-raw%} 子服原名、{@code %server-online%} 当前子服人数、
     * {@code %player%} 自己的名字、{@code %ping%} 自己的延迟（毫秒）、
     * {@code %time%} HH:mm:ss、{@code %date%} yyyy-MM-dd。
     * 认不出来的占位符原样留着（不去猜，免得配错看不见）。
     */
    private Map<String, String> variables(final Player viewer, final Configuration cfg,
                                          final int virtualTotal,
                                          final Map<String, Integer> virtualByServer) {
        final Map<String, String> vars = new HashMap<>();
        // 延迟未知时 Velocity 返回 -1，页脚显示 "-1 ms" 很怪 —— 按 0 显示
        final long ping = Math.max(0L, Math.round(viewer.getPing()));
        vars.put("%player%", viewer.getUsername());
        vars.put("%ping%", String.valueOf(ping));
        vars.put("%online%", String.valueOf(proxy.getPlayerCount() + virtualTotal));
        vars.put("%time%", java.time.LocalTime.now().withNano(0).toString());
        vars.put("%date%", java.time.LocalDate.now().toString());
        final String raw = viewer.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse("");
        final String server = raw.isEmpty() ? "" : cfg.serverDisplayName(raw.toLowerCase(Locale.ROOT));
        vars.put("%server%", server.isEmpty() ? "-" : server);
        vars.put("%server-raw%", raw.isEmpty() ? "-" : raw);
        vars.put("%server-online%", String.valueOf(serverOnline(raw, virtualByServer)));
        return vars;
    }

    /** 当前子服人数：真人（按服名比，忽略大小写）+ demo 虚拟玩家。 */
    private int serverOnline(final String raw, final Map<String, Integer> virtualByServer) {
        int n = virtualByServer.getOrDefault(raw == null ? "" : raw.toLowerCase(Locale.ROOT), 0);
        if (raw == null || raw.isEmpty()) {
            return n;
        }
        for (final Player p : proxy.getAllPlayers()) {
            final String s = p.getCurrentServer().map(x -> x.getServerInfo().getName()).orElse("");
            if (raw.equalsIgnoreCase(s)) {
                n++;
            }
        }
        return n;
    }

    /** 占位符替换：只动认得的键，其他原样。 */
    private static String fill(final String line, final Map<String, String> vars) {
        String out = line;
        for (final Map.Entry<String, String> e : vars.entrySet()) {
            if (out.indexOf('%') >= 0) {
                out = out.replace(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
