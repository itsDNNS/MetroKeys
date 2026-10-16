"""Names of the 0.7.0 builds (spec/05b-dictionaries.md §12.5): a key is a name when every source that makes it valid
describes it as a proper noun only. Names take the lower of their two frequencies (§12.2 item 4) and stay valid.

The rule is the one the evaluation's generator applies (tools/dicteval/languages.py, Language._names), computed here
from the same build passes:

- a key with a Wiktionary section of the language whose part of speech is known is a name when all those sections
  are proper-noun sections (German: given names, surnames and place names of the §3.7 pass; French: the build's own
  proper-noun flag; Spanish, Portuguese, Italian: the part-of-speech headings of their sections) and it is a
  Wiktionary form;
- otherwise a key that is no Wiktionary or abbreviation form is a name when the spelling dictionary accepts it only
  with a capital (05b §12.5: for German also "has no German Wiktionary section", which holds for every such key).
"""

import os
import sys

from common import jlower

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "dicteval"))

import wiktionary_pos  # noqa: E402

# 05b §12.5 German: the parts of speech of given names, surnames and place names
DE_NAME_POS = {"Vorname", "Nachname", "Toponym"}


class NameRule:
    def __init__(self, proper_titles, wiktionary_forms, abbreviation_keys, spelling):
        """[proper_titles]: key -> True if all its sections with a part of speech are proper-noun sections;
        [wiktionary_forms]: the keys of the Wiktionary pass (with the abbreviation forms); [spelling]: SpellingIndex."""
        self.proper = proper_titles
        self.forms = wiktionary_forms
        self.abbreviations = abbreviation_keys
        self.spelling = spelling

    def is_name(self, key):
        if key in self.proper:
            return self.proper[key] and key in self.forms
        if key in self.forms or key in self.abbreviations:
            return False
        return self.spelling.capital_only(key)

    def source(self, key):
        return "wiktionary" if key in self.proper else "spelling"


def german(pos_by_title):
    kinds = {}
    for title, parts in pos_by_title.items():
        if not parts:
            continue
        k = jlower(title)
        kinds[k] = kinds.get(k, True) and set(parts) <= DE_NAME_POS
    return kinds


def french(fr_kinds):
    kinds = {}
    for title, k in fr_kinds.items():
        if not k.get("form"):
            continue
        key = jlower(title)
        kinds[key] = kinds.get(key, True) and bool(k.get("proper"))
    return kinds


def from_headings(sections):
    """[sections]: list of (dump, {title: headings}); "en" is the English edition."""
    kinds = {}
    for dump, heads in sections:
        edition = "en" if dump == "en" else dump
        for title, hs in heads.items():
            kind = wiktionary_pos.section_kind(hs, edition)
            if kind is None:
                continue
            key = jlower(title)
            kinds[key] = kinds.get(key, True) and kind == "proper"
    return kinds


def headings_of(sections_by_title):
    return {t: wiktionary_pos.headings(s) for t, s in sections_by_title.items()}


def spanish_headings(cache, dumps):
    return [(d, wiktionary_pos.load_headings(wiktionary_pos.cache_path(cache, d))) for d in dumps]


def spanish_cache_ready(cache, dumps):
    return all(os.path.exists(wiktionary_pos.cache_path(cache, d)) for d in dumps)
