package io.github.polang233.velocitytoolbox.pack.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.Channels;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 扫描资源包目录：允许 Unicode 文件名，但拒绝路径穿越和控制字符。
 */
public final class PackScanner {

    private PackScanner() {
    }

    static Map<String, HostedPack> scan(Path directory, String publicOrigin) throws IOException {
        Map<String, HostedPack> packs = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            return packs;
        }
        directory = directory.toRealPath();
        List<Path> zipFiles;
        try (Stream<Path> stream = Files.list(directory)) {
            zipFiles = stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> isSafeZipFileName(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        for (Path zip : zipFiles) {
            String fileName = zip.getFileName().toString();
            BasicFileAttributes before = Files.readAttributes(zip, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!zip.toRealPath().getParent().equals(directory)) throw new IOException("Pack is outside hosting directory: " + fileName);
            String sha1 = sha1(zip);
            BasicFileAttributes after = Files.readAttributes(zip, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(before.fileKey(), after.fileKey()) || !after.isRegularFile())
                throw new IOException("Pack changed during scan; retry reload: " + fileName);
            String url = publicOrigin + "/packs/" + encode(fileName);
            if (packs.putIfAbsent(fileName.toLowerCase(Locale.ROOT), new HostedPack(fileName, zip, sha1, url,
                    after.size(), after.lastModifiedTime(), after.fileKey())) != null)
                throw new IOException("Duplicate pack filename ignoring case: " + fileName);
        }
        return packs;
    }

    public static boolean isSafeZipFileName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            return false;
        }
        if (name.contains("..") || name.indexOf(':') >= 0) {
            return false;
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static String encode(String fileName) {
        return URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Channels.newInputStream(Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 DigestInputStream digestStream = new DigestInputStream(in, digest)) {
                digestStream.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-1 is unavailable in this JDK", exception);
        }
    }
}
