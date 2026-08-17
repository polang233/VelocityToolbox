# VelocityToolbox 架构说明

功能和配置见 [README](../README.md)。这里只记源码结构和热加载实现要点。

```text
io.github.polang233.velocitytoolbox
├── VelocityToolboxPlugin            主类
├── BuildConstants                   Gradle 从 build.gradle 生成，供 @Plugin 使用
├── command/VelocityToolboxCommand   /vtoolbox
├── pack/                            资源包 HTTP 托管
│   ├── PackService                  读配置、扫描、启动 HTTP、写片段
│   ├── PackConfig                   config.yml 的 pack-host 段
│   ├── PackScanner                  扫描 zip、SHA-1、安全文件名
│   ├── PackHttpServer               JDK HttpServer
│   ├── PackSnippetWriter            VelocityResourcepacks 片段
│   ├── LanIpv4Addresses             public-url 留空时的内网探测
│   └── HostedPack                   单个 zip
└── hotload/                         运行时加载其它插件
    ├── PluginLoadService            load / unload / reload
    ├── PluginCleanup                监听器、任务、命令、线程池、类加载器
    └── VelocityInternalAccess       反射 4.0 以上内部加载器
```

## 资源包托管边界

`PackHttpServer` 只绑定 `bind:port`，在本机提供 HTTP。`public-url` 只用来写出客户端下载链接，插件不会做端口映射、域名或 HTTPS。外网访问要自己放行端口后再填 `public-url`。

## 热加载

命令操作的是 Velocity `plugins/` 里的真实 JAR。`/vtoolbox reload` 只重载资源包托管。

Velocity 4.0 以上的 `PluginManager` 没有公开 load / unload，因此按代理启动路径反射：`loadCandidate` → 创建容器 → Guice → `registerPlugin` → `registerInternally` → 只对该插件触发 `ProxyInitializeEvent`。卸载时对该插件触发 `ProxyShutdownEvent`，再拆监听器、任务、带 `.plugin(...)` 的命令、线程池和类加载器。

- 不能加载或卸载 `velocity`、`velocitytoolbox`。
- 有其它已加载插件对其声明非 optional 依赖时，不能卸载。
- `load` 只接受 `plugins/` 下的文件名。
- 重载若卸载成功、加载失败，插件保持卸载。
- 不是官方 API，代理升级可能破坏反射；任意插件都不保证能安全热卸载。
