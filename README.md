<p align="center">
  <img src="assets/logo.png" alt="VelocityToolbox" width="168">
</p>

# VelocityToolbox

**把常用的 Velocity 运维能力收进一个轻量工具箱：运行时插件管理、入口域名排查，以及可选的资源包 HTTP 托管。**

[English](docs/README.en.md) · [Modrinth 文案](docs/MODRINTH.md) · [架构说明](docs/ARCHITECTURE.md) · [问题与建议](https://github.com/polang233/VelocityToolbox/issues)

![Velocity](https://img.shields.io/badge/Velocity-4.0%2B-654FF0)
![Java](https://img.shields.io/badge/Java-25%2B-E76F00)

## 为什么用它

- **少重启一次代理**：加载、卸载或重载 `plugins/` 中的 Velocity 插件；操作前可只读检查风险，操作后报告清理结果。
- **排查多入口网络**：`/vtoolbox vhosts` 按玩家加入时使用的域名分组，快速核对 MiniMOTD 等按域名分流的配置。
- **资源包就地托管**：一次托管任意数量的 `.zip`，自动计算 SHA-1，并生成支持多包叠加的 VelocityResourcepacks 配置片段。
- **默认保持克制**：资源包 HTTP 服务默认关闭；分层权限、关键操作后台提示和中英文消息均已内置。

## 环境与安装

- Velocity 4.0+
- Java 25+

1. 从 [Releases](https://github.com/polang233/VelocityToolbox/releases) 下载 JAR，放入 Velocity 的 `plugins/`。
2. 完整启动代理一次，生成 `plugins/VelocityToolbox/config.yml`。
3. 给管理员授予 `velocitytoolbox.admin`，或按下方权限表细分授权；使用 `/vtoolbox help` 查看命令。

自行构建：

```powershell
.\gradlew.bat build
```

## 常用命令

```text
/vtoolbox info
/vtoolbox vhosts
/vtoolbox plugin list
/vtoolbox plugin inspect someplugin
/vtoolbox plugin load SomePlugin-1.0.jar
/vtoolbox plugin unload someplugin
/vtoolbox plugin reload someplugin
```

主命令别名为 `/vtb`。`velocitytoolbox.admin` 保留为全部命令的兼容权限。普通查询不会刷后台；插件加载、卸载、重载和配置重载等关键操作只输出简短状态。

| 命令 | 作用 |
|---|---|
| `/vtoolbox help` | 显示帮助 |
| `/vtoolbox info` | 显示插件、代理、Java、插件数量和资源包托管概要 |
| `/vtoolbox packs` | 列出资源包 URL 和 SHA-1 |
| `/vtoolbox vhosts` | 按入口分组显示域名、解析 IP、端口、人数和玩家基本信息 |
| `/vtoolbox reload` | 重载语言、配置与资源包托管 |
| `/vtoolbox plugin list` | 显示插件名称、版本和作者；悬停查看完整元数据 |
| `/vtoolbox plugin inspect <plugin-id>` | 按基本信息、依赖、运行时资源和风险四段检查插件 |
| `/vtoolbox plugin load <file.jar>` | 从 `plugins/` 加载插件 |
| `/vtoolbox plugin unload <plugin-id>` | 卸载插件 |
| `/vtoolbox plugin reload <plugin-id>` | 卸载后重新加载插件 |

### 细分权限

不使用 `velocitytoolbox.admin` 时，必须先有基础权限 `velocitytoolbox.command`，再授予对应子命令权限：

- 普通子命令：`velocitytoolbox.command.info`、`velocitytoolbox.command.packs`、`velocitytoolbox.command.vhosts`、`velocitytoolbox.command.reload`
- 插件管理父权限：`velocitytoolbox.command.plugin`
- 插件管理动作：`velocitytoolbox.command.plugin.list`、`velocitytoolbox.command.plugin.inspect`、`velocitytoolbox.command.plugin.load`、`velocitytoolbox.command.plugin.unload`、`velocitytoolbox.command.plugin.reload`

例如只允许查看插件风险，需要同时授予 `velocitytoolbox.command`、`velocitytoolbox.command.plugin` 和 `velocitytoolbox.command.plugin.inspect`。帮助只显示执行者有权使用的子命令。

## 可选资源包托管

资源包托管默认关闭。启用后，VelocityToolbox 会在代理机器上启动 HTTP 服务，扫描目录内任意数量的资源包并生成 `velocityresourcepacks-snippet.yml`；每个 ZIP 都有独立 URL、SHA-1 和 `local-path`。由 [VelocityResourcepacks](https://modrinth.com/plugin/velocityresourcepacks) 决定向哪些玩家发送哪些资源包，本插件本身不直接发包。

```yaml
pack-host:
  enabled: false
  bind: 0.0.0.0
  port: 8765
  public-url: ""          # 外网使用时填写玩家可以访问的地址
  packs-directory: packs  # 默认 plugins/VelocityToolbox/packs
```

启用步骤：

1. 将 `.zip` 放入 `packs-directory`。
2. 把 `enabled` 改为 `true`；外网使用时配置防火墙/反向代理和 `public-url`。
3. 执行 `/vtoolbox reload`，再将生成的配置片段合并进 VelocityResourcepacks。

生成片段的 `global.packs` 会按文件名顺序列出全部 ZIP：Minecraft 1.20.3+ 客户端可依次叠加多个资源包，旧客户端只使用列表第一项。该字段需要 VelocityResourcepacks 1.9.0+。不需要全局发送全部包时，删除不需要的条目；不同玩家需要不同组合时，可给包设置 `restricted` / `permission`，按服务器或版本分配也应在 VelocityResourcepacks 中配置。

本插件不会自动配置端口映射、域名或 HTTPS。`public-url` 留空时会尝试使用第一块局域网 IPv4；请勿把 `0.0.0.0` 当作玩家下载地址。

## 关于插件热管理

Velocity 4.0+ 没有公开的插件加载/卸载 API。VelocityToolbox 会阻止卸载仍被其它插件硬依赖的目标，并尽量清理监听器、任务、命令、消息通道、线程池与类加载器，但无法保证任意第三方插件都能安全热卸载。

简单工具插件适合在测试后使用热重载；权限、协议/数据包、连接管理或大型缓存插件更新后仍建议完整重启代理。实现边界见 [架构说明](docs/ARCHITECTURE.md)。

## 语言、统计与反馈

`language` 留空时自动跟随服务器系统语言，没有对应语言文件时回退中文；也可固定为 `zh_cn`、`en_us` 或 `lang/` 下的自定义文件名。标准语言文件是 `lang/zh_cn.yml` 和 `lang/en_us.yml`。玩家消息支持 MiniMessage；后台启动、资源包和关键插件操作使用 Adventure 组件分色。命令帮助中的命令文本使用浅橙色，与前缀区分。`/vtoolbox reload` 会重载语言。

本插件通过 [bStats](https://bstats.org/plugin/velocity/VelocityToolbox/33451) 收集匿名使用数据，可在 `plugins/bStats/config.txt` 中关闭。

[![bStats](https://bstats.org/signatures/velocity/VelocityToolbox.svg)](https://bstats.org/plugin/velocity/VelocityToolbox/33451)

欢迎在 [GitHub Issues](https://github.com/polang233/VelocityToolbox/issues) 提交问题和功能建议。尤其欢迎对失败自动回退、多代理统一运维、入口域名诊断和资源包可用性检测的想法。

## 开源协议

Copyright (C) 2026 Polang。

VelocityToolbox 使用 [GNU General Public License v3.0 only](LICENSE) 开源。

如果它帮你少重启了一次代理，欢迎给项目一个 [Star ⭐](https://github.com/polang233/VelocityToolbox)。
