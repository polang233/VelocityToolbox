<p align="center">
  <img src="assets/logo.png" alt="VelocityToolbox" width="168">
</p>

# VelocityToolbox

[Velocity](https://papermc.io/software/velocity) 4.0 以上的代理端工具插件，用来托管资源包，以及在运行时加载、卸载、重载其它 Velocity 插件。

[English](docs/README.en.md) · [架构说明](docs/ARCHITECTURE.md)

## 功能

- **资源包 HTTP 托管**：扫描指定目录里的 `.zip`，计算 SHA-1，在本机提供玩家客户端能下载的 URL。
- **插件管理**：对 `plugins/` 里的其它 Velocity 插件执行 load / unload / reload。

## 环境

- Java 25+
- Velocity 4.0 以上

## 安装

1. 把构建出的 `VelocityToolbox-*.jar` 放到 Velocity 的 `plugins` 目录。
2. 启动一次代理，生成 `plugins/VelocityToolbox/config.yml`。
3. 按下面两节配置资源包目录，或使用插件管理命令。

```powershell
.\gradlew.bat build
```

产物：`build/libs/VelocityToolbox-1.0.0.jar`

## 资源包托管

Minecraft 客户端从 HTTP URL 下载资源包。本插件只在**代理本机**起一个 HTTP 服务，把目录里的 zip 变成下载地址；发给哪个玩家、用哪个包，仍由 [VelocityResourcepacks](https://modrinth.com/plugin/velocityresourcepacks) 决定。

本插件**不会**做端口映射、域名解析或 HTTPS。它只提供内网可连的 HTTP 服务。外网玩家要下载，需要你自己把监听端口放到防火墙、路由器、云安全组或反代里，达到外网可访问，再把那个地址写进 `public-url`。

### bind / port / public-url

| 配置 | 作用 | 典型值 |
|---|---|---|
| `bind` | HTTP **监听**网卡。`0.0.0.0` 表示本机所有网卡都接请求 | `0.0.0.0` |
| `port` | 监听端口。要外网能下，需自行放行这个端口 | `8765` |
| `public-url` | 写进客户端下载链接的来源（协议 + 主机 + 端口） | 内网留空；外网填玩家能打开的地址 |

下载 URL 形态：`{public-url}/packs/{文件名}`。会出现在启动日志、`/vtoolbox packs`，以及 `plugins/VelocityToolbox/velocityresourcepacks-snippet.yml`。

- **内网**：`public-url` 留空，插件用探测到的第一块局域网 IPv4，拼成 `http://192.168.x.x:8765`。
- **外网**：先放行 `port`（或反代到该端口），再填 `http://公网IP:8765` 或 `https://pack.example.com`，然后 `/vtoolbox reload`。
- 不要填 `127.0.0.1` / `localhost`（除非玩家和代理同一台机器），也不要填 `0.0.0.0`（客户端打不开）。

### 步骤

1. 把 `.zip` 放到 `pack-host.packs-directory`，默认是 `plugins/VelocityToolbox/packs/`。
2. 按上面设置 `bind` / `port` / `public-url`。
3. 启动日志或 `/vtoolbox packs` 会列出每个包的 URL 和 SHA-1。
4. 把 `plugins/VelocityToolbox/velocityresourcepacks-snippet.yml` 合并进 VelocityResourcepacks 的 `config.yml`。
5. 更换 zip、目录或 `public-url` 后执行 `/vtoolbox reload`（或 `/velocity reload`）。

文件名可以含中文和空格，不能包含 `/`、`\` 或 `..`。

```yaml
language: zh          # zh / en，或 lang/ 下自定义文件名

pack-host:
  enabled: true
  bind: 0.0.0.0
  port: 8765
  public-url: ""          # 内网留空；外网填玩家能打开的地址
  packs-directory: packs  # 相对本插件数据目录，或绝对路径
```

| `packs-directory` | 实际位置 |
|---|---|
| `packs` | `plugins/VelocityToolbox/packs` |
| `../OtherPlugin/packs` | 旁边另一个插件的目录 |
| `D:/resourcepacks` | Windows 绝对路径 |
| `/var/www/resourcepacks` | Linux 绝对路径 |

## 语言

`config.yml` 里 `language` 默认 `zh`。改成 `en` 即英文；也可以填 `lang/` 下自定义文件名（不要写 `.yml`）。首次启动会把 `lang/zh.yml`、`lang/en.yml` 拷到数据目录，改这些文件就能改文案和颜色。`/vtoolbox reload` 会重载语言、配置和资源包托管。

玩家可见消息走 Adventure MiniMessage，默认强调色 `#FF6600`、正文 `#CCFFFF`。1.16 以上客户端能显示 RGB；控制台能否显示取决于终端。

## 插件管理

对 `plugins/` 目录里已有的 Velocity 插件 JAR 做运行时加载、卸载和重载。`/vtoolbox reload` 只重载本插件的语言、配置和资源包托管，不会重载其它插件。

```text
/vtoolbox plugin list
/vtoolbox plugin load SomePlugin-1.0.jar
/vtoolbox plugin unload someplugin
/vtoolbox plugin reload someplugin
```

- `load` 只接受 `plugins/` 下的文件名。
- 不能加载或卸载 `velocity` 和 `velocitytoolbox`。
- 若其它已加载插件对目标（含它 `provides` 的 ID）声明了非 optional 依赖，会拒绝卸载。
- `reload` 先卸载再从同一个 JAR 加载；重新加载失败时插件保持卸载。
- 卸载后会尽量拆掉监听器、任务、命令、自定义消息通道、线程池和类加载器；失败时聊天里会给出异常类型、原因，并提示去看代理日志。

**能卸掉不等于建议热卸载。** 像 ShadiaoVelocity 这种命令/监听器结构简单的插件，热卸载通常比较干净。LuckPerms、协议/数据包、权限、长期占玩家连接或内部缓存很大的插件，即使这次看起来卸掉了，也不建议当常规操作：消息通道在 Velocity 里不记插件归属、别的插件可能仍握着旧类、内存也不保证能立刻收回。这类插件请改 JAR 后**完整重启代理**。

Velocity 4.0 以上没有公开的插件 load / unload API，因此实现依赖代理内部加载器。细节见 [架构说明](docs/ARCHITECTURE.md)。该功能需要 `velocitytoolbox.admin`，不要发给普通玩家。

## 命令

权限：`velocitytoolbox.admin`。别名：`/vtb`。

| 命令 | 说明 |
|---|---|
| `/vtoolbox help` | 帮助 |
| `/vtoolbox version` | 版本、插件数量、托管开关 |
| `/vtoolbox status` | 代理版本、Java、托管来源、已加载插件 |
| `/vtoolbox packs` | 列出每个 zip 的 URL 和 SHA-1 |
| `/vtoolbox reload` | 重载语言、配置和资源包托管 |
| `/vtoolbox plugin list` | 列出已加载插件 |
| `/vtoolbox plugin load <file.jar>` | 从 `plugins/` 加载 |
| `/vtoolbox plugin unload <plugin-id>` | 卸载 |
| `/vtoolbox plugin reload <plugin-id>` | 卸载后再加载 |

## 统计

本插件使用 [bStats](https://bstats.org/plugin/velocity/VelocityToolbox/33451) 收集匿名使用数据。可在 `plugins/bStats/config.txt` 把 `enabled` 设为 `false` 关闭。

[![bStats](https://bstats.org/signatures/velocity/VelocityToolbox.svg)](https://bstats.org/plugin/velocity/VelocityToolbox/33451)

## 文档

- [架构说明](docs/ARCHITECTURE.md)
- [English README](docs/README.en.md)

---

*觉得好用的话点个 [Star⭐](https://github.com/polang233/VelocityToolbox) 支持一下！*
