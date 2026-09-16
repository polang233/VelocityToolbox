# VelocityToolbox

Velocity 运维工具箱：插件热管理、自定义资源包下发与托管、入口域名排查、子服客户端版本限制。

- 源码：[GitHub](https://github.com/polang233/VelocityToolbox)
- 下载：[Releases](https://github.com/polang233/VelocityToolbox/releases)
- 问题与建议：[Issues](https://github.com/polang233/VelocityToolbox/issues)

环境：**Velocity 4.0+**，**Java 25+**，无硬前置。资源包下发已内置。

![VelocityToolbox](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/logo-256.png)

## 能做什么

- **少重启一次代理**：加载、卸载或重载 `plugins/` 里的 Velocity 插件；操作前可只读检查风险，操作后报告清理结果。
- **自定义资源包下发**：设置全服默认包，也可按子服、客户端版本和权限分配。支持自托管与外部直链，自托管自动计算哈希；1.20.3+ 可叠加多个包。
- **排查多入口网络**：`/vtb server hosts` 按玩家加入时用的域名分组，先显示入口概要；点击入口行展开玩家名和延迟，悬停可看完整信息。
- **按子服限制客户端版本**：在 `config.yml` 的 `server-versions` 段配置最低/最高版本、允许列表和禁止列表，无需 ViaVersion。默认关闭，支持配置重载。

热加载插件：

![热加载插件](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-plugin-load.png)

热卸载插件，并报告清理结果：

![热卸载插件](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-plugin-unload.png)

资源包下发配置并启用后，玩家进服会收到标准下载提示：

![资源包下载提示](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-packs.png)

按入口域名查看人数和延迟：

![按入口查看在线玩家](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-vhosts.jpg)

## 安装

1. 从 [Releases](https://github.com/polang233/VelocityToolbox/releases) 下载 JAR，放入 Velocity 的 `plugins/`。
2. 完整启动代理一次，生成 `plugins/VelocityToolbox/config.yml`。
3. 给管理员授予 `velocitytoolbox.admin`，或按下方权限表细分授权；使用 `/vtoolbox help` 或 `/vtb help` 查看命令。

## 命令

主命令别名是 `/vtb`。`velocitytoolbox.admin` 仍可作为全部命令的兼容权限。普通查询不会刷后台；插件加载、卸载、重载和配置重载只输出简短状态。

| 命令 | 作用 |
| --- | --- |
| `/vtoolbox help` | 显示帮助 |
| `/vtoolbox info` | 插件、代理、Java、插件数量、子服版本限制和资源包托管概要 |
| `/vtoolbox pack list` | 列出资源包 URL 和 SHA-1 |
| `/vtoolbox server hosts` | 按入口分组显示域名、端口和人数；点击展开玩家名与延迟 |
| `/vtoolbox reload` | 重载语言、配置、子服版本限制与资源包托管 |
| `/vtoolbox plugin list` | 名称、版本和作者；悬停看完整元数据 |
| `/vtoolbox plugin inspect 插件ID` | 按基本信息、依赖、运行时资源和风险四段检查 |
| `/vtoolbox plugin load 文件.jar` | 从 `plugins/` 加载插件 |
| `/vtoolbox plugin unload 插件ID` | 卸载插件 |
| `/vtoolbox plugin reload 插件ID` | 卸载后重新加载 |

### 细分权限

不用 `velocitytoolbox.admin` 时，必须先有 `velocitytoolbox.command`，再授予对应子命令权限。

普通子命令：

- `velocitytoolbox.command.info`
- `velocitytoolbox.command.pack.list`
- `velocitytoolbox.command.server.hosts`
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

## 资源包托管与下发

`pack-host` 提供 HTTP 下载，`resource-packs` 决定玩家使用哪些包。两个模块默认关闭，使用外部直链时只需开启下发。放入目录的 ZIP 不会自动下发，必须在配置中定义并分配。

```yaml
pack-host:
  enabled: true
  bind: 0.0.0.0
  port: 8765
  public-url: "https://packs.example.com"
  packs-directory: packs

resource-packs:
  enabled: true
  settings:
    delay: 3
    timeout: 60
    required: false
    prompt: "<#CCFFFF>请加载服务器资源包。"
  packs:
    survival:
      - url: "@survival.zip"
        required: false
  servers:
    default:
      packs: [survival]
```

示例需在托管目录放入 survival.zip。public-url 是客户端下载地址前缀，留空自动选局域网地址；公网服请填写可访问的地址，HTTPS 需配置反代。

`url: "@"` 表示不下发材质包，无需托管；`@文件.zip` 使用自托管文件，均无需填写哈希。外部链接须填写真实 `hash`，用于缓存、更新识别和下载校验。修改后执行 `/vtb reload`。更多示例见 [资源包配置](https://github.com/polang233/VelocityToolbox/wiki/Resource-Packs)。

1.20.3+ 按顺序叠加多个包；旧客户端只发送首个有匹配变体的完整包。子服列表替换 `servers.default.packs`。变体的 `required/prompt` 覆盖 `settings` 默认值；条件全部不匹配则跳过，只有选中的必需包被拒绝、加载失败或超时才断开玩家。后端发包允许共存。


资源包操作需 pack 模块权限和对应的 list、status、resend 动作权限；入口查询需 server 模块权限和 hosts 动作权限。完整命令与权限见 [Wiki](https://github.com/polang233/VelocityToolbox/wiki/Modules)。

## 子服客户端版本限制

![各子服客户端版本限制](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-server-versions.png)

在 `config.yml` 的 `server-versions` 段中启用。`min` / `max` 限制包含边界的版本范围，`allow` 指定允许列表，`deny` 指定禁止列表；禁止列表优先，未配置的子服不限制。

切服拒绝时保留当前子服，首次进入拒绝时显示断开原因。`/vtoolbox reload` 重载规则，`/vtoolbox info` 查看状态和各子服的具体版本限制。共用协议的版本会一起匹配，例如 1.20 和 1.20.1。配置示例和完整说明见 [子服版本限制](https://github.com/polang233/VelocityToolbox/wiki/Server-Versions)。

## 热管理注意

Velocity 4.0+ 没有公开的插件加载 / 卸载 API。VelocityToolbox 会阻止卸载仍被其它插件硬依赖的目标，并尽量清理监听器、任务、命令、消息通道、线程池与类加载器，但不能保证任意第三方插件都能安全热卸载。

简单工具插件适合在测试后热重载；权限、协议 / 数据包、连接管理或大型缓存插件更新后，仍建议完整重启代理。实现边界见 [架构说明](https://github.com/polang233/VelocityToolbox/blob/main/docs/maintainer/ARCHITECTURE.md)。

## 语言

`language` 留空时跟随服务器系统语言，没有对应语言文件时回退中文；也可固定为 `zh_cn`、`zh_tw`、`en_us` 或 `lang/` 下的自定义文件名。玩家消息支持 MiniMessage。`/vtoolbox reload` 会重载语言。

**如果它帮你少重启了一次代理，欢迎给项目一个 [⭐ Star](https://github.com/polang233/VelocityToolbox)。**

## 使用统计

[![bStats](https://bstats.org/signatures/velocity/VelocityToolbox.svg)](https://bstats.org/plugin/velocity/VelocityToolbox/33451)
