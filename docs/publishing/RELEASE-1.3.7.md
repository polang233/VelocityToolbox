- 插件加载支持名称简写和 Tab 补全。
- 子服分配新增 `keep-existing: true`，配合 `packs: []` 保留已下发的资源包；该子服不发新包，未发出的延迟请求会取消。
- 默认配置补充保留和撤包样例。切服时不发送保留提示，管理员查询状态时显示简短说明。
- 兼容受保护 ZIP 的 `pack.mcmeta/` 元数据及虚报长度，仍按实际读取内容执行大小和格式校验。

替换旧 JAR 后完整重启代理。旧配置继续有效，新选项的用法见 [资源包配置](https://github.com/polang233/VelocityToolbox/blob/main/docs/RESOURCE_PACKS.md)。已有配置与语言文件不会被自动覆盖。

运行环境：Velocity 4.0+、Java 25+。完整构建与回归测试通过；本版未完成逐客户端实服测试。
