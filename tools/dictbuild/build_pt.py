"""Portuguese (Brazil) word list (05b-dictionaries §1.6, §9).

Inputs: the FrequencyWords pt_br list (counts) plus the hyphen tokens of its "ignored" list, the classified pages of
the Portuguese and the English Wiktionary (wiktionary_pt.py) and the Brazilian-filtered sentence sources (case evidence,
phrase counts). Every rule and threshold is recorded in `self.report`, which the driver writes to
tools/dictionary-build.json.
"""

import math
import re

import casing
from attested import AttestedRules
from common import jlower, short_key_dropped
from wiktionary_pt import strip_diacritics

EXCLUDED_LETTERS = set("İıŉſĸªº")
PORTUGUESE_PLAIN = set("abcdefghijklmnopqrstuvwxyzáàâãéêíóôõúüç")
VOWELS = set("aeiouy" "äöü" "àáâãåæèéêëìíîïòóôõøœùúûýÿ")
ONE_LETTER_WORDS = ("a", "à", "e", "é", "o")


def _allowed_key_chars():
    chars = set(PORTUGUESE_PLAIN)
    for cp in range(0xC0, 0x180):
        ch = chr(cp)
        if not ch.isalpha() or ch in EXCLUDED_LETTERS:
            continue
        low = ch.lower()
        if len(low) == 1 and low not in EXCLUDED_LETTERS:
            chars.add(low)
    return chars


ALLOWED = _allowed_key_chars()

# §9.4 step 1: the closed list of clitic pronouns
CLITIC_PRONOUNS = ["me", "te", "se", "nos", "vos", "lhe", "lhes", "o", "a", "os", "as", "lo", "la", "los", "las", "no",
                   "na", "nas"]
CLITIC_SET = set(CLITIC_PRONOUNS)

# thresholds of the first build (05b §9.4, §9.5; open point 9)
PRECLITIC_SHARE = 0.5
PRECLITIC_MIN_COUNT = 20
EURO_RATIO = 5
SPLIT_PART_MIN = 20_000
PHRASE_MIN = 10
PHRASE_RATIO = 10
FOREIGN_MAX = 100_000
KEEP_MIN_COUNT = 40
INFLECTED_MERGE_RATIO = 10
RUNNING_KEEP_MIN = 2
RUNNING_TARGET_MIN = 10
FUSED_MIN_PHRASE = 20
FUSED_MIN_EXPECTED = 5
FUSED_MAX_SHARE = 0.2

TOKEN = re.compile(r"[^\W\d_]+")
RUNNING_BREAK = set(".!?:\"“”«»([")

# §9.7 item 2: forms of "estar" before "a" + infinitive
ESTAR_FORMS = {
    "estar", "estou", "estás", "está", "estamos", "estais", "estão", "estava", "estavas", "estávamos", "estáveis",
    "estavam", "estive", "estiveste", "esteve", "estivemos", "estivestes", "estiveram", "estivera", "estiveras",
    "estivéramos", "estivéreis", "estarei", "estarás", "estará", "estaremos", "estareis", "estarão", "estaria",
    "estarias", "estaríamos", "estaríeis", "estariam", "esteja", "estejas", "estejamos", "estejais", "estejam",
    "estivesse", "estivesses", "estivéssemos", "estivésseis", "estivessem", "estiver", "estiveres", "estivermos",
    "estiverdes", "estiverem", "estando", "estares", "estarmos", "estardes", "estarem",
}
INFINITIVE = re.compile(r"^[^\W\d_]{1,}(?:ar|er|ir|or)$")


def plausible(key):
    if len(key) == 1:
        return key in ONE_LETTER_WORDS
    if not 2 <= len(key) <= 27:
        return False
    if not any(c in VOWELS for c in key):
        return False
    for i in range(len(key) - 2):
        if key[i] == key[i + 1] == key[i + 2]:
            return False
    return True


CEDILLA_NEXT = set("aouãõáóúâôà")


def misplaced_cedilla(key):
    """True if a ç is not followed by a, o or u (with or without a diacritic)."""
    return any(c == "ç" and (i + 1 == len(key) or key[i + 1] not in CEDILLA_NEXT) for i, c in enumerate(key))


def clean_token(tok):
    """§9.1: the key of a main-list token, or None if the token is dropped."""
    if any(c in "'’ªº" or c.isdigit() for c in tok):
        return None
    if "-" in tok:
        return None
    key = jlower(tok)
    if key != tok or not all(c in ALLOWED for c in key):
        return None
    return key


def hyphen_parts(tok):
    """§9.1: the non-empty parts of a hyphen token whose parts are all letters, else None."""
    if "-" not in tok:
        return None
    parts = [p for p in tok.split("-") if p]
    if not parts or not all(TOKEN.fullmatch(p) for p in parts):
        return None
    return parts


def diacritics_only(a, b):
    return a != b and strip_diacritics(a) == strip_diacritics(b)


class SentenceStats:
    """Running-text counts of the sentence sources (§9.3) and the Tatoeba capitalised tokens (§5 step 3)."""

    def __init__(self):
        self.rt_conv = {}
        self.rt_all = {}
        self.capitalised_tatoeba = {}
        self.tatoeba_tokens = 0

    def add_sentence(self, text, conversational, tatoeba=False):
        last_end = None
        for m in TOKEN.finditer(text):
            tok = m.group(0)
            if tatoeba:
                self.tatoeba_tokens += 1
                if tok[0].isupper():
                    self.capitalised_tatoeba[tok] = self.capitalised_tatoeba.get(tok, 0) + 1
            if last_end is not None:
                between = text[last_end:m.start()]
                if not any(c in RUNNING_BREAK for c in between):
                    self.rt_all[tok] = self.rt_all.get(tok, 0) + 1
                    if conversational:
                        self.rt_conv[tok] = self.rt_conv.get(tok, 0) + 1
            last_end = m.end()

    def finish(self):
        self.forms_by_key = {}
        for tok in self.rt_all:
            self.forms_by_key.setdefault(jlower(tok), set()).add(tok)

    def evidence(self, key):
        forms = self.forms_by_key.get(key, ())
        conv = {f: self.rt_conv[f] for f in forms if f in self.rt_conv}
        if sum(conv.values()) >= 10:
            return conv
        return {f: self.rt_all[f] for f in forms if f in self.rt_all}


class PhraseCounter:
    """Weighted counts of fused tokens and of their two-word phrases (adjacent, separated by whitespace only)."""

    def __init__(self, phrases):
        self.phrases = set(phrases)
        self.words = {w for p in self.phrases for w in p.split(" ")}
        self.keys = {p.replace(" ", "") for p in self.phrases}
        self.tokens = {}
        self.pairs = {}
        self.total = 0

    def add(self, text, weight):
        prev = None
        last_end = None
        for m in TOKEN.finditer(text):
            self.total += weight
            tok = jlower(m.group(0))
            if tok in self.keys:
                self.tokens[tok] = self.tokens.get(tok, 0) + weight
            if prev is not None and text[last_end:m.start()].isspace():
                p = prev + " " + tok
                if p in self.phrases:
                    self.pairs[p] = self.pairs.get(p, 0) + weight
            prev = tok if tok in self.words else None
            last_end = m.end()


class PortugueseBuilder(AttestedRules):
    def __init__(self, freq_path, ignored_path, pt_pages, en_pages, abbreviations=None, spelling=None, written=None,
                 english_keys=()):
        """[pt_pages], [en_pages]: (titles, {title: kinds}) of the classified Wiktionary passes
        (wiktionary_pt.classify_pt / classify_en); [en_pages] None = Portuguese dump alone. [spelling], [written],
        [english_keys]: the inputs of the 0.7.0 rules (05b §12)."""
        self.setup_attested(spelling, written, frozenset(english_keys))
        self.report = {}
        self.dumps = [("pt", pt_pages)] + ([("en", en_pages)] if en_pages else [])
        self.titles = set()
        for _, (titles, _) in self.dumps:
            self.titles |= titles
        # foreign-word detection (§9.5 step 6) uses the titles of the Portuguese dump: the English dump has a lower-case
        # title for almost every name of the dubbed films ("adam", "amy", "aaron"), see docs/DATA-SOURCES.md
        self.foreign_titles = {t for t in pt_pages[0] if t == jlower(t)}
        self.en_titles_lower = {t for t in en_pages[0] if t == jlower(t)} if en_pages else set()
        self._read_counts(freq_path, ignored_path)
        self._collect_pages()
        self._abbreviations(abbreviations or {})
        self._preclitic()
        self._reform()
        self._european()
        # 05b §9.4 (0.7.0): the spelling dictionary never keeps a former spelling (step 2) or a European form that
        # step 3 drops; the European-only pages without a subtitle count follow step 3 in build()
        self.spelling_excluded = (set(self.european_spellings) | set(self.european_dropped) | set(self.zero_forms)
                                  | {jlower(t) for _, t, _ in self.prereform})
        self._case_cache = {}
        self.fused = set()
        self.stats = None
        self.merged = {}

    def finish_spellings(self, stats):
        """§9.4 step 4 with the running-text statistics of the filtered sentences, then the header values."""
        self.set_stats(stats)
        # the spelling dictionary is asked for every key that could be kept (05b §12.1); parallel, cached
        self.prefetch_spelling(k for k, c in self.counts.items() if not self.is_form(k) and (c >= 2 or self.written(k) > 0))
        self._merge_accents()
        self.max_count = max(self.counts.values())
        self.scale = 255.0 / math.log(self.max_count)

    # ------------------------------------------------------------------ §9.1, §9.3 counts

    def _read_counts(self, freq_path, ignored_path):
        self.counts = {}
        main_total = 0
        with open(freq_path, encoding="utf-8") as f:
            for line in f:
                parts = line.split()
                if len(parts) != 2:
                    continue
                tok, c = parts[0], int(parts[1])
                main_total += c
                key = clean_token(tok)
                if key is not None:
                    self.counts[key] = self.counts.get(key, 0) + c
        self.hyphen_counts = {}   # key -> count credited from hyphen tokens
        self.preclitic = {}       # key -> count credited from hyphen tokens in which it stands before a clitic pronoun
        types = tokens = credited = 0
        with open(ignored_path, encoding="utf-8") as f:
            for line in f:
                parts = line.split()
                if len(parts) != 2:
                    continue
                tok, c = parts[0], int(parts[1])
                hp = hyphen_parts(tok)
                if hp is None:
                    continue
                types += 1
                tokens += c
                credited += c * len(hp)
                keys = [jlower(p) for p in hp]
                for i, key in enumerate(keys):
                    if not all(ch in ALLOWED for ch in key):
                        continue
                    self.counts[key] = self.counts.get(key, 0) + c
                    self.hyphen_counts[key] = self.hyphen_counts.get(key, 0) + c
                    if i + 1 < len(keys) and keys[i + 1] in CLITIC_SET:
                        self.preclitic[key] = self.preclitic.get(key, 0) + c
        self.corpus_tokens = main_total + credited
        self.report.update({"mainListTokens": main_total, "hyphenTokenTypes": types, "hyphenTokens": tokens,
                            "hyphenPartCountsCredited": credited, "corpusTokens": self.corpus_tokens})

    # ------------------------------------------------------------------ §9.2 page kinds per key

    def _collect_pages(self):
        self.forms_by_key = {}      # key -> cased titles that are Portuguese forms (case decision, validity)
        self.form_dumps = {}        # key -> {dump: [kinds of its form pages]}
        self.pointer_keys = set()
        self.prereform = []         # (dump, title, new spelling)
        self.phrase_pointer = {}    # key -> {dump: True if a pointer page to a phrase}
        conflicts = set()
        for dump, (titles, pages) in self.dumps:
            for title, k in pages.items():
                key = jlower(title)
                if not all(c in ALLOWED for c in key):
                    continue
                if k.get("prereform"):
                    new = k["prereform"].split("//")[0].strip()
                    if new:
                        self.prereform.append((dump, title, new))
                    continue
                for v in k.get("prereform_variants", ()):  # extension: pre-reform variants listed on the new page
                    if " " not in v:
                        self.prereform.append((dump + "-variant", v, title))
                if k.get("phrase") and not k["form"]:
                    self.phrase_pointer.setdefault(key, {})[dump] = True
                if k["form"]:
                    self.forms_by_key.setdefault(key, set()).add(title)
                    self.form_dumps.setdefault(key, {}).setdefault(dump, []).append(k)
                elif k.get("pointer"):
                    self.pointer_keys.add(key)
        # §9.2 open point 12: pre-reform in one dump, a form in the other
        for dump, title, new in self.prereform:
            other = self.form_dumps.get(jlower(title), {})
            if any(d != dump.split("-")[0] for d in other) and not dump.endswith("variant"):
                conflicts.add(title)
        self.report["prereformFormConflicts"] = sorted(conflicts)[:80]
        self.report["prereformFormConflictCount"] = len(conflicts)
        for p in CLITIC_PRONOUNS:
            self.forms_by_key.setdefault(p, set()).add(p)
        self.pointer_keys -= set(self.forms_by_key)

    def _abbreviations(self, abbreviations):
        """§3.6 abbreviation and affix titles (0.6.7): {dump: forms} ("doc" of "doc.", "pré" of "pré-") are forms of
        their dump, not inflected; the keys that are forms only by this rule are recorded."""
        self.abbreviation_keys = set()
        for dump, forms in sorted(abbreviations.items()):
            if dump not in dict(self.dumps):
                continue
            for form in sorted(forms):
                key = jlower(form)
                if not all(c in ALLOWED for c in key):
                    continue
                if key not in self.forms_by_key:
                    self.abbreviation_keys.add(key)
                self.forms_by_key.setdefault(key, set()).add(form)
                self.form_dumps.setdefault(key, {}).setdefault(dump, []).append(
                    {"form": True, "pointer": None, "prereform": None, "euro": False, "euro_target": None,
                     "inflected": False, "phrase": False, "prereform_variants": [], "old1911": False})
        self.pointer_keys -= set(self.forms_by_key)

    def is_form(self, key):
        return key in self.forms_by_key or key in self.accepted

    def count(self, key):
        return self.counts.get(key, 0)

    def form_in(self, key, dump):
        return dump in self.form_dumps.get(key, {}) or (key in CLITIC_SET) or key in self.accepted

    # ------------------------------------------------------------------ §9.4 step 1

    def _preclitic(self):
        self.accepted = set()
        for key in sorted(self.counts):
            if key in self.forms_by_key:
                continue
            c = self.counts[key]
            if c >= PRECLITIC_MIN_COUNT and self.preclitic.get(key, 0) >= PRECLITIC_SHARE * c:
                self.accepted.add(key)
        self.report["precliticFormsAccepted"] = len(self.accepted)
        self.report["precliticFormsAcceptedList"] = sorted(self.accepted, key=lambda k: (-self.counts[k], k))[:150]

    def is_preclitic(self, key):
        c = self.counts.get(key, 0)
        return c > 0 and self.preclitic.get(key, 0) >= PRECLITIC_SHARE * c

    # ------------------------------------------------------------------ §9.4 step 2

    def _reform(self):
        self.zero_forms = set()       # former Brazilian spellings, kept with count 0
        self.european_spellings = set()  # former European spellings (dropped)
        self.case_only_prereform = set()
        moved = 0
        moved_eu = 0
        seen = set()
        for dump, title, new in sorted(self.prereform):
            key = jlower(title)
            nkey = jlower(new)
            if title.lower() == new.lower():
                self.case_only_prereform.add(title)
                continue
            if not all(c in ALLOWED for c in nkey) or " " in new:
                continue
            self.forms_by_key.setdefault(nkey, set()).add(new)
            if key not in self.counts or key in seen:
                continue
            seen.add(key)
            if diacritics_only(title, new):
                c = self.counts[key]
                self.counts[nkey] = self.counts.get(nkey, 0) + c
                self.counts[key] = 0
                self.zero_forms.add(key)
                self.forms_by_key.setdefault(key, set()).add(title)
                moved += c
            else:
                c = self.counts.pop(key)
                if nkey in self.counts:
                    self.counts[nkey] += c
                self.european_spellings.add(key)
                self.forms_by_key.pop(key, None)
                moved_eu += c
        # case-only pre-reform pages are no forms for the case decision (§9.3)
        for title in self.case_only_prereform:
            fs = self.forms_by_key.get(jlower(title))
            if fs is not None and title in fs and len(fs) > 1:
                fs.discard(title)
        self.report["prereformPages"] = {d: sum(1 for x in self.prereform if x[0] == d) for d in sorted({x[0] for x in self.prereform})}
        self.report["prereformBrazilianKept"] = len(self.zero_forms)
        self.report["prereformBrazilianCountMoved"] = moved
        self.report["prereformBrazilianExamples"] = sorted(self.zero_forms, key=lambda k: -self.counts.get(jlower(next(
            (n for _, t, n in self.prereform if jlower(t) == k), k)), 0))[:40]
        self.report["prereformEuropeanDropped"] = len(self.european_spellings)
        self.report["prereformEuropeanCountMoved"] = moved_eu

    # ------------------------------------------------------------------ §9.4 step 3

    def _european(self):
        self.european_dropped = set()
        self.euro_only_keys = set()
        rows = []
        for key in sorted(self.form_dumps):
            dumps = self.form_dumps[key]
            pages = [k for ks in dumps.values() for k in ks]
            if not pages or not all(k["euro"] for k in pages) or key in self.zero_forms or key in CLITIC_SET:
                continue
            self.euro_only_keys.add(key)
            if key not in self.counts:
                continue
            c = self.counts[key]
            best = self.european_target(key, c)
            if best is None:
                continue
            if diacritics_only(key, best):
                self.counts[best] += c
            del self.counts[key]
            self.forms_by_key.pop(key, None)
            self.form_dumps.pop(key, None)
            self.european_dropped.add(key)
            rows.append((key, best, c))
        rows.sort(key=lambda r: -r[2])
        self.report["europeanOnlyKeys"] = len(self.euro_only_keys)
        self.report["europeanFormsDropped"] = len(self.european_dropped)
        self.report["europeanFormsDroppedExamples"] = [list(r) for r in rows[:60]]
        self.report["europeanFormsKeptExamples"] = sorted(
            (k for k in self.euro_only_keys - self.european_dropped if k in self.counts),
            key=lambda k: -self.counts[k])[:40]

    def european_target(self, key, c):
        """§9.4 step 3: the Brazilian counterpart of a European-only key counted [c] times if it is at least EURO_RATIO
        times as frequent (the key is then dropped), else None."""
        pages = [k for ks in self.form_dumps.get(key, {}).values() for k in ks]
        targets = sorted({jlower(k["euro_target"]) for k in pages if k.get("euro_target")})
        targets = [t for t in targets if t != key]
        best = max(targets, key=lambda t: (self.counts.get(t, 0), t), default=None)
        if best is None or self.counts.get(best, 0) < EURO_RATIO * max(c, 1):
            return None
        return best

    # ------------------------------------------------------------------ §9.4 step 4

    def _inflected_only(self, key):
        dumps = self.form_dumps.get(key)
        return bool(dumps) and all(all(k["inflected"] for k in ks) for ks in dumps.values())

    def _running(self, key):
        """Running-text count of a key in the filtered sentence sources (all cased variants)."""
        return sum(self.stats.rt_all.get(f, 0) for f in self.stats.forms_by_key.get(key, ()))

    @staticmethod
    def _adds_diacritics(key, target):
        """True if [target] is [key] with diacritics added at positions where [key] has a plain letter."""
        if len(key) != len(target):
            return False
        return all(a == b or (strip_diacritics(b) == a and strip_diacritics(a) == a) for a, b in zip(key, target))

    def _merge_accents(self):
        index = {}
        for k in set(self.counts) | set(self.forms_by_key):
            index.setdefault(strip_diacritics(k), []).append(k)
        merged = {}
        inflected_merged = []
        kept_running = []
        kept_foreign = []
        for key in sorted(self.counts):
            if key in self.accepted or key in self.zero_forms or key in CLITIC_SET:
                continue
            form = key in self.forms_by_key
            inflected = form and self._inflected_only(key)
            if form and not inflected:
                continue
            options = [t for t in index.get(strip_diacritics(key), ()) if t != key and self.language_form(t)
                       and t not in self.zero_forms]
            if not options:
                continue
            # 0.7.0 (05b §9.4, §12.1 item 5): a spelling the spelling dictionary knows (also the title of a pointer
            # page) is itself a form and never merged; Wiktionary's inflected-only forms still yield to a far more
            # frequent form of their class
            if not form and self.spelling_protects(key):
                continue
            if form:
                # an inflected-only page without a lemma of its own yields to a far more frequent form of its class
                # that only adds diacritics ("ultima" → "última", "ate" → "até"); forms that differ by other diacritics
                # are both words ("têm"/"tem", "séria"/"seria"), and a verb form used only before an enclitic
                # ("fazê") is no such target ("faze" stays, 05 §17.9 example 7)
                options = [t for t in options if not self.is_preclitic(t) and self._adds_diacritics(key, t)]
                if not options:
                    continue
            target = sorted(options, key=lambda t: (-self.counts.get(t, 0), t))[0]
            if not form:
                # a spelling the edited sentence sources use at least as often as the target is a real spelling
                # (names and loanwords: "julia", "thomas", "carter"), not an accent-less artefact
                rk = self._running(key)
                rt = self._running(target)
                if rk >= RUNNING_KEEP_MIN and rk >= rt:
                    kept_running.append((key, target, self.counts[key], rk))
                    continue
                # a word of another language is no artefact of a rare Portuguese word ("he" → "hê", "place" → "placê")
                if key in self.foreign_titles and rt < RUNNING_TARGET_MIN:
                    kept_foreign.append((key, target, self.counts[key], rt))
                    continue
            if form:
                if self.counts.get(target, 0) < INFLECTED_MERGE_RATIO * max(self.counts[key], 1):
                    continue
                inflected_merged.append((key, target, self.counts[key]))
            merged[key] = target
        merged_counts = {}
        for key, target in merged.items():
            merged_counts[key] = self.counts[key]
            self.counts[target] = self.counts.get(target, 0) + self.counts.pop(key)
            self.forms_by_key.pop(key, None)
        self.merged = merged
        self.report["accentMerged"] = len(merged)
        self.report["accentMergedPointerPages"] = sum(1 for k in merged if k in self.pointer_keys)
        self.report["accentMergedInflectedForms"] = len(inflected_merged)
        self.report["accentMergedInflectedExamples"] = [list(r) for r in sorted(inflected_merged, key=lambda r: -r[2])[:40]]
        self.report["accentMergeSkippedForeign"] = len(kept_foreign)
        self.report["accentMergeSkippedForeignExamples"] = [list(r) for r in sorted(kept_foreign, key=lambda r: -r[2])[:40]]
        self.report["accentMergeSkippedRunningText"] = len(kept_running)
        self.report["accentMergeSkippedRunningTextExamples"] = [list(r) for r in sorted(kept_running, key=lambda r: -r[2])[:40]]
        self.report["accentMergedExamples"] = [[k, merged[k], merged_counts[k]]
                                               for k in sorted(merged, key=lambda k: (-merged_counts[k], k))[:60]]

    # ------------------------------------------------------------------ §9.7 sentence filter

    def european_marker_keys(self, finished=None):
        """Keys whose tokens mark a European sentence (§9.7 item 1)."""
        out = set(self.european_dropped) | set(self.european_spellings)
        for k in self.euro_only_keys:
            if finished is None or k not in finished:
                if k not in self.counts or self.counts.get(k, 0) < 2:
                    out.add(k)
        return out

    @staticmethod
    def european_sentence(text, markers):
        prev = None
        prev2 = None
        last_end = None
        prev_adjacent = False
        for m in TOKEN.finditer(text):
            key = jlower(m.group(0))
            if key in markers:
                return True
            adjacent = last_end is not None and text[last_end:m.start()].isspace()
            if adjacent and prev == "a" and prev2 in ESTAR_FORMS and prev_adjacent and INFINITIVE.match(key):
                return True
            prev_adjacent = adjacent
            prev2, prev = prev, key
            last_end = m.end()
        return False

    # ------------------------------------------------------------------ coverage (§9.2)

    def coverage(self, top=20000):
        keys = sorted(self.counts, key=lambda k: (-self.counts[k], k))[:top]
        out = {}
        for name in ("pt", "en"):
            out[name] = sum(1 for k in keys if self.form_in(k, name)) / len(keys)
        out["both"] = sum(1 for k in keys if self.is_form(k)) / len(keys)
        return out

    # ------------------------------------------------------------------ case (§9.3)

    def set_stats(self, stats):
        self.stats = stats
        self._case_cache = {}

    def case_decision(self, key):
        if key in self._case_cache:
            return self._case_cache[key]
        variants = {f: c for f, c in self.stats.evidence(key).items() if c >= 2}
        dict_forms = self.forms_by_key.get(key, set())
        if not dict_forms and key in self.accepted:
            dict_forms = {key}
        if not dict_forms:
            dict_forms = self.dictionary_forms(key)  # the spelling dictionary's casings (05b §12.1 item 6)
        result = casing.decide(key, variants, dict_forms)
        self._case_cache[key] = result
        return result

    # ------------------------------------------------------------------ §9.5 step 5

    def _splits(self, key):
        out = []
        for i in range(1, len(key) - 1):
            a, b = key[:i], key[i:]
            if len(a) == 1 and a not in ONE_LETTER_WORDS or len(b) < 2:
                continue
            if (self.is_form(a) and self.is_form(b) and a not in self.zero_forms and b not in self.zero_forms
                    and self.count(a) >= SPLIT_PART_MIN and self.count(b) >= SPLIT_PART_MIN):
                out.append(a + " " + b)
        return out

    def phrase_candidates(self):
        """(key, phrase) pairs whose phrase counts the selection needs: unknown keys and inflected-only forms that split
        into two frequent forms."""
        out = []
        for key in sorted(self.counts):
            if len(key) < 4 or key in self.zero_forms:
                continue
            if self.is_form(key) and not self._inflected_only(key):
                continue
            for p in self._splits(key):
                out.append((key, p))
        return out

    def set_phrase_counts(self, counter):
        self.phrases = counter
        self.fused = set()
        rows = []
        sub_total = sum(self.counts.values())
        for key, phrase in self.phrase_candidates():
            t = counter.tokens.get(key, 0)
            p = counter.pairs.get(phrase, 0)
            if not self.is_form(key):
                if p >= PHRASE_MIN and p >= PHRASE_RATIO * t:
                    self.fused.add(key)
                    rows.append([key, phrase, t, p, "unknown"])
                continue
            # an inflected-only form whose phrase is far more frequent in running text (Spanish rule, "aveces")
            share = (t / max(counter.total, 1)) / (self.counts[key] / sub_total) if self.counts[key] else 0
            # the share is only evidence if the sentence sources should contain the form several times
            expected = self.counts[key] * counter.total / sub_total
            if expected < FUSED_MIN_EXPECTED:
                continue
            a, b = phrase.split(" ")
            # infinitive + "em" is also the personal infinitive / future subjunctive ("confiarem", "partirem")
            if a.endswith("r") and b == "em":
                continue
            if b not in CLITIC_SET and p >= max(FUSED_MIN_PHRASE, PHRASE_RATIO * t) and share < FUSED_MAX_SHARE:
                self.fused.add(key)
                rows.append([key, phrase, t, p, "inflected"])
        # fused-phrase pointer pages ("denovo")
        for key in sorted(self.phrase_pointer):
            if key in self.counts and not self.is_form(key):
                if key not in self.fused:
                    rows.append([key, "(phrase page)", counter.tokens.get(key, 0), 0, "wiktionary"])
                self.fused.add(key)
        rows.sort(key=lambda r: -self.count(r[0]))
        self.report["missingSpaceDropped"] = len(self.fused)
        self.report["missingSpaceDroppedExamples"] = rows[:60]

    # ------------------------------------------------------------------ §9.5 selection

    def select(self, key, dropped):
        """§9.5 with the 0.7.0 rules (05b §12): the junk rules exempt only forms of the Wiktionary passes, the
        abbreviation rule and the pre-clitic forms (is_form); a form of the spelling dictionary is kept like them; the
        count rule is the colloquial path."""
        count = self.count(key)
        form = self.is_form(key)
        zero = key in self.zero_forms
        if not plausible(key) and not (form and 2 <= len(key) <= 27):
            dropped["implausible"] += 1
            return None
        if len(key) == 1 and key not in ONE_LETTER_WORDS:
            dropped["oneLetter"] += 1
            return None
        if not form and any(c not in PORTUGUESE_PLAIN for c in key):
            dropped["nonPortugueseCharacters"] += 1
            return None
        if not form and misplaced_cedilla(key):
            # Portuguese writes ç only before a, o, u: "começ" (a subtitle cut-off) is no word
            dropped["misplacedCedilla"] += 1
            return None
        if key in self.fused:
            dropped["missingSpace"] += 1
            return None
        if not form and short_key_dropped(len(key), count):
            # 05b §3.6 step 6a: a key of at most 3 letters that is no form needs count >= 20 000
            dropped["shortKeyStep6a"] += 1
            if count >= KEEP_MIN_COUNT:
                self.short_dropped.append(key)
            return None
        if form and key not in self.counts and key in self.euro_only_keys and self.european_target(key, 0):
            dropped["europeanForm"] += 1  # §9.4 step 3 for a European-only page without a subtitle count
            return None
        if form or self.spelling_form(key):
            if zero or self.admitted(key, count):
                return self.case_decision(key) or [(key, 1.0)]
            dropped["formBelowCount2"] += 1
            return None
        if count >= KEEP_MIN_COUNT:
            return self._count_rule(key, count, dropped)
        dropped["rare"] += 1
        return None

    def _count_rule(self, key, count, dropped):
        """§9.5 count rule (German §3.6 step 7) for a non-form counted at least KEEP_MIN_COUNT times: after the
        foreign-word clause only the colloquial path keeps it (0.7.0)."""
        if key in self.en_titles_lower and key not in self.foreign_titles and count < FOREIGN_MAX:
            dropped["foreignEnglishDumpOnly"] += 1
            if len(dropped["foreignEnglishDumpOnlyExamples"]) < 40 and count >= 2000:
                dropped["foreignEnglishDumpOnlyExamples"].append(key)
        if key in self.foreign_titles and count < FOREIGN_MAX:
            dropped["foreign"] += 1
            if len(dropped["foreignExamples"]) < 40 and count >= 2000:
                dropped["foreignExamples"].append(key)
            return None
        if not self.colloquial_path(key, count):
            dropped["colloquialPath"] += 1
            return None
        dec = self.case_decision(key)
        if dec:
            return dec
        titles = self.titles_by_key.get(key, set())
        if key in titles or not titles:
            return [(key, 1.0)]
        best = sorted(titles, key=lambda f: (casing.case_kind_rank(f), f))[0]
        return [(best, 1.0)]

    def build(self, name_rule=None):
        self.name_rule = name_rule
        self.titles_by_key = {}
        for t in self.titles:
            self.titles_by_key.setdefault(jlower(t), set()).add(t)
        dropped = {k: 0 for k in ("implausible", "oneLetter", "nonPortugueseCharacters", "misplacedCedilla", "formBelowCount2",
                                  "missingSpace", "europeanForm", "foreign", "colloquialPath", "shortKeyStep6a", "rare")}
        dropped["foreignExamples"] = []
        dropped["foreignEnglishDumpOnly"] = 0  # not dropped: would be foreign with the English dump's titles
        dropped["foreignEnglishDumpOnlyExamples"] = []
        self.short_dropped = []
        self.colloquial = set()
        self.colloquial_dropped = {}
        self.written_admitted = set()
        selected = {}
        kept_unknown = 0
        candidates = set(self.counts)
        if self.freq is not None:
            key_ok = lambda k: 2 <= len(k) <= 27 and all(c in ALLOWED for c in k) and k not in self.spelling_excluded
            candidates |= self.written_candidates(key_ok, self.accepted)
        for key in sorted(candidates):
            if not (2 <= len(key) <= 27 or len(key) == 1):
                continue
            sel = self.select(key, dropped)
            if not sel:
                continue
            if not self.language_form(key):
                kept_unknown += 1
            selected[key] = sel
        entries = self.combined_entries(selected, zero=self.zero_forms, names=name_rule)
        self.report["dropped"] = dropped
        self.report["keptNotAForm"] = kept_unknown
        self.report["precliticKeys"] = sum(1 for k in entries if k in self.accepted)
        self.report["prereformKeysKept"] = sum(1 for k in entries if k in self.zero_forms)
        return entries
