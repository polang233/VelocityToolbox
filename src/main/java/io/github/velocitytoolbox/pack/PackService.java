package io.github.velocitytoolbox.pack;

import org.slf4j.Logger;

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

    private final Logger logger;
    private final Path dataDirectory;

    private PackConfig config;
    private Path packsDirectory;
    private String publicOrigin;
    private final Map<String, HostedPack> packs = new ConcurrentHashMap<>();
    private PackHttpServer httpServer;

    public PackService(Logger logger, Path dataDirectory) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    public synchronized void start() throws IOException {
        try {
            config = PackConfig.load(dataDirectory);
            packsDirectory = resolvePacksDirectory();
            if (Files.exists(packsDirectory) && !Files.isDirectory(packsDirectory)) {
                throw new IOException("pack-host.packs-directory 不是目录: " + packsDirectory);
            }
            Files.createDirectories(packsDirectory);

            if (!config.enabled()) {
                logger.info("资源包托管已关闭。");
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
        close();
        start();
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
                logger.warn("pack-host.public-url 使用了 localhost。只有本机客户端能下载资源包。");
            }
            if (origin.contains("0.0.0.0")) {
                logger.warn("pack-host.public-url 使用了 0.0.0.0。请改成玩家能访问的局域网 IP 或域名。");
            }
            return origin;
        }

        List<String> candidates = LanIpv4Addresses.detect();
        if (candidates.isEmpty()) {
            String fallback = "http://127.0.0.1:" + config.port();
            logger.warn("没有找到局域网 IPv4，暂用 {}。若玩家从其它机器加入，请设置 pack-host.public-url。", fallback);
            return fallback;
        }
        if (candidates.size() > 1) {
            logger.info("检测到多个局域网地址 {}，使用第一个。不对的话请设置 pack-host.public-url。", candidates);
        }
        return "http://" + candidates.getFirst() + ":" + config.port();
    }

    private void logStatus() {
        logger.info("资源包托管监听 {}:{}", config.bind(), config.port());
        logger.info("客户端下载来源: {}", publicOrigin);
        logger.info("资源包目录 {}", packsDirectory);
        if (packs.isEmpty()) {
            logger.warn("没有找到资源包。把 .zip 放到 {}，或修改 pack-host.packs-directory。", packsDirectory);
            return;
        }
        for (HostedPack pack : packs()) {
            logger.info("资源包 {} sha1={} url={}", pack.fileName(), pack.sha1(), pack.url());
        }
        logger.info("VelocityResourcepacks 片段已写入 {}", dataDirectory.resolve("velocityresourcepacks-snippet.yml"));
    }

    private static String trimSlash(String origin) {
        if (origin.endsWith("/")) {
            return origin.substring(0, origin.length() - 1);
        }
        return origin;
    }
}
