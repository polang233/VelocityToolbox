"""Generate forum BBCode from the Chinese Markdown publishing drafts."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / "docs/publishing"
SOURCES = {
    "FORUM.zh.md": "FORUM.zh.bbcode.txt",
    "RELEASE-1.3.0.md": "RELEASE-1.3.0.bbcode.txt",
    "RELEASE-1.3.5.md": "RELEASE-1.3.5.bbcode.txt",
}


def bbcode(text):
    # 发布稿只使用标题、列表、链接和图片，遇到其它块语法先报错，避免丢内容。
    if "```" in text or re.search(r"^\s*[|>]", text, re.MULTILINE):
        raise ValueError("Code blocks, tables and quotes need explicit BBCode conversion")
    text = re.sub(r"\[!\[([^\]]*)\]\(([^)]+)\)\]\(([^)]+)\)",
                  r"[url=\3][img]\2[/img][/url]", text)
    text = re.sub(r"!\[[^\]]*\]\(([^)]+)\)", r"[img]\1[/img]", text)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"[url=\2]\1[/url]", text)
    text = re.sub(r"^#{1,6} (.+)$", r"[b]\1[/b]", text, flags=re.MULTILINE)
    text = re.sub(r"\*\*(.+?)\*\*", r"[b]\1[/b]", text)
    text = re.sub(r"`([^`]+)`", r"\1", text)
    lines = []
    listing = False
    for line in text.splitlines():
        item = line.startswith("- ")
        if item and not listing:
            lines.append("[list]")
        elif listing and not item:
            lines.append("[/list]")
        listing = item
        lines.append("[*]" + line[2:] if item else line)
    if listing:
        lines.append("[/list]")
    return "\n".join(lines) + "\n"


def drafts():
    return {target: bbcode((DIRECTORY / source).read_text(encoding="utf-8"))
            for source, target in SOURCES.items()}


if __name__ == "__main__":
    for name, content in drafts().items():
        (DIRECTORY / name).write_text(content, encoding="utf-8")
    print(f"Updated {len(SOURCES)} BBCode drafts.")
