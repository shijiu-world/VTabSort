package cn.shijiu.vtabsort;

/**
 * 版本号常量，由 {@code templating-maven-plugin} 在 generate-sources 阶段
 * 用 pom.xml 的 {@code <version>} 生成。<b>别手改这个文件</b>，改了下次构建也会被覆盖。
 *
 * <p>它存在的理由：{@code @Plugin} 注解的值必须是编译期常量，没法直接写
 * {@code ${project.version}}。velocity-api 的注解处理器会在 compile 阶段按注解的值
 * 重写 {@code velocity-plugin.json}（盖掉 {@code src/main/resources} 里那份），
 * 所以把它定为唯一真源：pom 的 {@code <version>} → 本常量 → {@code @Plugin(version=...)}
 * → 产物里的描述文件，发版只用改 pom 一处。
 */
public final class BuildConstants {

    /** 插件版本，等于 pom.xml 的 {@code <version>}。 */
    public static final String VERSION = "${project.version}";

    private BuildConstants() {
    }
}
