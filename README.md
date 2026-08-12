# VelocityToolbox

面向 Velocity 公共 API 的工具箱与可重载模块宿主。

英文文档位于 [`docs/`](docs/) 目录：

- [English README](docs/README.en.md)
- [English architecture guide](docs/ARCHITECTURE.en.md)
- [English module guide](docs/MODULES.en.md)

## 当前状态

版本：`0.1.0-SNAPSHOT`

首版提供：

- 基于 `velocity-api:4.1.0-SNAPSHOT` 构建的 Velocity 插件。
- 模块目录：`plugins/VelocityToolbox/modules`。
- 模块生命周期：`enable` 和 `disable`。
- 自动清理模块注册的监听器、命令、定时任务和插件消息频道。
- 模块状态查询、模块加载、卸载和重载命令。
- 面向小型自有代理功能的公开模块 API。

Velocity 4.1 公共 API 提供插件查询和 classpath 注入能力，但没有受支持的外部插件加载/卸载接口。因此，VelocityToolbox 选择把可重载模块作为扩展边界，不依赖 Velocity 内部实现。

## 环境要求

- 当前 `4.1.0-SNAPSHOT` 构建需要 Java 25 或更高版本；该快照目前发布为 JVM 25 字节码。
- Velocity 4.1.0-SNAPSHOT 或兼容的更高版本 API/运行时。
- 正式使用前，建议先在测试代理上验证。

## 构建

项目自带 Gradle Wrapper：

```powershell
.\gradlew.bat build
```

构建产物：

```text
build/libs/VelocityToolbox-0.1.0-SNAPSHOT.jar
```

## 安装

1. 构建项目。
2. 将输出 JAR 复制到 Velocity 的 `plugins` 目录。
3. 启动代理一次。
4. 将模块 JAR 放入 `plugins/VelocityToolbox/modules`。
5. 使用模块命令管理模块。

## 命令

所有命令都需要权限：

```text
velocitytoolbox.admin
```

可用命令：

```text
/vtoolbox help
/vtoolbox version
/vtoolbox status
/vtoolbox reload
/vtoolbox module list
/vtoolbox module load <file.jar>
/vtoolbox module unload <module-id>
/vtoolbox module reload <module-id>
```

`/vtoolbox reload` 目前只确认代理配置重载请求；代码重载需要显式使用 `/vtoolbox module reload <module-id>`。

## 模块 JAR 约定

模块 JAR 必须：

1. 实现 `io.github.velocitytoolbox.api.ToolboxModule`。
2. 包含 ServiceLoader 注册文件：

   ```text
   META-INF/services/io.github.velocitytoolbox.api.ToolboxModule
   ```

3. 每个 JAR 只提供一个模块实现。
4. 返回稳定的小写 ID，并符合：

   ```text
   [a-z][a-z0-9-_]{0,63}
   ```

5. 通过 `ToolboxContext.registrations()` 注册运行时资源。

示例模块：

```java
public final class HelloModule implements ToolboxModule {
    @Override
    public String id() {
        return "hello";
    }

    @Override
    public void enable(ToolboxContext context) {
        context.registrations().registerListener(new HelloListener(context.logger()));
    }

    @Override
    public void disable() {
        // 在这里关闭模块自行创建的外部资源。
    }
}
```

## 重载规则

模块必须在 `disable()` 中释放自己拥有的资源，包括：

- 数据库连接；
- Executor、线程和线程池；
- 定时任务；
- 监听器；
- 命令；
- 插件消息频道；
- 对代理对象或其他模块的引用。

通过 `RegistrationScope` 注册的资源由 VelocityToolbox 自动清理，但模块直接创建的任意资源无法被宿主自动识别。若模块泄漏线程、Future、静态引用或第三方库的全局注册点，旧类加载器仍可能无法回收。

首版重载功能定位于自己维护的小型模块以及测试/开发流程，不等同于完整 Velocity 插件的安全热卸载。

## 安全提示

不要给普通玩家授予 `velocitytoolbox.admin`。加载模块 JAR 等同于在代理进程中执行任意代码。

只加载自己构建或已经审查过源码的模块 JAR。

## 后续计划

- 拆分独立发布的 `velocitytoolbox-api` 工件；
- 模块元数据与依赖声明；
- 更完善的命令补全；
- 模块健康状态和重载事务报告；
- 可选的模块文件监听器；
- 资源包模块；
- 跨服事件与路由模块；
- 在公共 API 仍不提供完整能力的前提下，评估 Velocity 内部插件加载的实验性适配器。

## 许可证

目前尚未选择许可证。正式公开发布前，请补充 MIT、Apache-2.0、GPL-3.0 等明确许可证。
