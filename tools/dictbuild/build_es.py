"""Spanish (Spain) word list (05b-dictionaries §1.5, §8).

Inputs: the FrequencyWords list (counts), the Spanish forms and titles of the Wiktionary passes (wiktionary_es.py)
and running-text case evidence from the pair sentences (SentenceStats). Every rule and threshold is recorded in
`self.report`, which the driver writes to tools/dictionary-build.json.
"""

import math
import re

import casing
from common import capitalize_first, freq_class, jlower

EXCLUDED_LETTERS = set("İıŉſĸ")
SPANISH_PLAIN = set("abcdefghijklmnopqrstuvwxyzáéíóúüñ")
VOWELS = set("aeiouy" "äöü" "àáâãåæèéêëìíîïòóôõøœùúûýÿ")
ONE_LETTER_WORDS = ("a", "e", "o", "u", "y")


def _allowed_key_chars():
    chars = set(SPANISH_PLAIN)
    for cp in range(0xC0, 0x180):
        ch = chr(cp)
        if not ch.isalpha() or ch in EXCLUDED_LETTERS:
            continue
        low = ch.lower()
        if len(low) == 1 and low not in EXCLUDED_LETTERS:
            chars.add(low)
    return chars


ALLOWED = _allowed_key_chars()

# §8.4 accents: the letters an accent merge may add (a e i o u → á é í ó ú, u → ü, n → ñ)
ADD_ACCENT = {"a": "á", "e": "é", "i": "í", "o": "ó", "u": "úü", "n": "ñ"}
STRIP_ALL = str.maketrans("áéíóúüñ", "aeiouun")
STRIP_ACUTE = str.maketrans("áéíóú", "aeiou")
ACUTE = set("áéíóú")

CLITICS = ["me", "te", "se", "lo", "la", "le", "nos", "os", "los", "las", "les"]
CLITIC_FIRST = ["se", "me", "te", "nos", "os"]
CLITIC_SECOND = ["lo", "la", "los", "las", "le", "les"]
CLITIC_SEQUENCES = sorted(set(CLITICS + [a + b for a in CLITIC_FIRST for b in CLITIC_SECOND]), key=lambda c: (-len(c), c))

# thresholds of the first build (05b §8.4, §8.5; open point 5)
CLITIC_MIN_COUNT = 20
SPLIT_PART_MIN = 100_000
FOREIGN_MAX = 100_000
KEEP_MIN_COUNT = 40
FUSED_RATIO = 10      # phrase at least 10 times as frequent as the fused form in the sentence sources
FUSED_MIN_PHRASE = 20
FUSED_MAX_SHARE = 0.2  # sentence-source share below 0.2 × the subtitle share
INFLECTED_MERGE_RATIO = 10

# running-text case evidence (§8.3)
TOKEN = re.compile(r"[^\W\d_]+")
RUNNING_BREAK = set(".!?:¿¡\"“”«»([")


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


def clean_token(tok):
    """§8.1 token clean-up: the key of a frequency-list token, or None if the token is dropped."""
    if any(c in "'’¿¡" or c.isdigit() for c in tok):
        return None
    if "-" in tok:
        stripped = tok.strip("-")
        if not stripped or "-" in stripped:
            return None
        tok = stripped
    key = jlower(tok)
    if key != tok or not all(c in ALLOWED for c in key):
        return None
    return key


class SentenceStats:
    """Running-text counts of the sentence sources (§8.3): conversational and all sources."""

    def __init__(self):
        self.rt_conv = {}
        self.rt_all = {}
        self.capitalised_tatoeba = {}   # capitalised token -> count, Tatoeba only (stock-name check, §5 step 3)
        self.tatoeba_tokens = 0

    def add_sentence(self, text, conversational, tatoeba=False):
        last_end = None
        for m in TOKEN.finditer(text):
            tok = m.group(0)
            if tatoeba:
                self.tatoeba_tokens += 1
            if tatoeba and tok[0].isupper():
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
        """Running-text counts of the key's variants: conversational if their total ≥ 10, else all sources."""
        forms = self.forms_by_key.get(key, ())
        conv = {f: self.rt_conv[f] for f in forms if f in self.rt_conv}
        if sum(conv.values()) >= 10:
            return conv
        return {f: self.rt_all[f] for f in forms if f in self.rt_all}


class PhraseCounter:
    """Lower-cased token and adjacent-pair counts of the sentence sources, restricted to the given phrases."""

    def __init__(self, phrases):
        self.phrases = set(phrases)
        self.words = {w for p in self.phrases for w in p.split(" ")}
        self.keys = {p.replace(" ", "") for p in self.phrases}
        self.tokens = {}
        self.pairs = {}
        self.total = 0

    def add(self, text):
        prev = None
        last_end = None
        for m in TOKEN.finditer(text):
            self.total += 1
            tok = jlower(m.group(0))
            if tok in self.keys:
                self.tokens[tok] = self.tokens.get(tok, 0) + 1
            if prev is not None and text[last_end:m.start()].isspace():
                p = prev + " " + tok
                if p in self.phrases:
                    self.pairs[p] = self.pairs.get(p, 0) + 1
            prev = tok if tok in self.words else None
            last_end = m.end()


class SpanishBuilder:
    def __init__(self, freq_path, es_pass, en_pass, stats):
        """[es_pass], [en_pass]: the sets of the Wiktionary passes (wiktionary_es.load); [en_pass] None = Spanish dump
        alone. Variant pages (only pointing to another spelling) may be merged into an accented word by the accent
        merge ("tambien" → "también"), otherwise they stay Spanish forms ("sólo", "éste")."""
        passes = [es_pass] + ([en_pass] if en_pass else [])
        self.stats = stats
        self.titles = es_pass["T"]
        self.report = {}
        variants = set().union(*(p["V"] for p in passes))
        es_forms = es_pass["F"]
        self.variant_keys = {jlower(v) for v in variants}
        # keys whose pages are inflection pointers in every dump that lists them as forms (no lemma of their own)
        listed = [({jlower(f) for f in p["F"]}, {jlower(f) for f in p["I"]}) for p in passes]
        self.inflected_keys = {k for k in set().union(*(f for f, _ in listed))
                               if all(k not in f or k in i for f, i in listed)}
        self.phrase_keys = {jlower(t) for p in passes for t in p["P"]}
        forms = set().union(*(p["F"] for p in passes)) | variants
        self.forms_by_key = {}
        for f in forms:
            k = jlower(f)
            if all(c in ALLOWED for c in k):
                self.forms_by_key.setdefault(k, set()).add(f)
        self.corpus_tokens = 0
        self.counts = {}
        hyphen_stripped = 0
        with open(freq_path, encoding="utf-8") as f:
            for line in f:
                parts = line.split()
                if len(parts) != 2:
                    continue
                tok, c = parts[0], int(parts[1])
                self.corpus_tokens += c
                key = clean_token(tok)
                if key is None:
                    continue
                if key != tok:
                    hyphen_stripped += 1
                self.counts[key] = self.counts.get(key, 0) + c
        self.report["hyphenTokensStripped"] = hyphen_stripped
        self.accepted = set()      # clitic forms accepted in §8.4 step 1
        self.merged = {}           # merged key -> target key (§8.4 step 2)
        self._clitics()
        self._merge_accents()
        self.max_count = max(self.counts.values())
        self.scale = 255.0 / math.log(self.max_count)
        self._case_cache = {}
        self.fused = set()

    # ------------------------------------------------------------------ forms

    def is_form(self, key):
        return key in self.forms_by_key or key in self.accepted

    def count(self, key):
        return self.counts.get(key, 0)

    def _stripped_index(self, keys):
        index = {}
        for k in keys:
            index.setdefault(k.translate(STRIP_ACUTE), []).append(k)
        return index

    # §8.4 step 1
    def _clitic_base(self, key, acute_index):
        """True if key = V + clitic sequence for a Spanish form V (compared without accents)."""
        plain = key.translate(STRIP_ACUTE)
        for seq in CLITIC_SEQUENCES:
            if len(key) <= len(seq) + 1 or not key.endswith(seq):
                continue
            stem = plain[:len(plain) - len(seq)]
            if stem in acute_index:
                return True
        # first and second person plural imperatives: V + "nos" (V + "s" a form), V + "os" (V + "d" a form)
        if key.endswith("nos") and len(key) > 4 and plain[:-3] + "s" in acute_index:
            return True
        if key.endswith("os") and len(key) > 3 and plain[:-2] + "d" in acute_index:
            return True
        return False

    def _clitics(self):
        acute_index = self._stripped_index(self.forms_by_key)
        candidates = [k for k in sorted(self.counts)
                      if k not in self.forms_by_key and self.counts[k] >= CLITIC_MIN_COUNT and len(k) >= 3
                      and all(c in SPANISH_PLAIN for c in k) and self._clitic_base(k, acute_index)]
        # spellings that differ only by acute accents compete: the most frequent candidate is accepted ("dámelo"
        # over "damelo"), unless a Spanish form of the same spelling without accents is at least as frequent (a wrongly
        # accented "díme" is no clitic form of "di"; "escúchame" is accepted next to the voseo form "escuchame")
        groups = {}
        for k in candidates:
            groups.setdefault(k.translate(STRIP_ACUTE), []).append(k)
        for plain, group in groups.items():
            best = sorted(group, key=lambda k: (-self.counts[k], k))[0]
            rivals = [self.counts.get(f, 0) for f in acute_index.get(plain, ())]
            if rivals and max(rivals) >= self.counts[best]:
                continue
            self.accepted.add(best)
        self.report["cliticCandidates"] = len(candidates)
        self.report["cliticFormsAccepted"] = len(self.accepted)

    # §8.4 step 2
    def _merge_accents(self):
        targets = set(self.forms_by_key) | self.accepted
        index = {}
        for t in targets:
            index.setdefault(t.translate(STRIP_ALL), []).append(t)
        merged = {}
        inflected_merged = 0
        for key in sorted(self.counts):
            inflected = key in self.inflected_keys and key not in self.accepted
            if self.is_form(key) and key not in self.variant_keys and not inflected:
                continue
            options = [t for t in index.get(key.translate(STRIP_ALL), ()) if t != key and self._adds_accents(key, t)
                       and t not in self.variant_keys]
            if not options:
                continue
            target = sorted(options, key=lambda t: (-self.counts.get(t, 0), t))[0]
            if self.is_form(key) and key not in self.variant_keys:
                # an inflected form without a lemma of its own ("dias": plural of dia, "llamame": a voseo form)
                # yields to an accented word that is far more frequent
                if self.counts.get(target, 0) < INFLECTED_MERGE_RATIO * self.counts[key]:
                    continue
                inflected_merged += 1
            merged[key] = target
        for key, target in merged.items():
            self.counts[target] = self.counts.get(target, 0) + self.counts.pop(key)
            self.forms_by_key.pop(key, None)
        self.merged = merged
        self.report["accentMerged"] = len(merged)
        self.report["accentMergedVariantPages"] = sum(1 for k in merged if k in self.variant_keys)
        self.report["accentMergedInflectedForms"] = inflected_merged

    @staticmethod
    def _adds_accents(key, target):
        if len(key) != len(target):
            return False
        for a, b in zip(key, target):
            if a != b and b not in ADD_ACCENT.get(a, ""):
                return False
        return True

    # ------------------------------------------------------------------ coverage (§8.2 point 3)

    def coverage(self, top=20000):
        keys = sorted(self.counts, key=lambda k: (-self.counts[k], k))[:top]
        known = sum(1 for k in keys if self.is_form(k))
        return known / len(keys)

    # ------------------------------------------------------------------ case (§8.3)

    def case_decision(self, key):
        if key in self._case_cache:
            return self._case_cache[key]
        variants = {f: c for f, c in self.stats.evidence(key).items() if c >= 2}
        dict_forms = self.forms_by_key.get(key, set())
        if not dict_forms and key in self.accepted:
            dict_forms = {key}
        result = casing.decide(key, variants, dict_forms)
        self._case_cache[key] = result
        return result

    # ------------------------------------------------------------------ selection (§8.5)

    def _missing_space(self, key):
        for i in range(1, len(key) - 1):
            a, b = key[:i], key[i:]
            if len(a) == 1 and a not in ONE_LETTER_WORDS:
                continue
            if len(b) < 2:
                continue
            if self.is_form(a) and self.is_form(b) and self.count(a) >= SPLIT_PART_MIN and self.count(b) >= SPLIT_PART_MIN:
                return True
        return False

    def fused_candidates(self):
        """Spanish forms that also split into two frequent Spanish forms ("aveces" = a + veces, "osea" = o + sea):
        (key, phrase) pairs for the phrase check of [set_phrase_counts]."""
        out = []
        for key in sorted(self.counts):
            if not self.is_form(key) or len(key) < 4:
                continue
            for i in range(1, len(key) - 1):
                a, b = key[:i], key[i:]
                if len(a) == 1 and a not in ONE_LETTER_WORDS or len(b) < 2:
                    continue
                if self.is_form(a) and self.is_form(b) and self.count(a) >= SPLIT_PART_MIN and self.count(b) >= SPLIT_PART_MIN:
                    out.append((key, a + " " + b))
        return out

    def set_phrase_counts(self, token_counts, phrase_counts, sentence_tokens):
        """§8.5 step 5 extended to Spanish forms that are missing-space artefacts of the subtitles:
        - a page of either dump calls the key a misspelling or alternative form of the phrase ("osea": "o sea",
          "enserio": "en serio", "talvez": "tal vez");
        - or the key is an inflected form without a lemma of its own ("aveces" of avezar), its second part is no
          clitic pronoun (so "pasarla", "dejala" stay), its phrase is at least FUSED_RATIO times as frequent as the
          key in the edited sentence sources (and at least FUSED_MIN_PHRASE times), and the key's share of those
          sources is below FUSED_MAX_SHARE of its share of the subtitles (its subtitle count is not explained by
          running text)."""
        self.fused = set()
        rows = []
        sub_total = sum(self.counts.values())
        for key, phrase in self.fused_candidates():
            t = token_counts.get(key, 0)
            p = phrase_counts.get(phrase, 0)
            share = (t / max(sentence_tokens, 1)) / (self.counts[key] / sub_total)
            b = phrase.split(" ")[1]
            stated = key in self.phrase_keys
            measured = (key in self.inflected_keys and b not in CLITICS and p >= max(FUSED_MIN_PHRASE, FUSED_RATIO * t)
                        and share < FUSED_MAX_SHARE)
            if stated or measured:
                self.fused.add(key)
                rows.append([key, phrase, t, p, round(share, 3), "wiktionary" if stated else "measured"])
        self.report["fusedFormsDropped"] = sorted(rows, key=lambda r: -self.count(r[0]))[:60]
        self.report["fusedFormsDroppedCount"] = len(self.fused)

    def _misaccented(self, key):
        """An unknown spelling with an acute accent whose spelling without that accent is a word ("vámos")."""
        if not any(c in ACUTE for c in key):
            return False
        return key.translate(STRIP_ACUTE) in self._plain_forms

    def select(self, key, dropped):
        count = self.count(key)
        form = self.is_form(key)
        if not plausible(key) and not (form and 2 <= len(key) <= 27):
            dropped["implausible"] += 1
            return None
        if len(key) == 1 and key not in ONE_LETTER_WORDS:
            dropped["oneLetter"] += 1
            return None
        if not form and any(c not in SPANISH_PLAIN for c in key):
            dropped["nonSpanishCharacters"] += 1
            return None
        if key in self.fused:
            dropped["missingSpace"] += 1
            return None
        if form:
            if count >= 2:
                return self.case_decision(key) or [(key, 1.0)]
            dropped["formBelowCount2"] += 1
            return None
        if self._missing_space(key):
            dropped["missingSpace"] += 1
            if len(dropped["missingSpaceExamples"]) < 40 and count >= 500:
                dropped["missingSpaceExamples"].append(key)
            return None
        if count >= KEEP_MIN_COUNT:
            if self._misaccented(key):
                dropped["misaccented"] += 1
                return None
            if key in self.titles and count < FOREIGN_MAX:
                dropped["foreign"] += 1
                return None
            dec = self.case_decision(key)
            if dec:
                return dec
            titles = self.titles_by_key.get(key, set())
            if key in titles or not titles:
                return [(key, 1.0)]
            best = sorted(titles, key=lambda f: (casing.case_kind_rank(f), f))[0]
            return [(best, 1.0)]
        dropped["rare"] += 1
        return None

    def build(self):
        self.titles_by_key = {}
        for t in self.titles:
            self.titles_by_key.setdefault(jlower(t), set()).add(t)
        self._plain_forms = {k.translate(STRIP_ACUTE) for k in self.forms_by_key if not any(c in ACUTE for c in k)}
        dropped = {k: 0 for k in ("implausible", "oneLetter", "nonSpanishCharacters", "formBelowCount2",
                                  "missingSpace", "misaccented", "foreign", "rare")}
        dropped["missingSpaceExamples"] = []
        entries = {}
        kept_unknown = 0
        for key in sorted(self.counts):
            if not (2 <= len(key) <= 27 or len(key) == 1):
                continue
            sel = self.select(key, dropped)
            if not sel:
                continue
            if not self.is_form(key):
                kept_unknown += 1
            count = self.counts[key]
            forms = []
            seen = set()
            for f, share in sel:
                if f in seen or jlower(f) != key:
                    continue
                seen.add(f)
                forms.append((f, freq_class(count * share, self.scale)))
            forms.sort(key=lambda fc: -fc[1])
            if forms:
                entries[key] = forms
        self.report["dropped"] = dropped
        self.report["keptNotAForm"] = kept_unknown
        self.report["acceptedCliticKeys"] = sum(1 for k in entries if k in self.accepted)
        return entries


def stock_names(stats, subtitle_counts, top=30, scan=100, ratio=10.0):
    """§5 step 3 for es: the base list plus translated stock names that are clearly over-represented in Tatoeba.

    Lists the [top] most frequent capitalised Tatoeba tokens; a token among the [scan] most frequent ones is added when
    its share of the Tatoeba tokens is at least [ratio] times the share of its key in the subtitles. Returns the final
    set, the top list (token, Tatoeba count, subtitle count, ratio) and the added names."""
    from pairs import ES_STOCK_BASE
    ranked = sorted(stats.capitalised_tatoeba.items(), key=lambda kv: (-kv[1], kv[0]))
    tat_total = stats.tatoeba_tokens
    sub_total = sum(subtitle_counts.values())
    rows = []
    for tok, c in ranked[:scan]:
        sub = subtitle_counts.get(jlower(tok), 0)
        rows.append((tok, c, sub, round((c / tat_total) / max(sub / sub_total, 1e-12), 1)))
    added = sorted(tok for tok, _, _, r in rows if r >= ratio and tok not in ES_STOCK_BASE)
    return set(ES_STOCK_BASE) | set(added), [list(r) for r in rows[:top]], added
