"""German Wiktionary pass (05b-dictionaries §3.2).

Streams the pages-articles dump (bz2) and collects:
  1. titles      - every main-namespace title that is a single letter-only word (all languages)
  2. de_forms    - titles with a German section plus single-token forms of the German overview tables
  3. case_counts - running-text token counts (not sentence-initial) from prose and list blocks of German sections

The dump is treated as untrusted data: it is only parsed as text, nothing in it is executed.
The result is cached as a gzip TSV next to the other intermediate build files.
"""

import bz2
import gzip
import html
import os
import re
import sys

LETTER_RUN = re.compile(r"[^\W\d_]+")
SINGLE_WORD = re.compile(r"^[^\W\d_]+$")

GERMAN_HEADING = re.compile(r"^==\s*[^=].*\(\{\{Sprache\|Deutsch\}\}\)\s*==\s*$")
LANG_HEADING = re.compile(r"^==\s*[^=].*\(\{\{Sprache\|[^}]*\}\}\)\s*==\s*$")
HEADING = re.compile(r"^=+.*=+\s*$")
BLOCK_LINE = re.compile(r"^\{\{([^{}|]+)(\|[^{}]*)?\}\}\s*$")

OVERVIEW_START = re.compile(r"^\{\{Deutsch [^|{}]*Übersicht")
FIELD_KEYWORDS = ("Singular", "Plural", "Positiv", "Komparativ", "Superlativ", "Präsens", "Präteritum",
                  "Partizip", "Konjunktiv", "Imperativ", "Nominativ", "Genitiv", "Dativ", "Akkusativ")

PROSE_BLOCKS = {"Bedeutungen", "Beispiele", "Herkunft", "Redewendungen", "Sprichwörter",
                "Charakteristische Wortkombinationen"}
LIST_BLOCKS = {"Synonyme", "Sinnverwandte Wörter", "Gegenwörter", "Oberbegriffe", "Unterbegriffe",
               "Wortbildungen", "Abkürzungen", "Nebenformen", "Alternative Schreibweisen",
               "Verkleinerungsformen", "Weibliche Wortformen", "Männliche Wortformen", "Koseformen",
               "Kurzformen"}
COUNT_BLOCKS = PROSE_BLOCKS | LIST_BLOCKS

SENTENCE_OPENERS = set(".!?:„“\"«»‚‘›‹(")

RE_COMMENT = re.compile(r"<!--.*?-->", re.S)
RE_REF_PAIR = re.compile(r"<ref[^>/]*>.*?</ref>", re.S)
RE_REF_SINGLE = re.compile(r"<ref[^>]*/>")
RE_TAG = re.compile(r"</?[A-Za-z][^>]*>")
RE_TEMPLATE = re.compile(r"\{\{[^{}]*\}\}")
RE_FILE_LINK = re.compile(r"\[\[(?:Datei|Bild|File|Image):[^\[\]]*\]\]")
RE_LINK_PIPE = re.compile(r"\[\[[^\[\]|]*\|([^\[\]]*)\]\]")
RE_LINK = re.compile(r"\[\[([^\[\]]*)\]\]")
RE_EXT_LINK = re.compile(r"\[(?:https?:)?//[^\s\]]*\s*([^\]]*)\]")
RE_QUOTES = re.compile(r"'{2,}")


def clean_markup(text):
    text = RE_COMMENT.sub(" ", text)
    text = RE_REF_PAIR.sub(" ", text)
    text = RE_REF_SINGLE.sub(" ", text)
    text = RE_TAG.sub(" ", text)
    for _ in range(4):  # nested templates, up to 4 levels
        new = RE_TEMPLATE.sub(" ", text)
        if new == text:
            break
        text = new
    text = RE_FILE_LINK.sub(" ", text)
    text = RE_LINK_PIPE.sub(r"\1", text)
    text = RE_LINK.sub(r"\1", text)
    text = RE_EXT_LINK.sub(r"\1", text)
    text = RE_QUOTES.sub("", text)
    text = html.unescape(text)
    return text


def count_running_text(line, counts):
    """Counts every letter-run token of a cleaned line except sentence-initial ones."""
    first = True
    for m in LETTER_RUN.finditer(line):
        tok = m.group(0)
        if first:
            first = False
            continue
        j = m.start() - 1
        while j >= 0 and line[j].isspace():
            j -= 1
        if j >= 0 and line[j] in SENTENCE_OPENERS:
            continue
        counts[tok] = counts.get(tok, 0) + 1


SPLIT_ALTERNATIVES = re.compile(r"<br\s*/?>|\n|,|;|/| oder ")


def overview_forms(lines, forms):
    """Single-token forms from the fields of a German overview table."""
    for raw in lines:
        if not raw.startswith("|") or "=" not in raw:
            continue
        name, value = raw[1:].split("=", 1)
        if name.strip().startswith("Bild") or not any(k in name for k in FIELD_KEYWORDS):
            continue
        for alt in SPLIT_ALTERNATIVES.split(value):
            alt = clean_markup(alt).strip()
            if alt and SINGLE_WORD.match(alt):
                forms.add(alt)


def process_page(title, text, titles, de_forms, counts):
    if SINGLE_WORD.match(title):
        titles.add(title)
    lines = text.split("\n")
    in_german = False
    block = None
    overview = None
    for line in lines:
        if LANG_HEADING.match(line):
            in_german = bool(GERMAN_HEADING.match(line))
            if in_german and SINGLE_WORD.match(title):
                de_forms.add(title)
            block = None
            overview = None
            continue
        if not in_german:
            continue
        if overview is not None:
            if line.startswith("}}"):
                overview_forms(overview, de_forms)
                overview = None
            else:
                overview.append(line)
            continue
        if OVERVIEW_START.match(line):
            overview = []
            block = None
            continue
        if HEADING.match(line):
            block = None
            continue
        m = BLOCK_LINE.match(line)
        if m:
            block = m.group(1).strip()
            continue
        if block in COUNT_BLOCKS and line.startswith(":"):
            count_running_text(clean_markup(line.lstrip(":*# ")), counts)


def run(dump_path, out_path):
    titles, de_forms, counts = set(), set(), {}
    title = None
    ns = None
    buf = None
    pages = 0
    with bz2.open(dump_path, "rt", encoding="utf-8") as f:
        for line in f:
            if buf is not None:
                end = line.find("</text>")
                if end >= 0:
                    buf.append(line[:end])
                    if ns == "0" and title is not None:
                        process_page(title, html.unescape("".join(buf)), titles, de_forms, counts)
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
                if pages % 200000 == 0:
                    print(f"  wiktionary: {pages} pages", file=sys.stderr)
                gt = line.find(">")
                rest = line[gt + 1:]
                if s.endswith("/>"):
                    continue
                end = rest.find("</text>")
                if end >= 0:
                    if ns == "0":
                        process_page(title, html.unescape(rest[:end]), titles, de_forms, counts)
                else:
                    buf = [rest]
    tmp = out_path + ".tmp"
    with gzip.open(tmp, "wt", encoding="utf-8") as out:
        for t in sorted(titles):
            out.write(f"T\t{t}\n")
        for t in sorted(de_forms):
            out.write(f"F\t{t}\n")
        for t in sorted(counts):
            out.write(f"C\t{t}\t{counts[t]}\n")
    os.replace(tmp, out_path)


WORTART = re.compile(r"\{\{Wortart\|([^|{}]+)\|Deutsch\}\}")


def pos_run(dump_path, out_path):
    """Parts of speech of the German sections (05b §3.7, 0.6.5): for every single-word title with a German section, the
    values of its {{Wortart|…|Deutsch}} headings. Cached as a gzip TSV: title TAB part|part|…"""
    pos = {}
    title = None
    ns = None
    buf = None
    with bz2.open(dump_path, "rt", encoding="utf-8") as f:
        for line in f:
            if buf is not None:
                end = line.find("</text>")
                if end >= 0:
                    buf.append(line[:end])
                    if ns == "0" and title is not None and SINGLE_WORD.match(title):
                        page_pos(title, html.unescape("".join(buf)), pos)
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
                gt = line.find(">")
                rest = line[gt + 1:]
                if s.endswith("/>"):
                    continue
                end = rest.find("</text>")
                if end >= 0:
                    if ns == "0" and SINGLE_WORD.match(title or ""):
                        page_pos(title, html.unescape(rest[:end]), pos)
                else:
                    buf = [rest]
    tmp = out_path + ".tmp"
    with gzip.open(tmp, "wt", encoding="utf-8") as out:
        for t in sorted(pos):
            out.write(f"{t}\t{'|'.join(sorted(pos[t]))}\n")
    os.replace(tmp, out_path)


def page_pos(title, text, pos):
    in_german = False
    for line in text.split("\n"):
        if LANG_HEADING.match(line):
            in_german = bool(GERMAN_HEADING.match(line))
            if in_german:
                pos.setdefault(title, set())
            continue
        if in_german and line.startswith("==="):
            for m in WORTART.finditer(line):
                pos[title].add(m.group(1).strip())


def load_pos(path):
    out = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            t, _, p = line.rstrip("\n").partition("\t")
            out[t] = set(p.split("|")) if p else set()
    return out


def load(path):
    titles, de_forms, counts = set(), set(), {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if parts[0] == "T":
                titles.add(parts[1])
            elif parts[0] == "F":
                de_forms.add(parts[1])
            else:
                counts[parts[1]] = int(parts[2])
    return titles, de_forms, counts


if __name__ == "__main__":
    run(sys.argv[1], sys.argv[2])
