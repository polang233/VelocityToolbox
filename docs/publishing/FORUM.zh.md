# VelocityToolbox

Velocity 运维工具箱：插件热管理、自定义资源包下发与托管、入口域名排查、子服客户端版本限制。

![VelocityToolbox](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/logo-256.png)

[下载插件](https://github.com/polang233/VelocityToolbox/releases/latest) · [Wiki 使用文档](https://github.com/polang233/VelocityToolbox/wiki) · [项目源码](https://github.com/polang233/VelocityToolbox) · [问题与建议](https://github.com/polang233/VelocityToolbox/issues)

需要 **Velocity 4.0+、Java 25+**，无硬前置。

## 插件热管理

用 `/vtb plugin list|inspect|load|unload|reload` 查看、检查或热管理代理插件。卸载前检查依赖，操作后报告命令、监听器、任务等资源的清理结果。

仍被其它插件硬依赖的插件不能卸载。热管理不适合所有插件，权限、协议或连接管理插件建议完整重启代理后更新。

![插件加载](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-plugin-load.png)

![插件卸载与清理结果](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-plugin-unload.png)

## 自定义资源包下发与托管

在 `config.yml` 的 `resource-packs` 中设置全服默认包，也可按子服、客户端版本和权限分配。每个包从上往下选择首个匹配的变体，未匹配则跳过。具体子服的分配替换全服默认分配。

支持必需或可选包、自定义提示、加载超时、状态查询和手动重发。已选中的必需包被拒绝、加载失败或超时会断开玩家。进服、切服和配置重载时自动更新，没变的包不重复发送。

- `url: "@文件.zip"`：使用 `pack-host.packs-directory` 中的文件，自动生成下载链接和 SHA-1，无需填写 `hash`。
- 外部 HTTP/HTTPS 直链：填写实际 ZIP 的 40 位 SHA-1，用于缓存、识别更新和下载校验，无需开启自托管。
- `url: "@"`：不下发这个包，无需文件、哈希或托管。

1.20.3+ 支持多包叠加；旧客户端只接收首个匹配的完整包。版本条件只负责选包，不转换材质格式。现代客户端只撤下 VTB 自己发送的包，旧客户端无法单独撤包。

![客户端资源包提示](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-packs.png)

`pack-host` 提供本地 ZIP 的 HTTP 下载，与下发分别开关，两者默认关闭。可在 `pack-host.security` 中调整限频、并发、下载期限、带宽和可信反代。

`public-url` 是玩家下载地址的前缀，留空自动选择本机局域网地址，公网玩家通常无法访问。公网服需填写可访问的 IP 或域名，并自行配置端口映射或反代。插件不会自动配置公网入口或 HTTPS，也不提供下载鉴权。

[资源包配置与原理](https://github.com/polang233/VelocityToolbox/wiki/Resource-Packs) · [带注释的默认配置](https://github.com/polang233/VelocityToolbox/wiki/Configuration)

## 子服与入口

`/vtb server hosts` 按玩家加入时使用的域名和端口分组，查看人数与延迟，点击展开玩家详情。

![按入口域名查看玩家](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-vhosts.jpg)

`server-versions` 按子服限制客户端版本，支持 `min/max/allow/deny`，与资源包的版本条件使用同一套规则。无需 ViaVersion，但不提供协议转换；共用协议的版本会一起匹配。

切服被拒时保留当前子服，首次进入被拒时显示断开原因。配置重载失败保留旧规则，首次加载失败则阻止子服连接，修复后重载即可。

![子服客户端版本限制](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-server-versions.png)

[子服版本限制说明](https://github.com/polang233/VelocityToolbox/wiki/Server-Versions)

## 安装与使用

1. 下载 JAR，放入代理的 `plugins/` 目录，完整启动代理一次。
2. 给管理员授予 `velocitytoolbox.admin`，用 `/vtb help` 查看命令，也可使用 `/vtoolbox`。
3. 按需编辑插件数据目录中的 `config.yml`，替换示例文件和子服名，删除不用的样例后再启用模块。
4. 执行 `/vtb reload` 应用配置，用 `/vtb info` 查看各模块状态。

子服版本限制、HTTP 托管和资源包下发默认关闭。更新插件时替换 JAR 后完整重启代理。旧配置可继续使用，想用资源包下发再补上 `resource-packs`，不加这段也不会启用下发。语言文件改动不多的话，建议备份后移走旧文件，再执行 `/vtb reload` 重新生成，自定义文案按新键名补回。

常用命令：

- `/vtb plugin list`、`inspect 插件ID`、`load 文件.jar`、`unload 插件ID`、`reload 插件ID`：查看或管理插件。
- `/vtb pack list`：查看包来源、匹配条件、托管文件和 HTTP 统计。
- `/vtb pack status 玩家`、`/vtb pack resend 玩家`：查看加载状态或重新下发。
- `/vtb server hosts`：查看玩家入口。
- `/vtb info`、`/vtb reload`：查看模块状态或重载配置、语言和规则。

管理员权限为 `velocitytoolbox.admin`。细分授权需要基础权限 `velocitytoolbox.command`，再加模块与动作权限。例如查看资源包状态还需 `velocitytoolbox.command.pack` 和 `velocitytoolbox.command.pack.status`。公共命令使用 `velocitytoolbox.command.info` 或 `velocitytoolbox.command.reload`。

[完整命令与权限](https://github.com/polang233/VelocityToolbox/wiki/Modules)

## 语言与反馈

支持简体中文 `zh_cn`、繁体中文 `zh_tw`、英文 `en_us` 和自定义 MiniMessage 语言文件。`language` 留空跟随系统语言，无对应翻译时回退简体中文。

[语言文件说明](https://github.com/polang233/VelocityToolbox/wiki/Language) · [提交问题与建议](https://github.com/polang233/VelocityToolbox/issues)

插件使用 bStats 统计，可在 `plugins/bStats/config.txt` 中关闭。

[![bStats](https://bstats.org/signatures/velocity/VelocityToolbox.svg)](https://bstats.org/plugin/velocity/VelocityToolbox/33451)
