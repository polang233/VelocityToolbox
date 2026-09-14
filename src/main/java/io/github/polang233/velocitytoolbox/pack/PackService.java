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

    public record Prepared(PackConfig config, Path directory, String origin, Map<String, HostedPack> packs) {
        public Prepared {
            packs = Map.copyOf(packs);
        }

        public List<HostedPack> list() {
            return List.copyOf(packs.values());
        }
    }

    public synchronized Prepared prepare(PackConfig next) throws IOException {
        Path dir = Path.of(next.packsDirectory());
        dir = (dir.isAbsolute() ? dir : dataDirectory.resolve(dir)).toAbsolutePath().normalize();
        if (!next.enabled()) return new Prepared(next, dir, null, Map.of());
        if (next.port() < 1 || next.port() > 65535) throw new IOException("Invalid pack-host.port");
        Files.createDirectories(dir);
        PackConfig old = config;
        String origin;
        try {
            config = next;
            origin = resolvePublicOrigin();
        } finally {
            config = old;
        }
        return new Prepared(next, dir, origin, PackScanner.scan(dir, origin));
    }

    public synchronized void apply(Prepared next) throws IOException {
        PackHttpServer replacement = null;
        boolean reuse = enabled() && next.config().enabled()
                && config.bind().equals(next.config().bind()) && config.port() == next.config().port();
        if (next.config().enabled() && !reuse) {
            replacement = new PackHttpServer(next.directory(), next.packs());
            try {
                replacement.bind(next.config().bind(), next.config().port());
            } catch (IOException failure) {
                replacement.close();
                // A changed bind address may overlap the currently bound port.
                if (!enabled() || config.port() != next.config().port()) throw failure;
                httpServer.close();
                httpServer = null;
                try {
                    replacement = new PackHttpServer(next.directory(), next.packs());
                    replacement.bind(next.config().bind(), next.config().port());
                } catch (IOException retry) {
                    replacement.close();
                    PackHttpServer restored = new PackHttpServer(packsDirectory, packs);
                    try {
                        restored.bind(config.bind(), config.port());
                        httpServer = restored;
                    } catch (IOException restore) {
                        restored.close();
                        retry.addSuppressed(restore);
                    }
                    throw retry;
                }
            }
        }
        if (reuse) httpServer.update(next.directory(), next.packs());
        else {
            if (httpServer != null) httpServer.close();
            httpServer = replacement;
        }
        config = next.config();
        packsDirectory = next.directory();
        publicOrigin = next.origin();
        packs.clear();
        packs.putAll(next.packs());
        if (enabled()) {
            try {
                PackSnippetWriter.write(dataDirectory.resolve("velocityresourcepacks-snippet.yml"), packsDirectory, packs());
            } catch (IOException e) {
                console.sendMessage(net.kyori.adventure.text.Component.text("Pack snippet: " + e.getMessage()));
            }
            logStatus();
        }
    }

    public synchronized void start() throws IOException {
        start(PackConfig.load(dataDirectory));
    }

    public synchronized void start(PackConfig config) throws IOException {
        apply(prepare(config));
    }

    public synchronized void reload() throws IOException {
        start();
    }

    public synchronized void reload(PackConfig config) throws IOException {
        start(config);
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
