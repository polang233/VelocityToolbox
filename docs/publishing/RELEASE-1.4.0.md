- 资源包配置更简洁：单文件可写 `base: "@base.zip"`；`versions`、`permission` 直接写在变体上；用 `- none` 表示不下发；子服可写 `rpg: [rpg, ui]`、`default: keep`、`minigame: []`。
- 子服名支持通配符，如 `"survival-*": [survival]`。精确名称优先，多个通配符按书写顺序取第一个。
- `keep-existing: true` 现在可以和 `packs` 一起写：玩家已有 VTB 包就保留，没有才下发。适合大厅首次进服下载、切回时保留。
- 共用出口的玩家不再互相挤占：VTB 下发给在线玩家的下载不受单 IP 限制，仍受全服并发、请求速率和带宽限制；直链照常受全部限制。
- 反代漏配 `trusted-proxies` 时后台提示一次。旧版内部安全选项改为警告并忽略，不再导致托管无法启动；拼错的选项仍会报错。
- 默认配置大幅精简，新增英文注释版：首次生成时中国大陆、港澳台使用中文注释，其它地区使用英文注释。README 和资源包文档同步精简。

**如何更新**

替换旧 JAR 后完整重启代理即可，**无需修改配置**。旧写法 `url: "@"`、`conditions`、`packs: [...]`、`keep-existing: true` 全部继续有效，也不会有迁移提示。语言文件保留，缺少的新提示使用随包文本。

想用新写法时，可按需手动替换，改完执行 `/vtb reload`：

- `- url: "@"` 改为 `- none`
- `conditions:` 这一层删除，把 `versions`、`permission` 上移到 `url` 同级
- `survival: { packs: [survival] }` 改为 `survival: [survival]`
- `lobby: { keep-existing: true }` 改为 `lobby: keep`

新配置项和完整样例见 [默认配置](https://github.com/polang233/VelocityToolbox/blob/main/src/main/resources/config.yml) 和 [资源包配置](https://github.com/polang233/VelocityToolbox/blob/main/docs/RESOURCE_PACKS.md)。已有 config.yml 不会被覆盖，也不会自动换成新注释；想要新注释时，先备份，再删除旧文件让插件重新生成，然后把自己的设置填回去。

运行环境：Velocity 4.0+、Java 25+。完整构建与回归通过，覆盖新旧写法、通配符、保留与下发组合及真实本机 HTTP。尚未进行客户端实服验证。
