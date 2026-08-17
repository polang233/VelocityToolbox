package io.github.polang233.velocitytoolbox.pack;

import java.nio.file.Path;

/**
 * 正在托管的一个资源包 zip。
 *
 * @param fileName 对外使用的文件名（{@code /packs/} 下的最后一段）
 * @param path     磁盘上的 zip
 * @param sha1     zip 的十六进制 SHA-1，给 Minecraft 客户端校验
 * @param url      写进配置里的下载地址（{@code public-url + /packs/文件名}）
 */
public record HostedPack(String fileName, Path path, String sha1, String url) {
}
