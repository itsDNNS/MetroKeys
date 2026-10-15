"""Wiktionary passes of the Spanish build (05b-dictionaries §8.2).

Spanish dump (es.wiktionary.org), main namespace, streaming:
  1. titles   - every title that is a single letter-only word (all languages; foreign-word detection)
  2. es_forms - titles with a Spanish language section ("== {{lengua|es}} =="), also pages that only describe an
                inflected form. Templates are never expanded or executed, so generated inflection tables add nothing.
  3. variants - titles whose Spanish section only points to another spelling: obsolete, old or substandard spellings
                ("tambien": {{grafía obsoleta|también}}) and spellings that differ from their target only by accents
                ("aqui": {{grafía|aquí}}). They are no Spanish forms for the build (§8.4 merges them into the accented
                word), and no foreign titles either.

English dump (en.wiktionary.org), optional (§8.2 point 3): titles with a "==Spanish==" section, as further Spanish
forms (mostly verb forms and plurals); variants as above (misspellings, obsolete and nonstandard spellings, and
alternative spellings that differ only by accents: "aqui").

Both passes also record
  - inflected - Spanish pages whose definitions are all inflection pointers (verb forms, plurals, feminine forms …),
                i.e. no lemma of their own ("aveces": a form of avezar; "dias": plural of dia);
  - phrases   - pages with a definition pointing to a phrase written with a space whose letters are the title
                ("osea": misspelling of "o sea"; "talvez": alternative form of "tal vez"): fused spellings of a phrase.

The dumps are untrusted data: they are only parsed as text. Results are cached as gzip TSV files.
"""

import bz2
import gzip
import html
import os
import re
import sys

SINGLE_WORD = re.compile(r"^[^\W\d_]+$")
STRIP_ALL = str.maketrans("áéíóúüñÁÉÍÓÚÜÑ", "aeiouunAEIOUUN")

# es.wiktionary: definition lines ";1: …" whose text is a pointer template
ES_DEF = re.compile(r"^;\s*\d+[^:]*:\s*(.*)$")
ES_ALWAYS_VARIANT = {"grafía obsoleta", "variante anticuada", "variante obsoleta", "variante subestándar"}
ES_ACCENT_VARIANT = {"grafía", "variante", "variante rara", "grafía alternativa"}
# en.wiktionary: definition lines "# …" (not "#:", "#*")
EN_DEF = re.compile(r"^#\s+(.*)$")
EN_ALWAYS_VARIANT = {"misspelling of", "obs sp", "obs form", "obsolete spelling of", "obsolete form of", "nonstandard spelling of",
                     "nonstandard form of", "archaic spelling of", "pronunciation spelling of", "eye dialect of",
                     "deliberate misspelling of", "es-misspelling of"}
EN_ACCENT_VARIANT = {"alternative spelling of", "alternative form of", "alt form", "alt sp", "alt form of", "alt sp of",
                     "standard spelling of", "superseded spelling of"}
TEMPLATE = re.compile(r"^\{\{\s*([^|{}]+?)\s*(\|[^{}]*)?\}\}")
LABEL = re.compile(r"^\{\{\s*(lb|lbl|tlb|q|qual|qualifier|i|gloss|gl|uso|ámbito)\s*\|[^{}]*\}\}\s*")
ES_INFLECTION = {"forma verbo", "f.v", "forma participio", "forma sustantivo plural", "forma sustantivo", "forma adjetivo",
                 "forma adjetivo 2", "f.adj2", "f.s.p", "gerundio", "participio", "forma pronombre", "enclítico", "plural"}
EN_INFLECTION = {"es-verb form of", "plural of", "feminine plural of", "feminine singular of", "masculine plural of",
                 "adj form of", "past participle of", "noun form of", "inflection of", "es-compound of"}
ES_SECTION = re.compile(r"^==\s*\{\{\s*lengua\s*\|\s*es\s*(\|[^{}]*)?\}\}\s*==\s*$")
EN_SPANISH_SECTION = re.compile(r"^==\s*Spanish\s*==\s*$")


def _pages(dump_path, label):
    """Yields (title, namespace, text) of every page of a pages-articles dump."""
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
                    yield title, ns, html.unescape("".join(buf))
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
                    print(f"  {label}: {pages} pages", file=sys.stderr)
                if s.endswith("/>"):
                    continue
                rest = line[line.find(">") + 1:]
                end = rest.find("</text>")
                if end >= 0:
                    yield title, ns, html.unescape(rest[:end])
                else:
                    buf = [rest]


def _section(text, pattern, level2):
    """Lines of the language section matched by [pattern], or None."""
    out = None
    for line in text.split("\n"):
        if level2(line):
            if out is not None:
                break
            if pattern.match(line.rstrip()):
                out = []
            continue
        if out is not None:
            out.append(line)
    return out


def _is_es_level2(line):
    return line.startswith("==") and not line.startswith("===")


def _is_en_level2(line):
    return line.startswith("==") and not line.startswith("===")


def _template_args(rest):
    return [a.strip() for a in rest.strip("|").split("|")] if rest else []


def _definitions(lines, def_re, skip_lang):
    """(template name, first positional argument) per definition line; name None for a definition in words."""
    out = []
    for line in lines:
        m = def_re.match(line)
        if not m:
            continue
        text = m.group(1).strip()
        while True:
            lm = LABEL.match(text)
            if not lm:
                break
            text = text[lm.end():]
        t = TEMPLATE.match(text)
        if not t:
            out.append((None, ""))
            continue
        args = [a for a in _template_args(t.group(2)) if "=" not in a]
        if skip_lang and args and args[0] == "es":
            args = args[1:]
        out.append((t.group(1).strip(), args[0] if args else ""))
    return out


def _classify(title, defs, always, accent, inflection):
    """(variant, inflected, phrase) flags of a Spanish section (see the module docstring)."""
    pointers = always | accent
    variant = bool(defs)
    for name, target in defs:
        if name in always:
            continue
        if name in accent and target and target != title and target.translate(STRIP_ALL) == title.translate(STRIP_ALL):
            continue
        variant = False
        break
    inflected = bool(defs) and all(name in inflection for name, _ in defs)
    plain = title.lower()
    phrase = any(name in pointers and " " in target and target.replace(" ", "").lower() == plain for name, target in defs)
    return variant, inflected, phrase


def _write(out_path, records):
    tmp = out_path + ".tmp"
    with gzip.open(tmp, "wt", encoding="utf-8") as out:
        for kind, values in records:
            for v in sorted(values):
                out.write(f"{kind}\t{v}\n")
    os.replace(tmp, out_path)


def run_es(dump_path, out_path):
    titles, forms, variants, inflected, phrases = set(), set(), set(), set(), set()
    for title, ns, text in _pages(dump_path, "eswiktionary"):
        if ns != "0" or title is None or not SINGLE_WORD.match(title):
            continue
        titles.add(title)
        lines = _section(text, ES_SECTION, _is_es_level2)
        if lines is None:
            continue
        v, i, p = _classify(title, _definitions(lines, ES_DEF, False), ES_ALWAYS_VARIANT, ES_ACCENT_VARIANT, ES_INFLECTION)
        (variants if v else forms).add(title)
        if i:
            inflected.add(title)
        if p:
            phrases.add(title)
    _write(out_path, [("T", titles), ("F", forms), ("V", variants), ("I", inflected), ("P", phrases)])


def run_en_spanish(dump_path, out_path):
    forms, variants, inflected, phrases = set(), set(), set(), set()
    for title, ns, text in _pages(dump_path, "enwiktionary"):
        if ns != "0" or title is None or not SINGLE_WORD.match(title):
            continue
        if "==Spanish==" not in text.replace(" ", ""):
            continue
        lines = _section(text, EN_SPANISH_SECTION, _is_en_level2)
        if lines is None:
            continue
        v, i, p = _classify(title, _definitions(lines, EN_DEF, True), EN_ALWAYS_VARIANT, EN_ACCENT_VARIANT, EN_INFLECTION)
        (variants if v else forms).add(title)
        if i:
            inflected.add(title)
        if p:
            phrases.add(title)
    _write(out_path, [("F", forms), ("V", variants), ("I", inflected), ("P", phrases)])


def load(path):
    """Returns a dict of the sets of a cached pass: T titles, F forms, V variants, I inflected, P phrases."""
    out = {"T": set(), "F": set(), "V": set(), "I": set(), "P": set()}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            kind, _, value = line.rstrip("\n").partition("\t")
            out[kind].add(value)
    return out


if __name__ == "__main__":
    if sys.argv[1] == "es":
        run_es(sys.argv[2], sys.argv[3])
    else:
        run_en_spanish(sys.argv[2], sys.argv[3])
