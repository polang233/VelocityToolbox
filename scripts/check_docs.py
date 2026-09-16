"""Check documentation links and code fences. Run: python scripts/check_docs.py"""
from pathlib import Path
from urllib.parse import unquote, urlsplit
import re
from export_wiki import PAGE_NAMES, WIKI, pages
from export_posts import drafts

ROOT = Path(__file__).resolve().parents[1]
markdown = [ROOT / "README.md", *sorted((ROOT / "docs").rglob("*.md"))]
forum = sorted((ROOT / "docs/publishing").glob("*.bbcode.txt"))
files = [*markdown, *forum]
errors = []
for path in files:
    text = path.read_text(encoding="utf-8")
    if sum(line.strip().startswith(chr(96) * 3) for line in text.splitlines()) % 2:
        errors.append(f"{path.relative_to(ROOT)}: unclosed code fence")
    targets = re.findall(r"\]\(([^)\s]+)\)", text)
    targets += re.findall(r'<img[^>]+src="([^"]+)"', text)
    if path in forum:
        targets += re.findall(r"\[url=([^\]]+)\]", text)
        targets += re.findall(r"\[img\]([^\[]+)\[/img\]", text)
        stack = []
        for tag in re.finditer(r"\[(/?)(b|url|img|list)(?:=[^\]]+)?\]", text):
            closing, name = tag.groups()
            if not closing:
                stack.append(name)
            elif not stack or stack.pop() != name:
                errors.append(f"{path.relative_to(ROOT)}: mismatched BBCode tag {tag[0]}")
        if stack:
            errors.append(f"{path.relative_to(ROOT)}: unclosed BBCode tags {stack}")
    for target in targets:
        target = target.strip("<>")
        parsed = urlsplit(target)
        if path.parent == ROOT / "docs/publishing" and path.name != "README.md" and not parsed.scheme:
            errors.append(f"{path.relative_to(ROOT)}: publishing links must be absolute: {target}")
        if parsed.scheme or target.startswith("#"):
            if target == WIKI or target.startswith(WIKI + "/"):
                page = unquote(parsed.path.rsplit("/", 1)[-1])
                if target != WIKI and page not in PAGE_NAMES:
                    errors.append(f"{path.relative_to(ROOT)}: unknown Wiki page {target}")
                continue
            elif parsed.netloc == "github.com" and parsed.path.startswith("/polang233/VelocityToolbox/blob/main/"):
                relative = parsed.path.split("/blob/main/", 1)[1]
                resolved = ROOT / unquote(relative)
            elif parsed.netloc == "raw.githubusercontent.com" and parsed.path.startswith("/polang233/VelocityToolbox/main/"):
                resolved = ROOT / unquote(parsed.path.split("/main/", 1)[1])
            else:
                continue
        else:
            resolved = path.parent / unquote(parsed.path)
        if not resolved.exists():
            errors.append(f"{path.relative_to(ROOT)}: missing link {target}")
for name, expected in drafts().items():
    path = ROOT / "docs/publishing" / name
    if not path.exists() or path.read_text(encoding="utf-8") != expected:
        errors.append(f"{name}: run python scripts/export_posts.py to update BBCode")
if errors:
    raise SystemExit("\n".join(errors))
exported = pages()
assert set(exported) == PAGE_NAMES
for name, content in exported.items():
    assert sum(line.strip().startswith(chr(96) * 3) for line in content.splitlines()) % 2 == 0, name
    for target in re.findall(r"\]\(([^)\s]+)\)", content):
        assert urlsplit(target).scheme or target.startswith("#"), f"{name}: relative link {target}"
print(f"Documentation checked: {len(markdown)} Markdown files, {len(forum)} BBCode drafts and {len(exported)} Wiki pages.")
