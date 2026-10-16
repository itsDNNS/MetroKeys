"""Abbreviation and affix titles of the Wiktionary dumps (05b §3.6 "Abbreviation and affix titles", 0.6.7).

A page whose title is letters followed by a period ("usw.", "etc.") and that has a section of a language counts as a
form of the key without the period, with the title's case; a title ending in a hyphen ("anti-") is a prefix, which only
the German compound rule (§3.5) uses as the first part of a compound. One pass over a dump records
per language every such title (the letters without the final character); for German also the parts of speech of its
German section (§3.7 lemmas). The cache is a gzip TSV: `lang<TAB>form<TAB>title<TAB>parts of speech (| separated)`.
Templates are only read as text, never expanded.
"""

import bz2
import gzip
import html
import os
import re
import sys

TITLE = re.compile(r"^([^\W\d_]+)[.-]$")

# language sections per dump: language tag -> heading pattern
SECTIONS = {
    "de": {"de": re.compile(r"^==\s*[^=].*\(\{\{Sprache\|Deutsch\}\}\)\s*==\s*$")},
    "es": {"es": re.compile(r"^==\s*\{\{\s*lengua\s*\|\s*es\s*(\|[^{}]*)?\}\}\s*==\s*$")},
    "pt": {"pt": re.compile(r"^=\s*\{\{\s*-pt-\s*\}\}\s*=\s*$")},
    "it": {"it": re.compile(r"^==\s*\{\{\s*-it-\s*\}\}\s*==\s*$")},
    "fr": {"fr": re.compile(r"^==\s*\{\{\s*langue\s*\|\s*fr\s*(?:\|[^{}]*)?\}\}\s*==\s*$")},
    "en": {"en": re.compile(r"^==\s*English\s*==\s*$"), "es": re.compile(r"^==\s*Spanish\s*==\s*$"),
           "pt": re.compile(r"^==\s*Portuguese\s*==\s*$"), "it": re.compile(r"^==\s*Italian\s*==\s*$"),
           "fr": re.compile(r"^==\s*French\s*==\s*$")},
}
# the end of a language section: the next heading of the same level
LEVEL2 = re.compile(r"^==[^=].*==\s*$")
LEVEL1 = re.compile(r"^=[^=].*=\s*$")
WORTART = re.compile(r"\{\{Wortart\|([^|{}]+)\|Deutsch\}\}")


def _sections(dump, text):
    """{lang: section text} of the languages of [dump] that [text] has."""
    out = {}
    lines = text.split("\n")
    level = LEVEL1 if dump == "pt" else LEVEL2
    for lang, head in SECTIONS[dump].items():
        for i, line in enumerate(lines):
            if head.match(line.rstrip()):
                body = []
                for nxt in lines[i + 1:]:
                    if level.match(nxt.rstrip()) and not nxt.startswith("===" if dump != "pt" else "=="):
                        break
                    body.append(nxt)
                out[lang] = "\n".join(body)
                break
    return out


def extract(dump_path, out_path, dump):
    rows = set()
    title = ns = None
    buf = None
    with bz2.open(dump_path, "rt", encoding="utf-8") as f:
        for line in f:
            if buf is not None:
                end = line.find("</text>")
                if end >= 0:
                    buf.append(line[:end])
                    _page(dump, title, html.unescape("".join(buf)), rows)
                    buf = None
                else:
                    buf.append(line)
                continue
            s = line.strip()
            if s.startswith("<title>"):
                title = html.unescape(s[7:-8])
                ns = None
            elif s.startswith("<ns>"):
                ns = s[4:-5]
            elif s.startswith("<text"):
                if ns != "0" or not TITLE.match(title or "") or s.endswith("/>"):
                    # skip the text of every other page
                    if "</text>" not in line and not s.endswith("/>"):
                        for skip in f:
                            if "</text>" in skip:
                                break
                    continue
                rest = line[line.find(">") + 1:]
                end = rest.find("</text>")
                if end >= 0:
                    _page(dump, title, html.unescape(rest[:end]), rows)
                else:
                    buf = [rest]
    tmp = out_path + ".tmp"
    with gzip.open(tmp, "wt", encoding="utf-8") as out:
        for row in sorted(rows):
            out.write("\t".join(row) + "\n")
    os.replace(tmp, out_path)
    print(f"  {dump}: {len(rows)} abbreviation and affix titles", file=sys.stderr)


def _page(dump, title, text, rows):
    m = TITLE.match(title)
    for lang, body in _sections(dump, text).items():
        pos = "|".join(sorted(set(WORTART.findall(body)))) if lang == "de" else ""
        rows.add((lang, m.group(1), title, pos))


def load(paths, lang, ending):
    """{form: set of parts of speech} of [lang] from the given caches (union over the dumps), titles ending in
    [ending]: "." = abbreviations (forms), "-" = prefixes (German compound parts only, 05b §3.6 as decided in 0.6.7)."""
    out = {}
    for p in paths:
        with gzip.open(p, "rt", encoding="utf-8") as f:
            for line in f:
                lg, form, title, pos = line.rstrip("\n").split("\t")
                if lg == lang and title.endswith(ending):
                    out.setdefault(form, set()).update(x for x in pos.split("|") if x)
    return out


def cache_path(cache_dir, dump):
    return os.path.join(cache_dir, f"{dump}wiktionary-20261001-affix.tsv.gz")


if __name__ == "__main__":
    # python3 wiktionary_affix.py <dump> <dump path> <cache dir>
    extract(sys.argv[2], cache_path(sys.argv[3], sys.argv[1]), sys.argv[1])
