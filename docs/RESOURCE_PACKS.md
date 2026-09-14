# 资源包功能与配置

核对日期：2026-09-14。现有配置指本仓库的 `src/main/resources/config.yml`。VTB 配置说明对应 1.3.0。

## 1. VelocityResourcepacks 的功能与配置

以下按公开介绍和默认配置整理，聚焦 Velocity 版。配置名保留原名供查阅，说明用中文概括；未采用其实现代码。

| 功能 | 对应配置或入口 | 用途 |
| --- | --- | --- |
| 定义资源包 | `packs.<名称>` 下的 `url`、`hash`、`uuid` | 配置下载地址、SHA-1 和包标识 |
| 按客户端选版本 | 包内 `version`、`format`、`variants` | 支持版本名、协议号或包格式；变体从上到下取首个匹配项 |
| 按权限选包 | 包内 `restricted`、`permission` | 控制玩家能使用的资源包 |
| 全网与子服分配 | `global`、`servers.<名称>` 下的 `pack` 或 `packs` | 指定单包或多包列表 |
| 批量匹配子服 | 分配项内 `regex` | 用服务器名正则匹配 |
| 保留或回退到可选包 | 分配项内 `optional-packs` | 保留已应用的允许包，也可作为版本或权限不匹配时的候选 |
| 延迟下发 | 分配项内 `send-delay` | 登录或切服后等待，单位为 tick |
| 玩家手动选择 | `/usepack`、`/resetpack` | 选包、恢复默认，支持指定玩家 |
| 保存玩家选择 | `usepack-is-temporary`、`permanent-pack-remove-time` | 控制临时选择及短时间掉线后的撤销 |
| 手选包与分配规则冲突 | `selected-packs-remove-existing`、`stored-packs-override-assignments` | 控制替换和覆盖 |
| 哈希更新 | `autogeneratehashes`、`append-hash-to-url` | 自动生成哈希、处理 URL 缓存兼容 |
| 认证后下发 | `use-auth-plugin` | 默认配置说明要求后端配套 WorldResourcepacks |
| 语言与日志 | `default-language`、`debug`、`disable-metrics` | 语言、调试与统计设置 |

来源：[Velocity 默认配置](https://github.com/Phoenix616/ResourcepacksPlugins/blob/master/velocity/src/main/resources/velocity-config.yml)。

公开功能还包括：1.20.3+ 多包与入服前下发、切服时避免重复应用、处理后端发送的包，以及配置管理命令和开发者事件。这些不是每项都有独立开关。世界级分配需要后端 WorldResourcepacks 配合。来源：[VelocityResourcepacks 介绍与命令](https://modrinth.com/plugin/velocityresourcepacks)。

发行说明还记录了包内 `local-path`，用于读取本机文件更新哈希，本身不提供 HTTP 托管。来源：[发行说明](https://github.com/Phoenix616/ResourcepacksPlugins/releases)。

强制接受、按加载结果执行动作、PlaceholderAPI 和 WorldGuard 联动被项目介绍列为 ForceResourcepacks 增强版能力，不应全部算作免费 VelocityResourcepacks 已有功能。来源：[项目说明](https://github.com/Phoenix616/ResourcepacksPlugins)。


## VTB 配置与使用

`resource-packs` 与 `pack-host` 同级。托管负责下载，资源包模块负责选包、下发和状态。默认都关闭，使用外部链接时可以只启用下发。

```yaml
resource-packs:
  enabled: false
  delay: 1
  timeout: 60
  required: false
  prompt: "<#CCFFFF>建议加载服务器资源包。"
  packs:
    base:
      file: base.zip
    prison:
      variants:
        - file: prison-modern.zip
          versions:
            min: "1.20.3"
        - file: prison-full.zip
          versions:
            max: "1.20.2"
    old:
      file: prison-full.zip
    event:
      url: "https://packs.example.com/event.zip"
      sha1: "0123456789abcdef0123456789abcdef01234567"
      permission: "velocitytoolbox.pack.event"
  default: [base]
  servers:
    lobby:
      packs: [base]
    prison:
      packs: [base, prison]
      legacy: old
      required: true
      prompt: "<#CCFFFF>监狱服需要资源包。"
```

示例中的文件名、下载地址与哈希需要替换。包名使用英文字母、数字、短横线或下划线；文件名支持中文。

| 配置 | 含义 |
| --- | --- |
| `delay`、`timeout` | 秒，支持小数；分别为进入子服后等待与加载期限。timeout 必须大于零 |
| `file` | 托管目录扫描到的 ZIP 文件名，自动使用 URL 和 SHA-1 |
| `url`、`sha1` | 外部 HTTP/HTTPS 直链与实际 ZIP 的 40 位 SHA-1；与 file 互斥 |
| `default` | 未配置子服的包列表 |
| `servers.<子服>.packs` | 完整替换默认列表；空列表表示撤下 VTB 管理的现代客户端包 |
| `legacy` | 1.20.3 之前客户端的完整单包；未填时取首个兼容包 |
| `versions` | 沿用子服限制的 min/max/allow/deny；版本名加引号，也支持整数协议号 |
| `variants` | 按顺序取首个版本与权限都匹配的变体 |
| `permission` | 包与变体都可配置，均须满足；缺省不限制 |
| `required`、`prompt` | 可在子服覆盖全局设置；提示文本使用 MiniMessage |

1.20.3+ 按列表顺序叠加，后面的包覆盖前面的同名资源。旧客户端不会合并多个 ZIP，需要为 `legacy` 准备包含所有必要资源的完整包。

必需包无法匹配、缺权限、被拒绝、下载或加载失败、超时会断开玩家。可选包无法匹配时跳过，失败时提示。接受下载不代表应用成功。`required` 不会把空列表变成必需资源包，也不会在第一版阻止玩家先进入子服。

后端资源包允许共存。VTB 仅撤下自己管理的现代客户端包；旧客户端以后端最新发包为准，直到切服或管理员重发。旧客户端无法单独撤包，原包可能保持到下次替换或断线。VTB 配置的包顺序只约束自己的包，不重排后端的包。

首次配置失败时下发关闭。重载先校验全部包和引用，失败保留旧配置；成功后重算在线玩家的目标列表，不重复发送未变化的包。HTTP 的监听地址没变时只换目录快照，改地址失败时尝试恢复旧监听；恢复失败会停止新的本地包下发，外部包仍可使用。

更新本地 ZIP 后执行 `/vtb reload`，以重新计算哈希。发布 ZIP 时先写临时文件再替换，避免客户端下载未写完的包。

## 命令与权限迁移

| 命令 | 作用 |
| --- | --- |
| `/vtb pack list` | 配置包、下载地址、可用状态和本地托管文件 |
| `/vtb pack status <玩家>` | 子服、选包、等待、加载结果 |
| `/vtb pack resend <玩家>` | 根据当前配置重新发送 |
| `/vtb server hosts [序号]` | 入口域名查询 |
| `/vtb plugin` | 插件管理模块帮助 |
| `/vtb info`、`/vtb reload` | 全局状态与重载 |

旧 `/vtb packs` 已改为 `/vtb pack list`，旧 `/vtb vhosts` 已改为 `/vtb server hosts`，不保留旧入口。更新聊天点击命令和运维脚本。

管理员继续使用 `velocitytoolbox.admin`。细分授权需要 `velocitytoolbox.command`，加模块权限与具体动作权限：

- 资源包模块：`velocitytoolbox.command.pack`。
- 资源包动作：`velocitytoolbox.command.pack.list`、`velocitytoolbox.command.pack.status`、`velocitytoolbox.command.pack.resend`。
- 子服模块：`velocitytoolbox.command.server`，动作：`velocitytoolbox.command.server.hosts`。
- 原插件管理、info 和 reload 权限保持不变。

原 `velocitytoolbox.command.packs` 与 `velocitytoolbox.command.vhosts` 不再授权新命令。模块帮助与补全按权限过滤。

接管原下发插件时，先将其包定义与分配转换为 VTB 配置，再停用原下发插件并开启 VTB 下发。原本地托管目录和 URL 可继续使用。VTB 仍生成 `velocityresourcepacks-snippet.yml` 供只使用托管的场景，启用 VTB 下发不需要导入它。

## 实现与验证边界

`PackRules` 保存不可变配置并选包，`PackSender` 管理当前连接的请求、回执和任务；`VersionRule` 与子服限制共用协议解析。资源包使用 Velocity 公共 API。每次新请求有独立 UUID，用于区分重复发送和旧回执，客户端缓存以 SHA-1 为依据。

`bash gradlew check build` 运行现有检查和新增 `packDeliveryTest`。新增测试覆盖真实 YAML、HTTP GET/HEAD、哈希更新、端口切换失败恢复，以及模拟代理下的选包、快速切服、迟到回执、拒绝、超时、命令权限与补全。

自动化使用真实业务代码和受控代理接口，不是实际 Minecraft 客户端验证。部署前在测试代理上检查现代客户端多包顺序、旧客户端回退、与后端共存、拒绝和断线提示。
