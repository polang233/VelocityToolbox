package io.github.velocitytoolbox.pack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
final class PackScanner {

    private PackScanner() {
    }

    static Map<String, HostedPack> scan(Path directory, String publicOrigin) throws IOException {
        Map<String, HostedPack> packs = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            return packs;
        }
        List<Path> zipFiles;
        try (Stream<Path> stream = Files.list(directory)) {
            zipFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> isSafeZipFileName(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        for (Path zip : zipFiles) {
            String fileName = zip.getFileName().toString();
            String sha1 = sha1(zip);
            String url = publicOrigin + "/packs/" + encode(fileName);
            packs.put(fileName.toLowerCase(Locale.ROOT), new HostedPack(fileName, zip, sha1, url));
        }
        return packs;
    }

    static boolean isSafeZipFileName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            return false;
        }
        if (name.contains("..")) {
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

    static String packName(String fileName) {
        String name = fileName.replaceFirst("(?i)\\.zip$", "");
        return name.toLowerCase(Locale.ROOT).replace('.', '-');
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file);
                 DigestInputStream digestStream = new DigestInputStream(in, digest)) {
                digestStream.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("当前 JDK 不提供 SHA-1", exception);
        }
    }
}
