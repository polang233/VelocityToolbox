# 发布文案

正文与更新日志分开准备，打开对应文件后复制全文即可。所有正文链接和图片均使用完整地址，可直接粘贴到站外编辑器。

## 插件介绍正文

- [中文 Markdown](FORUM.zh.md)：用于支持 Markdown 的编辑器。
- [中文 BBCode](FORUM.zh.bbcode.txt)：用于 MineBBS、苦力怕论坛等的 BBCode 源码编辑器；粘贴前切换到源码模式。
- [Modrinth 英文介绍](MODRINTH.md)：用于项目 Description。

中文标题可用：`[Velocity] VelocityToolbox | 插件热管理 · 自定义资源包下发 · 子服版本限制`

英文简短介绍：`Runtime plugin management, custom resource packs, entry-domain diagnostics and per-backend client version rules for Velocity.`

## 版本更新日志

版本名可用 `VelocityToolbox 1.3.0`，版本号为 `1.3.0`。

- [中文 Markdown 更新日志](RELEASE-1.3.0.md)
- [中文 BBCode 更新日志](RELEASE-1.3.0.bbcode.txt)
- [英文更新日志](RELEASE-1.3.0.en.md)：用于 Modrinth Changelog。

上传文件为构建目录中的 `VelocityToolbox-1.3.0.jar`，也可从 [GitHub Release](https://github.com/polang233/VelocityToolbox/releases/tag/v1.3.0) 下载。平台分类选择 Velocity，运行环境为 Velocity 4.0+、Java 25+；客户端资源包兼容范围取决于实际 ZIP 内容，按测试结果填写。

## 维护文案

中文 Markdown 是 BBCode 的源文件，改完后执行：

```sh
python scripts/export_posts.py
python scripts/check_docs.py
```

英文稿按实际含义同步修改。发布前核对命令、下载链接与 [公开模块说明](../MODULES.md)，详细配置统一链接到 Wiki。

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
