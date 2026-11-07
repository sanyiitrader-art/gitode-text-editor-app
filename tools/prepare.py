#!/usr/bin/env python3
"""Gitode build-time asset preparation. CI runs this before Gradle.

1. Launcher icons generated from icon.png (adaptive icon, transparent background).
2. TextMate grammars and themes (tm-grammars / tm-themes through jsDelivr),
   plus two small grammars written here: Qt project files (qmake) and .gitignore.
3. Fonts (google/fonts repository).
4. Language logos (devicon / simple-icons rasterised to PNG, plus a drawn XML logo).
5. Theme preview screenshots copied from theme-previews/.

Everything generated lives in src/main/assets and src/main/res/mipmap-*,
both git-ignored. Required items fail the build; optional ones only warn.
"""
import json
import os
import re
import shutil
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "src" / "main"
ASSETS = MAIN / "assets"
RES = MAIN / "res"

warnings = []
errors = []


def warn(msg):
    print("WARN: " + msg)
    warnings.append(msg)


def fail(msg):
    print("ERROR: " + msg)
    errors.append(msg)


def http_get(url, headers=None, retries=3):
    h = {"User-Agent": "gitode-build"}
    if headers:
        h.update(headers)
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers=h)
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"{url}: {last}")


# ---------------------------------------------------------------- icons

DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
SAFE_ZONE = 66 / 108  # adaptive icons keep the artwork inside the 66dp of 108dp

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/transparent"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
"""


def make_icons():
    from PIL import Image

    src = ROOT / "icon.png"
    if not src.is_file():
        fail("icon.png is missing from the project root")
        return
    img = Image.open(src).convert("RGBA")

    for d in RES.glob("mipmap-*"):
        shutil.rmtree(d, ignore_errors=True)

    # Foreground layer: whole artwork, scaled uniformly into the safe zone,
    # centred on a fully transparent canvas. Nothing is cropped, stretched or filled.
    for name, size in DENSITIES.items():
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        box = round(size * SAFE_ZONE)
        scale = min(box / img.width, box / img.height)
        w = max(1, round(img.width * scale))
        h = max(1, round(img.height * scale))
        art = img.resize((w, h), Image.LANCZOS)
        canvas.alpha_composite(art, ((size - w) // 2, (size - h) // 2))
        out = RES / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        canvas.save(out / "ic_launcher_foreground.png")

    any_dir = RES / "mipmap-anydpi-v26"
    any_dir.mkdir(parents=True, exist_ok=True)
    (any_dir / "ic_launcher.xml").write_text(ADAPTIVE_XML, encoding="utf-8")
    (any_dir / "ic_launcher_round.xml").write_text(ADAPTIVE_XML, encoding="utf-8")

    # Full artwork for the in-app welcome screen (never upscaled).
    ASSETS.mkdir(parents=True, exist_ok=True)
    big = img
    if max(img.size) > 512:
        s = 512 / max(img.size)
        big = img.resize((max(1, round(img.width * s)), max(1, round(img.height * s))), Image.LANCZOS)
    big.save(ASSETS / "icon.png")
    print("icons: generated")


# ------------------------------------------------------------- textmate

GRAMMAR_URL = "https://cdn.jsdelivr.net/npm/tm-grammars@1/grammars/{}.json"
THEME_URL = "https://cdn.jsdelivr.net/npm/tm-themes@1/themes/{}.json"

LANGS = [
    "python", "kotlin", "typescript", "tsx", "javascript", "toml", "gdscript",
    "cpp", "c", "swift", "java", "ruby", "php", "rust",
    "json", "jsonc", "xml", "markdown", "yaml", "html", "css",
    "shellscript", "groovy", "ini", "sql", "csharp", "go", "dart",
]
REQUIRED_LANGS = {
    "python", "kotlin", "typescript", "tsx", "javascript", "toml", "gdscript",
    "cpp", "swift", "java", "ruby", "php", "rust", "yaml", "xml",
}
THEMES = [
    "dark-plus", "one-dark-pro", "dracula", "monokai", "nord", "github-dark",
    "solarized-dark", "light-plus", "github-light", "solarized-light",
]
REQUIRED_THEMES = {"dark-plus", "light-plus"}

LANG_CONFIG = {
    "brackets": [["{", "}"], ["[", "]"], ["(", ")"]],
    "autoClosingPairs": [
        {"open": "{", "close": "}"},
        {"open": "[", "close": "]"},
        {"open": "(", "close": ")"},
    ],
}

# Qt project files (.pro, .pri, .prf) are written in qmake's language.
QMAKE_GRAMMAR = {
    "name": "qmake",
    "scopeName": "source.qmake",
    "patterns": [
        {"include": "#comment"},
        {"include": "#string"},
        {"include": "#variable-ref"},
        {"include": "#continuation"},
        {"include": "#assignment-target"},
        {"include": "#operator"},
        {"include": "#keyword"},
        {"include": "#function"},
        {"include": "#platform"},
        {"include": "#number"},
    ],
    "repository": {
        "comment": {"name": "comment.line.number-sign.qmake", "match": r"#.*$"},
        "string": {
            "name": "string.quoted.double.qmake",
            "begin": r'"',
            "end": r'"',
            "patterns": [
                {"name": "constant.character.escape.qmake", "match": r"\\."},
                {"include": "#variable-ref"},
            ],
        },
        "variable-ref": {
            "name": "variable.other.readwrite.qmake",
            "match": r"\$\$(?:\{[^}]*\}|\[[^\]]*\]|[A-Za-z_][A-Za-z0-9_.]*)|\$\([^)]*\)",
        },
        "continuation": {
            "name": "constant.character.escape.continuation.qmake",
            "match": r"\\\s*$",
        },
        "assignment-target": {
            "match": r"^\s*([A-Za-z_][A-Za-z0-9_.]*)(?=\s*(?:\+=|-=|\*=|~=|=))",
            "captures": {"1": {"name": "variable.other.qmake"}},
        },
        "operator": {"name": "keyword.operator.assignment.qmake", "match": r"\+=|-=|\*=|~=|="},
        "keyword": {
            "name": "keyword.control.qmake",
            "match": r"\b(?:else|for|if|return|defineTest|defineReplace|include|load|requires)\b",
        },
        "function": {"name": "support.function.qmake", "match": r"\b[A-Za-z_][A-Za-z0-9_]*(?=\()"},
        "platform": {
            "name": "constant.language.qmake",
            "match": r"\b(?:unix|win32|win64|macx|linux|android|ios|mac|msvc|gcc|debug|release|debug_and_release)\b",
        },
        "number": {"name": "constant.numeric.qmake", "match": r"\b\d+\b"},
    },
}

GITIGNORE_GRAMMAR = {
    "name": "gitignore",
    "scopeName": "source.gitignore",
    "patterns": [
        {"name": "comment.line.number-sign.gitignore", "match": r"^\s*#.*$"},
        {"name": "keyword.operator.negation.gitignore", "match": r"^\s*!"},
        {"name": "constant.character.escape.gitignore", "match": r"\\."},
        {"name": "keyword.operator.wildcard.gitignore", "match": r"\*\*|\*|\?|\[[^\]\n]*\]"},
        {"name": "entity.name.section.gitignore", "match": r"/(?=\s*$)"},
        {"name": "punctuation.separator.gitignore", "match": r"/"},
        {"name": "string.unquoted.path.gitignore", "match": r"[^\s*?\[\]!/\\#]+"},
    ],
}

CUSTOM_GRAMMARS = {"qmake": QMAKE_GRAMMAR, "gitignore": GITIGNORE_GRAMMAR}


def write_grammar(tm, name, data, scope):
    d = tm / name
    (d / "syntaxes").mkdir(parents=True, exist_ok=True)
    grammar_rel = f"textmate/{name}/syntaxes/{name}.tmLanguage.json"
    config_rel = f"textmate/{name}/language-configuration.json"
    (d / "syntaxes" / f"{name}.tmLanguage.json").write_bytes(data)
    (d / "language-configuration.json").write_text(json.dumps(LANG_CONFIG), encoding="utf-8")
    return {
        "name": name,
        "scopeName": scope,
        "grammar": grammar_rel,
        "languageConfiguration": config_rel,
    }


def fetch_textmate():
    tm = ASSETS / "textmate"
    shutil.rmtree(tm, ignore_errors=True)
    (tm / "themes").mkdir(parents=True, exist_ok=True)

    entries = []
    for lang in LANGS:
        try:
            data = http_get(GRAMMAR_URL.format(lang))
            scope = json.loads(data)["scopeName"]
        except Exception as e:  # noqa: BLE001
            (fail if lang in REQUIRED_LANGS else warn)(f"grammar {lang}: {e}")
            continue
        entries.append(write_grammar(tm, lang, data, scope))

    for name, grammar in CUSTOM_GRAMMARS.items():
        data = json.dumps(grammar, indent=1).encode("utf-8")
        entries.append(write_grammar(tm, name, data, grammar["scopeName"]))

    (tm / "languages.json").write_text(json.dumps({"languages": entries}, indent=1), encoding="utf-8")

    for theme in THEMES:
        try:
            data = http_get(THEME_URL.format(theme))
            json.loads(data)
        except Exception as e:  # noqa: BLE001
            (fail if theme in REQUIRED_THEMES else warn)(f"theme {theme}: {e}")
            continue
        (tm / "themes" / f"{theme}.json").write_bytes(data)
    print(f"textmate: {len(entries)} grammars")


# ---------------------------------------------------------------- fonts

# (id used by the app, directory in github.com/google/fonts)
FONTS = [
    ("jetbrains-mono", "ofl/jetbrainsmono"),
    ("fira-code", "ofl/firacode"),
    ("source-code-pro", "ofl/sourcecodepro"),
    ("ibm-plex-mono", "ofl/ibmplexmono"),
    ("roboto-mono", "ofl/robotomono"),
    ("ubuntu-mono", "ufl/ubuntumono"),
    ("cascadia-code", "ofl/cascadiacode"),
    ("inconsolata", "ofl/inconsolata"),
    ("noto-sans-mono", "ofl/notosansmono"),
]


def pick_font(files):
    ttf = [
        f for f in files
        if f.get("type") == "file"
        and f["name"].lower().endswith(".ttf")
        and "italic" not in f["name"].lower()
    ]
    for f in ttf:
        if f["name"].endswith("-Regular.ttf"):
            return f
    for f in ttf:
        if "[" in f["name"]:  # variable font: one file covers every weight
            return f
    return ttf[0] if ttf else None


def fetch_fonts():
    out = ASSETS / "fonts"
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True, exist_ok=True)
    token = os.environ.get("GITHUB_TOKEN")
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    got = 0
    for font_id, path in FONTS:
        try:
            listing = json.loads(http_get(f"https://api.github.com/repos/google/fonts/contents/{path}", headers))
            if not isinstance(listing, list):
                raise RuntimeError(str(listing)[:120])
            chosen = pick_font(listing)
            if chosen is None:
                raise RuntimeError("no regular TTF found")
            (out / f"{font_id}.ttf").write_bytes(http_get(chosen["download_url"]))
            got += 1
        except Exception as e:  # noqa: BLE001
            warn(f"font {font_id}: {e} (the app falls back to the system monospace font)")
    print(f"fonts: {got}/{len(FONTS)}")


# ---------------------------------------------------------------- logos

DEVICON = "https://cdn.jsdelivr.net/gh/devicons/devicon@v2.16.0/icons/{0}/{0}-original.svg"
SIMPLE = "https://cdn.jsdelivr.net/npm/simple-icons@11/icons/{}.svg"

# key used by the app (the language key) -> (source, slug, optional fill colour)
LOGOS = {
    "python": ("devicon", "python", None),
    "kotlin": ("devicon", "kotlin", None),
    "typescript": ("devicon", "typescript", None),
    "javascript": ("devicon", "javascript", None),
    "cpp": ("devicon", "cplusplus", None),
    "swift": ("devicon", "swift", None),
    "java": ("devicon", "java", None),
    "ruby": ("devicon", "ruby", None),
    "php": ("devicon", "php", None),
    "rust": ("simple", "rust", "#DEA584"),
    "toml": ("simple", "toml", "#C2603E"),
    "gdscript": ("simple", "godotengine", "#478CBF"),
    "tsx": ("simple", "react", "#61DAFB"),
    "yaml": ("simple", "yaml", "#CB171E"),
    "qmake": ("simple", "qt", "#41CD52"),      # Qt project files (.pro)
    "gitignore": ("simple", "git", "#F05032"),
    "xml": ("local", None, None),              # drawn here: a "</>" mark
}

# XML has no suitable brand logo, so its mark is drawn from plain strokes (no fonts needed).
XML_SVG = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="#F58220" '
    'stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round">'
    '<polyline points="8,6 2.5,12 8,18"/><polyline points="16,6 21.5,12 16,18"/>'
    '<line x1="14" y1="4.5" x2="10" y2="19.5"/></svg>'
)

# If a logo download fails, these still get a simple labelled badge instead of a generic icon.
BADGES = {
    "yaml": ("YML", "#CB171E"),
    "qmake": ("PRO", "#41CD52"),
    "gitignore": ("GIT", "#F05032"),
}


def badge_svg(text, color):
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96">'
        f'<rect width="96" height="96" rx="18" fill="{color}"/>'
        '<text x="48" y="61" font-family="DejaVu Sans, Arial, sans-serif" font-size="32" '
        f'font-weight="700" fill="#ffffff" text-anchor="middle">{text}</text></svg>'
    )


def fetch_logos():
    out = ASSETS / "icons" / "lang"
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True, exist_ok=True)
    try:
        import cairosvg
    except Exception as e:  # noqa: BLE001
        warn(f"cairosvg unavailable, language logos skipped: {e}")
        return
    got = 0
    for key, (source, slug, color) in LOGOS.items():
        svg = None
        try:
            if source == "local":
                svg = XML_SVG
            else:
                url = DEVICON.format(slug) if source == "devicon" else SIMPLE.format(slug)
                svg = http_get(url).decode("utf-8")
                if color:
                    svg = re.sub(r"<svg ", f'<svg fill="{color}" ', svg, count=1)
        except Exception as e:  # noqa: BLE001
            if key in BADGES:
                text, badge_color = BADGES[key]
                svg = badge_svg(text, badge_color)
                warn(f"logo {key}: download failed ({e}); using a drawn badge instead")
            else:
                warn(f"logo {key}: {e} (the app falls back to a generic file icon)")
                continue
        try:
            png = cairosvg.svg2png(bytestring=svg.encode("utf-8"), output_width=96, output_height=96)
            (out / f"{key}.png").write_bytes(png)
            got += 1
        except Exception as e:  # noqa: BLE001
            warn(f"logo {key}: couldn't render ({e}) (the app falls back to a generic file icon)")
    print(f"logos: {got}/{len(LOGOS)}")


# ------------------------------------------------------ theme previews

def copy_previews():
    dest = ASSETS / "theme-previews"
    shutil.rmtree(dest, ignore_errors=True)
    dest.mkdir(parents=True, exist_ok=True)
    src = ROOT / "theme-previews"
    count = 0
    if src.is_dir():
        for p in src.glob("*.png"):
            shutil.copy(p, dest / p.name.lower())
            count += 1
    print(f"theme previews: {count}")


# ----------------------------------------------------------------- main

def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    for stage in (make_icons, fetch_textmate, fetch_fonts, fetch_logos, copy_previews):
        try:
            stage()
        except Exception as e:  # noqa: BLE001
            fail(f"{stage.__name__}: {e}")
    print(f"\nDone: {len(errors)} error(s), {len(warnings)} warning(s)")
    for w in warnings:
        print("  warn  " + w)
    for e in errors:
        print("  ERROR " + e)
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()