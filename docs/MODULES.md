# VelocityToolbox 模块开发说明

## 目标

VelocityToolbox 的模块不是 Velocity 官方插件。

它们由已经正常加载的 VelocityToolbox 主插件托管，通过独立的 URLClassLoader 加载和卸载。模块适合自己控制的、小型、低依赖功能。

## 资源注册原则

模块必须通过 ToolboxContext.registrations() 注册运行时资源：

- registerListener
- registerCommand
- schedule
- registerChannel

VelocityToolbox 会在模块卸载时清理这些资源。

不要直接把资源注册到无法追踪的对象上，也不要把模块实例缓存到静态字段中。

## 最小模块示例

~~~java
package example;

import io.github.velocitytoolbox.api.ToolboxContext;
import io.github.velocitytoolbox.api.ToolboxModule;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;

public final class ExampleModule implements ToolboxModule {
    @Override
    public String id() {
        return "example";
    }

    @Override
    public void enable(ToolboxContext context) {
        context.registrations().registerListener(new Listener(context));
        context.logger().info("Example module enabled.");
    }

    @Override
    public void disable() {
        // 关闭数据库、线程池、HTTP 客户端等模块私有资源。
    }

    private static final class Listener {
        private final ToolboxContext context;

        private Listener(ToolboxContext context) {
            this.context = context;
        }

        @Subscribe
        public void onServerConnected(ServerConnectedEvent event) {
            context.logger().info("{} connected to {}.",
                    event.getPlayer().getUsername(),
                    event.getServer().getServerInfo().getName());
        }
    }
}
~~~

## ServiceLoader 注册

在模块 JAR 中创建：

~~~text
src/main/resources/META-INF/services/io.github.velocitytoolbox.api.ToolboxModule
~~~

内容为实现类的完整类名：

~~~text
example.ExampleModule
~~~

## 生命周期

加载：

1. 创建模块专用类加载器。
2. 通过 ServiceLoader 找到 ToolboxModule。
3. 创建模块数据目录。
4. 调用 enable。
5. 将模块标记为已加载。

卸载：

1. 调用 disable。
2. 取消模块注册的任务。
3. 注销命令。
4. 注销插件频道。
5. 注销模块监听器。
6. 关闭模块类加载器。

如果 disable() 抛出异常，VelocityToolbox 仍然会尝试完成清理。

## 已知边界

- 模块 JAR 目前不支持自动依赖解析。
- 模块之间不应互相缓存实例。
- 模块不能假设可以卸载其他 Velocity 插件。
- 模块泄漏线程、Future、静态引用或第三方库全局注册点时，旧类加载器仍可能留在内存中。
- 外部插件的完整 load/unload 仍然不是 Velocity 4.1 公共 API 的能力。
