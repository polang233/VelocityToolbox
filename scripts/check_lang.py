"""Check locale keys, placeholders, Java references. Requires PyYAML."""
from pathlib import Path
import re
import yaml

ROOT = Path(__file__).resolve().parents[1]
LANG = ROOT / "src/main/resources/lang"
GROUPS = {"common", "main", "plugin", "server", "pack"}
NON_MESSAGE_LITERALS = {"pack.mcmeta"}  # ZIP metadata filename, not a language key.
TAGS = set("black dark_blue dark_green dark_aqua dark_red dark_purple gold gray dark_gray blue green aqua red light_purple yellow white bold italic underlined strikethrough obfuscated reset newline".split())

class UniqueLoader(yaml.BaseLoader):
    pass

def mapping(loader, node):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node)
        if key in result:
            raise ValueError(f"Duplicate YAML key: {key} at line {key_node.start_mark.line + 1}")
        result[key] = loader.construct_object(value_node)
    return result

UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)

def flatten(node, path=""):
    result = {}
    for key, value in node.items():
        name = f"{path}.{key}" if path else key
        if isinstance(value, dict):
            result.update(flatten(value, name))
        else:
            assert isinstance(value, str), name
            result[name] = value
    return result

def tokens(value):
    return set(re.findall(r"<([a-z][a-z0-9_-]*)>", value)) - TAGS

def main():
    locales = {}
    for locale in ["zh_cn", "zh_tw", "en_us"]:
        node = yaml.load((LANG / (locale + ".yml")).read_text(encoding="utf-8"), Loader=UniqueLoader)
        assert set(node) == GROUPS, f"{locale}: unexpected root"
        locales[locale] = flatten(node)
    zh = locales["zh_cn"]
    for locale, messages in locales.items():
        assert zh.keys() == messages.keys(), f"{locale}: language keys differ"
        for key in zh:
            assert tokens(zh[key]) == tokens(messages[key]), f"{locale}: placeholders differ: {key}"
            assert re.fullmatch(r"[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*", key), key
    for file in (ROOT / "src/main/java").rglob("*.java"):
        text = file.read_text(encoding="utf-8")
        for literal in re.findall(r'"(?:\\.|[^"\\])*"', text):
            key = literal[1:-1]
            if key in NON_MESSAGE_LITERALS:
                continue
            if re.fullmatch(r"(common|main|plugin|server|pack)(\.[a-z0-9-]+)+\.?", key):
                assert key in zh or (key.endswith(".") and any(k.startswith(key) for k in zh)), f"{file.name}: missing {key}"
    config = (ROOT / "src/main/resources/config.yml").read_text(encoding="utf-8")
    assert not re.search(r"/(?:vtb|vtoolbox|velocity)\b", config), "Command usage belongs in docs"
    print(f"Language check passed: {len(zh)} keys across {len(locales)} locales, placeholders and Java references.")

if __name__ == "__main__":
    main()
