#!/usr/bin/env python3
"""Gitode build-time asset preparation. CI runs this before Gradle.

1. Launcher icons generated from icon.png (adaptive icon, transparent background).
2. TextMate grammars and themes (tm-grammars / tm-themes through jsDelivr),
   plus small self-contained grammars written here: XML, plain text, Qt project
   files (qmake) and .gitignore.
3. Fonts (google/fonts repository).
4. Language logos (devicon / simple-icons rasterised to PNG, plus a few drawn here).
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

# Keys here are Gitode's language keys (they match Fs.languageOf in the app).
# "xml" and "text" are NOT listed: they are self-contained grammars written below.
LANGS = [
    "python", "kotlin", "typescript", "tsx", "javascript", "toml", "gdscript",
    "cpp", "c", "swift", "java", "ruby", "php", "rust",
    "json", "jsonc", "markdown", "yaml", "html", "css",
    "shellscript", "groovy", "ini", "sql", "csharp", "go", "dart",
    "lua", "xsl",
    "powershell", "dockerfile", "makefile", "r", "julia", "matlab", "scala",
    # newest languages
    "graphql", "haskell", "elixir", "erlang", "perl", "asm", "zig", "nix",
    "terraform", "vue", "svelte", "clojure",
    # helper grammars that Vue, Svelte and others build on (optional)
    "html-derivative", "vue-html", "vue-directives", "vue-interpolations",
    "vue-sfc-style-variable-injection", "markdown-vue",
    "scss", "sass", "less", "stylus", "pug", "jsx", "json5",
]

# Some grammars are published under a different name; each is tried in order.
SOURCE_NAMES = {
    "dockerfile": ["docker", "dockerfile"],
    "makefile": ["make", "makefile"],
    "asm": ["asm"],
}

REQUIRED_LANGS = {
    "python", "kotlin", "typescript", "tsx", "javascript", "toml", "gdscript",
    "cpp", "swift", "java", "ruby", "php", "rust", "yaml", "markdown",
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

# Plain text (.txt): a grammar with no rules, so nothing is coloured.
PLAIN_GRAMMAR = {"name": "text", "scopeName": "text.plain", "patterns": []}

XML_GRAMMAR = {
    "name": "xml",
    "scopeName": "text.xml",
    "patterns": [
        {"include": "#comment"},
        {"include": "#cdata"},
        {"include": "#doctype"},
        {"include": "#processing"},
        {"include": "#tag"},
        {"include": "#entity"},
    ],
    "repository": {
        "comment": {"name": "comment.block.xml", "begin": r"<!--", "end": r"-->"},
        "cdata": {
            "name": "string.unquoted.cdata.xml",
            "begin": r"<!\[CDATA\[",
            "end": r"\]\]>",
        },
        "doctype": {
            "name": "meta.tag.sgml.doctype.xml",
            "begin": r"<!DOCTYPE",
            "end": r">",
            "beginCaptures": {"0": {"name": "entity.name.tag.xml"}},
            "patterns": [{"include": "#string"}],
        },
        "processing": {
            "name": "meta.tag.preprocessor.xml",
            "begin": r"(<\?)\s*([-_a-zA-Z0-9]+)",
            "end": r"(\?>)",
            "beginCaptures": {
                "1": {"name": "punctuation.definition.tag.xml"},
                "2": {"name": "entity.name.tag.xml"},
            },
            "endCaptures": {"1": {"name": "punctuation.definition.tag.xml"}},
            "patterns": [{"include": "#attribute"}, {"include": "#string"}],
        },
        "tag": {
            "name": "meta.tag.xml",
            "begin": r"(</?)\s*([-_a-zA-Z0-9:.]+)",
            "end": r"(/?>)",
            "beginCaptures": {
                "1": {"name": "punctuation.definition.tag.xml"},
                "2": {"name": "entity.name.tag.xml"},
            },
            "endCaptures": {"1": {"name": "punctuation.definition.tag.xml"}},
            "patterns": [{"include": "#attribute"}, {"include": "#string"}],
        },
        "attribute": {
            "match": r"([-_a-zA-Z0-9:.]+)(=)",
            "captures": {
                "1": {"name": "entity.other.attribute-name.xml"},
                "2": {"name": "punctuation.separator.key-value.xml"},
            },
        },
        "string": {
            "patterns": [
                {
                    "name": "string.quoted.double.xml",
                    "begin": r'"',
                    "end": r'"',
                    "patterns": [{"include": "#entity"}],
                },
                {
                    "name": "string.quoted.single.xml",
                    "begin": r"'",
                    "end": r"'",
                    "patterns": [{"include": "#entity"}],
                },
            ]
        },
        "entity": {
            "name": "constant.character.entity.xml",
            "match": r"&(?:#[0-9]+|#x[0-9a-fA-F]+|[a-zA-Z_][a-zA-Z0-9_.-]*);",
        },
    },
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

CUSTOM_GRAMMARS = {
    "text": PLAIN_GRAMMAR,
    "xml": XML_GRAMMAR,
    "qmake": QMAKE_GRAMMAR,
    "gitignore": GITIGNORE_GRAMMAR,
}


def external_scope(include, own):
    """The foreign scope an include points to, or None for local references."""
    if not isinstance(include, str) or include.startswith("#") or include in ("$self", "$base"):
        return None
    scope = include.split("#", 1)[0]
    if scope == "" or scope == own:
        return None
    return scope


def strip_unresolved(node, own, known, removed):
    """Remove includes of grammars that are not bundled, so loading never depends on them."""
    if isinstance(node, dict):
        pats = node.get("patterns")
        if isinstance(pats, list):
            keep = []
            for p in pats:
                if isinstance(p, dict) and set(p.keys()) <= {"include"}:
                    sc = external_scope(p.get("include"), own)
                    if sc is not None and sc not in known:
                        removed.add(sc)
                        continue
                keep.append(p)
            node["patterns"] = keep
        repo = node.get("repository")
        if isinstance(repo, dict):
            for key, rule in list(repo.items()):
                if isinstance(rule, dict) and set(rule.keys()) <= {"include"}:
                    sc = external_scope(rule.get("include"), own)
                    if sc is not None and sc not in known:
                        removed.add(sc)
                        repo[key] = {"patterns": []}
        for value in node.values():
            strip_unresolved(value, own, known, removed)
    elif isinstance(node, list):
        for value in node:
            strip_unresolved(value, own, known, removed)


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


def download_grammar(lang):
    """Download the grammar for a Gitode language key, trying alternative published names."""
    last = None
    for source in SOURCE_NAMES.get(lang, [lang]):
        try:
            grammar = json.loads(http_get(GRAMMAR_URL.format(source)))
            return grammar, grammar["scopeName"]
        except Exception as e:  # noqa: BLE001
            last = e
    raise RuntimeError(last)


def fetch_textmate():
    tm = ASSETS / "textmate"
    shutil.rmtree(tm, ignore_errors=True)
    (tm / "themes").mkdir(parents=True, exist_ok=True)

    downloaded = {}
    for lang in LANGS:
        try:
            downloaded[lang] = download_grammar(lang)
        except Exception as e:  # noqa: BLE001
            (fail if lang in REQUIRED_LANGS else warn)(f"grammar {lang}: {e}")

    custom = {name: (g, g["scopeName"]) for name, g in CUSTOM_GRAMMARS.items()}
    known = {scope for _, scope in downloaded.values()} | {scope for _, scope in custom.values()}

    seen = {}
    entries = []
    for lang, (grammar, scope) in downloaded.items():
        if scope in seen:
            warn(f"grammar {lang} has the same scope as {seen[scope]} ({scope})")
        seen[scope] = lang
        removed = set()
        strip_unresolved(grammar, scope, known, removed)
        if removed:
            print(f"  {lang}: dropped includes of missing grammars {sorted(removed)}")
        data = json.dumps(grammar, separators=(",", ":")).encode("utf-8")
        entries.append(write_grammar(tm, lang, data, scope))

    for name, (grammar, scope) in custom.items():
        data = json.dumps(grammar, indent=1).encode("utf-8")
        entries.append(write_grammar(tm, name, data, scope))

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
    "qmake": ("simple", "qt", "#41CD52"),          # Qt project files (.pro)
    "gitignore": ("simple", "git", "#F05032"),
    "xml": ("local", None, None),                  # drawn here: a "</>" mark
    "csharp": ("devicon", "csharp", None),
    "go": ("devicon", "go", None),
    "dart": ("devicon", "dart", None),
    "lua": ("devicon", "lua", None),
    "sql": ("local", None, None),                  # drawn here: a database cylinder
    "html": ("devicon", "html5", None),
    "css": ("devicon", "css3", None),
    "json": ("local", None, None),                 # drawn here: curly braces
    "shellscript": ("simple", "gnubash", "#4EAA25"),  # Bash
    "powershell": ("simple", "powershell", "#5391FE"),
    "dockerfile": ("devicon", "docker", None),
    "makefile": ("local", None, None),             # drawn here: a cog
    "r": ("devicon", "r", None),
    "julia": ("devicon", "julia", None),
    "matlab": ("devicon", "matlab", None),
    "scala": ("devicon", "scala", None),
    # ---- newest additions
    "markdown": ("devicon", "markdown", None),
    "txt": ("local", None, None),                  # drawn here: a text document
    "c": ("devicon", "c", None),
    "graphql": ("simple", "graphql", "#E10098"),
    "haskell": ("devicon", "haskell", None),
    "elixir": ("devicon", "elixir", None),
    "erlang": ("devicon", "erlang", None),
    "perl": ("devicon", "perl", None),
    "asm": ("local", None, None),                  # drawn here: a chip
    "zig": ("simple", "zig", "#F7A41D"),
    "nix": ("simple", "nixos", "#5277C3"),
    "terraform": ("simple", "terraform", "#844FBA"),
    "vue": ("devicon", "vuejs", None),
    "svelte": ("devicon", "svelte", None),
    "clojure": ("devicon", "clojure", None),
}

_SVG_HEAD = (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="{c}" '
    'stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round">'
)

# Marks with no suitable brand logo are drawn from plain strokes (no fonts needed).
LOCAL_SVGS = {
    "xml": (
        _SVG_HEAD.format(c="#F58220", w="2.6")
        + '<polyline points="8,6 2.5,12 8,18"/><polyline points="16,6 21.5,12 16,18"/>'
        '<line x1="14" y1="4.5" x2="10" y2="19.5"/></svg>'
    ),
    "sql": (
        _SVG_HEAD.format(c="#E38C00", w="2")
        + '<ellipse cx="12" cy="5.5" rx="7.5" ry="3"/>'
        '<path d="M4.5 5.5v13c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-13"/>'
        '<path d="M4.5 12c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3"/></svg>'
    ),
    "json": (
        _SVG_HEAD.format(c="#F2C94C", w="2.2")
        + '<path d="M9 4.5C6.5 4.5 6.5 6.5 6.5 8.5V10c0 1.4-1 2-2.5 2 1.5 0 2.5.6 2.5 2v1.5c0 2 0 4 2.5 4"/>'
        '<path d="M15 4.5c2.5 0 2.5 2 2.5 4V10c0 1.4 1 2 2.5 2-1.5 0-2.5.6-2.5 2v1.5c0 2 0 4-2.5 4"/></svg>'
    ),
    "makefile": (
        _SVG_HEAD.format(c="#8FA3AD", w="2.2")
        + '<circle cx="12" cy="12" r="3.2"/><circle cx="12" cy="12" r="7.2"/>'
        '<path d="M12 2.8v2M12 19.2v2M2.8 12h2M19.2 12h2M5.5 5.5l1.4 1.4M17.1 17.1l1.4 1.4'
        'M5.5 18.5l1.4-1.4M17.1 6.9l1.4-1.4"/></svg>'
    ),
    "txt": (
        _SVG_HEAD.format(c="#9AA5B1", w="1.8")
        + '<path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4"/>'
        '<line x1="9" y1="12" x2="15" y2="12"/><line x1="9" y1="15.5" x2="15" y2="15.5"/>'
        '<line x1="9" y1="19" x2="12.5" y2="19"/></svg>'
    ),
    "asm": (
        _SVG_HEAD.format(c="#6C8EBF", w="1.9")
        + '<rect x="7" y="7" width="10" height="10" rx="1.5"/><rect x="10" y="10" width="4" height="4"/>'
        '<path d="M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4"/></svg>'
    ),
}

# If a logo download fails, these still get a simple labelled badge instead of a generic icon.
BADGES = {
    "yaml": ("YML", "#CB171E"),
    "qmake": ("PRO", "#41CD52"),
    "gitignore": ("GIT", "#F05032"),
    "csharp": ("C#", "#68217A"),
    "go": ("GO", "#00ADD8"),
    "dart": ("DRT", "#0175C2"),
    "lua": ("LUA", "#2C2D72"),
    "html": ("HTM", "#E34F26"),
    "css": ("CSS", "#1572B6"),
    "shellscript": ("SH", "#4EAA25"),
    "powershell": ("PS", "#5391FE"),
    "dockerfile": ("DKR", "#2496ED"),
    "r": ("R", "#276DC3"),
    "julia": ("JL", "#9558B2"),
    "matlab": ("ML", "#E16737"),
    "scala": ("SCA", "#DC322F"),
    "markdown": ("MD", "#519ABA"),
    "c": ("C", "#5C6BC0"),
    "graphql": ("GQL", "#E10098"),
    "haskell": ("HS", "#5E5086"),
    "elixir": ("EX", "#6E4A7E"),
    "erlang": ("ERL", "#A90533"),
    "perl": ("PL", "#39457E"),
    "zig": ("ZIG", "#F7A41D"),
    "nix": ("NIX", "#5277C3"),
    "terraform": ("TF", "#844FBA"),
    "vue": ("VUE", "#42B883"),
    "svelte": ("SV", "#FF3E00"),
    "clojure": ("CLJ", "#5881D8"),
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
                svg = LOCAL_SVGS[key]
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