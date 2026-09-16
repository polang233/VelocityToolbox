package io.github.polang233.velocitytoolbox.pack.host;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/** 扫描时的文件和哈希快照，下载前核对文件状态。 */
public record HostedPack(String fileName, Path path, String sha1, String url,
                         long size, FileTime modified, Object fileKey) {
    public HostedPack(String fileName, Path path, String sha1, String url) {
        this(fileName, path, sha1, url, -1, null, null);
    }
    public boolean matches(BasicFileAttributes attrs) {
        return attrs.isRegularFile() && !attrs.isSymbolicLink() && attrs.size() == size
                && Objects.equals(attrs.lastModifiedTime(), modified) && Objects.equals(attrs.fileKey(), fileKey);
    }
}
