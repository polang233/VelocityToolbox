package io.github.polang233.velocitytoolbox.plugins;

import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Filename resolution only; no test loads or unloads a plugin. */
public final class PluginJarMatcherTest {
    public static void main(String[] args) throws Exception {
        List<String> jars = List.of("ShadiaoVelocity-1.0.1.jar", "Example.jar", "Example-2.0.jar",
                "My Plugin-1.0.JAR", "OtherExample.jar", "readme.txt");
        expect("ShadiaoVelocity", jars, "ShadiaoVelocity-1.0.1.jar");
        expect("shadiaovelocity", jars, "ShadiaoVelocity-1.0.1.jar");
        expect("Velocity", jars, "ShadiaoVelocity-1.0.1.jar");
        expect("ShadiaoVelocity-1.0.1", jars, "ShadiaoVelocity-1.0.1.jar");
        expect("shadiaovelocity-1.0.1.JAR", jars, "ShadiaoVelocity-1.0.1.jar");
        expect("Example", jars, "Example.jar");
        expect("Example.jar", jars, "Example.jar");
        expect(" My Plugin ", jars, "My Plugin-1.0.JAR");
        expect("Examp", List.of("OtherExample.jar", "Example-2.jar", "Example-1.jar"),
                "Example-1.jar", "Example-2.jar");
        expect("foo.jar", List.of("foo.JAR", "foo.jar"), "foo.jar");
        expect("FOO", List.of("Foo.jar", "foo.jar"), "Foo.jar", "foo.jar");
        expect("unknown", jars);
        expect("readme", jars);
        for (String invalid : List.of("", " ", ".", "..", ".jar", "../Example", "sub/Example", "sub\\Example", "C:Example", "bad\0name")) {
            check(!PluginJarMatcher.valid(invalid), "invalid path accepted: " + invalid);
            expect(invalid, jars);
        }
        check(!PluginJarMatcher.valid(null), "null input");
        check(PluginJarMatcher.suggestions("Velocity", jars).equals(List.of("ShadiaoVelocity-1.0.1.jar")), "substring completion");
        check(PluginJarMatcher.suggestions("Example.j", jars).contains("Example.jar"), "extension completion");
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            expect("mini", List.of("MINI-1.jar"), "MINI-1.jar");
        } finally { Locale.setDefault(original); }

        Path directory = Files.createTempDirectory("vtb-plugin-names-");
        try {
            Files.writeString(directory.resolve("Example-1.jar"), "fixture");
            Files.writeString(directory.resolve("Example-2.jar"), "fixture");
            Files.createDirectory(directory.resolve("NotAFile.jar"));
            PluginLoadService service = new PluginLoadService(null,
                    LoggerFactory.getLogger(PluginJarMatcherTest.class), null, directory.resolve("velocitytoolbox"));
            var ambiguous = service.loadByFileName("example");
            check(!ambiguous.success() && ambiguous.messageKey().equals("plugin.load.ambiguous"), "ambiguous request must stop before loading");
            check(ambiguous.placeholders().get("files").equals("Example-1.jar, Example-2.jar"), "show both candidate files");
            check(service.loadByFileName("absent").messageKey().equals("plugin.load.no-match"), "missing file result");
            check(service.loadByFileName("../outside.jar").messageKey().equals("plugin.load.need-jar"), "path rejection before loading");
            check(!service.jarFileNames().contains("NotAFile.jar"), "ignore directories");
            check(service.jarSuggestions("ample").size() == 2, "service completion matches command resolution");
        } finally {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) Files.delete(file);
            }
            Files.delete(directory);
        }
        System.out.println("Plugin name tests passed: exact, short, case-insensitive, partial, ambiguous, completion and path validation.");
    }

    private static void expect(String query, List<String> names, String... expected) {
        var actual = PluginJarMatcher.matches(query, names);
        check(actual.equals(List.of(expected)), query + ": " + actual);
    }

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
}
