"""Wiktionary pass of the French build (05b-dictionaries §11.2).

Stage 1 (slow, cached): stream the French dump (fr.wiktionary.org) and keep
  - every page title that is a word (letters, letters with inner hyphens or apostrophes, or letters + a final
    apostrophe) of all languages (foreign-word detection, §11.6 step 8), with the language codes of its sections;
  - per such title the French section ("== {{langue|fr}} =="), reduced to its headings, the lines of form and the
    definition lines (examples, translations, pronunciations and lists of related words are left out).
  Titles use the typographic apostrophe ’, which is read as '.
Stage 2 (fast): classify the cached sections into the page kinds of §11.2: form, pointer (typographic, obsolete or
  misspelt spelling, with its target), letter page, elision page, inflected-only page, fused-phrase page.

The dump is untrusted data: it is only parsed as text, templates are read as text and never expanded.
"""

import gzip
import os
import re

from wiktionary_es import _pages
from wiktionary_pt import _unescape, strip_diacritics  # noqa: F401

WORD_TITLE = re.compile(r"^[^\W\d_]+(?:[-'][^\W\d_]+)*'?$")
LANG_HEADING = re.compile(r"^==\s*\{\{\s*langue\s*\|\s*([^|{}\s]+)\s*(?:\|[^{}]*)?\}\}\s*==\s*$")
SUB_HEADING = re.compile(r"^(={3,})\s*\{\{\s*S\s*\|\s*([^|{}]+?)\s*((?:\|[^{}]*)?)\}\}")
# subsections whose content the classification does not need
SKIP_SECTIONS = {"traductions", "prononciation", "anagrammes", "références", "voir aussi", "synonymes", "antonymes",
                 "dérivés", "apparentés", "vocabulaire", "hyperonymes", "hyponymes", "méronymes", "holonymes",
                 "homophones", "paronymes", "traductions à trier", "dérivés autres langues", "phrases", "proverbes",
                 "quasi-synonymes", "troponymes", "composés", "variantes dialectales", "gentilés", "attestations",
                 "notes", "note", "taux de reconnaissance", "étymologie", "anciennes orthographes"}


# ---------------------------------------------------------------------------------------------- stage 1

def _reduce(lines):
    out = []
    skip = False
    for line in lines:
        m = SUB_HEADING.match(line)
        if m or line.startswith("==="):
            name = m.group(2).strip().lower() if m else line.strip("= ").lower()
            skip = name in SKIP_SECTIONS
            out.append(line[:300])
            continue
        if skip:
            continue
        if line.startswith("#*") or line.startswith("#:") or line.startswith("##*") or line.startswith("##:"):
            continue
        if line.startswith("#") or line.startswith("'''") or line.startswith("{{"):
            out.append(line[:400])
    return out


def _scan(text):
    """Language codes of the page's sections and the reduced French section (or None)."""
    langs = []
    fr = None
    cur = None
    for line in text.split("\n"):
        if line.startswith("==") and not line.startswith("==="):
            m = LANG_HEADING.match(line.rstrip())
            if m:
                code = m.group(1)
                langs.append(code)
                if cur is not None:
                    fr = _reduce(cur)
                    cur = None
                if code == "fr" and fr is None:
                    cur = []
                continue
        if cur is not None:
            cur.append(line)
    if cur is not None:
        fr = _reduce(cur)
    return langs, (None if fr is None else "\n".join(fr))


def extract(dump_path, out_path):
    """Writes a gzip file of lines "T<TAB>title<TAB>language codes" and "S<TAB>title<TAB>escaped French section"."""
    tmp = out_path + ".tmp"
    titles = {}
    sections = {}
    for title, ns, text in _pages(dump_path, "frwiktionary"):
        if ns != "0" or title is None:
            continue
        title = title.replace("’", "'")
        if not WORD_TITLE.match(title):
            continue
        langs, sec = _scan(text)
        # "aujourd’hui" and its redirect "aujourd'hui" give the same title: keep the union of their languages
        titles[title] = ",".join(sorted(set(langs) | set(filter(None, titles.get(title, "").split(",")))))
        if sec is not None and title not in sections:
            sections[title] = sec
    with gzip.open(tmp, "wt", encoding="utf-8") as out:
        for t in sorted(titles):
            out.write(f"T\t{t}\t{titles[t]}\n")
        for t in sorted(sections):
            esc = sections[t].replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
            out.write(f"S\t{t}\t{esc}\n")
    os.replace(tmp, out_path)


def load_extract(path):
    """(titles: {title: set of language codes}, sections: {title: French section text})."""
    titles = {}
    sections = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            kind, _, rest = line.rstrip("\n").partition("\t")
            if kind == "T":
                title, _, langs = rest.partition("\t")
                titles[title] = set(langs.split(",")) if langs else set()
            else:
                title, _, esc = rest.partition("\t")
                sections[title] = _unescape(esc)
    for t in sections:
        # a French section seen through the typographic-apostrophe page of a title (see [extract])
        titles.setdefault(t, set()).add("fr")
    return titles, sections


# ---------------------------------------------------------------------------------------------- stage 2

POS_HEADING = re.compile(r"^={3,}\s*\{\{\s*S\s*\|\s*([^|{}]+?)\s*((?:\|[^{}]*)?)\}\}")
# subsections that are no part of speech (their definitions, if any, are not senses of the title)
NON_POS = SKIP_SECTIONS | {"variantes orthographiques", "variantes", "abréviations", "sources", "bibliographie",
                           "faux-amis", "transcriptions", "diminutifs", "augmentatifs", "voir", "drv", "dial",
                           "erreurs"}
DEF = re.compile(r"^#(?![*:#])\s*(.*)$")
LABEL = re.compile(r"^\{\{\s*([^|{}]+?)\s*(?:\|[^{}]*)?\}\}\s*")
OLD_LABELS = {"désuet", "vieilli", "avant 1835", "archaïsme", "archaïque", "obsolète", "ancienne orthographe"}
POINTER_ALWAYS = re.compile(r"^(?:ancienne orthographe|orthographe ancienne|graphie ancienne|variante ancienne|"
                            r"variante archaïque|orthographe archaïque|variante par contrainte typographique|"
                            r"variante typographique|mauvaise orthographe|orthographe erronée|graphie erronée|"
                            r"faute d['’]orthographe|erreur d['’]orthographe|écriture de .* (?:sans|avec omission d))",
                            re.I)
VARIANT = re.compile(r"^variante (?:orthographique |graphique )?(?:\(incorrecte\) )?d(?:e|['’])", re.I)
INCORRECT = re.compile(r"\b(?:incorrect|erron|fautiv)", re.I)
VARIANT_TEMPLATES = {"variante de", "variante ortho de", "variante orthographique de", "variante typographique de"}
INFLECTION = re.compile(r"^(?:(?:ancien |ancienne )?(?:masculin|féminin|pluriel|singulier)\b|participe |"
                        r"(?:première|deuxième|troisième) personne|un des pluriels|forme (?:du )?(?:masculin|féminin|"
                        r"pluriel|conjuguée))", re.I)
LETTER = re.compile(r"^(?:(?:\w+ )?lettre\b|lettre latine|nom de la lettre)", re.I)
ELISION = re.compile(r"^élision\b", re.I)
TARGET = re.compile(r"\[\[([^\[\]|#]+)(?:#[^\[\]|]*)?(?:\|[^\[\]]*)?\]\]")


def _plain(text):
    t = re.sub(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]", r"\1", text)
    t = re.sub(r"\{\{[^{}]*\}\}", "", t)
    return t.replace("'''", "").replace("''", "").strip()


def _letters_only(s):
    return strip_diacritics(s).replace("œ", "oe").replace("æ", "ae").replace("'", "").replace("-", "").lower()


def classify_fr(title, section):
    """Page kinds of a French section of the French dump (§11.2): form, pointer (target), letter, elision,
    inflected (only inflection senses), phrase (a definition points to a phrase with the title's letters), proper (only
    proper-noun senses)."""
    out = {"form": False, "pointer": None, "letter": False, "elision": False, "inflected": False, "phrase": False,
           "proper": False}
    own = inflections = letters = elisions = 0
    pointers = []
    pos = None
    pos_kinds = set()
    for line in section.split("\n"):
        hm = POS_HEADING.match(line)
        if hm:
            name = hm.group(1).strip().lower()
            pos = None if name in NON_POS else name
            if pos is not None:
                pos_kinds.add(pos)
            flexion = "flexion" in hm.group(2)
            continue
        if line.startswith("==="):
            pos = None
            continue
        if pos is None:
            continue
        dm = DEF.match(line)
        if not dm:
            continue
        text = dm.group(1).strip()
        labels = []
        while True:
            m = LABEL.match(text)
            if not m:
                break
            labels.append(m.group(1).strip().lower())
            text = text[m.end():]
        tm = TARGET.search(text)
        target = tm.group(1).strip().replace("’", "'") if tm else None
        tpl = re.match(r"^\{\{\s*([^|{}]+?)\s*\|\s*([^|{}]+)", dm.group(1).strip())
        plain = _plain(text)
        if target and " " in target and _letters_only(target).replace(" ", "") == _letters_only(title):
            out["phrase"] = True
        if pos == "variante typographique":
            pointers.append(target or "")
            continue
        if pos == "lettre" or (LETTER.match(plain) and len(plain) < 120 and not tpl):
            letters += 1
            continue
        if ELISION.match(plain):
            elisions += 1
            continue
        if any(lb in VARIANT_TEMPLATES for lb in labels) or (tpl and tpl.group(1).strip().lower() in VARIANT_TEMPLATES):
            vt = tpl.group(2).strip() if tpl and tpl.group(1).strip().lower() in VARIANT_TEMPLATES else target
            if any(lb in OLD_LABELS for lb in labels) and vt:
                pointers.append(vt)
                continue
            own += 1
            continue
        if POINTER_ALWAYS.match(plain) and target:
            pointers.append(target)
            continue
        if VARIANT.match(plain) and target and (INCORRECT.search(plain) or any(lb in OLD_LABELS for lb in labels)):
            pointers.append(target)
            continue
        if flexion or INFLECTION.match(plain):
            inflections += 1
            continue
        own += 1
    if own + inflections == 0 and (letters or elisions or pointers):
        if pointers and not letters and not elisions:
            out["pointer"] = pointers[0]
        out["letter"] = letters > 0 and not pointers
        out["elision"] = elisions > 0
    else:
        out["form"] = True
        out["inflected"] = inflections > 0 and own == 0
    out["proper"] = bool(pos_kinds) and pos_kinds <= {"nom propre", "prénom", "nom de famille", "nom propre|flexion"}
    return out


# English dump, French sections ---------------------------------------------------------------------------------

EN_SKIP = re.compile(r"^\{\{\s*(?:lb|lbl|label|q|qual|qualifier|i|gloss|gl|senseid|sid|tcl|n-g|ng|tlb)\s*\|[^{}]*\}\}\s*")
EN_TEMPLATE = re.compile(r"^\{\{\s*([^|{}]+?)\s*((?:\|[^{}]*)?)\}\}")
EN_POINTER = {"misspelling of", "obsolete spelling of", "obsolete form of", "obs sp", "obs form", "archaic spelling of",
              "archaic form of", "nonstandard spelling of", "nonstandard form of", "eye dialect of",
              "pronunciation spelling of", "deliberate misspelling of", "superseded spelling of", "dated spelling of"}
EN_ALT = {"alternative form of", "alt form", "alt sp", "alternative spelling of", "altform", "alt form of", "alt sp of",
          "alt of", "alternative case form of", "alt case", "alt case form"}


def classify_en(title, section):
    """Page kinds of a French section of the English dump (form, pointer, inflected), as [classify_fr]."""
    out = {"form": False, "pointer": None, "letter": False, "elision": False, "inflected": False, "phrase": False,
           "proper": False}
    own = inflections = 0
    pointers = []
    defs = 0
    for line in section.split("\n"):
        dm = DEF.match(line)
        if not dm:
            continue
        defs += 1
        text = dm.group(1).strip()
        while True:
            m = EN_SKIP.match(text)
            if not m:
                break
            text = text[m.end():]
        t = EN_TEMPLATE.match(text)
        name = t.group(1).strip() if t else None
        args = [a.strip() for a in t.group(2).strip("|").split("|") if "=" not in a] if t and t.group(2) else []
        if args and args[0] == "fr":
            args = args[1:]
        target = args[0].replace("’", "'") if args else None
        if name in EN_POINTER and target:
            pointers.append(target)
            continue
        if name in EN_ALT and target and target != title and _letters_only(target) == _letters_only(title):
            pointers.append(target)
            continue
        if name and (name.endswith(" of") or name.startswith("fr-")) and any(
                w in name for w in ("plural", "feminine", "masculine", "participle", "inflection", "form of", "verb form")):
            inflections += 1
            continue
        own += 1
    if defs and own + inflections == 0 and pointers:
        out["pointer"] = pointers[0]
    else:
        out["form"] = True
        out["inflected"] = inflections > 0 and own == 0
    return out


# ---------------------------------------------------------------------------------------------- English dump (optional)

EN_SECTION = re.compile(r"^==\s*French\s*==\s*$")


def _en_extract(text):
    if "==French==" not in text.replace(" ", ""):
        return None
    out = None
    for line in text.split("\n"):
        if line.startswith("==") and not line.startswith("==="):
            if out is not None:
                break
            if EN_SECTION.match(line.rstrip()):
                out = []
            continue
        if out is not None and (line.startswith("=") or (line.startswith("#") and not line.startswith(("#*", "#:")))
                                or line.startswith("{{")):
            out.append(line[:400])
    return None if out is None else "\n".join(out)


def extract_en(dump_path, out_path):
    """The French sections of the English dump (§11.2 point 3), in the format of [extract] (no language codes)."""
    tmp = out_path + ".tmp"
    sections = {}
    for title, ns, text in _pages(dump_path, "enwiktionary"):
        if ns != "0" or title is None:
            continue
        title = title.replace("’", "'")
        if not WORD_TITLE.match(title):
            continue
        sec = _en_extract(text)
        if sec is not None:
            sections[title] = sec
    with gzip.open(tmp, "wt", encoding="utf-8") as out:
        for t in sorted(sections):
            esc = sections[t].replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
            out.write(f"S\t{t}\t{esc}\n")
    os.replace(tmp, out_path)
