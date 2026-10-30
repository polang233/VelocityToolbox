package io.github.polang233.velocitytoolbox.plugins;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** 将命令参数匹配到 plugins/ 目录下已有的 JAR 文件名，不进入子目录。 */
final class PluginJarMatcher {
    private PluginJarMatcher() { }

    static boolean valid(String query) {
        if (query == null) return false;
        String name = query.trim();
        return !name.isEmpty() && !stem(name).isEmpty()
                && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0
                && name.indexOf(':') < 0 && name.indexOf('\0') < 0;
    }

    static List<String> matches(String query, List<String> filenames) {
        if (!valid(query)) return List.of();
        String requested = query.trim();
        String shortName = stem(requested);
        List<String> jars = filenames.stream()
                .filter(PluginJarMatcher::valid)
                .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".jar"))
                .sorted(Comparator.comparing((String name) -> name.toLowerCase(Locale.ROOT))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        List<String> exact = filter(jars, name -> name.equals(requested));
        if (!exact.isEmpty()) return exact;
        exact = filter(jars, name -> stem(name).equals(shortName));
        if (!exact.isEmpty()) return exact;
        exact = filter(jars, name -> name.equalsIgnoreCase(requested) || stem(name).equalsIgnoreCase(shortName));
        if (!exact.isEmpty()) return exact;
        String needle = shortName.toLowerCase(Locale.ROOT);
        List<String> prefix = filter(jars, name -> stem(name).toLowerCase(Locale.ROOT).startsWith(needle));
        if (!prefix.isEmpty()) return prefix;
        return filter(jars, name -> stem(name).toLowerCase(Locale.ROOT).contains(needle)
                || name.toLowerCase(Locale.ROOT).contains(requested.toLowerCase(Locale.ROOT)));
    }

    static List<String> suggestions(String query, List<String> filenames) {
        if (query == null || query.isBlank()) return filenames;
        // 补全所有匹配的完整文件名，输入到 ".jar" 中间时也能匹配。
        if (!valid(query)) return List.of();
        String needle = stem(query.trim()).toLowerCase(Locale.ROOT);
        return filenames.stream().filter(name -> name.toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    private static List<String> filter(List<String> values, Predicate<String> predicate) {
        return values.stream().filter(predicate).toList();
    }

    private static String stem(String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? filename.substring(0, filename.length() - 4) : filename;
    }
}
