<p align="center">
  <img src="assets/logo.png" alt="VelocityToolbox" width="168">
</p>

# VelocityToolbox

**Velocity 运维工具箱：插件热管理、自定义资源包下发与托管、入口域名排查、子服客户端版本限制。**

[English](docs/README.en.md) · [Wiki 使用文档](https://github.com/polang233/VelocityToolbox/wiki) · [问题与建议](https://github.com/polang233/VelocityToolbox/issues)

![Velocity](https://img.shields.io/badge/Velocity-4.0%2B-654FF0)
![Java](https://img.shields.io/badge/Java-25%2B-E76F00)

## 下载与发布平台

[![GitHub Releases](https://img.shields.io/badge/GitHub-Releases-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/polang233/VelocityToolbox/releases)
[![Hangar](assets/hangar-badge.svg)](https://hangar.papermc.io/polang/VelocityToolBox)
[![Modrinth](https://img.shields.io/badge/Modrinth-Download-1BD96A?style=for-the-badge&logo=modrinth&logoColor=white)](https://modrinth.com/plugin/velocitytoolbox)
[![MineBBS](https://img.shields.io/badge/MineBBS-Download-1976D2?style=for-the-badge)](https://www.minebbs.com/resources/velocitytoolbox.18104/)
[![苦力怕论坛](https://img.shields.io/badge/KLPBBS-Download-2E7D32?style=for-the-badge)](https://klpbbs.com/thread-173633-1-1.html)

## 安装

需要 **Velocity 4.0+、Java 25+**。将 JAR 放入代理的 plugins/ 后完整启动一次，生成 config.yml。
管理员权限为 `velocitytoolbox.admin`，入口为 `/vtb help`，也可使用 `/vtoolbox`。

子服版本限制、HTTP 托管和资源包下发默认关闭。配置中提供完整样例，启用前替换文件及子服名，删除不用的样例。修改后执行 `/vtb reload`。

更新时替换 JAR 后完整重启代理。旧配置可以继续使用，想用资源包下发再补上 `resource-packs`，不加这段也不会启用下发。语言文件改动不多的话，建议备份后移走旧文件，再执行 `/vtb reload` 重新生成；自定义文案可按新键名补回。

## 模块

### 插件管理

`/vtb plugin list|inspect|load|unload|reload` 查看、检查或热管理代理插件，并报告清理结果。仍被其它插件硬依赖的插件不能卸载。权限、协议及连接管理插件建议重启代理更新。

<p align="center">
  <img src="assets/screenshot-plugin-load.png" alt="插件加载" width="720">
</p>
<p align="center">
  <img src="assets/screenshot-plugin-unload.png" alt="插件卸载与清理结果" width="720">
</p>

### 自定义资源包下发与托管

`resource-packs` 可设置全服默认资源包，再按子服、客户端版本和权限分配。`pack-host` 提供 ZIP 下载、自动哈希和访问保护。两者独立开关，可以配合使用。

- `url: "@文件.zip"`：从托管目录取文件，自动生成链接和 SHA-1。
- 外部 HTTP/HTTPS 直链：客户端从该地址下载，须填写真实 hash。
- `url: "@"`：不下发，无需文件或托管。

自托管的 `public-url` 是玩家下载地址前缀。留空自动选本机局域网地址；公网服请填可访问的 IP 或域名，自行配置端口映射或反代。

1.20.3+ 支持多包叠加；旧客户端只接收首个匹配的完整包。已选中的必需包被拒绝、失败或超时会断开玩家。
用 `/vtb pack list`、`status 玩家`、`resend 玩家` 查看或重新下发。

<p align="center">
  <img src="assets/screenshot-packs.png" alt="客户端资源包下载提示" width="720">
</p>

### 子服与入口

`/vtb server hosts` 按加入域名分组显示在线玩家、端口和延迟，点击展开玩家详情。

<p align="center">
  <img src="assets/screenshot-vhosts.jpg" alt="按入口域名分组查看玩家" width="720">
</p>

`server-versions` 按子服限制客户端的版本范围、允许列表和排除列表；无需 ViaVersion，但不提供协议转换。

<p align="center">
  <img src="assets/screenshot-server-versions.png" alt="子服客户端版本限制" width="720">
</p>

## 状态与文档

`/vtb info` 按模块展示运行状态；`/vtb reload` 重载配置、语言、版本规则和资源包，不重载其它插件。

[模块、命令和权限](https://github.com/polang233/VelocityToolbox/wiki/Modules) · [资源包原理与配置](https://github.com/polang233/VelocityToolbox/wiki/Resource-Packs) · [子服版本限制](https://github.com/polang233/VelocityToolbox/wiki/Server-Versions)

界面支持简体中文、繁体中文、英文和自定义语言文件，玩家消息使用 MiniMessage。language 留空跟随系统语言，无对应翻译时回退中文。bStats 可在 plugins/bStats/config.txt 中关闭。

[问题与建议](https://github.com/polang233/VelocityToolbox/issues) · [维护文档](docs/maintainer/README.md)

[![bStats](https://bstats.org/signatures/velocity/VelocityToolbox.svg)](https://bstats.org/plugin/velocity/VelocityToolbox/33451)
