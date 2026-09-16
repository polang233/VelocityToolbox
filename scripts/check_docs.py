"""Check documentation links and code fences. Run: python scripts/check_docs.py"""
from pathlib import Path
from urllib.parse import unquote, urlsplit
import re
from export_wiki import PAGE_NAMES, WIKI, pages

ROOT = Path(__file__).resolve().parents[1]
files = [ROOT / "README.md", *sorted((ROOT / "docs").rglob("*.md"))]
errors = []
for path in files:
    text = path.read_text(encoding="utf-8")
    if sum(line.strip().startswith(chr(96) * 3) for line in text.splitlines()) % 2:
        errors.append(f"{path.relative_to(ROOT)}: unclosed code fence")
    targets = re.findall(r"\]\(([^)\s]+)\)", text)
    targets += re.findall(r'<img[^>]+src="([^"]+)"', text)
    for target in targets:
        target = target.strip("<>")
        parsed = urlsplit(target)
        if parsed.scheme or target.startswith("#"):
            if target == WIKI or target.startswith(WIKI + "/"):
                page = unquote(parsed.path.rsplit("/", 1)[-1])
                if target != WIKI and page not in PAGE_NAMES:
                    errors.append(f"{path.relative_to(ROOT)}: unknown Wiki page {target}")
                continue
            elif parsed.netloc == "github.com" and parsed.path.startswith("/polang233/VelocityToolbox/blob/main/"):
                relative = parsed.path.split("/blob/main/", 1)[1]
                resolved = ROOT / unquote(relative)
            else:
                continue
        else:
            resolved = path.parent / unquote(parsed.path)
        if not resolved.exists():
            errors.append(f"{path.relative_to(ROOT)}: missing link {target}")
if errors:
    raise SystemExit("\n".join(errors))
exported = pages()
assert set(exported) == PAGE_NAMES
for name, content in exported.items():
    assert sum(line.strip().startswith(chr(96) * 3) for line in content.splitlines()) % 2 == 0, name
    for target in re.findall(r"\]\(([^)\s]+)\)", content):
        assert urlsplit(target).scheme or target.startswith("#"), f"{name}: relative link {target}"
print(f"Documentation checked: {len(files)} Markdown files and {len(exported)} Wiki pages.")
