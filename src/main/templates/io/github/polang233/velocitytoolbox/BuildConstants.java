package io.github.polang233.velocitytoolbox;

/**
 * 由 Gradle {@code generateTemplates} 从 {@code build.gradle} 生成，供 {@code @Plugin} 使用。
 */
public final class BuildConstants {

    public static final String ID = "${pluginId}";
    public static final String NAME = "${pluginName}";
    public static final String VERSION = "${version}";
    public static final String DESCRIPTION = "${description}";
    public static final String URL = "${pluginUrl}";
    public static final String AUTHOR = "${pluginAuthor}";

    private BuildConstants() {
    }
}
