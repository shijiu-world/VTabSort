package cn.shijiu.vtabsort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 TOML 解析 —— 只认本插件 config.toml 里用到的那点语法：
 * <pre>
 *   [section]            # 分节，支持 [a.b] 这种写法
 *   key = "值"           # 引号可有可无
 *   key = ["a", "b"]     # 单行数组
 *   # 注释
 * </pre>
 *
 * <p>为什么自己写：第三方 TOML 库（toml4j）要额外依赖，而 Velocity 插件的 jar 里
 * 默认不打依赖，也没有 maven-shade 可用（离线环境装不上插件），带出去的类在运行时会
 * NoClassDefFoundError。这个 parser 零依赖，格式错了也不会炸，顶多取到默认值。
 *
 * <p>结果是一张扁平表：分节名用点拼到键前面，例如 {@code [cooldown] seconds = 5}
 * 变成 {@code "cooldown.seconds" = 5L}。
 */
public final class TomlLite {

    private TomlLite() {
    }

    /** 读文件并按 UTF-8 解析；顺手干掉 Windows 记事本爱加的 BOM。 */
    public static Map<String, Object> parse(final Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return parse(text);
    }

    public static Map<String, Object> parse(final String text) {
        final Map<String, Object> out = new LinkedHashMap<>();
        String prefix = "";
        // 跨行数组：还没读到收尾的 ] 时，把后续行攒在这里
        StringBuilder pending = null;
        for (final String rawLine : text.split("\n", -1)) {
            String line = stripComment(rawLine).trim();
            if (pending != null) {
                pending.append(' ').append(line);
                if (unbalancedBracket(pending.toString())) {
                    continue;
                }
                line = pending.toString();
                pending = null;
            }
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                // ⚠️ 用「第一个 ]」而不是「最后一个 ]」：写成
                //    [messages]   # 提示语 [重要]
                //    时 lastIndexOf 会抓到注释里那个 ]，整段的键都跑到错误前缀下面去。
                //    注释在 stripComment 那一步已经剥掉了，所以第一个 ] 就是段名的收尾。
                final int end = line.indexOf(']');
                if (end > 1) {
                    final String section = unquote(line.substring(1, end).trim());
                    prefix = section.isEmpty() ? "" : section + ".";
                }
                continue;
            }
            final int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            final String key = unquote(line.substring(0, eq).trim());
            if (key.isEmpty()) {
                continue;
            }
            final String value = line.substring(eq + 1).trim();
            if (value.startsWith("[") && unbalancedBracket(value)) {
                // 数组跨行了（list = [\n "bedwars",\n]），接着读下一行
                pending = new StringBuilder(line);
                continue;
            }
            out.put(prefix + key, parseValue(value));
        }
        return out;
    }

    /**
     * 剥掉行尾注释（引号里的 # 不算）。
     *
     * <p>为什么必须做这一步：以前只有「带引号的值」会截到闭合引号，
     * 没引号的值会把注释一起吞进去 —— {@code allow-by-default = true # 默认允许}
     * 会解析成字符串 {@code "true # 默认允许"}，再丢给 {@code Boolean.parseBoolean}
     * 就变成了 **false**。一个空格加 # 就把开关翻过来，而且日志里毫无痕迹。
     *
     * <p>按 TOML 规范：# 只有前面是空白时才算注释，所以 {@code abc#def} 这种仍然是内容。
     * ⚠️ 唯一例外是**行首的 #**（i == 0）：整行注释。少了这条，
     * {@code # lobby = "player:Notch"} 这种"注释掉的示例"会被当成真配置
     * —— 键名变成 {@code "servers.skins.# lobby"}，动态扫前缀的功能（如
     * [servers.skins]）就会凭空多出一个叫 "# lobby" 的服。
     */
    private static String stripComment(final String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** 方括号有没有配好（引号里的不算）—— 跨行数组就靠它判断要不要接着读。 */
    private static boolean unbalancedBracket(final String s) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']' && depth > 0) {
                depth--;
            }
        }
        return depth > 0;
    }

    private static Object parseValue(final String value) {
        if (value.isEmpty()) {
            return "";
        }
        final char first = value.charAt(0);
        if (first == '"' || first == '\'') {
            final int end = endOfQuoted(value, first);
            final String inner = end < 0 ? value.substring(1) : value.substring(1, end);
            return unescape(inner);
        }
        if (first == '[') {
            return parseArray(value);
        }
        return coerce(value);
    }

    /**
     * 找收尾的引号。没找到就返回 -1，调用方容错到行尾。
     *
     * <p>⚠️ 遇到反斜杠要连后面那个字符一起跳过：写成 {@code "a\\"}（内容是单个反斜杠）时，
     * 只看「前一个字符是不是 \」会把结尾那个被引号…… 前导的反斜杠误判成转义，
     * 于是永远找不到闭合引号。
     */
    private static int endOfQuoted(final String value, final char quote) {
        for (int i = 1; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == quote) {
                return i;
            }
        }
        return -1;
    }

    /** 解析数组（支持跨行，见 {@link #parse}）。 */
    private static List<String> parseArray(final String value) {
        final List<String> list = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 1; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (quote != 0) {
                if (c == '\\' && i + 1 < value.length()) {
                    // 保留转义序列本身，交给 unescape 统一处理（跟标量值一个待遇）
                    current.append('\\').append(value.charAt(i + 1));
                    i++;
                } else if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == ']') {
                break;
            }
            if (c == ',') {
                add(list, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        add(list, current);
        return list;
    }

    private static void add(final List<String> list, final StringBuilder s) {
        final String item = unescape(s.toString().trim());
        if (!item.isEmpty()) {
            list.add(item);
        }
    }

    private static Object coerce(final String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        try {
            return Long.valueOf(value);
        } catch (final NumberFormatException ignored) {
            // 不是整数，接着试小数
        }
        try {
            return Double.valueOf(value);
        } catch (final NumberFormatException ignored) {
            // 都不是那就当字符串
        }
        return unescape(value);
    }

    private static String unquote(final String s) {
        if (s.length() >= 2) {
            final char first = s.charAt(0);
            final char last = s.charAt(s.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static String unescape(final String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        final StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            final char next = s.charAt(++i);
            switch (next) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case '\\': out.append('\\'); break;
                case '"': out.append('"'); break;
                case '\'': out.append('\''); break;
                default: out.append('\\').append(next); break;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // 取值的小工具（类型不对就退回默认值，绝不因为配错了把插件搞挂）
    // ------------------------------------------------------------------

    public static String string(final Map<String, Object> map, final String key, final String def) {
        final Object o = map.get(key);
        return o == null ? def : String.valueOf(o);
    }

    public static boolean bool(final Map<String, Object> map, final String key, final boolean def) {
        final Object o = map.get(key);
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o != null) {
            return Boolean.parseBoolean(String.valueOf(o).trim());
        }
        return def;
    }

    public static long integer(final Map<String, Object> map, final String key, final long def) {
        final Object o = map.get(key);
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o != null) {
            try {
                return Long.parseLong(String.valueOf(o).trim());
            } catch (final NumberFormatException ignored) {
                // 写歪了走默认
            }
        }
        return def;
    }

    public static double decimal(final Map<String, Object> map, final String key, final double def) {
        final Object o = map.get(key);
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o != null) {
            try {
                return Double.parseDouble(String.valueOf(o).trim());
            } catch (final NumberFormatException ignored) {
                // 写歪了走默认
            }
        }
        return def;
    }

    /** 取字符串列表：配置里写成数组；只写了一个字符串也认。 */
    @SuppressWarnings("unchecked")
    public static List<String> list(final Map<String, Object> map, final String key, final List<String> def) {
        final Object o = map.get(key);
        if (o instanceof List) {
            return new ArrayList<>((List<String>) o);
        }
        if (o instanceof String && !((String) o).isEmpty()) {
            final List<String> one = new ArrayList<>();
            one.add((String) o);
            return one;
        }
        return def == null ? new ArrayList<>() : new ArrayList<>(def);
    }

    /** 把数组里的字符串全部转小写，用于服名这类不区分大小写的比较。 */
    public static List<String> lowercase(final List<String> in) {
        final List<String> out = new ArrayList<>(in.size());
        for (final String s : in) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
