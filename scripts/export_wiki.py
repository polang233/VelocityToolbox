"""Export public Markdown guides to a cloned GitHub Wiki. Does not commit or push."""
import argparse
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
REPO = "https://github.com/polang233/VelocityToolbox"
WIKI = REPO + "/wiki"
PAGES = {
    "docs/MODULES.md": "Modules",
    "docs/MODULES.en.md": "Modules-English",
    "docs/RESOURCE_PACKS.md": "Resource-Packs",
    "docs/RESOURCE_PACKS.en.md": "Resource-Packs-English",
    "docs/SERVER_VERSIONS.md": "Server-Versions",
    "docs/LANGUAGE.md": "Language",
    "docs/README.en.md": "English",
}
PAGE_NAMES = {"Home", "_Sidebar", "Configuration", *PAGES.values()}


def target_url(source, target):
    parsed = urlsplit(target)
    if parsed.scheme or target.startswith("#"):
        return target
    path = (source.parent / unquote(parsed.path)).resolve()
    relative = path.relative_to(ROOT).as_posix()
    if not path.is_file():
        raise ValueError(f"{source}: missing link {target}")
    page = PAGES.get(relative)
    if relative == "docs/README.md":
        page = "Home"
    elif relative == "src/main/resources/config.yml":
        page = "Configuration"
    suffix = "#" + parsed.fragment if parsed.fragment else ""
    if page:
        return f"{WIKI}/{page}{suffix}"
    if relative.startswith("assets/"):
        return f"https://raw.githubusercontent.com/polang233/VelocityToolbox/main/{relative}{suffix}"
    return f"{REPO}/blob/main/{relative}{suffix}"


def render(source):
    text = source.read_text(encoding="utf-8")
    text = re.sub(r"(?<=\]\()([^\s)]+)(?=\))",
                  lambda m: target_url(source, m[0]), text)
    return re.sub(r'(?<=src=")([^"]+)(?=")',
                  lambda m: target_url(source, m[0]), text)


def pages():
    result = {name: render(ROOT / source) for source, name in PAGES.items()}
    result["Home"] = f"""# VelocityToolbox 使用文档

Velocity 4.0+ 运维工具箱，需要 Java 25+。

- [安装、功能与截图]({REPO}#安装)
- [模块、命令与权限]({WIKI}/Modules)
- [资源包托管与下发]({WIKI}/Resource-Packs)：全服默认分配、子服覆盖、版本与权限、哈希和网络配置。
- [子服客户端版本限制]({WIKI}/Server-Versions)
- [语言文件]({WIKI}/Language)
- [完整默认配置]({WIKI}/Configuration)
- [English]({WIKI}/English)

资源包在 config.yml 的 resource-packs 中配置，HTTP 托管在 pack-host 中配置。两者默认关闭；启用前替换示例文件及子服名，删除不用的样例。

旧配置可以继续使用，需要下发时再补充 resource-packs，不加这段也不会启用下发。语言文件改动不多的话，建议备份后移走旧文件，再执行 /vtb reload 重新生成；自定义文案可按新键名补回。

[下载]({REPO}/releases/latest) · [问题与建议]({REPO}/issues)
"""
    result["_Sidebar"] = "\n".join(f"- [{label}]({WIKI}/{page})" for label, page in [
        ("首页", "Home"), ("模块与命令", "Modules"), ("资源包", "Resource-Packs"),
        ("版本限制", "Server-Versions"), ("语言文件", "Language"),
        ("默认配置", "Configuration"),
        ("English", "English"), ("Modules", "Modules-English"),
        ("Resource packs", "Resource-Packs-English"),
    ]) + "\n"
    config = (ROOT / "src/main/resources/config.yml").read_text(encoding="utf-8")
    result["Configuration"] = ("# 完整默认配置\n\n"
        f"适用于 1.3.0。[配置源文件]({REPO}/blob/main/src/main/resources/config.yml)\n\n"
        "已有配置不会自动覆盖，也不必整份替换。需要下发时再补充 resource-packs，不加这段也不会启用下发。启用前替换样例文件及子服名，删除不用的定义。\n\n"
        f"```yaml\n{config.rstrip()}\n```\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Cloned Wiki directory")
    args = parser.parse_args()
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents:
        parser.error("Use a separate Wiki checkout outside the source repository")
    output.mkdir(parents=True, exist_ok=True)
    for name, content in pages().items():
        (output / f"{name}.md").write_text(content, encoding="utf-8")
    print(f"Exported {len(PAGE_NAMES)} Wiki pages to {output}; review before committing.")


if __name__ == "__main__":
    main()
