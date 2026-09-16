# 语言文件

[文档入口](README.md)

config.yml 的 language 可填 zh_cn（简体）、zh_tw（繁体）、en_us（英文），或 lang/ 中自定义文件名，不含 .yml。留空跟随系统语言，缺少翻译时回退中文。文本支持 MiniMessage。

繁体中文可设置 language: zh_tw；zh_TW、zh_HK、zh_MO、zh_Hant 也会选用繁体版。自动选择时，以系统的繁简文字标记为优先；没有标记时，台湾、香港、澳门地区的中文系统使用繁体。其余缺少翻译的语言仍回退简体中文。

## 按模块编辑

- common：前缀、通用文字、标点和共享版本格式。
- main：公共帮助、基本信息、配置重载和初始化日志。
- plugin：插件管理的帮助、检查、操作结果和日志。
- server：入口玩家列表和 versions 子服版本限制。
- pack：资源包共用说明、host 托管和 delivery 下发。

各模块的 help 是命令描述，info/status 是状态，log 是后台消息。只需修改文字，保留键名和原有占位符。yes/no 等 YAML 特殊键已加引号，保留引号。

例如只修改前缀和资源包失败提示：

```yaml
common:
  prefix: "<gold>[服务器]</gold> "
pack:
  delivery:
    failure: "<red>资源包 <pack> 加载异常：<reason>"
```

缺少的条目会使用随包默认值，因此自定义文件可以只写需要覆盖的内容。缩进层级是 YAML 结构；引号内的开头空格用于游戏中的对齐。版本名、玩家名和路径等占位值按普通文本插入，不会当作 MiniMessage 指令。

## 更新语言文件

语言键使用小写和短横线，例如 plugin.inspect.issue.no-source-jar。旧版结构不再兼容；检测到旧文件时会暂用随包默认语言，并在启动或重载时提示需要重新生成。

如果语言文件没改多少，建议备份后移走旧文件，执行 /vtb reload 重新生成。自定义文案较多的话，保留备份，参照新版键名把需要的文字补回。

自定义文件名不会自动生成，可从 [简体中文](../src/main/resources/lang/zh_cn.yml)、[繁體中文](../src/main/resources/lang/zh_tw.yml) 或 [英文](../src/main/resources/lang/en_us.yml) 复制模板。

已有文件不会被自动删除或覆盖。当前格式仍支持只填写需要覆盖的条目，其余使用随包默认值。这里的语言结构调整不改变 config.yml 的 server-versions 或 resource-packs 配置名称。
