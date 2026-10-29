"""CI check: the locales Hiyori maintains must cover every prewarm key that exists
in the base strings - the diff set must be empty, or new English strings silently
show up untranslated in those languages again. Also verifies format placeholders
(%1$s, %1$d, ...) match the base entry, since a dropped placeholder crashes at
runtime. Plurals: the "other" quantity must exist with matching placeholders
(CJK locales legitimately carry only "other").

Restore note: the original script (upscale/video keys) was lost in the baseline
reset; this variant guards the prewarm feature keys and is run manually or from
CI after i18n changes. The reader status keys are included because the UX pass
unified the enhancement vocabulary across the reader overlay and prewarm UI.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "i18n" / "src" / "commonMain" / "moko-resources"
CHECKED_LOCALES = ["zh-rCN", "zh-rTW", "ja", "ko"]
KEY_PATTERN = re.compile(r"prewarm|enhancement|^reader_status_|^reader_show_processing_status", re.IGNORECASE)
STRING_ENTRY = re.compile(r'<string name="([^"]+)">(.*?)</string>', re.S)
PLURAL_ENTRY = re.compile(r'<plurals name="([^"]+)">(.*?)</plurals>', re.S)
PLURAL_ITEM = re.compile(r'<item quantity="([^"]+)">(.*?)</item>', re.S)
PLACEHOLDER = re.compile(r"%\d+\$[sd]")


def load_strings(locale: str) -> dict:
    text = (ROOT / locale / "strings.xml").read_text(encoding="utf-8")
    return dict(STRING_ENTRY.findall(text))


def load_plurals(locale: str) -> dict:
    path = ROOT / locale / "plurals.xml"
    if not path.exists():
        return {}
    text = path.read_text(encoding="utf-8")
    return {
        name: {q: v for q, v in PLURAL_ITEM.findall(body)}
        for name, body in PLURAL_ENTRY.findall(text)
    }


def main() -> int:
    failed = False
    base_strings = load_strings("base")
    base_keys = {key for key in base_strings if KEY_PATTERN.search(key)}
    base_plurals = load_plurals("base")
    base_plural_keys = {key for key in base_plurals if KEY_PATTERN.search(key)}

    for locale in CHECKED_LOCALES:
        have = load_strings(locale)
        missing = sorted(base_keys - have.keys())
        if missing:
            print(f"{locale}: missing {len(missing)} keys: {missing}")
            failed = True
        for key in base_keys & have.keys():
            expected = sorted(PLACEHOLDER.findall(base_strings[key]))
            actual = sorted(PLACEHOLDER.findall(have[key]))
            if expected != actual:
                print(f"{locale}: placeholder mismatch for {key}: base={expected} locale={actual}")
                failed = True

        have_plurals = load_plurals(locale)
        for key in sorted(base_plural_keys):
            if key not in have_plurals:
                print(f"{locale}: missing plurals {key}")
                failed = True
                continue
            if "other" not in have_plurals[key]:
                print(f"{locale}: plurals {key} is missing the 'other' quantity")
                failed = True
                continue
            expected = sorted(PLACEHOLDER.findall(base_plurals[key].get("other", "")))
            actual = sorted(PLACEHOLDER.findall(have_plurals[key]["other"]))
            if expected != actual:
                print(f"{locale}: plurals {key} placeholder mismatch: base={expected} locale={actual}")
                failed = True

    if failed:
        sys.exit(1)
    print(f"i18n coverage OK ({len(base_keys)} keys + {len(base_plural_keys)} plurals x {len(CHECKED_LOCALES)} locales)")


if __name__ == "__main__":
    main()
