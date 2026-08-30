# VelocityToolbox 架构说明

功能和配置见 [README](../README.md)。这里只记源码结构和插件管理实现要点。

```text
io.github.polang233.velocitytoolbox
├── VelocityToolboxPlugin            主类
├── BuildConstants                   Gradle 从 build.gradle 生成，供 @Plugin 使用
├── config/                          config.yml 与默认文件拷贝
├── lang/Lang                        MiniMessage 语言（默认 zh_cn，可 en_us / 自定义）
├── command/VelocityToolboxCommand   /vtoolbox
├── metrics/Metrics                  bStats 官方单文件（只改了 package）
├── pack/                            资源包 HTTP 托管
│   ├── PackService                  读配置、扫描、启动 HTTP、写片段
│   ├── PackConfig                   config.yml 的 pack-host 段
│   ├── PackScanner                  扫描 zip、SHA-1、安全文件名
│   ├── PackHttpServer               JDK HttpServer
│   ├── PackSnippetWriter            VelocityResourcepacks 多包片段
│   ├── LanIpv4Addresses             public-url 留空时的内网探测
│   └── HostedPack                   单个 zip
└── plugins/                         运行时插件管理
    ├── PluginLoadService            load / unload / reload
    ├── PluginInspection             只读风险等级、依赖与运行时资源快照
    ├── PluginCleanup                监听器、任务、命令、通道、线程池、类加载器
    ├── CleanupReport                清理计数和残留提示
    └── VelocityInternalAccess       反射 4.0 以上内部加载器
```

## 资源包托管边界

`PackHttpServer` 只绑定 `bind:port`，在本机提供 HTTP。`public-url` 只用来写出客户端下载链接，插件不会做端口映射、域名或 HTTPS。外网访问要自己放行端口后再填 `public-url`。

目录内每个 ZIP 都会生成独立 URL、SHA-1 和 `local-path`。生成片段使用 VelocityResourcepacks 1.9.0+ 的 `global.packs`：1.20.3+ 客户端可以按顺序叠加多个包，旧客户端只使用第一项。真正的玩家、版本和后端服务器分配仍由 VelocityResourcepacks 负责。

## 插件管理

命令操作的是 Velocity `plugins/` 里的真实 JAR。`/vtoolbox reload` 重载语言、配置和资源包托管，不重载其它插件。

Velocity 4.0 以上的 `PluginManager` 没有公开 load / unload，因此按代理启动路径反射：`loadCandidate` → 创建容器 → Guice → `registerPlugin` → `registerInternally` → 只对该插件触发 `ProxyInitializeEvent`。卸载时对该插件触发 `ProxyShutdownEvent`（抛错也继续清理），再用**插件实例**拆监听器，按类加载器补扫残留监听器和自定义消息通道，取消任务，注销命令（含没写 `CommandMeta.plugin(...)` 的，例如 ShadiaoVelocity），关闭线程池和类加载器。清理结果和异常类型会发到执行命令的人，完整堆栈进代理日志。

对照 [ServerUtils](https://github.com/FrankHeijden/ServerUtils) / [VelocityHotReloader](https://github.com/HauntedMC/VelocityHotReloader) 的 Velocity 实现：它们同样拆监听器、任务、命令、类加载器。这里额外按类加载器扫残留，并检查 `provides` 依赖。Velocity 的 `ChannelRegistrar` 不记插件归属，用 API 自带 `MinecraftChannelIdentifier` 登记的通道卸不掉。

- 不能加载或卸载 `velocity`、`velocitytoolbox`。
- 有其它已加载插件对其 ID 或 `provides` 声明非 optional 依赖时，不能卸载。
- `load` 只接受 `plugins/` 下的文件名。
- 重载若卸载成功、加载失败，插件保持卸载。
- 简单插件热卸载通常没问题；大型插件（权限、协议/数据包、占连接、大缓存）即使能卸掉，也不建议当常规操作，改 JAR 后请完整重启代理。
- 不是官方 API，代理升级可能破坏反射；任意插件都不保证能安全热卸载。

`/vtoolbox plugin inspect <id>` 以基本信息、依赖关系、运行时资源、风险结论四段展示名称、作者、描述、主页、源 JAR、实例类、必需/可选依赖、反向硬依赖、`provides`、命令、任务、监听器、自定义通道和容器线程池。它不会触发关闭事件或注销资源，结果是当前快照而非安全保证。`plugin list` 的玩家端输出支持悬停查看同类元数据，点击可补全 inspect 命令。

命令权限采用三层兼容模型：`velocitytoolbox.admin` 可直接放行全部操作；细分授权则要求 `velocitytoolbox.command`、插件管理时额外要求 `velocitytoolbox.command.plugin`，最后再检查具体动作权限。普通查询不写后台操作日志；插件加载、卸载、重载和配置重载保留简短状态。
