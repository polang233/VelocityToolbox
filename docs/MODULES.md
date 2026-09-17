# 模块、命令与权限

[文档索引](README.md) · [English](MODULES.en.md)

## 插件管理

用于查看代理插件信息、检查热卸载风险，以及从 plugins/ 加载、卸载、重载 JAR。

- `/vtb plugin list`：名称、版本、作者；悬停显示依赖等详情。
- `/vtb plugin inspect 插件ID`：基本信息、依赖、已登记资源和风险。
- `/vtb plugin load 文件.jar`、`unload 插件ID`、`reload 插件ID`：执行热管理并报告清理结果。

正在被硬依赖的插件不能卸载。清理包括命令、监听器、任务、部分通道和类加载器；插件自建线程、数据库和外部连接仍需它自己关闭。检查结果不等于任意插件都可安全热更新。

## 资源包

托管和下发放在同一模块介绍，因为自托管文件可以直接参与下发；两者独立开关。

- `pack-host`：HTTP 下载、SHA-1 扫描、限频、并发和带宽控制。它不负责决定哪个玩家使用哪个包。
- `resource-packs`：按子服、协议版本和权限选择变体，发送下载链接并处理回执。可使用托管文件或外部直链。
- `/vtb pack list`：配置来源与条件、托管文件、HTTP 统计。
- `/vtb pack status 玩家`：当前子服、加载状态，以及按当前版本和权限计算的分配来源、变体与不匹配原因。
- `/vtb pack resend 玩家`：重新计算并下发，没有匹配包时说明原因。
- `/vtb pack check`（1.3.5+）：检查磁盘配置、哈希和本地包元数据，不应用配置或发包；失败会向执行者和后台说明具体位置。
- `/vtb pack resend all`（1.3.5+）：对执行时在线的玩家每秒最多安排 5 人重发，执行前重新选包；重复调用不叠加队列，重载或停用会取消剩余队列。完成提示是安排结果，加载结果仍看 status。

托管成功启动不代表公网可达，也不代表客户端已加载。详见 [资源包原理与配置](RESOURCE_PACKS.md)。

## 子服与入口

`/vtb server hosts [序号]` 按玩家加入时使用的域名与端口分组，显示人数、玩家和延迟。点击展开或收起，悬停可查看更多连接信息。这里显示客户端提供的入口信息，不修改 DNS 或路由。

`server-versions` 按目标子服检查 min/max/allow/deny。切服被拒时保留原服；首次进入被拒时断开并显示原因。重载失败保留旧规则，首次加载失败则阻止子服连接，修复后重载。详见 [版本限制](SERVER_VERSIONS.md)。

## 公共命令与语言

`/vtb help` 按模块显示有权限的命令；`/vtb info` 按基本信息、插件管理、子服与入口、资源包展示状态，资源包下再区分托管和下发。启动和配置重载使用相同层级。

`/vtb reload` 与 `/velocity reload` 都会重载配置、语言和模块规则。资源包失败不阻止版本规则尝试重载。已有 config.yml 和 lang/ 文件不会自动覆盖；缺少的语言键从随包文件补齐。

## 权限

管理员使用 `velocitytoolbox.admin` 可执行全部命令。细分授权按“基础 + 模块 + 动作”组合：

- 基础：`velocitytoolbox.command`。
- 公共动作：`velocitytoolbox.command.info`、`velocitytoolbox.command.reload`。
- 插件模块：`velocitytoolbox.command.plugin`，加 `velocitytoolbox.command.plugin.<动作>`；动作为 list、inspect、load、unload、reload。
- 资源包模块：`velocitytoolbox.command.pack`，加 `velocitytoolbox.command.pack.<动作>`；动作为 list、check、status、resend。批量重发还需 `velocitytoolbox.command.pack.resend.all`，管理员权限已包含。
- 入口模块：`velocitytoolbox.command.server`，加 `velocitytoolbox.command.server.hosts`。

例如只查看资源包状态，需要 `velocitytoolbox.command`、`velocitytoolbox.command.pack` 和 `velocitytoolbox.command.pack.status`。资源包变体里的 permission 控制选包，与管理命令权限分开。

旧版 packs/vhosts 命令改为 pack list/server hosts，相关脚本和细分权限需同步调整。
