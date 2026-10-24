package cn.shijiu.vtabsort;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简遗留颜色解析 —— 认 {@code &0-&f} 颜色、{@code &k/&l/&m/&n/&o} 样式、
 * {@code &r} 重置、{@code &#RRGGBB} 十六进制；不认识的 {@code &x} 原样保留。
 *
 * <p>v3.2 起额外认 <b>渐变</b>（和 TAB 一个写法）：{@code <#RRGGBB>文字</#RRGGBB>}
 * —— 起止色之间逐字符线性插值；段内照样认样式码（{@code <#FFFFFF>&m —— </#FFFF00>}
 * 就是一条渐变删除线）。渐变段外面的文字走原样解析，两种写法可以混着写。
 *
 * <p>设计目标：配错不炸、最多不好看。
 *
 * <p>为什么不复用 Vmessage 的 ChatColors：Velocity 插件 ClassLoader 互相隔离，
 * 跨插件引不到对方的类（铁律 2），只能每家自带一份。
 */
public final class Colors {

    private Colors() {
    }

    private static final int OBFUSCATED = 1;
    private static final int BOLD = 2;
    private static final int STRIKETHROUGH = 4;
    private static final int UNDERLINED = 8;
    private static final int ITALIC = 16;

    /** 渐变段：{@code <#RRGGBB>…</#RRGGBB>}。非贪婪 —— 一行里能写多段。 */
    private static final Pattern GRADIENT =
            Pattern.compile("<#([0-9a-fA-F]{6})>([\\s\\S]*?)</#([0-9a-fA-F]{6})>");

    /**
     * 入口：先切渐变段，段内做插值、段外用 {@link #legacy} 解析，最后拼起来。
     *
     * <p>🔴 顺序不能反：先换占位符（%online% 之类）再调这里，否则数字里的
     * 字符可能被当颜色码吃掉；反过来先解析再替换就得拆 Component 树，划不来。
     */
    public static Component parse(final String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        final List<Component> parts = new ArrayList<>();
        final Matcher m = GRADIENT.matcher(input);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                parts.add(legacy(input.substring(last, m.start())));
            }
            final TextColor from = TextColor.fromHexString("#" + m.group(1));
            final TextColor to = TextColor.fromHexString("#" + m.group(3));
            parts.add(from == null || to == null
                    ? legacy(m.group(2))          // 理论不可能（正则已保证六位十六进制）
                    : gradient(from, to, m.group(2)));
            last = m.end();
        }
        if (last < input.length()) {
            parts.add(legacy(input.substring(last)));
        }
        return join(parts);
    }

    /**
     * 前缀（可空）+ 名字：两段挂在空父组件下做<b>兄弟节点</b> —— 前缀的颜色/
     * 样式不会传染名字。flat 的前缀、layout 的玩家前缀都走这里。
     *
     * <p>前缀为空时只返回名字本身（= 不加前缀，调用方可以据此判断「要不要动
     * 显示名」，layout 模式就是靠这个跟 TAB 划清边界的）。
     */
    static Component prefixed(final String prefix, final String name) {
        if (prefix == null || prefix.isEmpty()) {
            return Component.text(name == null ? "" : name);
        }
        return prefixed(parse(prefix), name);
    }

    /**
     * 同上，但前缀是**已经解析好**的组件。
     *
     * <p>留给需要反复渲染同一批名字的调用方（flat 的显示名守卫每 0.5 秒扫所有人）：
     * 前缀按子服缓存一份 `Colors.parse(prefix)`，就不用给每个玩家重新 parse 一遍。
     */
    static Component prefixed(final Component prefix, final String name) {
        final Component text = Component.text(name == null ? "" : name);
        if (prefix == null) {
            return text;
        }
        return Component.text().append(prefix).append(text).build();
    }

    /** 原样段 + 渐变段拼成一个组件（多段挂空父组件，样式互不传染）。 */
    private static Component join(final List<Component> parts) {
        if (parts.isEmpty()) {
            return Component.empty();
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        final TextComponent.Builder parent = Component.text();
        for (final Component p : parts) {
            parent.append(p);
        }
        return parent.build();
    }

    /**
     * 渐变：起止色之间逐字符线性插值。
     *
     * <p>段内认样式码（{@code &l} 加粗、{@code &m} 删除线…）和 {@code &r} 重置，
     * 样式码本身不占可见字符（不参与插值下标）。颜色码在渐变段里没意义 ——
     * 会被插值覆盖，直接忽略。
     */
    private static Component gradient(final TextColor from, final TextColor to, final String text) {
        // 先扫一遍：剥掉样式码，记下每个可见字符当时的样式状态
        final StringBuilder glyphs = new StringBuilder();
        final List<Integer> decos = new ArrayList<>();
        int deco = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length()) {
                final char code = Character.toLowerCase(text.charAt(i + 1));
                final int d = decoOf(code);
                if (d != 0) {
                    deco |= d;
                    i++;
                    continue;
                }
                if (code == 'r') {
                    deco = 0;
                    i++;
                    continue;
                }
            }
            glyphs.append(c);
            decos.add(deco);
        }
        final int n = glyphs.length();
        if (n == 0) {
            return Component.empty();
        }
        final TextComponent.Builder parent = Component.text();
        for (int i = 0; i < n; i++) {
            // 单个字符时取起始色（n-1 会除零）
            final TextColor color = n == 1 ? from : lerp(from, to, (double) i / (n - 1));
            parent.append(decorate(Component.text(String.valueOf(glyphs.charAt(i))).color(color),
                    decos.get(i)));
        }
        return parent.build();
    }

    /** 两个颜色之间按 t(0~1) 插值。 */
    private static TextColor lerp(final TextColor from, final TextColor to, final double t) {
        return TextColor.color(
                (int) Math.round(from.red() + (to.red() - from.red()) * t),
                (int) Math.round(from.green() + (to.green() - from.green()) * t),
                (int) Math.round(from.blue() + (to.blue() - from.blue()) * t));
    }

    /** 原来的 {@code &} 码解析（渐变段之外走这条）。 */
    private static Component legacy(final String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        final List<Component> parts = new ArrayList<>();
        final StringBuilder sb = new StringBuilder();
        TextColor color = null;
        int deco = 0;
        for (int i = 0; i < input.length(); i++) {
            final char c = input.charAt(i);
            if (c != '&' || i + 1 >= input.length()) {
                sb.append(c);
                continue;
            }
            final char code = Character.toLowerCase(input.charAt(i + 1));
            // &#RRGGBB（六个十六进制位）
            // 🔴 只换颜色、**不清样式**：原版 §x 也不重置格式，而 TAB 那套
            //    "&l&#ffadbb欢&#f3b0c2迎…" 就靠这个把加粗一路带下去。
            //    （命名颜色码 &0-&f 才清样式，跟原版 § 行为一致。）
            if (code == '#' && i + 7 < input.length() && hexAt(input, i + 2)) {
                flush(parts, sb, color, deco);
                final TextColor hex = TextColor.fromHexString(input.substring(i + 1, i + 8));
                color = hex; // hexAt 已保证合法，这里不会是 null
                i += 7;
                continue;
            }
            final TextColor named = colorOf(code);
            if (named != null) {
                flush(parts, sb, color, deco);
                color = named;
                deco = 0; // 颜色码顺带清样式（跟原版 § 行为一致）
                i++;
                continue;
            }
            final int d = decoOf(code);
            if (d != 0) {
                flush(parts, sb, color, deco);
                deco |= d;
                i++;
                continue;
            }
            if (code == 'r') {
                flush(parts, sb, color, deco);
                color = null;
                deco = 0;
                i++;
                continue;
            }
            sb.append(c); // 不是颜色码：& 原样输出
        }
        flush(parts, sb, color, deco);
        return join(parts);
    }

    private static void flush(final List<Component> parts, final StringBuilder sb,
                              final TextColor color, final int deco) {
        if (sb.length() == 0) {
            return;
        }
        Component text = Component.text(sb.toString());
        if (color != null) {
            text = text.color(color);
        }
        parts.add(decorate(text, deco));
        sb.setLength(0);
    }

    /** 按位图把 {@code &k/&l/&m/&n/&o} 加到组件上。 */
    private static Component decorate(Component text, final int deco) {
        if (deco == 0) {
            return text;
        }
        if ((deco & OBFUSCATED) != 0) {
            text = text.decorate(TextDecoration.OBFUSCATED);
        }
        if ((deco & BOLD) != 0) {
            text = text.decorate(TextDecoration.BOLD);
        }
        if ((deco & STRIKETHROUGH) != 0) {
            text = text.decorate(TextDecoration.STRIKETHROUGH);
        }
        if ((deco & UNDERLINED) != 0) {
            text = text.decorate(TextDecoration.UNDERLINED);
        }
        if ((deco & ITALIC) != 0) {
            text = text.decorate(TextDecoration.ITALIC);
        }
        return text;
    }

    private static boolean hexAt(final String s, final int from) {
        for (int i = from; i < from + 6 && i < s.length(); i++) {
            final char c = s.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return true;
    }

    private static TextColor colorOf(final char c) {
        return switch (c) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            case 'f' -> NamedTextColor.WHITE;
            default -> null;
        };
    }

    private static int decoOf(final char c) {
        return switch (c) {
            case 'k' -> OBFUSCATED;
            case 'l' -> BOLD;
            case 'm' -> STRIKETHROUGH;
            case 'n' -> UNDERLINED;
            case 'o' -> ITALIC;
            default -> 0;
        };
    }
}
