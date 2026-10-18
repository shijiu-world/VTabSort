package cn.shijiu.vtabsort;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简遗留颜色解析 —— 认 {@code &0-&f} 颜色、{@code &k/&l/&m/&n/&o} 样式、
 * {@code &r} 重置、{@code &#RRGGBB} 十六进制；不认识的 {@code &x} 原样保留。
 * 设计目标：配错不炸、最多不好看。
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

    public static Component parse(final String input) {
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
            if (code == '#' && i + 7 < input.length() && hexAt(input, i + 2)) {
                flush(parts, sb, color, deco);
                final TextColor hex = TextColor.fromHexString(input.substring(i + 1, i + 8));
                color = hex; // hexAt 已保证合法，这里不会是 null
                deco = 0;
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
        if (parts.isEmpty()) {
            return Component.empty();
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        // 多段挂到一个空父组件上做兄弟节点：互不传染样式（父自身无样式）
        final TextComponent.Builder parent = Component.text();
        for (final Component p : parts) {
            parent.append(p);
        }
        return parent.build();
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
        parts.add(text);
        sb.setLength(0);
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
