# 发布文案

这里是平台发布稿，不作为配置说明或维护记录的唯一来源。

- [中文 Markdown 论坛稿](FORUM.zh.md)
- [中文 BBCode 论坛稿](FORUM.zh.bbcode.txt)
- [Modrinth 英文稿](MODRINTH.md)
- [1.3.0 更新说明](RELEASE-1.3.0.md)

发布前核对版本、命令、下载链接与 [公开模块说明](../MODULES.md)。资源包详细限制统一链接到资源包指南。

## 同步 Wiki

公开指南以 docs/ 下的 Markdown 和默认 config.yml 为源文件。维护记录与平台发布稿保留在主仓库。

首次在 GitHub Wiki 创建 Home 页面，再克隆 Wiki 到主仓库之外：

```sh
git clone https://github.com/polang233/VelocityToolbox.wiki.git ../VelocityToolbox.wiki
```

修改文档后，在主仓库执行检查与导出：

```sh
python scripts/check_docs.py
python scripts/export_wiki.py --output ../VelocityToolbox.wiki
```

脚本转换页面链接并生成首页、侧栏和完整配置页，只覆盖它负责的页面。检查 Wiki 差异后，在 Wiki 仓库单独提交并推送。主仓库提交不会自动更新 Wiki。
