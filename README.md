<p align="center">
  <img src="assets/logo.png" alt="VelocityToolbox" width="168">
</p>

# VelocityToolbox

**Velocity 运维工具箱：运行时插件管理、入口域名排查、子服客户端版本限制，以及资源包托管与下发。**

[English](docs/README.en.md) · [架构说明](docs/ARCHITECTURE.md) · [问题与建议](https://github.com/polang233/VelocityToolbox/issues)

![Velocity](https://img.shields.io/badge/Velocity-4.0%2B-654FF0)
![Java](https://img.shields.io/badge/Java-25%2B-E76F00)

## 下载与发布平台

[![GitHub Releases](https://img.shields.io/badge/GitHub-Releases-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/polang233/VelocityToolbox/releases)
[![Modrinth](https://img.shields.io/badge/Modrinth-Download-1BD96A?style=for-the-badge&logo=modrinth&logoColor=white)](https://modrinth.com/plugin/velocitytoolbox)
[![MineBBS](https://img.shields.io/badge/MineBBS-Download-2E7D32?style=for-the-badge)](https://www.minebbs.com/resources/velocitytoolbox.18104/)
[![苦力怕论坛](https://img.shields.io/badge/KLPBBS-Download-4CAF50?style=for-the-badge)](https://klpbbs.com/thread-173633-1-1.html)

## 为什么用它

- **少重启一次代理**：加载、卸载或重载 `plugins/` 里的 Velocity 插件；操作前可只读检查风险，操作后报告清理结果。
- **排查多入口网络**：`/vtb server hosts` 按玩家加入时用的域名分组，先显示入口概要；点击入口行展开玩家名和延迟，悬停可看完整信息。
- **资源包就地托管**：托管 ZIP 并自动计算 SHA-1，按子服、版本与权限下发资源包，支持多包和旧客户端回退。

<p align="center">
  <img src="assets/screenshot-vhosts.jpg" alt="按入口查看在线玩家" width="720">
</p>
<p align="center"><sub>按入口域名查看人数和延迟</sub></p>

<p align="center">
  <img src="assets/screenshot-plugin-load.png" alt="热加载插件" width="720">
</p>
<p align="center">
  <img src="assets/screenshot-plugin-unload.png" alt="热卸载插件" width="720">
</p>
<p align="center"><sub>热加载 / 卸载插件，并报告清理结果</sub></p>

<p align="center">
  <img src="assets/screenshot-packs.png" alt="资源包下载提示" width="720">
</p>
<p align="center"><sub>配置并启用资源包下发后，玩家进服会收到标准下载提示</sub></p>

## 环境与安装

- Velocity 4.0+
- Java 25+

1. 从 [Releases](https://github.com/polang233/VelocityToolbox/releases) 下载 JAR，放入 Velocity 的 `plugins/`。
2. 完整启动代理一次，生成 `plugins/VelocityToolbox/config.yml`。
3. 给管理员授予 `velocitytoolbox.admin`，或按下方权限表细分授权；使用 `/vtoolbox help` 或 `/vtb help` 查看命令。

自行构建：

```powershell
.\gradlew.bat build
```

## 命令

主命令别名是 `/vtb`。`velocitytoolbox.admin` 仍可作为全部命令的兼容权限。普通查询不会刷后台；插件加载、卸载、重载和配置重载只输出简短状态。

| 命令 | 作用 |
| --- | --- |
| `/vtoolbox help` | 显示帮助 |
| `/vtoolbox info` | 插件、代理、Java、插件数量、子服版本限制和资源包托管概要 |
| `/vtoolbox pack list` | 查看配置包、托管文件与可用状态 |
| `/vtoolbox pack status 玩家` | 查看玩家的下发与加载状态 |
| `/vtoolbox pack resend 玩家` | 重新下发当前子服的资源包 |
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

资源包模块需 `velocitytoolbox.command.pack`，动作权限为 `velocitytoolbox.command.pack.list/status/resend`。子服查询需 `velocitytoolbox.command.server` 和 `velocitytoolbox.command.server.hosts`。这里的斜杠表示分别授予动作权限。

1.3.0 起，旧 `packs`、`vhosts` 入口已改为 `pack list`、`server hosts`，原对应权限需更新。管理员权限继续有效。

例如只允许查看插件风险，需要同时授予 `velocitytoolbox.command`、`velocitytoolbox.command.plugin` 和 `velocitytoolbox.command.plugin.inspect`。帮助只显示执行者有权使用的子命令。

## 子服客户端版本限制

![各子服客户端版本限制](assets/screenshot-server-versions.png)

在 `plugins/VelocityToolbox/config.yml` 的 `server-versions` 段中启用，并按子服配置最低/最高版本、允许列表和禁止列表，无需 ViaVersion。禁止列表优先，未配置的子服不限制。

```yaml
server-versions:
  enabled: true
  servers:
    survival:
      allow: ["1.12.2", "1.20.1"]
    minigame:
      min: "1.18"
      max: max
      deny: ["1.20.2"]
```

新安装默认关闭。`/vtoolbox reload` 和 `/velocity reload` 会重载规则，`/vtoolbox info` 显示模块状态及各子服的版本范围、允许列表和禁止列表。切服拒绝时保留原服；首次进入拒绝时断开并显示原因。配置重载失败保留旧规则，首次加载失败则拒绝连接，修复后可重载恢复。

同协议版本无法区分，例如 1.20 和 1.20.1 会一起匹配。模块只限制进入，跨版本协议转换仍需兼容插件。完整配置、提示自定义与验收步骤见 [版本限制说明](docs/SERVER_VERSIONS.md)。

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
  delay: 1
  timeout: 60
  required: false
  prompt: "<#CCFFFF>建议加载服务器资源包。"
  packs:
    base:
      file: base.zip
  default: [base]
  servers: {}
```

将实际资源包放入 `packs-directory`，配置客户端下载地址，然后执行 `/vtb reload`。`file` 自动使用本地 URL 和 SHA-1；外部地址用 `url` 与真实 `sha1`。子服分配、版本变体、权限和旧客户端回退见 [资源包配置](docs/RESOURCE_PACKS.md)。

1.20.3+ 支持按顺序叠加多个包；旧客户端使用 `legacy` 指定的完整包，未指定时取第一个兼容包。`required` 开启后，拒绝、无法匹配、加载失败或超时会断开。后端发包允许共存，旧客户端以后端最新发送为准。

VTB 仅配置本机 HTTP 监听，不自动映射端口或配置 HTTPS。`public-url` 必须是玩家能访问的地址。原有配置片段仍会生成，供只使用托管的场景使用；启用 VTB 下发时停用原下发插件，避免重复发送。

## 关于插件热管理

Velocity 4.0+ 没有公开的插件加载/卸载 API。VelocityToolbox 会阻止卸载仍被其它插件硬依赖的目标，并尽量清理监听器、任务、命令、消息通道、线程池与类加载器，但无法保证任意第三方插件都能安全热卸载。

简单工具插件适合在测试后使用热重载；权限、协议/数据包、连接管理或大型缓存插件更新后仍建议完整重启代理。实现边界见 [架构说明](docs/ARCHITECTURE.md)。

## 语言与反馈

`language` 留空时自动跟随服务器系统语言，没有对应语言文件时回退中文；也可固定为 `zh_cn`、`en_us` 或 `lang/` 下的自定义文件名。标准语言文件是 `lang/zh_cn.yml` 和 `lang/en_us.yml`。玩家消息支持 MiniMessage；后台启动、资源包和关键插件操作使用 Adventure 组件分色。命令帮助中的命令文本使用浅橙色，与前缀区分。`/vtoolbox reload` 会重载语言。

欢迎在 [GitHub Issues](https://github.com/polang233/VelocityToolbox/issues) 提交问题和功能建议。

如果它帮你少重启了一次代理，欢迎给项目一个 [Star🌟](https://github.com/polang233/VelocityToolbox)。

## 使用统计

[![bStats](https://bstats.org/signatures/velocity/VelocityToolbox.svg)](https://bstats.org/plugin/velocity/VelocityToolbox/33451)
