package io.github.polang233.velocitytoolbox.pack.host;

import com.velocitypowered.api.command.CommandSource;
import io.github.polang233.velocitytoolbox.lang.Lang;
import io.github.polang233.velocitytoolbox.pack.config.PackConfig;
import io.github.polang233.velocitytoolbox.pack.http.LanIpv4Addresses;
import io.github.polang233.velocitytoolbox.pack.http.PackHttpServer;
import io.github.polang233.velocitytoolbox.pack.http.DownloadTickets;
import io.github.polang233.velocitytoolbox.pack.config.HostLimits;
import io.github.polang233.velocitytoolbox.pack.config.PackRules;
import io.github.polang233.velocitytoolbox.config.ResourceFiles;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理 ZIP 扫描、HTTP 监听和下载保护。
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
    private final DownloadTickets tickets = new DownloadTickets();

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

    /** 生成待应用的托管快照，不修改当前监听器。 */
    public synchronized Prepared prepare(PackConfig next) throws IOException {
        return prepare(next, false);
    }

    public record CheckResult(Prepared host, PackRules rules) {
    }

    /** 读取磁盘上的候选配置，不创建目录、不改监听器，也不触发玩家更新。 */
    public CheckResult check() throws IOException {
        Path file = dataDirectory.resolve("config.yml");
        if (!Files.isRegularFile(file)) throw new IOException("configuration file not found: " + file);
        var root = ResourceFiles.loadYaml(file);
        PackConfig config = PackConfig.from(root.node("pack-host"));
        Prepared prepared = prepare(config, true);
        PackRules rules = PackRules.read(root.node("resource-packs"), prepared.list(), prepared.directory(), config.enabled());
        return new CheckResult(prepared, rules);
    }

    private Prepared prepare(PackConfig next, boolean checking) throws IOException {
        Path dir = Path.of(next.packsDirectory());
        dir = (dir.isAbsolute() ? dir : dataDirectory.resolve(dir)).toAbsolutePath().normalize();
        if (!next.enabled()) return new Prepared(next, dir, null, Map.of());
        if (next.port() < 1 || next.port() > 65535) throw new IOException("Invalid pack-host.port");
        if (checking) {
            if (!Files.isDirectory(dir)) throw new IOException("pack-host.packs-directory: directory not found: " + dir);
        } else Files.createDirectories(dir);
        String origin = resolvePublicOrigin(next, checking);
        return new Prepared(next, dir, origin, PackScanner.scan(dir, origin));
    }

    public synchronized void apply(Prepared next) throws IOException {
        PackHttpServer replacement = null;
        boolean reuse = enabled() && next.config().enabled()
                && config.bind().equals(next.config().bind()) && config.port() == next.config().port();
        if (next.config().enabled() && !reuse) {
            replacement = new PackHttpServer(next.directory(), next.packs(), next.config().limits(), tickets, this::reportHttp);
            try {
                replacement.bind(next.config().bind(), next.config().port());
            } catch (IOException failure) {
                replacement.close();
                // 同端口换网卡可能冲突；重新绑定失败时恢复旧监听。
                if (!enabled() || config.port() != next.config().port()) throw failure;
                httpServer.close();
                httpServer = null;
                try {
                    replacement = new PackHttpServer(next.directory(), next.packs(), next.config().limits(), tickets, this::reportHttp);
                    replacement.bind(next.config().bind(), next.config().port());
                } catch (IOException retry) {
                    replacement.close();
                    PackHttpServer restored = new PackHttpServer(packsDirectory, packs, config.limits(), tickets, this::reportHttp);
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
        if (reuse) httpServer.update(next.directory(), next.packs(), next.config().limits());
        else {
            if (httpServer != null) httpServer.close();
            httpServer = replacement;
        }
        config = next.config();
        packsDirectory = next.directory();
        publicOrigin = next.origin();
        packs.clear();
        packs.putAll(next.packs());
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

    public synchronized DownloadTickets.Ticket ticket(String url, long lifetimeMs) { return tickets.issue(url, lifetimeMs); }
    public synchronized HostLimits limits() { return config == null ? HostLimits.defaults() : config.limits(); }
    public synchronized String httpStatus() {
        if (httpServer == null) return "";
        var stats = httpServer.stats();
        return lang.plain("pack.host.stats-detail", Lang.ph("downloads", stats.downloads()), Lang.ph("clients", stats.clients()),
                Lang.ph("limited", stats.limited()), Lang.ph("busy", stats.busy()), Lang.ph("bytes", stats.bytes()));
    }

    private void reportHttp(PackHttpServer.Traffic traffic) {
        String detail = lang.plain("pack.host.log.traffic-detail", Lang.ph("limited", traffic.limited()),
                Lang.ph("busy", traffic.busy()), Lang.ph("timeouts", traffic.timeouts()));
        console("pack.host.log.traffic", Lang.ph("detail", detail));
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
        tickets.clear();
        publicOrigin = null;
    }

    private String resolvePublicOrigin(PackConfig next, boolean quiet) throws IOException {
        String configured = next.publicUrl();
        if (!configured.isEmpty()) {
            String origin = trimSlash(configured);
            if (!quiet && (origin.contains("127.0.0.1") || origin.contains("localhost"))) {
                console("pack.host.log.warn-localhost");
            }
            if (!quiet && origin.contains("0.0.0.0")) {
                console("pack.host.log.warn-wildcard");
            }
            return origin;
        }

        List<String> candidates = LanIpv4Addresses.detect();
        if (candidates.isEmpty()) {
            String fallback = "http://127.0.0.1:" + next.port();
            if (!quiet) console("pack.host.log.warn-fallback", Lang.ph("origin", fallback));
            return fallback;
        }
        if (!quiet && candidates.size() > 1) {
            console("pack.host.log.multiple-addresses",
                    Lang.ph("addresses", String.join(", ", candidates)),
                    Lang.ph("selected", candidates.getFirst()));
        }
        String origin = "http://" + candidates.getFirst() + ":" + next.port();
        return origin;
    }

    private void console(String key, TagResolver... resolvers) {
        lang.send(console, key, resolvers);
    }

    private static String trimSlash(String origin) {
        return origin.replaceFirst("/+$", "");
    }
}
