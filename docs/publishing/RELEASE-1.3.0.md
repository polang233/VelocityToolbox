新增自定义资源包下发，可以设置全服默认包，也能按子服、客户端版本和权限分配。在 `config.yml` 的 `resource-packs` 中配置，写法见 [Wiki](https://github.com/polang233/VelocityToolbox/wiki/Resource-Packs) 和 [默认配置](https://github.com/polang233/VelocityToolbox/wiki/Configuration)。

- 支持必需或可选包、自定义提示、加载超时、状态查询和手动重发；1.20.3+ 可叠加多个包。重载会更新在线玩家，无变化的包不重复发送。
- `@文件.zip` 使用自托管，自动计算哈希；外部直链填写真实 SHA-1，单独 `@` 表示不发包。自托管新增限频、并发、超时、带宽和可信反代设置，位于 `pack-host.security`。
- 命令、帮助和状态按模块整理，资源包与子服限制共用版本规则。旧 `/vtb packs`、`/vtb vhosts` 改为 `/vtb pack list`、`/vtb server hosts`，使用脚本或细分权限的服主需要对应调整。
- 新增繁体中文，整理中英文文案和语言文件层级，使用说明放到 [Wiki](https://github.com/polang233/VelocityToolbox/wiki)。
- 修复插件热管理中异常导致重复调用、注销失败后错误处理注册信息的问题。

更新时替换 JAR 后完整重启代理，仍需 Velocity 4.0+、Java 25+。旧配置可以继续使用，想用资源包下发再补上 `resource-packs`，不加这段也不会启用下发。语言文件没改多少的话，建议备份后移走旧文件，再执行 `/vtb reload` 重新生成；有自定义文案的，按新键名补回即可。
