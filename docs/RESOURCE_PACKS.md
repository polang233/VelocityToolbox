# 资源包配置

[English](RESOURCE_PACKS.en.md) · [完整配置注释](../src/main/resources/config.yml) · [命令与权限](MODULES.md#资源包)

`resource-packs` 决定给谁发哪个包；`pack-host` 提供 ZIP 下载。两者默认关闭、独立开关：只用外部直链时无需开启托管，只开托管也不会自动下发。

## 客户端如何加载资源包

代理通过游戏协议发送下载链接、SHA-1 和提示文字，客户端再自己访问该 HTTP/HTTPS 地址下载 ZIP，ZIP 不走游戏端口。所以玩家能进服不代表能下载。

玩家保存的资源包偏好可能让客户端自动接受或拒绝，所以并非每次都弹窗。VTB 以“加载成功”回执为准，接受或下载完成都不算成功。

版本差异：

- 1.17 起支持自定义提示和原生必需标记，更早的客户端界面不同。
- 1.20.3 起支持多包叠加和按 ID 撤包。旧客户端只接收分配列表中首个匹配的包，应为它准备包含全部资源的完整单包。
- `conditions.versions` 只决定发哪个文件，不转换材质格式，也不合并 ZIP。需要为目标版本准备相应的包，并用该版本客户端实测。

## 来源写法

| 写法 | 说明 |
| --- | --- |
| `"@survival.zip"` | 托管目录中的 ZIP，需开启 pack-host。自动生成链接和 SHA-1，无需 hash |
| `"https://cdn.example.com/a.zip"` | 外部直链，必须填写真实的 40 位 SHA-1 `hash`；VTB 托管不参与 |
| `none` | 不下发，常用作最后的回退项；全部不匹配时会提示玩家，写了 `none` 则不提示 |

`public-url` 是告诉玩家的下载地址前缀，`@survival.zip` 对应 `<public-url>/packs/survival.zip`。留空时自动选本机局域网地址，公网玩家通常访问不到；公网服请填写实际可访问的 IP 或域名，自行配置端口映射、反代或 HTTPS。用 `/vtb pack list` 查看生成的链接，并从外部网络实际下载一次确认。

## 配置示例

```yaml
resource-packs:
  enabled: true
  settings:
    delay: 3        # 进服后等待秒数
    timeout: 60     # 从发出到加载成功的期限
    required: false
    prompt: "<#CCFFFF>请加载服务器资源包。"
  packs:
    # 只有一个文件时直接写来源
    lobby-ui: "@lobby-ui.zip"
    # 需要按条件选择时写成变体列表，取第一个满足条件的
    survival:
      - url: "@survival.zip"
        required: true
        versions:
          min: "1.20.3"
      - none
    vip:
      - url: "https://packs.example.com/vip.zip"
        hash: "填写实际 ZIP 的 40 位 SHA-1"
        permission: "velocitytoolbox.pack.vip"
  servers:
    default: [lobby-ui]
    "survival-*": [survival, vip]
    minigame: []
```

随包的 config.yml 带有主要写法的样例，首次生成时大陆、港澳台使用中文注释，其它地区使用英文注释。旧写法 `url: "@"`、`conditions`、`packs:` 完整形式继续有效。

## 选包规则

- **变体**：每个包按列表顺序取第一个满足条件的变体；`versions` 和 `permission` 都可省略，同时填写须都满足，全部不匹配则跳过该包。回退项放在最后。
- **版本条件**：与 [子服版本限制](SERVER_VERSIONS.md) 规则相同。min/max 包含边界，allow 非空时须在列表内，deny 优先排除。
- **required / prompt**：变体上的值覆盖 `settings`。必需包被拒绝、加载失败或超时会断开玩家；可选包失败只提示。
- **子服分配**：未单独配置的子服使用 `servers.default`；单独配置会完整覆盖 default，不继承它的内容。子服名可用 `*` 通配，如 `"survival-*"`，精确名称优先，多个通配按书写顺序取第一个。1.20.3+ 按 `packs` 顺序叠加，后面的覆盖前面的同名资源。
- **撤包**：`packs: []` 不下发，并撤下 VTB 发给 1.20.3+ 客户端的包。旧客户端无法单独撤包，需要后续包替换或重连。
- **缺失文件**：引用的 `@文件.zip` 不存在时，后台提示并跳过该变体，其它包正常下发；补回文件后 `/vtb reload` 即可。

VTB 只管理自己下发的包，不影响子服或其它插件的资源包。

### 切服保留资源包

子服写 `keep`，玩家进入时保留已收到的 VTB 包，不发新包，从未收到包的玩家也不会下载。需要“没有包才下发”时用完整写法：

```yaml
resource-packs:
  servers:
    default: keep
    lobby:
      keep-existing: true
      packs: [base]
    survival: [survival]
```

此例首次进大厅下载 base，进入 survival 换成 survival，之后切到大厅或其它子服都保留。回到 survival 时相同的包不重复下载，有变化的才更新。保留只在本次连接内有效，退出代理后清除。

## 更新与重发

- **文件更新**：替换 ZIP 后执行 `/vtb reload`，VTB 重新计算哈希，只给需要更新的玩家按每秒 5 人分批重发。建议先写临时文件再替换，避免下载中途改写。外部文件更新时同步修改 hash。
- **重载失败**：保留上次有效的规则；首次加载失败则关闭下发。
- **`/vtb pack resend 玩家|all`**：按当前规则重新下发，all 每秒最多 5 人；没有匹配包时说明原因。
- **`/vtb pack remove 玩家|all`**：撤下 VTB 的包并暂停该玩家在当前子服的下发，reload 不会恢复；切到其它子服或手动 resend 后恢复。旧客户端只能暂停，已加载的包需重连清除。
- **`/vtb pack check`**：只检查磁盘上的配置、哈希和 ZIP 元数据，不应用、不发包，也不测试公网连通。

## 失败排查

玩家只看到“资源包未能成功应用：原因”。后台记录玩家、子服、包名、客户端回执和自托管的 HTTP 诊断（状态码、已发送字节、耗时）。可区分请求过频、服务繁忙、文件不存在、文件已变更、传输超时和连接中断等原因；客户端只笼统回报下载失败时，标为“原因未明”，需要查看客户端日志。

自托管包下载失败或遇到托管过载时会自动重试，最多两次（等待 5 秒、10 秒），用尽后按 required 处理。拒绝、无效链接和客户端加载失败不重试。

## 托管与安全

ZIP 在根目录必须有合法的 `pack.mcmeta`；重载和 `pack check` 会检查被引用的本地 ZIP 能否打开及元数据是否有效，兼容 PackSquash 等工具处理过的包。检查不覆盖全部资源内容。

pack-host 是带限流的公开 HTTP，知道完整链接即可下载；选包权限和链接上的票据都不是下载鉴权。TLS、大流量攻击和慢请求头防护需要反代或专门的下载服务。

### 可调整的保护

`pack-host.security` 通常保持默认，整段省略也按默认值运行。旧版内部选项会被忽略并提示，拼错的选项会使重载失败。

| 选项 | 默认 | 说明 |
| --- | --- | --- |
| `max-downloads` | 64 | 全服同时下载数 |
| `max-downloads-per-ip` | 12 | 单 IP 同时下载数 |
| `requests-per-minute-per-ip` | 120 | 单 IP 每分钟请求额度，可短时突发 40 次 |
| `max-download-seconds` | 60 | 单次传输期限；`settings.timeout` 还需留出客户端加载时间 |
| `bandwidth-mib` / `per-download-mib` | 0 | 全服 / 单次下载带宽（MiB/s），0 不限 |
| `trusted-proxies` | `[]` | 可信反代 IP/CIDR，只信任这些来源的 X-Forwarded-For |

VTB 下发给在线玩家的链接带有短期票据，前 4 次请求不受单 IP 限制，学校、网吧等共用出口的玩家不会互相挤占；它们仍计入全服并发、全服请求速率和带宽限制。没有票据的直链照常受全部限制。

使用 Nginx 等 HTTP 反代时，把反代地址填入 `trusted-proxies`，否则所有玩家会按反代的 IP 共用额度，后台会提示一次。frp 等内网穿透的 TCP 隧道会让所有访问者显示为 127.0.0.1，不要填入。

`/vtb pack list` 显示 HTTP 统计，后台每分钟汇总新增的限流与超时。
