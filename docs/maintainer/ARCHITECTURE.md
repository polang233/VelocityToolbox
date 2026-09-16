# 源码结构

代码根包：io.github.polang233.velocitytoolbox。构建使用 Java 25，版本与插件元数据来自 build.gradle。

## 模块

- VelocityToolboxPlugin：初始化、命令注册、模块重载和关闭。
- config：主配置及随包文件读取；lang：简体、繁体与英文消息与 MiniMessage。
- command：plugin、pack、server 子命令及公共权限/显示逻辑。
- version：VersionRule 共用版本解析与匹配，VersionText 共用显示；ServerVersionService 处理切服检查。
- pack.config：PackParser 解析和校验，PackRules 保存规则并选包，PackConfig 读取托管配置。
- pack.host：PackService 管理托管快照和监听器，PackScanner 扫描 ZIP，HostedPack 记录文件和哈希。
- pack.http：HTTP GET/HEAD、IP 限频、并发名额、带宽与期限控制，以及自托管下载重试关联。
- pack.delivery：PackSender 管理每次连接的资源包请求、回执、延迟和超时。
- plugins：PluginLoadService 执行加载/卸载，PluginInspector 负责检查，PluginCleanup 清理资源；PluginInspection 和 CleanupReport 保存结果。
- plugins.internal：PluginAccess 访问 Velocity 加载器及生命周期，PluginResources 查找资源归属，Reflection 提供反射工具。
- metrics：bStats 统计代码。

## 资源包

配置通过全部校验后才应用。每个选中变体保存有效的 required/prompt；失败处理按单个请求执行。重载或切服保留未变化的包前缀，后续包按原顺序更新。

每次下发使用独立 UUID，过期回执和已取消任务不能修改新会话。现代客户端只撤下 VTB 自己的包。HTTP 托管开关和下发开关独立，具体行为见 [资源包配置](../RESOURCE_PACKS.md)。

## 插件生命周期

加载顺序为解析 JAR、创建容器、Guice 注入、登记插件及监听器，最后只对目标插件触发 ProxyInitializeEvent。卸载先触发目标的 ProxyShutdownEvent，再清理资源、注销容器、关闭类加载器。

硬依赖仍在使用的插件不能卸载。标准资源按插件实例清理，缺少归属信息时按类加载器补查。自定义线程或外部连接仍需要目标插件正确关闭；运行时反射依赖 Velocity 内部结构，升级代理后应验证。

## 验证

运行 gradlew.bat build（Windows）或 ./gradlew build。check 包含语言、版本规则、资源包事件/HTTP 和插件资源归属测试。代理内部加载流程及真实客户端行为还需实服验证。
