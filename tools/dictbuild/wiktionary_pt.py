"""Wiktionary passes of the Portuguese (Brazil) build (05b-dictionaries §9.2).

Stage 1 (slow, cached): stream a dump and keep, per single-word page title, the Portuguese section text
  - Portuguese dump (pt.wiktionary.org): the "={{-pt-}}=" section plus the page head before the first language
    section (where the page notices "{{Anterior ao Acordo de 1990|…}}" and "{{grafiaPtpt|…}}" usually stand);
  - English dump (en.wiktionary.org): the "==Portuguese==" section;
  and every single-word (letters only) page title of all languages (foreign-word detection).
Stage 2 (fast): classify the cached sections into the page kinds of §9.2:
  F forms, V pointer pages (with target), R pre-reform pages (with the new spelling and, for the English dump, the
  countries), E European-only pages (with the Brazilian counterpart, if named), I inflected-only pages, P fused-phrase
  pages.

The dumps are untrusted data: they are only parsed as text, templates are read as text and never expanded.
"""

import gzip
import os
import re
import unicodedata

from wiktionary_es import _pages

SINGLE_WORD = re.compile(r"^[^\W\d_]+$")

PT_SECTION = re.compile(r"^=\s*\{\{\s*-pt-\s*\}\}\s*=\s*$")
PT_LANG_HEADING = re.compile(r"^=\s*\{\{\s*-[^{}]+-\s*\}\}\s*=\s*$")
EN_SECTION = re.compile(r"^==\s*Portuguese\s*==\s*$")


def strip_diacritics(s):
    """Removes every combining mark (acute, grave, circumflex, tilde, diaeresis, cedilla …) after NFD."""
    d = unicodedata.normalize("NFD", s)
    return unicodedata.normalize("NFC", "".join(c for c in d if not unicodedata.combining(c)))


# ---------------------------------------------------------------------------------------------- stage 1

def _pt_extract(text):
    head = []
    section = None
    for line in text.split("\n"):
        s = line.rstrip()
        if PT_LANG_HEADING.match(s):
            if section is not None:
                break
            if PT_SECTION.match(s):
                section = []
            continue
        if section is not None:
            section.append(line)
        else:
            head.append(line)
    if section is None:
        return None
    return "\n".join(head).strip() + "\n<<<SECTION>>>\n" + "\n".join(section)


def _en_extract(text):
    if "==Portuguese==" not in text.replace(" ", ""):
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
    fn = _pt_extract if kind == "pt" else _en_extract
    tmp = out_path + ".tmp"
    titles = set()
    sections = {}
    for title, ns, text in _pages(dump_path, kind + "wiktionary"):
        if ns != "0" or title is None or not SINGLE_WORD.match(title):
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


def load_extract(path):
    titles = set()
    sections = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            kind, _, rest = line.rstrip("\n").partition("\t")
            if kind == "T":
                titles.add(rest)
            else:
                title, _, esc = rest.partition("\t")
                sections[title] = _unescape(esc)
    return titles, sections


def _unescape(s):
    out = []
    i = 0
    n = len(s)
    while i < n:
        c = s[i]
        if c == "\\" and i + 1 < n:
            nx = s[i + 1]
            out.append("\n" if nx == "n" else "\t" if nx == "t" else nx)
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


# ---------------------------------------------------------------------------------------------- stage 2

DEF_LINE = re.compile(r"^#(?![*:#])\s*(.*)$")
TEMPLATE_AT = re.compile(r"^\{\{\s*([^|{}]+?)\s*((?:\|[^{}]*)?)\}\}\s*")
LINK = re.compile(r"\[\[([^\[\]|#]*)(?:#[^\[\]|]*)?(?:\|([^\[\]]*))?\]\]")
BOLD_LINK = re.compile(r"'''\s*\[\[([^\[\]|#]*)")


def _args(rest):
    return [a.strip() for a in rest.strip().strip("|").split("|")] if rest and rest.strip() else []


def _plain_args(rest):
    return [a for a in _args(rest) if "=" not in a]


def _diacritics_only(a, b):
    """True if a and b differ, but only by diacritics (case compared exactly)."""
    return a != b and strip_diacritics(a) == strip_diacritics(b)


def _case_only(a, b):
    return a != b and a.lower() == b.lower()


# Portuguese dump -------------------------------------------------------------------------------

PT_LABELS = {"escopo", "escopo2", "cont", "gramática", "g", "m", "f", "mf", "escopoClasseMorfo", "escopoGrafiaPort",
             "escopoGrafiaBrasil", "escopoGrafiaUsadaAte1911", "datação", "grifar", "c2g", "pr", "p", "tônico", "átono"}
PT_OBSOLETE = ("arcaísmo", "arcaico", "obsoleto", "antigo", "desusado", "grafia antiga", "grafia arcaica")
PT_EURO_LABELS = ("grafia lusitana", "grafia portuguesa", "portugal", "lusitanismo", "português europeu",
                  "português de portugal")
PT_NOT_EURO_TOPICS = ("geografia", "gentílico", "topônimo", "topónimo", "toponímia", "município", "freguesia",
                      "concelho", "história", "antropônimo", "antropónimo")
PT_POINTER = re.compile(r"^(?:vide|ver|veja|o mesmo que|mesmo que|grafia (?:antiga|arcaica|obsoleta|alternativa|"
                        r"errada|incorreta|popular)(?: de)?|forma (?:antiga|arcaica|obsoleta|alternativa|"
                        r"popular|errada|incorreta)(?: de)?|erro (?:ortográfico|comum)(?: de| por)?|variante(?: "
                        r"(?:gráfica|ortográfica|de))?(?: de)?|o mesmo que o)\s*[:,]?\s*", re.I)
PT_INFLECTION = re.compile(r"(?:\bdo verbo\b|\bpessoa do\b|^plural de\b|^feminino\b|^masculino\b|^gerúndio\b|"
                           r"^particípio\b|^infinitivo\b|\bplural de\b|^diminutivo de\b|^aumentativo de\b|"
                           r"^superlativo\b|^forma (?:feminina|plural|masculina)\b)", re.I)


def _pt_definition(line):
    """(labels, text) of a Portuguese-dump definition line: leading label templates removed."""
    text = line.strip()
    labels = []
    while True:
        m = TEMPLATE_AT.match(text)
        if not m or m.group(1) not in PT_LABELS:
            break
        labels.append((m.group(1), _args(m.group(2))))
        text = text[m.end():].strip()
    text = re.sub(r"^''+[^']*''+\s*", "", text) if text.startswith("''") and not text.startswith("'''") else text
    return labels, text


def _first_link(text):
    m = LINK.search(text)
    return (m.group(1).strip(), m.start()) if m else (None, -1)


def _is_euro_label(labels):
    for name, args in labels:
        low = [a.lower() for a in args if a.lower() != "pt"]
        if name == "escopoGrafiaPort":
            return True
        if name not in ("escopo", "escopo2"):
            continue
        joined = ", ".join(low)
        if "brasil" in joined:
            return False
        if any(t in joined for t in PT_NOT_EURO_TOPICS):
            continue
        if any(e == a or a.startswith(e) for a in low for e in PT_EURO_LABELS) or any(
                e in joined for e in ("grafia lusitana", "grafia portuguesa")):
            return True
    return False


def _is_obsolete_label(labels):
    for name, args in labels:
        if name == "escopoGrafiaUsadaAte1911":
            return True
        if name in ("escopo", "escopo2") and any(o in a.lower() for a in args for o in PT_OBSOLETE):
            return True
    return False


def classify_pt(title, section):
    """Page kinds of a Portuguese-dump page (§9.2): dict with the keys
    form (bool), pointer (target or None), prereform (new spelling or None), euro (bool), euro_target (or None),
    inflected (bool), phrase (bool), prereform_variants (titles listed as pre-reform variants of this page)."""
    head, _, body = section.partition("<<<SECTION>>>\n")
    whole = head + "\n" + body
    out = {"form": False, "pointer": None, "prereform": None, "euro": False, "euro_target": None,
           "inflected": False, "phrase": False, "prereform_variants": [], "old1911": False}
    for m in re.finditer(r"\{\{\s*Anterior ao Acordo de 1990\s*\|([^{}|]+)", whole):
        out["prereform"] = m.group(1).strip()
        break
    for m in re.finditer(r"\{\{\s*grafiaPtpt\s*\|([^{}|]+)", whole):
        out["euro"] = True
        out["euro_target"] = m.group(1).strip()
        break
    if re.search(r"\{\{\s*Anterior à Reforma Ortográfica de 1911\b", whole):
        out["old1911"] = True
    for m in re.finditer(r"\{\{\s*escopo2\s*\|\s*Anterior ao AO 1990\s*\}\}\s*\[\[([^\[\]|#]+)", body):
        out["prereform_variants"].append(m.group(1).strip())
    defs = []
    for line in body.split("\n"):
        dm = DEF_LINE.match(line)
        if dm:
            defs.append(_pt_definition(dm.group(1)))
    if not defs:
        out["form"] = True  # a Portuguese section without definition lines still describes the word
        return out
    pointer_targets = []
    own = 0
    euro_all = True
    inflections = 0
    plain = title.lower()
    for labels, text in defs:
        if not _is_euro_label(labels):
            euro_all = False
        inflection = bool(PT_INFLECTION.search(re.sub(r"\[\[([^\]|]*\|)?([^\]]*)\]\]", r"\2", text).replace("'''", "")))
        pm = PT_POINTER.match(text)
        target, pos = _first_link(text)
        if target and " " in target and target.replace(" ", "").lower() == plain:
            out["phrase"] = True
        rest = LINK.sub("", text).strip(" .;,")
        is_pointer = False
        if target and (pm or pos == 0) and not rest:
            if pm or _diacritics_only(title, target) or _case_only(title, target):
                if (_diacritics_only(title, target) or _case_only(title, target) or _is_obsolete_label(labels)
                        or out["old1911"] or (pm and re.match(r"^(?:grafia|forma|erro|variante)", text, re.I))):
                    is_pointer = True
                elif pm and re.match(r"^vide\b", text, re.I) and out["prereform"]:
                    is_pointer = True
        if is_pointer:
            pointer_targets.append(target)
        elif inflection:
            inflections += 1
            own += 1
        else:
            own += 1
    if own == 0 and pointer_targets:
        out["pointer"] = pointer_targets[0]
    else:
        out["form"] = True
    out["euro"] = out["euro"] or euro_all
    # inflected-only: inflection pointers (and spelling pointers), no definition of its own
    out["inflected"] = inflections > 0 and own == inflections
    return out


# English dump ----------------------------------------------------------------------------------

EN_HEAD_LABEL = re.compile(r"\{\{\s*tlb\s*\|\s*pt\s*\|([^{}]*)\}\}")
EN_SENSE_LABEL = re.compile(r"^\{\{\s*(?:lb|lbl|label)\s*\|\s*pt\s*\|([^{}]*)\}\}\s*")
EN_SKIP = re.compile(r"^\{\{\s*(?:q|qual|qualifier|i|gloss|gl|senseid|sid|tcl)\s*\|[^{}]*\}\}\s*")
EN_ALT = re.compile(r"\{\{\s*(?:alt|alter|alt form)\s*\|\s*pt\s*\|([^{}]*)\}\}")
EN_POINTER = {"obsolete spelling of", "obsolete form of", "obs sp", "obs form", "misspelling of", "nonstandard spelling of",
              "nonstandard form of", "archaic spelling of", "archaic form of", "pronunciation spelling of",
              "deliberate misspelling of", "rare spelling of", "dated spelling of", "dated form of",
              "superseded spelling of", "standard spelling of", "standard form of", "pt-1931", "eye dialect of"}
EN_ALT_DEF = {"alternative spelling of", "alternative form of", "alt form of", "alt sp of", "alt form", "alt sp"}
EN_INFLECTION = {"pt-verb form of", "plural of", "adj form of", "feminine plural of", "feminine singular of",
                 "masculine plural of", "past participle of", "gerund of", "inflection of", "noun form of",
                 "pt-apocopic-verb", "verb form of", "feminine of", "masculine of"}
EN_EURO = ("portugal", "european portuguese", "european portuguese spelling", "european portuguese form",
           "european form", "european spelling")


def _en_labels(raw):
    return [a.strip().lower() for a in raw.split("|") if a.strip() and "=" not in a]


def _euro(labels):
    if any("brazil" in a for a in labels):
        return False
    return any(a in EN_EURO for a in labels)


def classify_en(title, section):
    """Page kinds of an English-dump Portuguese section (§9.2), in the same dict shape as [classify_pt]."""
    out = {"form": False, "pointer": None, "prereform": None, "prereform_countries": None, "euro": False,
           "euro_target": None, "inflected": False, "phrase": False, "prereform_variants": [], "old1911": False}
    head_labels = []
    brazil_alts = []
    defs = []
    for line in section.split("\n"):
        if line.startswith("="):
            head_labels = []
            continue
        for m in EN_ALT.finditer(line):
            parts = m.group(1).split("||")
            target = parts[0].split("|")[0].strip()
            qual = parts[1].lower() if len(parts) > 1 else ""
            if target and "brazil" in qual:
                brazil_alts.append(target)
        if line.startswith("{{") and not line.startswith("{{lb") and EN_HEAD_LABEL.search(line):
            head_labels = []
            for m in EN_HEAD_LABEL.finditer(line):
                head_labels += _en_labels(m.group(1))
            continue
        if line.startswith("{{tlb"):
            head_labels = []
            for m in EN_HEAD_LABEL.finditer(line):
                head_labels += _en_labels(m.group(1))
            continue
        dm = DEF_LINE.match(line)
        if not dm:
            continue
        text = dm.group(1).strip()
        labels = list(head_labels)
        while True:
            m = EN_SENSE_LABEL.match(text)
            if m:
                labels += _en_labels(m.group(1))
                text = text[m.end():]
                continue
            m = EN_SKIP.match(text)
            if m:
                text = text[m.end():]
                continue
            break
        defs.append((labels, text))
    if not defs:
        out["form"] = True
        return out
    own = 0
    pointers = []
    euro_all = True
    inflections = 0
    lemmas = 0
    plain = title.lower()
    for labels, text in defs:
        t = TEMPLATE_AT.match(text)
        name = t.group(1).strip() if t else None
        args = _args(t.group(2)) if t else []
        plain_args = [a for a in args if "=" not in a]
        named = dict(a.split("=", 1) for a in args if "=" in a)
        if plain_args and plain_args[0] == "pt" and name != "pt-pre-reform":
            plain_args = plain_args[1:]
        target = plain_args[0] if plain_args else None
        euro = _euro(labels)
        if name in ("standard spelling of", "standard form of") and "european" in named.get("from", "").lower():
            euro = True
            out["euro_target"] = out["euro_target"] or target
        if not euro:
            euro_all = False
        if name in EN_INFLECTION:
            inflections += 1
        elif not (name == "pt-pre-reform" or (name in EN_POINTER and target) or (name in EN_ALT_DEF and target and (
                _diacritics_only(title, target) or _case_only(title, target)))):
            lemmas += 1
        if name == "pt-pre-reform":
            # br= / pt= give the reform after which the spelling ceased to be standard in each country (90 = the 1990
            # agreement, 43/71 = Brazil 1943/1971, 11/45/73 = Portugal); only spellings standard until 1990 are
            # pre-reform pages of §9.2 item 4, the older ones are obsolete spellings (pointer pages)
            years = (named.get("br", ""), named.get("pt", ""))
            if target and any(y in ("90", "1990") for y in years):
                out["prereform"] = out["prereform"] or target
                out["prereform_countries"] = out["prereform_countries"] or years
            pointers.append(target)
            continue
        if target and " " in target and target.replace(" ", "").lower() == plain and (name in EN_POINTER or name in EN_ALT_DEF):
            out["phrase"] = True
        if name in EN_POINTER and target and not (euro and name in ("standard spelling of", "standard form of")):
            pointers.append(target)
            continue
        if name in EN_ALT_DEF and target and (_diacritics_only(title, target) or _case_only(title, target)):
            pointers.append(target)
            continue
        own += 1
    if own == 0 and pointers and not out["prereform"]:
        out["pointer"] = pointers[0]
    elif own > 0 or not out["prereform"]:
        out["form"] = True
    if not out["prereform"] and own == 0 and not pointers:
        out["form"] = True
    if euro_all and defs:
        out["euro"] = True
    inflected_all = inflections > 0 and lemmas == 0
    if not out["euro"] and inflected_all and brazil_alts and not any("brazil" in a for lab, _ in defs for a in lab):
        # extension: an inflected form with an alternative form labelled Brazil that differs only by diacritics
        # ("falámos" → "falamos")
        alt = next((a for a in brazil_alts if _diacritics_only(title, a)), None)
        if alt:
            out["euro"] = True
            out["euro_target"] = alt
    if out["euro"] and not out["euro_target"] and brazil_alts:
        out["euro_target"] = brazil_alts[0]
    out["inflected"] = inflected_all
    return out
