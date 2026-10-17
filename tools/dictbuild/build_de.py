"""German word list (05b-dictionaries §3)."""

import json
import math
import unicodedata

import casing
import combined
from attested import AttestedRules
from casing import case_kind_rank
from common import capitalize_first, jlower

EXCLUDED_LETTERS = set("İıŉſĸ")


def _allowed_key_chars():
    chars = set("abcdefghijklmnopqrstuvwxyzäöüß")
    for cp in range(0xC0, 0x180):
        ch = chr(cp)
        if not ch.isalpha() or ch in EXCLUDED_LETTERS:
            continue
        low = ch.lower()
        if len(low) == 1 and low not in EXCLUDED_LETTERS:
            chars.add(low)
    return chars


ALLOWED = _allowed_key_chars()
GERMAN_PLAIN = set("abcdefghijklmnopqrstuvwxyzäöüß")
VOWELS = set("aeiouyäöü" "àáâãåæèéêëìíîïòóôõøœùúûýÿ")

FUNCTION_WORDS = set("""ich sie wir ihr mich dich sich uns euch mir dir ihn ihm ihnen der die das den dem des eine
einen einem einer eines und oder aber ist sind war waren hat habe hast haben nicht kein keine keinen mein meine meinen
dein deine deinen sein seine seinen was wie wenn dass bin bist kann kannst muss musst will willst doch noch schon auch
nur sehr ganz gar""".split())

COMPOUND_LINKS = ["", "s", "es", "n", "en", "e", "er", "ens"]
# §3.6 step 6a (0.6.4): a key of at most 3 letters that is not a German form needs this count, as in the English build
SHORT_KEY_LEN = 3
SHORT_KEY_MIN = 20000
# §3.6 real-word typos (0.6.4): a German form counted fewer than TYPO_MAX_COUNT times, whose most frequent form is lower
# case, and whose one-key-aside substitution or swap is a German form at least TYPO_RATIO times as frequent gets class 0
TYPO_MAX_COUNT = 100
TYPO_RATIO = 5000
# §3.7 dictionary-only German lemmas (0.6.5/0.6.6): the lowest class that is still offered (class 0 is never offered)
DICT_ONLY_CLASS = 1
# §3.7 (0.6.6): a lemma whose key occurs at most this often in the subtitles counts as dictionary-only
DICT_ONLY_MAX_COUNT = 1
# §3.7 lemmas: German parts of speech of inflected-form pages (spec/QUESTIONS.md, 0.6.5); a title is a lemma if its
# German section has any other part of speech
FORM_POS = {"Deklinierte Form", "Konjugierte Form", "Dekliniertes Gerundivum", "Komparativ", "Superlativ", "Partizip I",
            "Partizip II", "Erweiterter Infinitiv"}
# §3.6 umlaut merge (0.6.9): a non-form key that lacks umlauts or ß of a German form is merged into it when the form is
# counted at least this many times as often
UMLAUT_MERGE_RATIO = 10
UMLAUT_BASE = {"ä": "a", "ö": "o", "ü": "u"}
# neighbour keys as the engine's touch model sees them (05 §4): proximity at the key centres <= NEAR_MAX
TOUCH_KX, TOUCH_KY, TOUCH_UNIT, NEAR_MAX = 13516.8, 18432.0, 35.0, 1000


def fold_umlauts(s):
    """ä → a, ö → o, ü → u, ß → ss (§3.6 umlaut merge)."""
    return s.replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")


def lacks_umlauts(key, form):
    """True if [key] is [form] with one or more of its umlauts written without the dots or transliterated (ä → a or ae,
    ö → o or oe, ü → u or ue; 0.7.0) or ß written as ss (any number of positions), and nothing else changed."""
    if key == form:
        return False
    memo = {}

    def match(i, j):
        if (i, j) in memo:
            return memo[(i, j)]
        if i == len(form):
            r = j == len(key)
        elif j >= len(key):
            r = False
        else:
            c = form[i]
            if c in UMLAUT_BASE:
                b = UMLAUT_BASE[c]
                r = ((key[j] == c and match(i + 1, j + 1)) or (key[j] == b and match(i + 1, j + 1))
                     or (key.startswith(b + "e", j) and match(i + 1, j + 2)))
            elif c == "ß":
                r = (key[j] == "ß" and match(i + 1, j + 1)) or (key.startswith("ss", j) and match(i + 1, j + 2))
            else:
                r = key[j] == c and match(i + 1, j + 1)
        memo[(i, j)] = r
        return r

    return match(0, 0)


def untransliterated(key):
    """The spellings of [key] with each "ae", "oe", "ue" kept or written as "a", "o", "u" (the index lookup of the
    transliteration merge; at most 64)."""
    out = [""]
    i = 0
    while i < len(key):
        if key[i] in "aou" and i + 1 < len(key) and key[i + 1] == "e" and len(out) <= 32:
            out = [o + key[i] + "e" for o in out] + [o + key[i] for o in out]
            i += 2
        else:
            out = [o + key[i] for o in out]
            i += 1
    return out


def key_letter(ch):
    """The letter of the key that types [ch] on the German letter page: diacritics removed, ß on s (05 §4)."""
    if ch == "ß":
        return "s"
    return unicodedata.normalize("NFD", ch)[0]


def neighbour_keys(layout_path):
    """Letter -> set of letters on neighbouring keys of the German letter page (spec/data layout, 05 §4)."""
    with open(layout_path, encoding="utf-8") as f:
        page = json.load(f)["pages"]["text.letters"]
    centres = {}
    widths, heights = [], []
    for k in page["keys"]:
        out = k.get("output", "")
        if k.get("role") == "char" and len(out) == 1 and out.isalpha():
            x0, y0, x1, y1 = k["rect"]
            centres[out] = ((x0 + x1) / 2, (y0 + y1) / 2)
            widths.append(round(x1 - x0, 6))
            heights.append(round(y1 - y0, 6))
    w = max(set(widths), key=widths.count)
    h = max(set(heights), key=heights.count)
    near = {}
    for a, (ax, ay) in centres.items():
        near[a] = set()
        for b, (bx, by) in centres.items():
            if a == b:
                continue
            v = math.floor((((ax - bx) / w) ** 2 * TOUCH_KX + ((ay - by) / h) ** 2 * TOUCH_KY) / TOUCH_UNIT + 0.5)
            if v <= NEAR_MAX:
                near[a].add(b)
    return near
NOUN_SUFFIXES = ["heit", "keit", "ung", "schaft", "ität", "tion", "ismus", "nis"]
NOUN_SUFFIX_ENDINGS = ["", "en", "se", "ses", "s"]


def plausible(key):
    if not 2 <= len(key) <= 27:
        return False
    if not any(c in VOWELS for c in key):
        return False
    for i in range(len(key) - 2):
        if key[i] == key[i + 1] == key[i + 2]:
            return False
    return True


def has_noun_suffix(key):
    for suf in NOUN_SUFFIXES:
        for end in NOUN_SUFFIX_ENDINGS:
            s = suf + end
            if key.endswith(s) and len(key) > len(s):
                return True
    return False


class GermanBuilder(AttestedRules):
    def __init__(self, freq_path, wikt, abbreviations=(), prefixes=(), spelling=None, written=None, english_keys=()):
        """[abbreviations]: forms of the abbreviation titles ("usw" of "usw.", §3.6, 0.6.7); [prefixes]: the prefix titles
        without the hyphen ("anti" of "anti-"), which are no forms but may be the first part of a compound (§3.5);
        [spelling], [written], [english_keys]: the spelling dictionary, the written-language list and the en-US keys
        of the 0.7.0 rules (05b §12)."""
        self.setup_attested(spelling, written, frozenset(english_keys))
        self.prefix_parts = {jlower(p) for p in prefixes}
        self.titles, self.de_forms, counts = wikt
        before = {jlower(f) for f in self.de_forms}
        self.de_forms = set(self.de_forms) | {f for f in abbreviations if all(ch in ALLOWED for ch in jlower(f))}
        # keys that are German forms only by the abbreviation and affix rule
        self.abbreviation_keys = {jlower(f) for f in self.de_forms} - before
        # running-text variants per key (occurring >= 2 times)
        self.variants = {}
        for tok, c in counts.items():
            if c >= 2:
                self.variants.setdefault(jlower(tok), {})[tok] = c
        self.forms_by_key = {}
        for f in self.de_forms:
            self.forms_by_key.setdefault(jlower(f), set()).add(f)
        self.titles_by_key = {}
        for t in self.titles:
            self.titles_by_key.setdefault(jlower(t), set()).add(t)
        self.corpus_tokens = 0
        self.counts = {}
        with open(freq_path, encoding="utf-8") as f:
            for line in f:
                parts = line.split()
                if len(parts) != 2:
                    continue
                tok, c = parts[0], int(parts[1])
                self.corpus_tokens += c
                key = jlower(tok)
                if key != tok or not all(ch in ALLOWED for ch in key):
                    continue
                self.counts[key] = self.counts.get(key, 0) + c
        # the spelling dictionary is asked for every key that could be kept (05b §12.1); parallel, cached
        self.prefetch_spelling(k for k, c in self.counts.items() if k not in self.forms_by_key and 2 <= len(k) <= 27
                               and (c >= 2 or self.written(k) > 0))
        self._merge_umlauts()
        self._case_cache = {}
        # filled by build(): short keys dropped by step 6a, real-word typos (key -> dominating key)
        self.short_dropped = []
        self.typos = {}
        self.prefix_compounds = set()  # keys kept by step 5 only through a prefix as first part (report)

    def has_sharp_s(self):
        return True

    def case_evidence(self, key):
        return self.variants.get(key, {})

    # §3.6 umlaut merge (0.6.9)
    def _merge_umlauts(self):
        """Keys that are no German form and differ from a German form only by missing or transliterated umlauts (0.7.0)
        or ß, counted at most a
        tenth as often as that form, are removed and their count is added to the form (the most frequent one if
        several qualify). German forms are never merged; from 0.7.0 the spelling dictionary's forms count as German
        forms (05b §12.1 item 5), also as targets (those with a subtitle count)."""
        index = {}
        targets = set(self.forms_by_key) | {k for k in self.counts if any(c in k for c in "äöüß") and self.spelling_form(k)}
        for f in targets:
            if any(c in f for c in "äöüß"):
                index.setdefault(fold_umlauts(f), []).append(f)
        merged = {}
        for key in sorted(self.counts):
            if key in self.forms_by_key:
                continue
            c = self.counts[key]
            pool = {f for v in untransliterated(key) for f in index.get(fold_umlauts(v), ())}
            options = sorted(f for f in pool if lacks_umlauts(key, f) and self.counts.get(f, 0) >= UMLAUT_MERGE_RATIO * c)
            if options and not self.wiktionary_form(key) and self.spelling_protects(key):
                continue
            if options:
                merged[key] = max(options, key=lambda f: (self.counts.get(f, 0), f))
        self.merged_counts = {k: self.counts[k] for k in merged}
        self.merged_into = {}
        for key, target in merged.items():
            c = self.counts.pop(key)
            self.counts[target] += c
            self.merged_into[target] = self.merged_into.get(target, 0) + c
        self.umlaut_merged = merged

    def is_german_form(self, key):
        """A German form (0.7.0, 05b §3.6): of the Wiktionary pass, of the abbreviation rule or of the spelling
        dictionary."""
        return self.language_form(key)

    def count(self, key):
        return self.counts.get(key, 0)

    # §3.4
    def case_decision(self, key):
        if key in self._case_cache:
            return self._case_cache[key]
        result = casing.decide(key, self.variants.get(key, {}), self.dictionary_forms(key))
        self._case_cache[key] = result
        return result

    def primary_form(self, key):
        dec = self.case_decision(key)
        return dec[0][0] if dec else key

    def has_capitalised_variant(self, key):
        dec = self.case_decision(key) or []
        return any(f[0].isupper() for f, _ in dec) or any(f[0].isupper() for f in self.forms_by_key.get(key, ()))

    def _compound_part_ok(self, part, first=False):
        """A German form counted ≥ 20 times and no function word; the first part may also be a prefix title (0.6.7).
        The parts are forms of the Wiktionary pass and the abbreviation rule (05b §12.1 items 2 and 4: compounds stay
        with §3.5 and Wiktionary)."""
        known = self.wiktionary_form(part) or (first and part in self.prefix_parts)
        return known and self.count(part) >= 20 and part not in FUNCTION_WORDS

    # §3.5
    def compound_case(self, key):
        for i in range(3, len(key) - 3):
            head = key[i:]
            if len(head) < 4 or not self._compound_part_ok(head):
                continue
            modifier = key[:i]
            for link in COMPOUND_LINKS:
                if not modifier.endswith(link):
                    continue
                stem = modifier[:len(modifier) - len(link)] if link else modifier
                if len(stem) < 3 or not self._compound_part_ok(stem, first=True):
                    continue
                if not self.wiktionary_form(stem):
                    self._prefix_hit = True
                head_primary = self.primary_form(head)
                stem_primary = self.primary_form(stem)
                cap = (head_primary[0].isupper()
                       or (stem_primary[0].isupper() and self.has_capitalised_variant(head))
                       or has_noun_suffix(key))
                return capitalize_first(key) if cap else key
        if has_noun_suffix(key):
            return capitalize_first(key)
        return None

    # §3.6
    def select(self, key):
        """Returns a list of (form, share) or None if the key is dropped."""
        return self.select_step(key)[0]

    def select_step(self, key):
        """Returns (list of (form, share) or None, the step of §3.6 that decided). The junk rules (steps 1–3 and 6a)
        exempt only forms of the Wiktionary pass and the abbreviation rule (05b §12.1 item 4)."""
        count = self.count(key)
        wform = self.wiktionary_form(key)
        german = wform or self.spelling_form(key)
        if not plausible(key) and not (wform and 2 <= len(key) <= 27):
            return None, 1
        if not wform and any(c not in GERMAN_PLAIN for c in key):
            return None, 2
        if not wform and key.startswith("l") and self.is_german_form("i" + key[1:]):
            return None, 3
        if not wform and len(key) <= SHORT_KEY_LEN and count < SHORT_KEY_MIN:
            return None, "6a"
        if german:
            if self.admitted(key, count):
                return self.case_decision(key) or [(key, 1.0)], 4
            return None, 4
        if len(key) >= 6 and count >= 4:
            self._prefix_hit = False
            comp = self.compound_case(key)
            if comp is not None:
                if self._prefix_hit:
                    self.prefix_compounds.add(key)
                return self.case_decision(key) or [(comp, 1.0)], 5
        # step 6 (glued German forms) is not applied, see spec/QUESTIONS.md (0.6.4); step 6a is checked above
        return self.step7(key, count)

    def step7(self, key, count):
        """Step 7: a non-form counted at least 40 times is kept only by the colloquial path (0.7.0, 05b §12.4), after
        the foreign-title clause."""
        if count >= 40:
            titles = self.titles_by_key.get(key, set())
            if key in self.titles and count < 3000:
                return None, 7
            if not self.colloquial_path(key, count):
                return None, 7
            dec = self.case_decision(key)
            if dec:
                return dec, 7
            if key in titles or not titles:
                return [(key, 1.0)], 7
            best = sorted(titles, key=lambda f: (case_kind_rank(f), f))[0]
            return [(best, 1.0)], 7
        return None, 8

    def real_word_typos(self, german_keys, near, selected):
        """§3.6 real-word typos (0.6.4): German-form keys counted fewer than TYPO_MAX_COUNT times whose most frequent form
        (largest share) is lower case, with a German-form key one neighbour-key substitution or one swap away that is at least TYPO_RATIO
        times as frequent; key -> that most frequent key."""
        # letters (as they occur in the keys) per key letter, so a substitution may also produce ä, é …
        on_key = {}
        for key in german_keys:
            for ch in key:
                on_key.setdefault(key_letter(ch), set()).add(ch)
        out = {}
        for key in sorted(german_keys):
            c = self.count(key)
            # a key without a subtitle occurrence (admitted by its written frequency, 05b §12.3) is no subtitle typo:
            # the dominance test count(w') ≥ 5000 × 0 says nothing (spec/QUESTIONS.md, 0.7.0)
            if c >= TYPO_MAX_COUNT or c == 0:
                continue
            main = max(selected[key], key=lambda fs: fs[1])[0]  # largest share; ties: case-decision order
            if main != jlower(main):
                continue
            need = TYPO_RATIO * c
            best = None
            n = len(key)
            for i in range(n):
                kl = key_letter(key[i])
                for nk in near.get(kl, ()):
                    for ch in on_key.get(nk, ()):
                        cand = key[:i] + ch + key[i + 1:]
                        if cand in german_keys and self.count(cand) >= need:
                            if best is None or (self.count(cand), cand) > (self.count(best), best):
                                best = cand
            for i in range(n - 1):
                if key[i] != key[i + 1]:
                    cand = key[:i] + key[i + 1] + key[i] + key[i + 2:]
                    if cand in german_keys and self.count(cand) >= need:
                        if best is None or (self.count(cand), cand) > (self.count(best), best):
                            best = cand
            if best is not None:
                # abbreviations are not exempted (the item 4 of the 0.7.1 draft is withdrawn); they carry the
                # abbreviation bit of §1.1 instead, which changes no class
                out[key] = best
        return out

    def build(self, layout_path=None, name_rule=None):
        """Selection of §3.6 over the subtitle keys and the forms without a subtitle count that the written-language
        list has (05b §12.3), real-word typos, classes from the combined frequency (05b §12.2)."""
        self.name_rule = name_rule
        selected = {}
        self.short_dropped = []
        self.typos = {}
        self.prefix_compounds = set()
        self.colloquial = set()
        self.colloquial_dropped = {}
        self.written_admitted = set()
        key_ok = lambda k: 2 <= len(k) <= 27 and all(ch in ALLOWED for ch in k)
        candidates = set(self.counts) | self.written_candidates(key_ok)
        for key in sorted(candidates):
            if not 2 <= len(key) <= 27:
                continue
            sel, step = self.select_step(key)
            if step == "6a" and self.count(key) >= 40:
                self.short_dropped.append(key)
            if not sel:
                continue
            selected[key] = sel
        if layout_path:
            german = {k for k in selected if self.is_german_form(k)}
            self.typos = self.real_word_typos(german, neighbour_keys(layout_path), selected)
        return self.combined_entries(selected, zero=set(self.typos), names=name_rule)

    # §3.7 (0.6.5)
    def dictionary_only_keys(self, entries, lemma_keys=None):
        """German-form keys with no subtitle occurrence or exactly one (valid alphabet, 2…27 letters), not yet in
        [entries]; with [lemma_keys] only those (§3.7 since 0.6.6: lemmas only)."""
        out = []
        for key in sorted(self.forms_by_key):
            if self.count(key) > DICT_ONLY_MAX_COUNT or key in entries:
                continue
            if not 2 <= len(key) <= 27 or any(ch not in ALLOWED for ch in key):
                continue
            if lemma_keys is not None and key not in lemma_keys:
                continue
            out.append(key)
        return out

    def add_dictionary_only(self, entries, lemma_keys=None):
        """Adds the dictionary-only German lemmas without a written-language frequency (0.7.0: a lemma that has one is
        admitted by step 4, 05b §12.3) with class DICT_ONLY_CLASS, case from §3.4; returns the added keys."""
        added = []
        for key in self.dictionary_only_keys(entries, lemma_keys):
            if self.has_written(key, self.count(key)):
                continue
            dec = self.case_decision(key) or [(key, 1.0)]
            forms = []
            seen = set()
            for form, _ in dec:
                if form in seen or jlower(form) != key:
                    continue
                seen.add(form)
                forms.append((form, DICT_ONLY_CLASS))
            if forms:
                entries[key] = forms
                added.append(key)
                # 0.7.1: their form counts for the frequency ranks of 05b §1.1 (subtitles alone, no written frequency)
                cc = combined.combined_count(self.count(key), self.corpus_tokens, 0.0)
                for f, _ in forms:
                    self.form_counts[(key, f)] = cc / len(forms)
        return added


def lemma_keys(pos_by_title, abbreviations=None):
    """Keys of the titles whose German section has a part of speech other than an inflected form (§3.7 lemmas); with
    [abbreviations] ({form: parts of speech} of the abbreviation and affix titles, 0.6.7) also those forms whose title
    has such a part of speech."""
    keys = {jlower(t) for t, pos in pos_by_title.items() if pos - FORM_POS}
    for form, pos in (abbreviations or {}).items():
        if pos - FORM_POS:
            keys.add(jlower(form))
    return keys

