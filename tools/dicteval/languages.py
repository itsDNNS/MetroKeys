"""Per-language inputs of the evaluation test sets (spec/05c-dictionary-evaluation.md §2): the forms of the
Wiktionary pass, the spelling dictionary, names, case forms, abbreviation keys and the held-out sentences, all computed
from the pinned inputs through the build's own passes and page-kind rules (the build pipeline runs in dry mode and
writes nothing; the built word lists are never read).
"""

import bz2
import gzip
import os
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(TOOLS, "dictbuild"))
sys.path.insert(0, TOOLS)

import build_dictionaries as bd  # noqa: E402
import build_es  # noqa: E402
import build_fr  # noqa: E402
import build_it  # noqa: E402
import build_pt  # noqa: E402
import build_de  # noqa: E402
import wiktionary_de  # noqa: E402
import wiktionary_it  # noqa: E402
import wiktionary_pt  # noqa: E402
from common import capitalize_first, jlower  # noqa: E402

import spelling  # noqa: E402
import wiktionary_pos  # noqa: E402
import wordfreq  # noqa: E402

WORDFREQ = {"de-DE": "de", "en-US": "en", "es-ES": "es", "pt-BR": "pt", "it-IT": "it", "fr-FR": "fr"}
LAYOUT = {"de-DE": "de", "en-US": "en", "es-ES": "es", "pt-BR": "pt", "it-IT": "it", "fr-FR": "fr"}
TATOEBA = {"de-DE": ("deu_sentences.tsv.bz2", "deu"), "en-US": ("eng_sentences.tsv.bz2", "eng"),
           "es-ES": ("spa_sentences.tsv.bz2", "spa"), "pt-BR": ("por_sentences.tsv.bz2", "por"),
           "it-IT": ("ita_sentences.tsv.bz2", "ita"), "fr-FR": ("fra_sentences.tsv.bz2", "fra")}
CV = {"de-DE": "de", "en-US": "en", "es-ES": "es", "pt-BR": "pt", "it-IT": "it", "fr-FR": "fr"}
# 05 §1.1 ELISION_PREFIXES
PREFIXES = {"pt-BR": ["d'"], "it-IT": list(build_it.PREFIXES), "fr-FR": list(build_fr.PREFIXES)}
# 05b §5 step 2: sentence ends and the openers that keep the sentence start
SENTENCE_END = {"es-ES": set(".!?:¿¡")}
OPENERS = set("„“\"«»‚‘›‹([")
# 05b §12.6
EVAL_HOLDOUT_MOD = 20
EVAL_HOLDOUT_REM = 7
# 05b §12.5 German: the parts of speech of given names, surnames and place names
DE_NAME_POS = {"Vorname", "Nachname", "Toponym"}
WORD_MAX_LEN = 27


def strip_diacritics(text):
    """05c E5: every diacritic removed (canonical decomposition, combining marks dropped), ß → ss, œ → oe, æ → ae
    (case kept)."""
    out = []
    for ch in text:
        if ch == "ß":
            out.append("ss")
        elif ch in "œŒæÆ":
            out.append({"œ": "oe", "Œ": "Oe", "æ": "ae", "Æ": "Ae"}[ch])
        else:
            d = unicodedata.normalize("NFD", ch)
            out.append("".join(c for c in d if not unicodedata.combining(c)))
    return unicodedata.normalize("NFC", "".join(out))


class Language:
    """Inputs of one language. [src]: build-data/src, [cache]: build-data/cache."""

    def __init__(self, tag):
        self.tag = tag
        self.src = bd.SRC
        self.cache = bd.CACHE
        print(f"{tag}: build pipeline (dry run)", file=sys.stderr)
        self.ctx = bd.PROFILES[tag]({}, dry=True)
        self.builder = self.ctx["builder"]
        self.stock = set(self.ctx.get("stock") or ())
        self.prefixes = PREFIXES.get(tag, [])
        self.sentence_end = SENTENCE_END.get(tag, set(".!?:"))
        self.freq = wordfreq.load(os.path.join(self.src, "wordfreq", f"large_{WORDFREQ[tag]}.msgpack.gz"))
        self.spelling = spelling.load(self.src, tag)
        self.layout_path = os.path.join(bd.ROOT, "spec", "data", f"layouts-{LAYOUT[tag]}.json")
        self.neighbours = build_de.neighbour_keys(self.layout_path)
        self.apostrophe_words = {}
        self.compounds = {}
        self.contractions = set()
        self._init_language()

    # ------------------------------------------------------------------ language specifics

    def _init_language(self):
        b = self.builder
        tag = self.tag
        if tag == "en-US":
            self.alphabet = None
            # SCOWL forms: key -> {form: (size, category)} of the US lists up to size 70
            self.scowl = b.main_forms
            self.abbreviation_keys = {k for k, fs in self.scowl.items() if fs and
                                      all(c == "abbreviations" for _, c in fs.values())}
            self.contractions = {f for k, fs in self.scowl.items() for f, (s, c) in fs.items()
                                 if c == "contractions" and s <= 50}
            self.names = {k for k, fs in self.scowl.items() if fs and all(c == "proper-names" for _, c in fs.values())}
            return
        module = {"de-DE": build_de, "es-ES": build_es, "pt-BR": build_pt, "it-IT": build_it, "fr-FR": build_fr}[tag]
        self.alphabet = set(module.ALLOWED)
        self.abbreviation_keys = set(b.abbreviation_keys)
        if tag == "fr-FR":
            self.apostrophe_words = dict(b.apostrophe_words)          # key -> form ("aujourd'hui")
            self.compounds = {jlower(f): f for f in b.compound_forms}  # "est-ce" -> "est-ce"
        self.names = self._names()

    def wiktionary_form(self, key):
        """A form of the language's Wiktionary pass (with the build's page-kind rules), not only by the abbreviation
        rule or a subtitle rule."""
        b = self.builder
        if self.tag == "en-US":
            return False
        return key in b.forms_by_key and key not in self.abbreviation_keys

    def spelling_casings(self, key):
        if self.tag == "en-US":
            fs = self.scowl.get(key, {})
            return (key in fs, capitalize_first(key) in fs, False)
        return self.spelling.casings(key)

    def letters_ok(self, key):
        if not 2 <= len(key) <= WORD_MAX_LEN:
            return False
        if not all(ch.isalpha() for ch in key):
            return False
        if self.alphabet is not None:
            return all(ch in self.alphabet for ch in key)
        return all(ch.isalpha() and (ch.isascii() or 0xC0 <= ord(ch) <= 0x17F) for ch in key)

    def trusted_candidate(self, key):
        """05c §2.1 without the frequency condition."""
        if not self.letters_ok(key) or key in self.names or key in self.abbreviation_keys:
            return False
        if self.tag == "en-US":
            fs = self.scowl.get(key, {})
            return any(s <= 50 and c in ("words", "upper") for s, c in fs.values())
        return self.wiktionary_form(key) and any(self.spelling_casings(key))

    def is_form(self, key):
        """05b §12.1: a form of the Wiktionary pass, of the abbreviation rule or of the spelling dictionary."""
        if self.tag == "en-US":
            return key in self.scowl or key in self.builder.large_forms
        return key in self.builder.forms_by_key or key in self.abbreviation_keys or any(self.spelling_casings(key))

    def case_forms(self, key):
        """The build's case decision for [key] (05b §3.4 and its pendants), most frequent form first."""
        b = self.builder
        if self.tag == "en-US":
            fs = self.scowl.get(key)
            if not fs:
                return [key]
            return [f for f, _ in b._scowl_case(key, list(fs), b.count(key))]
        dec = b.case_decision(key)
        if dec:
            forms = []
            # the decision lists its forms by share, the most frequent first (05b §3.4)
            for f, _ in dec:
                if jlower(f) == key and f not in forms:
                    forms.append(f)
            if forms:
                return forms
        cas = self.spelling_casings(key)
        if not cas[0] and cas[1]:
            return [capitalize_first(key)]
        return [key]

    def subtitle_count(self, key):
        return self.builder.count(key)

    # ------------------------------------------------------------------ names (05b §12.5)

    def _names(self):
        tag = self.tag
        b = self.builder
        candidates = set(b.counts) | {wordfreq.fold(w) for w in self.freq}
        proper_titles = None   # key -> True (all sections proper), False (some other part of speech)
        if tag == "de-DE":
            pos = wiktionary_de.load_pos(os.path.join(self.cache, "dewiktionary-20261001-pos.tsv.gz"))
            kinds = {}
            for title, parts in pos.items():
                if not parts:
                    continue
                k = jlower(title)
                kinds[k] = kinds.get(k, True) and set(parts) <= DE_NAME_POS
            proper_titles = kinds
        elif tag == "fr-FR":
            kinds = {}
            for title, k in b.fr_kinds.items():
                if not k.get("form"):
                    continue
                key = jlower(title)
                kinds[key] = kinds.get(key, True) and bool(k.get("proper"))
            proper_titles = kinds
        else:
            sections = []
            if tag == "es-ES":
                sections.append(("es", wiktionary_pos.load_headings(wiktionary_pos.cache_path(self.cache, "es"))))
                if self.ctx.get("englishDump"):
                    sections.append(("en", wiktionary_pos.load_headings(wiktionary_pos.cache_path(self.cache, "en"))))
            else:
                lang = "pt" if tag == "pt-BR" else "it"
                loader = wiktionary_pt.load_extract if tag == "pt-BR" else wiktionary_it.load_extract
                for dump in (lang, "en"):
                    _, secs = loader(os.path.join(self.cache, f"{dump}wiktionary-20261001-{lang}.tsv.gz"))
                    sections.append((dump, {t: wiktionary_pos.headings(s) for t, s in secs.items()}))
                    del secs
            kinds = {}
            for dump, heads in sections:
                edition = "en" if dump == "en" else dump
                for title, hs in heads.items():
                    kind = wiktionary_pos.section_kind(hs, edition)
                    if kind is None:
                        continue
                    key = jlower(title)
                    kinds[key] = kinds.get(key, True) and kind == "proper"
            proper_titles = kinds
        self.name_source = {}
        names = set()
        for key in candidates:
            if not key or not all(ch.isalpha() for ch in key):
                continue
            if key in proper_titles:
                if proper_titles[key] and self._wiktionary_any(key):
                    names.add(key)
                    self.name_source[key] = "wiktionary"
                continue
            if key in b.forms_by_key or key in self.abbreviation_keys:
                continue
            if self.alphabet is not None and not all(ch in self.alphabet for ch in key):
                continue
            cas = self.spelling.casings(key)
            if not cas[0] and cas[1]:
                names.add(key)
                self.name_source[key] = "spelling"
        return names

    def _wiktionary_any(self, key):
        return key in self.builder.forms_by_key

    # ------------------------------------------------------------------ held-out sentences (05b §12.6)

    def held_out(self):
        """Yields (sentence id, text, tatoeba) of the held-out sentences of the language's pair sources, in source
        order: Tatoeba ids with id mod 20 = 7, Common Voice lines whose 1-based line number mod 20 = 7 (all weights);
        Portuguese after the Brazilian filter of 05b §9.7."""
        name, lang = TATOEBA[self.tag]
        markers = self.ctx.get("markers")
        with bz2.open(os.path.join(self.src, "tatoeba", name), "rt", encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 3 or parts[1] != lang or not parts[0].isdigit():
                    continue
                if int(parts[0]) % EVAL_HOLDOUT_MOD != EVAL_HOLDOUT_REM:
                    continue
                text = parts[2]
                if markers is not None and build_pt.PortugueseBuilder.european_sentence(text, markers):
                    continue
                yield f"tatoeba:{parts[0]}", text, True
        cv_lang = CV[self.tag]
        chosen = bd.CV_FILES[cv_lang]
        d = os.path.join(self.src, "cv", cv_lang)
        for fn in sorted(os.listdir(d)):
            if chosen is not None:
                if fn not in chosen:
                    continue
            elif not fn.endswith(".txt") or fn.startswith("europarl"):
                continue
            with open(os.path.join(d, fn), encoding="utf-8") as f:
                for i, line in enumerate(f, start=1):
                    if i % EVAL_HOLDOUT_MOD != EVAL_HOLDOUT_REM:
                        continue
                    text = line.strip()
                    if not text:
                        continue
                    if markers is not None and build_pt.PortugueseBuilder.european_sentence(text, markers):
                        continue
                    yield f"cv:{fn}:{i}", text, False

    # ------------------------------------------------------------------ tokens (05b §5 step 2)

    def tokens(self, text):
        """Word tokens of a sentence: (start, end, text, kind) with kind "w" (a word), "p" (an elision prefix token
        directly followed by a word), plus None entries for sentence ends. The apostrophe ’ counts as '."""
        t = text.replace("’", "'")
        out = []
        i = 0
        n = len(t)
        prefixes = set(self.prefixes)
        while i < n:
            ch = t[i]
            if ch.isalpha():
                j = i
                while j < n and t[j].isalpha():
                    j += 1
                if self.tag == "en-US":
                    while j + 1 < n and t[j] == "'" and t[j + 1].isalpha():
                        j += 1
                        while j < n and t[j].isalpha():
                            j += 1
                    out.append((i, j, t[i:j], "w"))
                    i = j
                    continue
                if j + 1 < n and t[j] == "'" and t[j + 1].isalpha():
                    run = t[i:j + 1]
                    if jlower(run) in prefixes:
                        out.append((i, j + 1, run, "p"))
                        i = j + 1
                        continue
                    if self.tag == "fr-FR":
                        k = j + 1
                        while k < n and t[k].isalpha():
                            k += 1
                        if jlower(t[i:k]) in self.apostrophe_words:
                            out.append((i, k, t[i:k], "w"))
                            i = k
                            continue
                out.append((i, j, t[i:j], "w"))
                i = j
                continue
            if ch in self.sentence_end:
                out.append(None)
            i += 1
        return out
