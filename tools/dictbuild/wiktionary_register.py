"""Register labels of the English Wiktionary: the do-not-suggest flag of spec/05b-dictionaries.md §2.16 (0.7.1).

One pass over the English dump reads, for every main-namespace page, the level-2 section of each keyboard language
(`==German==`, `==English==`, `==Spanish==`, `==Portuguese==`, `==Italian==`, `==French==`) and classifies its sense
lines (lines starting with one or more `#` not followed by `:` or `*`):

- **labelled**: the line has a label template `{{lb|<code>|…}}` (also `{{lbl|…}}`, `{{label|…}}`) with the language's
  code, one of whose label arguments contains a label word (vulgar, derogatory, pejorative, offensive, slur) as a whole
  word, case-insensitively; named arguments and the connectors `_`, `and`, `or` are no label arguments;
- **form of**: otherwise, a line that consists of templates only, one of which is a form-of template (a name ending in
  " of", or `infl of`) with the language's code as first and the lemma as second argument;
- **other**: every other sense line.

The cache (gzip TSV, one row per language section) is
`code<TAB>title<TAB>senses<TAB>labelled<TAB>other<TAB>form-of lemmas (| separated)<TAB>first deciding label`.
`flagged()` applies rules 4 and 5: a title is flagged if its section has a sense line and every sense line is labelled,
a form-of line counting as labelled when its lemma's own sense lines are all labelled (one level, no chains). Templates
are only read as text, never expanded. Nothing here names a word.
"""

import bz2
import gzip
import html
import os
import re
import sys

LANGUAGES = {"de": "German", "en": "English", "es": "Spanish", "pt": "Portuguese", "it": "Italian", "fr": "French"}
HEADINGS = {re.compile(r"^==\s*" + name + r"\s*==\s*$"): code for code, name in LANGUAGES.items()}
LEVEL2 = re.compile(r"^==[^=].*==\s*$")
SENSE = re.compile(r"^#+(?![:*])")
LABEL_TEMPLATES = {"lb", "lbl", "label"}
LABEL_WORDS = re.compile(r"\b(vulgar|derogatory|pejorative|offensive|slur)\b", re.IGNORECASE)
CONNECTORS = {"_", "and", "or"}


def cache_path(cache_dir):
    return os.path.join(cache_dir, "enwiktionary-20261001-register.tsv.gz")


def templates(text):
    """Top-level templates of [text] as (name, [arguments]) and the text outside them. Arguments are split at `|`
    outside nested templates and links."""
    out = []
    rest = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("{{", i):
            depth = 0
            j = i
            parts = []
            cur = []
            link = 0
            while j < n:
                if text.startswith("{{", j):
                    depth += 1
                    if depth > 1:
                        cur.append("{{")
                    j += 2
                    continue
                if text.startswith("}}", j):
                    depth -= 1
                    if depth == 0:
                        parts.append("".join(cur))
                        j += 2
                        break
                    cur.append("}}")
                    j += 2
                    continue
                if text.startswith("[[", j):
                    link += 1
                    cur.append("[[")
                    j += 2
                    continue
                if text.startswith("]]", j) and link:
                    link -= 1
                    cur.append("]]")
                    j += 2
                    continue
                ch = text[j]
                if ch == "|" and depth == 1 and not link:
                    parts.append("".join(cur))
                    cur = []
                else:
                    cur.append(ch)
                j += 1
            else:
                # unclosed template: the rest is text
                rest.append(text[i:])
                break
            if parts:
                out.append((parts[0].strip(), [p.strip() for p in parts[1:]]))
            i = j
            continue
        rest.append(text[i])
        i += 1
    return out, "".join(rest)


def _label(tpls, code):
    """The first label argument with a label word of a label template of [code], or None."""
    for name, args in tpls:
        if name.lower() not in LABEL_TEMPLATES or not args or args[0] != code:
            continue
        for a in args[1:]:
            if "=" in a or a in CONNECTORS:
                continue
            if LABEL_WORDS.search(a):
                return a
    return None


def _form_of(tpls, rest, code):
    """The lemma of a form-of template of [code] when the line consists of templates only, else None."""
    if any(ch.isalpha() for ch in rest):
        return None
    for name, args in tpls:
        low = name.lower()
        if (low.endswith(" of") or low == "infl of") and len(args) >= 2 and args[0] == code:
            lemma = args[1].replace("[[", "").replace("]]", "").strip()
            if lemma:
                return lemma
    return None


def classify(section_lines, code):
    """(senses, labelled, other, form-of lemmas, first label) of one language section."""
    senses = labelled = other = 0
    lemmas = []
    first = ""
    for line in section_lines:
        m = SENSE.match(line)
        if not m:
            continue
        senses += 1
        tpls, rest = templates(line[m.end():])
        lab = _label(tpls, code)
        if lab is not None:
            labelled += 1
            if not first:
                first = lab
            continue
        lemma = _form_of(tpls, rest, code)
        if lemma is not None:
            lemmas.append(lemma)
        else:
            other += 1
    return senses, labelled, other, lemmas, first


def _sections(text):
    """{code: section lines} of the keyboard languages on a page."""
    out = {}
    cur = None
    for line in text.split("\n"):
        s = line.rstrip()
        if LEVEL2.match(s) and not s.startswith("==="):
            cur = None
            for pat, code in HEADINGS.items():
                if pat.match(s):
                    cur = code
                    out[code] = []
                    break
            continue
        if cur is not None:
            out[cur].append(s)
    return out


def _pages(dump_path):
    title = ns = None
    buf = None
    pages = 0
    with bz2.open(dump_path, "rt", encoding="utf-8") as f:
        for line in f:
            if buf is not None:
                end = line.find("</text>")
                if end >= 0:
                    buf.append(line[:end])
                    yield title, "".join(buf)
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
                pages += 1
                if pages % 500000 == 0:
                    print(f"  enwiktionary register pass: {pages} pages", file=sys.stderr)
                if s.endswith("/>"):
                    continue
                rest = line[line.find(">") + 1:]
                end = rest.find("</text>")
                if ns != "0":
                    if end < 0:
                        for skip in f:
                            if "</text>" in skip:
                                break
                    continue
                if end >= 0:
                    yield title, rest[:end]
                else:
                    buf = [rest]


def extract(dump_path, out_path):
    """One pass over the English dump; writes the cache of every keyboard-language section with a sense line."""
    rows = []
    markers = ["==" + name + "==" for name in LANGUAGES.values()]
    for title, raw in _pages(dump_path):
        if not any(m in raw for m in markers):
            continue
        text = html.unescape(raw)
        for code, lines in _sections(text).items():
            senses, labelled, other, lemmas, first = classify(lines, code)
            if senses:
                rows.append((code, title, senses, labelled, other, "|".join(lemmas), first))
    rows.sort()
    tmp = out_path + ".tmp"
    with gzip.GzipFile(tmp, "wb", compresslevel=9, mtime=0) as gz:
        for r in rows:
            clean = [str(x).replace("\t", " ").replace("\n", " ") for x in r]
            gz.write(("\t".join(clean) + "\n").encode("utf-8"))
    os.replace(tmp, out_path)


def load(path, code):
    """{title: (senses, labelled, other, [lemmas], first label)} of one language."""
    out = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if p[0] != code:
                continue
            out[p[1]] = (int(p[2]), int(p[3]), int(p[4]), [x for x in p[5].split("|") if x], p[6])
    return out


def flagged(pages):
    """Rules 4 and 5 of §2.16: {title: deciding label} of the flagged titles of one language."""
    direct = {t for t, (s, lab, oth, lem, _) in pages.items() if s > 0 and lab == s}
    out = {}
    for t, (s, lab, oth, lem, first) in pages.items():
        if s == 0 or oth > 0:
            continue
        if all(l in direct for l in lem):
            if lab == s:
                out[t] = first
            else:
                via = next(l for l in lem)
                out[t] = first or f"form of {via}: {pages[via][4]}"
    return out
