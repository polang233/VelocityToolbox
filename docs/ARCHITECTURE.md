# VelocityToolbox 架构说明

~~~text
Velocity
└── VelocityToolboxPlugin
    ├── ToolboxCommand
    ├── ModuleManager
    │   ├── URLClassLoader per module
    │   ├── ServiceLoader discovery
    │   └── lifecycle and error isolation
    └── RegistrationScope
        ├── EventManager listeners
        ├── CommandManager commands
        ├── Scheduler tasks
        └── ChannelRegistrar channels
~~~

## 设计选择

项目使用 velocity-api:4.1.0-SNAPSHOT，通过以下公共 API 管理模块资源：

- EventManager
- CommandManager
- Scheduler
- ChannelRegistrar
- Proxy lifecycle events

Velocity 4.1 的 PluginManager 可以查询插件并向插件 classpath 注入 JAR，但没有公开的外部插件加载/卸载接口。因此，VelocityToolbox 不依赖 Velocity 内部实现。

## 为什么不直接模拟 PlugMan

直接加载一个完整 Velocity 插件需要处理：

- 插件元数据和依赖排序
- Velocity 插件容器
- 插件初始化生命周期
- 插件主类监听器
- 命令、任务和频道清理
- 类加载器和旧代码引用
- 与其他插件的依赖关系

模块系统只承担自己能够控制的部分，边界更小，失败时更容易诊断。

## 首版安全边界

- 只加载 plugins/VelocityToolbox/modules 下的 JAR。
- 每个 JAR 只接受一个 ToolboxModule 实现。
- 只允许安全的模块 ID。
- 文件名会被规范化并限制在模块目录内。
- 任何模块 JAR 都应被视为可执行代码。
- 不支持在线下载模块。
- 不提供给普通玩家使用的加载权限。
