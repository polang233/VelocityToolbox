# 资源包配置

[English](RESOURCE_PACKS.en.md) · [完整配置注释](../src/main/resources/config.yml)

resource-packs 可设置全服默认资源包，并按子服、版本和权限分配；pack-host 提供 HTTP 下载。两者默认关闭。自托管来源需开启 pack-host，并设置玩家可访问的 public-url；外部链接可单独下发。

旧配置可以继续使用，需要下发时再补上 resource-packs，不加这段也不会启用下发。

## Minecraft 原版如何加载资源包

代理通过游戏协议发送资源包 URL、SHA-1，以及客户端支持的提示/必需标记和包 ID。客户端随后自己连接 HTTP/HTTPS 地址下载 ZIP、读取资源并回报状态；ZIP 不通过游戏端口传输。服务器能进不代表下载端口能通。

玩家保存的服务器资源包偏好可能让客户端自动接受或拒绝，所以并非每次下发都会弹窗。接受、下载完成、加载成功是不同状态；VTB 以加载成功回执确认结果。客户端回执不是防作弊证明，资源包下载后也不能作为保密文件。

### 版本差异

- 1.17 之前没有新版的自定义确认提示和原生必需标记。1.17 增加这两项；不要期待老客户端显示完全相同的界面。[原版 1.17 说明](https://feedback.minecraft.net/hc/en-us/articles/4402626897165-Minecraft-Caves-Cliffs-Part-1-1-17-Java)
- 1.20.3 之前只使用一个服务器资源包；VTB 选择分配列表中首个实际匹配的完整包。1.20.3+ 支持多包、按 ID 撤包及更多加载回执，VTB 按列表顺序叠加，后面的覆盖前面的同名资源。[原版 1.20.3 说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-20-3)
- 协议版本、ZIP 内的 pack.mcmeta 格式和材质内容兼容性是不同问题。conditions.versions 只决定发哪个文件，不转换材质格式、不合并 ZIP，也不修复模型或字体。需要为目标客户端准备相应变体。
- 客户端还有下载大小、资源格式和加载能力限制，随游戏版本变化；大包可能因网络、客户端内存或格式问题失败。应使用目标版本客户端验证，增加 timeout 不能修复损坏或不兼容的包。

VTB 下发的外链和自托管包统一由当前有效请求执行 required 校验，协议中的原生强制标记关闭。当前必需包被拒绝、失败或超时仍会断开玩家；已取消请求的迟到回执不再触发原生或 VTB 断开。自托管包遇到 FAILED_DOWNLOAD 或本机明确记录的过载，最多重试两次，分别等待 5 秒、10 秒，耗尽后按 required 处理。重试保留前面已加载的包，撤下失败包及后续叠加项，再使用新请求 ID 下发。拒绝、无效 URL、客户端加载失败和丢弃回执不触发自动重试。

## 托管、链接和下发的关系

Minecraft 客户端始终通过 HTTP/HTTPS 链接下载 ZIP。三个配置各管一件事：

- `packs-directory`：文件放在哪，填写服务器上的 ZIP 目录。
- `pack-host`：提供文件下载。`bind/port` 是本机监听地址，`public-url` 是告诉玩家的下载地址前缀。
- `resource-packs`：按子服、版本和权限选包，再把链接发给客户端。仅开启托管不会自动下发。

来源有三种写法：

- `url: "@survival.zip"`：开启自托管，将 ZIP 放入托管目录。VTB 自动生成链接和哈希，无需填写 `hash`。
- `url: "https://cdn.example.com/survival.zip"`：使用现成直链，填写真实 `hash`。客户端直接访问该链接，VTB 的托管和 `public-url` 不参与。
- `url: "@"`：不下发这个包，无需文件、哈希或托管。

例如 `public-url: "https://packs.example.com"` 配合 `@survival.zip`，会生成 `https://packs.example.com/packs/survival.zip`。

`public-url` 不会自动映射端口或配置 HTTPS。地址可带反代路径前缀，但不能包含账号口令、查询参数或片段。当前版本留空会选本机局域网地址，例如 `192.168.1.10`，公网玩家通常无法访问。公网服请填写实际可访问的 IP 或域名，并配置端口映射或反代。用 `/vtb pack list` 查看生成的链接，再从外部网络验证下载。

pack-host 提供的是带限流的公开 HTTP 下载。知道完整 URL 的人都可以下载 ZIP；查询参数里的票据用来关联下载诊断和过载重试，不是登录或鉴权。选包权限也不会变成 HTTP 下载口令。

## 示例

随包配置已展开默认、生存、RPG、界面叠加、权限包和外部活动包样例。模块开关默认关闭；启用前准备或替换示例资源，并删除不用的定义与分配。

使用时替换文件、外部链接、哈希和子服名。自托管的 survival.zip 放入 packs-directory。

```yaml
resource-packs:
  enabled: true
  settings:
    delay: 3
    timeout: 60
    required: false
    prompt: "<#CCFFFF>请加载服务器资源包。"
  packs:
    default:
      - url: "@"
    survival:
      - url: "@survival.zip"
        required: true
        conditions:
          versions:
            min: "1.20.3"
            max: max
      - url: "@"
    rpg:
      - url: "https://packs.example.com/rpg.zip"
        hash: "填写实际 ZIP 的 40 位 SHA-1"
        conditions:
          versions:
            min: "1.20.3"
            max: max
            deny: ["1.21.2"]
          permission: "velocitytoolbox.pack.rpg"
  servers:
    default:
      packs: [default]
    survival:
      packs: [survival]
    rpg:
      packs: [rpg]
    lobby:
      packs: []
```

## 哈希与文件更新

SHA-1 是 ZIP 字节内容的指纹，用于缓存、识别更新和校验下载内容；它不是下载口令或作者签名。自托管文件由 VTB 自动计算，无需填写 hash；外部文件由服主提供哈希。即使只重新压缩相同文件，也可能改变 ZIP 哈希。修改 ZIP 后执行 /vtb reload；外部文件更新时同步修改 hash。本地来源仍接受 hash: "@"，显式哈希须与文件一致。

## 匹配与下发

重载时若配置中的 `@filename.zip` 在 `packs-directory` 下不存在，VTB 会在代理后台提示配置位置和文件名，并跳过该变体。若一个资源包的变体全部缺失，该资源包不会下发到分配它的子服；其它有效资源包仍正常下发。补回文件后再次执行 `/vtb reload` 即可恢复。

每个包按列表顺序取第一个满足 conditions 的变体。版本与权限须同时满足；全部不匹配则跳过。未写 conditions 表示无条件，应将回退项放在最后。

versions 与 [子服客户端版本限制](SERVER_VERSIONS.md) 共用规则：min/max 包含边界，省略时采用代理支持的最低/最高版本；max: max 跟随代理更新。allow 非空时还须在列表内，deny 优先排除。版本名加引号，也可写整数协议号；同协议版本一起匹配。

变体的 required/prompt 分别覆盖 settings。设置省略时为延迟 3 秒、超时 60 秒、非强制、空提示。已选中的必需包被拒绝、下载/加载失败或超时会断开玩家；可选包失败仅提示。接受下载不代表加载成功。

具体子服的配置完整覆盖 servers.default，不继承其中的 packs 或 keep-existing；default 仅用于未单独配置的子服。keep-existing 为 false 时，packs: [] 不下发，并撤下 VTB 管理的现代客户端包。1.20.3+ 按分配顺序叠加，后面的覆盖前面的；旧客户端只用首个匹配包，应提供完整单包。

VTB 只管理自己的包。旧客户端收到后端包后，以后端为准，直到切服或手动重发。旧客户端无法单独撤包，无材质选项也不会清除其它插件的现代客户端包。

### 切服保留已下发的包（1.3.7+）

在需要保留的子服分配中设置 `keep-existing: true`，可省略 `packs`。这种分配只保留当前代理连接已发送的 VTB 包，不发起新请求；从未收到包的玩家不会下载。也兼容显式的 `packs: []`，不能与非空 packs 列表组合。未设置时保持原有的切服选包/撤包行为。

```yaml
resource-packs:
  servers:
    default:
      keep-existing: true
    survival:
      packs: [survival]
```

此例首次进大厅不下载，进入 survival 才请求资源包，切到其它未单独配置的子服时保留。大厅等子服无需逐个列出；若另写 `lobby: { packs: [] }`，就会覆盖 default 并撤包。未发出的延迟任务在切服时取消；已发送的请求继续接收结果，required/timeout 仍按原请求处理。保留子服不发起过载重试，手动 resend 或 resend all 也不会在这里新增请求。返回分配子服后，相同包保留，变更的包再更新。保留外观会继续影响其它子服；其它插件的包仍由其它插件管理。

配置重载时保留子服继续保持原包；退出代理、关闭下发模块或卸载 VTB 会按原生命周期清理，不跨连接保存。正常切服不发送保留提示；管理员命令返回状态信息时，只向执行者显示“切服处理：保留资源包”。默认配置的 `lobby` 展示保留用法，`vanilla` 展示撤包用法。

## 命令与更新

- /vtb reload：重新读取配置和哈希，需要下发的玩家按每秒 5 人错开，另加配置的 delay。30 人都需更新时分 6 批，最后一批比首批晚 5 秒启动。无变化且已加载或正在下载的包保留；自托管包的下载失败、繁忙和超时状态可重新下发。
- /vtb pack list：查看来源、版本、权限和托管文件。
- /vtb pack status 玩家：查看加载状态和强制要求，再按当前条件展示分配来源、匹配变体、版本或权限不符的原因。
- /vtb pack resend 玩家：按当前规则重新下发，没有匹配包时说明原因。
- /vtb pack remove 玩家|all：撤掉玩家或全部在线玩家的 VTB 包，取消相关等待、重试和超时任务，并暂停当前子服的自动下发。all 同时取消原批量重发队列；单人撤包使该玩家已入队的条目失效。管理员收到处理结果；玩家只收到“管理员已撤下你的资源包”，尚未发包或旧客户端则提示暂停下发，重复撤包不重复通知。
- /vtb pack check（1.3.5+）：读取磁盘配置并检查启用的模块，不应用配置、不创建文件或目录、不启动监听，也不发包。下发关闭时跳过其规则和资源包元数据检查。它不测试端口占用、公网连通或外链内容。
- /vtb pack resend all（1.3.5+）：先为全部在线玩家作废旧请求、撤包并取消旧任务，再按每秒最多 5 人下发，各玩家仍使用配置的 delay 和换包间隔。keep-existing 子服保留原包。排队后切服或单独操作的条目会跳过，保留更新的请求。重复执行不会叠加队列；重载由最新规则接管剩余请求并继续分批，停用或 remove all 则取消。结束时区分已安排、无适用包和跳过情况，实际加载结果用 status 查看。

命令需 velocitytoolbox.admin，或 velocitytoolbox.command、velocitytoolbox.command.pack 及对应的 velocitytoolbox.command.pack.list/check/status/resend/remove 动作权限。resend all 另需 velocitytoolbox.command.pack.resend.all；remove all 另需 velocitytoolbox.command.pack.remove.all。

remove 只撤本次代理连接中 VTB 自己下发的包，不影响子服或其它插件的包。1.20.3+ 客户端支持按 ID 撤包；低于 1.20.3 只取消任务和暂停下发，已应用的包需重新连接清除，命令会明确提示。即使尚未发包，也会取消排队并暂停；暂停状态显示在 pack status 中。同一子服内的 reload、规则变化或下发模块停用后重新启用，都不会恢复；下次切到其它子服或手动 resend 玩家/all 后按最新规则恢复。keep-existing 子服仍遵守保留规则，恢复下发不强制覆盖分配策略。暂停只在当前代理连接中有效，退出或重启后不保存。

连续操作以最后一次 remove/resend 为准，reload 保留该意图。remove all → resend all → reload 会恢复全部玩家并采用最新配置；resend all → remove all → reload 保持暂停。撤包前的批量回调、下发延迟、重试、超时和旧回执均不能恢复暂停或踢掉玩家。

重载校验失败保留旧规则；首次加载失败则关闭下发。ZIP 建议先写临时文件再替换。public-url 留空选择局域网地址，公网玩家需要可访问的公网地址或反代。

加载超时从实际发出各包请求时计算，不包含分批等待时间。再次重载相同配置不会提前待重试请求或重置已有下发延迟；切服、退出或停用会使旧任务失效。每秒 5 人控制的是请求启动速度，实际下载仍受 HTTP 并发、带宽和传输期限约束，并不保证客户端一定加载成功。后台的 Resource pack retry 区分 host-busy 与 FAILED_DOWNLOAD；只有后者时仍需结合客户端日志定位网络、文件校验或客户端问题。

换包、更新和强制重发时，撤旧包后至少留出 1 秒再发新包。它与配置的 delay 取较大值，重载的分批间隔再叠加；首次下发、只新增叠加包或保留相同包无需额外等待。等待期间相同的 reload/resend 合并到现有任务，连续切服只发送最新分配。回执和超时按玩家连接、当前实际子服与请求 ID 核对；撤包前先移除整段旧请求，旧失败回执和已取消任务不能撤掉新包。

玩家失败提示为“资源包未能成功应用：原因”，不含具体包名。自托管 GET 请求记录 HTTP 状态、已发送/预期字节数、耗时和服务端异常；后台失败及重试日志保留这些诊断。可区分限频 429、繁忙 503、文件不存在 404、文件变更或不可用 409、传输超时、连接中断和文件读取错误。HEAD 不覆盖 GET 诊断；同一票据的旧连接也不覆盖较新请求。

玩家自动提示不重复插件名，不列出包名、重试次数或内部任务信息；这些细节保留在管理员状态和后台日志。已有自定义语言文件继续保留，缺少的新提示键自动使用内置文本。

FAILED_DOWNLOAD 本身不包含客户端网络异常或哈希校验详情。未收到对应 GET、外部下载服务或服务端已写完文件后仍失败时，不能据此判断 DNS、网络波动、客户端取消或校验问题，提示标为“下载失败，原因未明”，需要客户端日志继续定位。连接中断只说明传输被中断，不判断中断责任；客户端回报 DOWNLOADED 后的超时归为应用超时。

实现与验证记录见 [维护记录](maintainer/README.md)。

## 自托管能力与局限

1.3.6 起也接受 PackSquash 等保护工具使用的非空 `pack.mcmeta/` 元数据入口，与 Java ZipFile 的读取语义一致；64 KiB 上限按实际解压读取的字节计算，兼容保护 ZIP 虚报的头部长度。仍拒绝空目录、重复/别名冲突、无效 JSON 和超限元数据。

1.3.5 起，重载与 pack check 会检查被下发规则引用的本地 ZIP：能否打开、根目录是否有 pack.mcmeta、JSON 和必要字段是否有效。元数据限制为 64 KiB、最多 64 层嵌套，支持旧 pack_format 和新版 min_format/max_format。错误带配置路径与文件名，重载失败保留旧规则。此检查不验证全部资源内容，也不代替客户端兼容性测试；外链只检查 URL 与显式哈希，不下载文件。

VTB 读取本机 ZIP，提供 GET/HEAD 下载、文件哈希、限频和并发控制；它适合将已有资源包目录直接提供给玩家。外部直链由外部服务器提供，VTB 的托管限流不约束那个服务器。

public-url 留空仍自动选择本机地址，选到局域网地址时会输出简短提醒。该设置只生成客户端链接，不自动探测公网、不配置端口映射、域名、HTTPS 或 CDN。填入的域名需指向可用下载服务；确认公网可达应从外部网络访问实际文件链接。

文件边界检查、请求大小限制、IP 记录回收、目录隐藏和本机过载重试由插件自动处理。pack-host 是公开 HTTP：链接可被分享，选包权限并不是下载鉴权，票据查询参数也不是。大规模流量攻击、TLS 和慢请求头防护需要反代或专门的下载服务。应用层限制在请求头解析后生效。[JDK HTTP 服务边界](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/module-summary.html)

### 服主可调整的保护

pack-host.security 只保留实际生效的选项，通常先用默认值。出现旧版内部键名（如 show-index、burst-per-ip）时重载会失败并指出键名，删除即可。

- max-downloads / max-downloads-per-ip：总/单 IP 同时下载数，默认 64/12。共用公网 IP 的多名玩家共用额度，切服时旧包尚未结束的下载也占名额。
- requests-per-minute-per-ip：单 IP 请求补充速率，默认每分钟 120 次；HEAD 和错误路径也计入，最多允许 40 次短时突发。切服、探测和重试共用该额度，持续刷请求仍返回 429，并发超限仍返回 503。
- max-download-seconds：HTTP 传输期限；resource-packs.settings.timeout 还需留出客户端加载时间。
- bandwidth-mib / per-download-mib：总/单次下载带宽，单位 MiB/s，0 为不限。按真实出口调整，限速后留足下载时间。
- trusted-proxies：仅填写实际反代 IP/CIDR。默认忽略转发头；正确配置后从右向左解析 X-Forwarded-For。

已有配置中的显式上限保持生效；若 max-downloads-per-ip 仍为旧默认 8，可改为 12 后执行 /vtb reload。40 次突发额度由插件自动应用，无需添加配置项。票据不会绕过这些上限。

1.3.9 升级时若需采用新的单 IP 并发默认值，合并以下片段，其余 security 配置保留：

```yaml
pack-host:
  security:
    max-downloads-per-ip: 12
```

/vtb pack list 显示 HTTP 统计，后台每分钟汇总新增限流与超时。文件更新后必须重载；状态不符的文件返回 409。用写完的临时 ZIP 替换原文件，避免下载过程中原地改写。
