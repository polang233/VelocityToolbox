package io.github.polang233.velocitytoolbox.pack;

import com.velocitypowered.api.command.CommandSource;
import io.github.polang233.velocitytoolbox.lang.Lang;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资源包托管入口：读配置、扫描 zip、启动 HTTP、写出 VelocityResourcepacks 片段。
 */
public final class PackService implements AutoCloseable {

    private final Path dataDirectory;
    private final CommandSource console;
    private final Lang lang;

    private PackConfig config;
    private Path packsDirectory;
    private String publicOrigin;
    private final Map<String, HostedPack> packs = new ConcurrentHashMap<>();
    private PackHttpServer httpServer;

    public PackService(Path dataDirectory, CommandSource console, Lang lang) {
        this.dataDirectory = dataDirectory;
        this.console = console;
        this.lang = lang;
    }

    public synchronized void start() throws IOException {
        start(PackConfig.load(dataDirectory));
    }

    public synchronized void start(PackConfig packConfig) throws IOException {
        try {
            config = packConfig;
            packsDirectory = resolvePacksDirectory();
            if (Files.exists(packsDirectory) && !Files.isDirectory(packsDirectory)) {
                throw new IOException("pack-host.packs-directory 不是目录: " + packsDirectory);
            }
            Files.createDirectories(packsDirectory);

            if (!config.enabled()) {
                return;
            }
            if (config.port() <= 0 || config.port() > 65535) {
                throw new IOException("无效的 pack-host.port: " + config.port());
            }

            publicOrigin = resolvePublicOrigin();
            packs.clear();
            packs.putAll(PackScanner.scan(packsDirectory, publicOrigin));
            httpServer = new PackHttpServer(packsDirectory, packs);
            httpServer.bind(config.bind(), config.port());
            PackSnippetWriter.write(
                    dataDirectory.resolve("velocityresourcepacks-snippet.yml"),
                    packsDirectory,
                    packs());
            logStatus();
        } catch (IOException exception) {
            close();
            throw exception;
        }
    }

    public synchronized void reload() throws IOException {
        reload(PackConfig.load(dataDirectory));
    }

    public synchronized void reload(PackConfig packConfig) throws IOException {
        close();
        start(packConfig);
    }

    public List<HostedPack> packs() {
        return packs.values().stream()
                .sorted(Comparator.comparing(HostedPack::fileName))
                .toList();
    }

    public boolean enabled() {
        return httpServer != null && httpServer.running();
    }

    public String publicOrigin() {
        return publicOrigin;
    }

    public Path packsDirectory() {
        return packsDirectory;
    }

    @Override
    public synchronized void close() {
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
        packs.clear();
        publicOrigin = null;
    }

    /**
     * 绝对路径原样使用；相对路径从 {@code plugins/VelocityToolbox/} 起算，
     * 因此 {@code ../OtherPlugin/packs} 可以指到旁边另一个插件的数据目录。
     */
    private Path resolvePacksDirectory() {
        Path configured = Path.of(config.packsDirectory());
        Path resolved = configured.isAbsolute()
                ? configured
                : dataDirectory.resolve(configured);
        return resolved.toAbsolutePath().normalize();
    }

    private String resolvePublicOrigin() throws IOException {
        String configured = config.publicUrl();
        if (!configured.isEmpty()) {
            String origin = trimSlash(configured);
            if (origin.contains("127.0.0.1") || origin.contains("localhost")) {
                console("log.pack.warn-localhost");
            }
            if (origin.contains("0.0.0.0")) {
                console("log.pack.warn-wildcard");
            }
            return origin;
        }

        List<String> candidates = LanIpv4Addresses.detect();
        if (candidates.isEmpty()) {
            String fallback = "http://127.0.0.1:" + config.port();
            console("log.pack.warn-fallback", Lang.ph("origin", fallback));
            return fallback;
        }
        if (candidates.size() > 1) {
            console("log.pack.multiple-addresses",
                    Lang.ph("addresses", String.join(", ", candidates)),
                    Lang.ph("selected", candidates.getFirst()));
        }
        return "http://" + candidates.getFirst() + ":" + config.port();
    }

    private void logStatus() {
        console("log.pack.listen", Lang.ph("bind", config.bind()), Lang.ph("port", config.port()));
        console("log.pack.origin", Lang.ph("origin", publicOrigin));
        console("log.pack.directory", Lang.ph("path", packsDirectory));
        if (packs.isEmpty()) {
            console("log.pack.empty", Lang.ph("path", packsDirectory));
            return;
        }
        for (HostedPack pack : packs()) {
            console("log.pack.item", Lang.ph("file", pack.fileName()));
            console("log.pack.item-sha1", Lang.ph("sha1", pack.sha1()));
            console("log.pack.item-url", Lang.ph("url", pack.url()));
        }
        console("log.pack.snippet",
                Lang.ph("path", dataDirectory.resolve("velocityresourcepacks-snippet.yml")));
    }

    private void console(String key, TagResolver... resolvers) {
        lang.send(console, key, resolvers);
    }

    private static String trimSlash(String origin) {
        if (origin.endsWith("/")) {
            return origin.substring(0, origin.length() - 1);
        }
        return origin;
    }
}
