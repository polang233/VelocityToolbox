# VelocityToolbox

把常用的 Velocity 运维能力收进一个轻量工具箱：运行时插件管理、入口域名排查，以及可选的资源包 HTTP 托管。

- 源码：[GitHub](https://github.com/polang233/VelocityToolbox)
- 下载：[Releases](https://github.com/polang233/VelocityToolbox/releases)
- 问题与建议：[Issues](https://github.com/polang233/VelocityToolbox/issues)

环境：**Velocity 4.0+**，**Java 25+**，无硬前置。资源包分配可搭配 [VelocityResourcepacks 1.9.0+](https://modrinth.com/plugin/velocityresourcepacks)。

![VelocityToolbox](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/logo-256.png)

## 能做什么

- **少重启一次代理**：加载、卸载或重载 `plugins/` 里的 Velocity 插件；操作前可只读检查风险，操作后报告清理结果。
- **排查多入口网络**：`/vtb vhosts` 按玩家加入时用的域名分组，先显示入口概要；点击入口行展开玩家名和延迟，悬停可看完整信息。
- **资源包就地托管**：一次托管任意数量的 `.zip`，自动算 SHA-1，并生成支持多包叠加的 VelocityResourcepacks 配置片段。资源包 HTTP 服务默认关闭。

按入口域名查看人数和延迟：

![按入口查看在线玩家](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-vhosts.jpg)

热加载插件：

![热加载插件](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-plugin-load.png)

热卸载插件，并报告清理结果：

![热卸载插件](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-plugin-unload.png)

资源包托管启用后，玩家进服会收到标准下载提示：

![资源包下载提示](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-packs.png)

## 安装

1. 从 [Releases](https://github.com/polang233/VelocityToolbox/releases) 下载 JAR，放入 Velocity 的 `plugins/`。
2. 完整启动代理一次，生成 `plugins/VelocityToolbox/config.yml`。
3. 给管理员授予 `velocitytoolbox.admin`，或按下方权限表细分授权；使用 `/vtoolbox help` 或 `/vtb help` 查看命令。

## 命令

主命令别名是 `/vtb`。`velocitytoolbox.admin` 仍可作为全部命令的兼容权限。普通查询不会刷后台；插件加载、卸载、重载和配置重载只输出简短状态。

| 命令 | 作用 |
| --- | --- |
| `/vtoolbox help` | 显示帮助 |
| `/vtoolbox info` | 插件、代理、Java、插件数量和资源包托管概要 |
| `/vtoolbox packs` | 列出资源包 URL 和 SHA-1 |
| `/vtoolbox vhosts` | 按入口分组显示域名、端口和人数；点击展开玩家名与延迟 |
| `/vtoolbox reload` | 重载语言、配置与资源包托管 |
| `/vtoolbox plugin list` | 名称、版本和作者；悬停看完整元数据 |
| `/vtoolbox plugin inspect 插件ID` | 按基本信息、依赖、运行时资源和风险四段检查 |
| `/vtoolbox plugin load 文件.jar` | 从 `plugins/` 加载插件 |
| `/vtoolbox plugin unload 插件ID` | 卸载插件 |
| `/vtoolbox plugin reload 插件ID` | 卸载后重新加载 |

### 细分权限

不用 `velocitytoolbox.admin` 时，必须先有 `velocitytoolbox.command`，再授予对应子命令权限。

普通子命令：

- `velocitytoolbox.command.info`
- `velocitytoolbox.command.packs`
- `velocitytoolbox.command.vhosts`
- `velocitytoolbox.command.reload`

插件管理父权限：

- `velocitytoolbox.command.plugin`

插件管理动作：

- `velocitytoolbox.command.plugin.list`
- `velocitytoolbox.command.plugin.inspect`
- `velocitytoolbox.command.plugin.load`
- `velocitytoolbox.command.plugin.unload`
- `velocitytoolbox.command.plugin.reload`

例如只允许查看插件风险，需要同时授予 `velocitytoolbox.command`、`velocitytoolbox.command.plugin` 和 `velocitytoolbox.command.plugin.inspect`。帮助只显示执行者有权使用的子命令。

## 可选资源包托管

资源包托管默认关闭。启用后会在代理机器上启动 HTTP 服务，扫描目录内的 zip，并生成 `velocityresourcepacks-snippet.yml`。每个 zip 都有独立 URL、SHA-1 和 `local-path`。由 VelocityResourcepacks 决定发给哪些玩家；本插件不直接发包。

```yaml
pack-host:
  enabled: false
  bind: 0.0.0.0
  port: 8765
  public-url: ""          # 外网使用时填写玩家可以访问的地址
  packs-directory: packs  # 默认 plugins/VelocityToolbox/packs
```

启用步骤：

1. 把 `.zip` 放入 `packs-directory`。
2. 把 `enabled` 改为 `true`；外网使用时配置防火墙 / 反向代理和 `public-url`。
3. 执行 `/vtoolbox reload`，再把生成的配置片段合并进 VelocityResourcepacks。

生成片段的 `global.packs` 会按文件名顺序列出全部 zip。Minecraft 1.20.3+ 客户端可依次叠加多个资源包，旧客户端只使用列表第一项。该字段需要 VelocityResourcepacks 1.9.0+。

本插件不会自动配置端口映射、域名或 HTTPS。`public-url` 留空时会尝试使用第一块局域网 IPv4；不要把 `0.0.0.0` 当作玩家下载地址。

## 热管理注意

Velocity 4.0+ 没有公开的插件加载 / 卸载 API。VelocityToolbox 会阻止卸载仍被其它插件硬依赖的目标，并尽量清理监听器、任务、命令、消息通道、线程池与类加载器，但不能保证任意第三方插件都能安全热卸载。

简单工具插件适合在测试后热重载；权限、协议 / 数据包、连接管理或大型缓存插件更新后，仍建议完整重启代理。实现边界见 [架构说明](https://github.com/polang233/VelocityToolbox/blob/main/docs/ARCHITECTURE.md)。

## 语言与统计

`language` 留空时跟随服务器系统语言，没有对应语言文件时回退中文；也可固定为 `zh_cn`、`en_us` 或 `lang/` 下的自定义文件名。玩家消息支持 MiniMessage。`/vtoolbox reload` 会重载语言。

## 使用统计
[![bStats](https://bstats.org/signatures/velocity/VelocityToolbox.svg)](https://bstats.org/plugin/velocity/VelocityToolbox/33451)

### 如果它帮你少重启了一次代理，欢迎给项目一个 [Star🌟](https://github.com/polang233/VelocityToolbox)。
