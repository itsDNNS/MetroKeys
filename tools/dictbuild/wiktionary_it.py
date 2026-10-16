"""Wiktionary passes of the Italian build (05b-dictionaries §10.2).

Stage 1 (slow, cached): stream a dump and keep, per single-word page title (letters only, or letters + a final
apostrophe), the Italian section text
  - Italian dump (it.wiktionary.org): the "== {{-it-}} ==" section;
  - English dump (en.wiktionary.org): the "==Italian==" section;
  and every single-word page title of all languages (foreign-word detection).
Stage 2 (fast): classify the cached sections into the page kinds of §10.2:
  form, pointer (with target), apocopic (with the full word), inflected-only, fused phrase, letter page.

The dumps are untrusted data: they are only parsed as text, templates are read as text and never expanded.
"""

import gzip
import os
import re

from wiktionary_es import _pages
from wiktionary_pt import LINK, TEMPLATE_AT, _args, _unescape, load_extract, strip_diacritics  # noqa: F401

SINGLE_WORD = re.compile(r"^[^\W\d_]+'?$")

IT_LANG_HEADING = re.compile(r"^==\s*\{\{\s*-[^{}]+-\s*\}\}\s*==\s*$")
IT_SECTION = re.compile(r"^==\s*\{\{\s*-it-\s*\}\}\s*==\s*$")
EN_SECTION = re.compile(r"^==\s*Italian\s*==\s*$")


# ---------------------------------------------------------------------------------------------- stage 1

def _it_extract(text):
    out = None
    for line in text.split("\n"):
        s = line.rstrip()
        if s.startswith("==") and not s.startswith("===") and IT_LANG_HEADING.match(s):
            if out is not None:
                break
            if IT_SECTION.match(s):
                out = []
            continue
        if out is not None:
            out.append(line)
    return None if out is None else "\n".join(out)


def _en_extract(text):
    if "==Italian==" not in text.replace(" ", ""):
        return None
    out = None
    for line in text.split("\n"):
        if line.startswith("==") and not line.startswith("==="):
            if out is not None:
                break
            if EN_SECTION.match(line.rstrip()):
                out = []
            continue
        if out is not None:
            out.append(line)
    return None if out is None else "\n".join(out)


def extract(dump_path, out_path, kind):
    """Writes a gzip file of lines "T<TAB>title" (all single-word titles) and "S<TAB>title<TAB>escaped section"."""
    fn = _it_extract if kind == "it" else _en_extract
    tmp = out_path + ".tmp"
    titles = set()
    sections = {}
    for title, ns, text in _pages(dump_path, kind + "wiktionary"):
        if ns != "0" or title is None:
            continue
        title = title.replace("’", "'")
        if not SINGLE_WORD.match(title):
            continue
        titles.add(title)
        sec = fn(text)
        if sec is not None:
            sections[title] = sec
    with gzip.open(tmp, "wt", encoding="utf-8") as out:
        for t in sorted(titles):
            out.write(f"T\t{t}\n")
        for t in sorted(sections):
            esc = sections[t].replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
            out.write(f"S\t{t}\t{esc}\n")
    os.replace(tmp, out_path)


# ---------------------------------------------------------------------------------------------- stage 2

DEF_LINE = re.compile(r"^#(?![*:#])\s*(.*)$")


def _letters_only(s):
    """Lower case, diacritics and apostrophes removed (pointer targets that differ only by these)."""
    return strip_diacritics(s).replace("'", "").replace("’", "").lower()


def _spelling_only(a, b):
    """True if a and b differ, but only by diacritics, case or apostrophes."""
    return a != b and _letters_only(a) == _letters_only(b)


def _kinds():
    return {"form": False, "pointer": None, "apocopic": None, "inflected": False, "phrase": False, "letter": False,
            "host": False, "verb": False}


def _finish(out, own, pointers, inflections, apocopic, letters_only):
    if letters_only:
        out["letter"] = True
        return out
    if own + inflections + len(apocopic) == 0 and pointers:
        out["pointer"] = pointers[0]
    else:
        out["form"] = True
    if apocopic:
        out["apocopic"] = apocopic[0]
    out["inflected"] = inflections > 0 and own == 0 and not apocopic
    return out


# Italian dump ----------------------------------------------------------------------------------

IT_LABEL = re.compile(r"^\{\{\s*(?:Term|term|Glossa|glossa|Fig|fig|Est|est|Obs|obs|Lett|Pop|Spreg|spreg|Vulg|volg|Ant|"
                      r"Tabs|Teen|Botanic)\s*(?:\|[^{}]*)?\}\}\s*")
IT_OBSOLETE = re.compile(r"\b(?:obsolet|antic|arcaic|antiquat|raro|rara|desuet|letterari)", re.I)
IT_POINTER = re.compile(r"^(?:variante|grafia|forma|errore|scrittura)\b[^\[{]*?(?:\b(?:grafica|ricercata|antica|"
                        r"antiquata|obsoleta|arcaica|errata|scorretta|alternativa|erronea|letteraria|rara|meno comune|"
                        r"ortografic\w*|comune)\b|,|\bdi\b)", re.I)
IT_MISSPELLING = re.compile(r"\b(?:errat|scorrett|erron|errore)", re.I)
IT_INFLECTION = re.compile(r"(?:\bpersona\b|\bparticipio\b|\bgerundio\b|^(?:,\s*)?plurale\b|\bplurale (?:di|maschile|"
                           r"femminile)\b|^(?:,\s*)?femminile\b|^(?:,\s*)?maschile (?:plurale|singolare)\b|\bª persona\b)",
                           re.I)
IT_APOCOPIC = re.compile(r"\b(?:forma (?:tronca|apocopata)|troncamento|apocope|apocopato)\b", re.I)
IT_TARGET = re.compile(r"\{\{\s*(?:Vd|vd)\s*\|([^{}|]+)[^{}]*\}\}|\[\[([^\[\]|#]+)(?:#[^\[\]|]*)?(?:\|[^\[\]]*)?\]\]")
IT_HOST = re.compile(r"\b(?:imperativo|gerundio)\b", re.I)
IT_HEADING = re.compile(r"^\{\{\s*-([^|{}]+?)-\s*(?:\|[^{}]*)?\}\}")


def _plain(text):
    t = re.sub(r"\[\[([^\]|]*\|)?([^\]]*)\]\]", r"\2", text)
    t = re.sub(r"\{\{[^{}]*\}\}", "", t)
    return t.replace("'''", "").replace("''", "").strip()


def classify_it(title, section):
    """Page kinds of an Italian-dump Italian section (§10.2)."""
    out = _kinds()
    own = inflections = 0
    pointers = []
    apocopic = []
    headings = set()
    defs = 0
    for line in section.split("\n"):
        hm = IT_HEADING.match(line)
        if hm:
            headings.add(hm.group(1).strip())
        dm = DEF_LINE.match(line)
        if not dm:
            continue
        defs += 1
        text = dm.group(1).strip()
        labels = []
        while True:
            m = IT_LABEL.match(text)
            if not m:
                break
            labels.append(m.group(0))
            text = text[m.end():]
        tm = IT_TARGET.search(text)
        target = (tm.group(1) or tm.group(2)).strip() if tm else None
        plain = _plain(text)
        if target and " " in target and target.replace(" ", "").replace("'", "").lower() == title.replace("'", "").lower():
            out["phrase"] = True
        if IT_APOCOPIC.search(plain) and target:
            apocopic.append(target)
            continue
        if IT_POINTER.match(plain) and target and len(LINK.sub("", IT_TARGET.sub("", text)).strip(" .,;:")) < 60:
            obsolete = any(IT_OBSOLETE.search(lab) for lab in labels) or IT_OBSOLETE.search(plain)
            if _spelling_only(title, target) or obsolete or IT_MISSPELLING.search(plain):
                pointers.append(target)
                continue
        if IT_INFLECTION.search(plain):
            if IT_HOST.search(plain):
                out["host"] = True
            inflections += 1
            continue
        own += 1
    out["verb"] = "verb" in headings
    if defs == 0:
        out["letter"] = headings == {"lett"}
        out["form"] = not out["letter"]
        return out
    return _finish(out, own, pointers, inflections, apocopic, headings and headings <= {"lett", "sill", "pron", "etim", "ref"}
                   and "lett" in headings)


# English dump ----------------------------------------------------------------------------------

EN_SKIP = re.compile(r"^\{\{\s*(?:lb|lbl|label|q|qual|qualifier|i|gloss|gl|senseid|sid|tcl|n-g|ng|tlb)\s*\|[^{}]*\}\}\s*")
EN_INFLECTION = {"inflection of", "infl of", "plural of", "feminine plural of", "feminine singular of",
                 "masculine plural of", "adj form of", "present participle of", "past participle of", "gerund of",
                 "it-compound of", "verb form of", "noun form of", "reflexive of", "masculine singular of"}
EN_POINTER = {"misspelling of", "obsolete spelling of", "obsolete form of", "obs sp", "obs form", "archaic spelling of",
              "archaic form of", "nonstandard spelling of", "nonstandard form of", "eye dialect of",
              "pronunciation spelling of", "deliberate misspelling of", "medieval spelling of", "superseded spelling of",
              "dated spelling of", "dated form of"}
EN_ALT = {"alternative form of", "alt form", "alt sp", "alternative spelling of", "altform", "alt form of", "alt sp of",
          "alt of", "alternative case form of", "alt case", "alt case form", "standard spelling of"}
EN_APOCOPIC = {"apocopic form of", "apoc of", "apocope of"}


def classify_en(title, section):
    """Page kinds of an English-dump Italian section (§10.2)."""
    out = _kinds()
    own = inflections = 0
    pointers = []
    apocopic = []
    defs = 0
    letter_defs = 0
    in_letter = False
    out["verb"] = "{{it-verb" in section
    for line in section.split("\n"):
        if line.startswith("==="):
            in_letter = line.strip("= ").lower() == "letter"
            continue
        dm = DEF_LINE.match(line)
        if not dm:
            continue
        defs += 1
        text = dm.group(1).strip()
        while True:
            m = EN_SKIP.match(text)
            if not m:
                break
            text = text[m.end():]
        t = TEMPLATE_AT.match(text)
        name = t.group(1).strip() if t else None
        args = [a for a in _args(t.group(2)) if "=" not in a] if t else []
        if args and args[0] == "it":
            args = args[1:]
        target = args[0].split(",")[0].strip() if args else None
        if name and " " in (target or "") and target.replace(" ", "").replace("'", "").lower() == title.replace("'", "").lower():
            out["phrase"] = True
        if name == "Latn-def" or in_letter:
            letter_defs += 1
            continue
        if name in EN_INFLECTION:
            if name == "gerund of" or (t and re.search(r"\|(?:impr|imp)(?:\||\}|$)", t.group(0))):
                out["host"] = True
            inflections += 1
            continue
        if name in EN_APOCOPIC and target:
            apocopic.append(target)
            continue
        if name in EN_POINTER and target:
            pointers.append(target)
            continue
        if name in EN_ALT and target and _spelling_only(title, target):
            pointers.append(target)
            continue
        own += 1
    if defs == 0:
        out["form"] = True
        return out
    return _finish(out, own, pointers, inflections, apocopic, letter_defs == defs)
