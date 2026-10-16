"""French word list, elision prefix table and pair profile (05b-dictionaries §1.8, §11).

Inputs: the FrequencyWords fr list (counts, including its elision prefixes, apostrophe and hyphen tokens, §11.1), the
classified pages of the French Wiktionary (wiktionary_fr.py; the English dump's French sections only if the French dump
alone recognises less than 95 % of the top 20 000 keys, §11.2) and the sentence sources (Tatoeba, Common Voice), which
give the running-text case evidence (§11.3), the sentence counts of the spelling, apostrophe and elision steps
(§11.4–§11.7) and the pairs (§5). Every rule and threshold is recorded in `self.report`, which the driver writes to
tools/dictionary-build.json.
"""

import math
import re

import casing
from common import freq_class, jlower
from wiktionary_pt import strip_diacritics

# 05 §1.1 ELISION_PREFIXES (notes on fr-FR), in order of their frequency in the frequency source
PREFIXES = "c' l' j' d' qu' n' t' m' s' jusqu' lorsqu' puisqu' quoiqu' ç'".split()
PREFIX_SET = set(PREFIXES)
PREFIX_LETTERS = {p[:-1]: p for p in PREFIXES}
# §11.6 step 3: the letters of the elision prefixes with two or more letters are no words
PREFIX_LETTER_KEYS = ("qu", "jusqu", "lorsqu", "puisqu", "quoiqu")
# §11.7 rule 1: the full words of the prefixes
UNELIDED = {"c'": ("ce",), "l'": ("le", "la"), "j'": ("je",), "d'": ("de",), "qu'": ("que",), "n'": ("ne",),
            "s'": ("se", "si"), "m'": ("me",), "t'": ("te",), "jusqu'": ("jusque",), "lorsqu'": ("lorsque",),
            "puisqu'": ("puisque",), "quoiqu'": ("quoique",), "ç'": ("ça",)}

EXCLUDED_LETTERS = set("İıŉſĸªº")
FRENCH_PLAIN = set("abcdefghijklmnopqrstuvwxyzàâæçéèêëîïôœùûüÿ")
VOWELS = set("aeiouy" "äöü" "àáâãåæèéêëìíîïòóôõøœùúûýÿ")
ONE_LETTER_WORDS = ("a", "à", "y")
# §11.5 (b): the parts that may follow the first part of an inversion, an imperative or a demonstrative word
POSTPOSED = set("moi toi soi le la les lui leur nous vous y en il ils elle elles on je tu ce là ci".split())
EUPHONIC_BEFORE = {"il", "elle", "on"}

# thresholds of the first build (05b §11.4–§11.7, open point 19)
DOMINATED_MIN = 100
DOMINATED_RATIO = 100
DOMINATED_MIN_COUNT = 40
LOSS_CLASS_MIN = 1000
APOSTROPHE_WORD_MIN = 40
COMPOUND_MIN = 1000
ALIAS_RARER = 100
SPLIT_PART_MIN = 20_000
SPLIT_CANDIDATE_PART_MIN = 10_000   # candidate phrases are counted before §11.4 changes the part counts
PHRASE_MIN = 10
PHRASE_RATIO = 10
FUSED_ELISION_MIN = 10
FUSED_ELISION_RATIO = 10
ELIDED_PAIR_RATIO = 10
FOREIGN_MAX = 100_000
KEEP_MIN_COUNT = 40
RUNNING_KEEP_MIN = 2
RUNNING_TARGET_MIN = 10
COVERAGE_ENGLISH_DUMP = 0.95

LETTER_RUN = re.compile(r"[^\W\d_]+")
IS_LETTER = re.compile(r"[^\W\d_]")
RUNNING_BREAK = set(".!?:\"“”«»([")


def _allowed_key_chars():
    chars = set(FRENCH_PLAIN)
    for cp in range(0xC0, 0x180):
        ch = chr(cp)
        if not ch.isalpha() or ch in EXCLUDED_LETTERS:
            continue
        low = ch.lower()
        if len(low) == 1 and low not in EXCLUDED_LETTERS:
            chars.add(low)
    return chars


ALLOWED = _allowed_key_chars()


def expand(s):
    """Diacritics removed and ligatures expanded (œ → oe, æ → ae): the accent class of §11.4."""
    return strip_diacritics(s.replace("œ", "oe").replace("æ", "ae").replace("Œ", "Oe").replace("Æ", "Ae"))


def accent_class(key):
    return expand(key)


def diacritic_positions(key):
    """Positions (in the key) of letters with a diacritic or a ligature."""
    return frozenset(i for i, c in enumerate(key) if expand(c) != c)


def has_diacritics(key):
    return bool(diacritic_positions(key))


def starts_vowel_or_h(word):
    if not word:
        return False
    c = word[0].lower()
    return c in "œæ" or strip_diacritics(c) in "aeiouyh"


def plausible(key):
    k = key.replace("'", "")
    if len(k) == 1:
        return key in ONE_LETTER_WORDS
    if not 2 <= len(k) <= 27:
        return False
    if not any(c in VOWELS for c in k):
        return False
    for i in range(len(k) - 2):
        if k[i] == k[i + 1] == k[i + 2]:
            return False
    return True


def plain_key_ok(key):
    return bool(key) and all(c in ALLOWED for c in key)


def alias_of(form):
    """§11.5: the alias key of a compound or apostrophe word: its letters alone, accents kept."""
    return jlower(form).replace("-", "").replace("'", "")


def tokens(text, apostrophe_words):
    """§5 step 2 for French (’ → ' first): yields ("w", word), ("p", prefix) and ("c", char) for every other non-space
    character. A letter run + apostrophe + letter whose run + ' is an elision prefix is a prefix token; a letter run +
    apostrophe + letter run that is an apostrophe word of §11.5 is one word; every other apostrophe and every hyphen is
    an ordinary character."""
    text = text.replace("’", "'")
    i = 0
    n = len(text)
    while i < n:
        m = LETTER_RUN.match(text, i)
        if m:
            run = m.group(0)
            j = m.end()
            if j + 1 < n and text[j] == "'" and IS_LETTER.match(text[j + 1]):
                m2 = LETTER_RUN.match(text, j + 1)
                whole = run + "'" + m2.group(0)
                if jlower(whole) in apostrophe_words:
                    yield "w", whole
                    i = m2.end()
                    continue
                cand = jlower(run) + "'"
                if cand in PREFIX_SET:
                    yield "p", cand
                    i = j + 1
                    continue
            yield "w", run
            i = j
            continue
        ch = text[i]
        i += 1
        if not ch.isspace():
            yield "c", ch


class SentenceStats:
    """Pass over the sentence sources (§11.3, §11.4): running-text case evidence of letter runs (as §8.3; a capital
    without its accent counts for the form with the accented capital), the weighted sentence counts s(x) of words and
    elided phrases (§5 step 2 tokens) and the counts of candidate phrases (two words separated by whitespace only).
    Stock names count as unknown words in Tatoeba only (§5 step 3)."""

    def __init__(self, stock, phrase_candidates, apostrophe_words):
        self.stock = stock
        self.phrases = set(phrase_candidates)
        self.phrase_first = {p.split(" ")[0] for p in self.phrases}
        self.apostrophe_words = apostrophe_words
        self.rt_conv = {}
        self.rt_all = {}
        self.s_word = {}
        self.s_elided = {}
        self.s_phrase = {}
        self.s_total = 0

    def add(self, text, weight, conversational, tatoeba):
        last_end = None
        for m in LETTER_RUN.finditer(text):
            tok = m.group(0)
            if last_end is not None and not (tatoeba and tok in self.stock):
                between = text[last_end:m.start()]
                if not any(c in RUNNING_BREAK for c in between):
                    self.rt_all[tok] = self.rt_all.get(tok, 0) + 1
                    if conversational:
                        self.rt_conv[tok] = self.rt_conv.get(tok, 0) + 1
            last_end = m.end()
        prev_word = None
        prefix = None
        for kind, tok in tokens(text, self.apostrophe_words):
            if kind == "c":
                prev_word = None
                prefix = None
                continue
            if kind == "p":
                prev_word = None
                prefix = tok
                continue
            if tatoeba and tok in self.stock:
                prev_word = None
                prefix = None
                continue
            key = jlower(tok)
            self.s_total += weight
            self.s_word[key] = self.s_word.get(key, 0) + weight
            if prefix is not None:
                pk = (prefix, key)
                self.s_elided[pk] = self.s_elided.get(pk, 0) + weight
            elif prev_word is not None and prev_word in self.phrase_first:
                p = prev_word + " " + key
                if p in self.phrases:
                    self.s_phrase[p] = self.s_phrase.get(p, 0) + weight
            prev_word = key
            prefix = None

    def finish(self, forms_by_key):
        """[forms_by_key]: key -> cased titles of French forms. A capitalised token whose capital lacks its accent
        ("Etat") counts for the form with the accented capital ("État") when its own key is no French form."""
        unaccented = {}
        for key, forms in forms_by_key.items():
            if not key or expand(key[0]) == key[0]:
                continue
            for f in forms | {key}:
                cap = f[0].upper() + f[1:]
                bare = expand(cap[0]) + cap[1:]
                if len(bare) == len(cap):
                    unaccented.setdefault(bare, cap)
        self.capitals_moved = 0
        for d in (self.rt_all, self.rt_conv):
            for tok in [t for t in d if t in unaccented]:
                if jlower(tok) in forms_by_key:
                    continue
                target = unaccented[tok]
                d[target] = d.get(target, 0) + d.pop(tok)
                if d is self.rt_all:
                    self.capitals_moved += 1
        self.forms_by_key = {}
        for tok in self.rt_all:
            self.forms_by_key.setdefault(jlower(tok), set()).add(tok)

    def evidence(self, key):
        forms = self.forms_by_key.get(key, ())
        conv = {f: self.rt_conv[f] for f in forms if f in self.rt_conv}
        if sum(conv.values()) >= 10:
            return conv
        return {f: self.rt_all[f] for f in forms if f in self.rt_all}

    def running(self, key):
        return sum(self.rt_all.get(f, 0) for f in self.forms_by_key.get(key, ()))


class FrenchBuilder:
    def __init__(self, freq_path, fr_pages, en_pages=None):
        """[fr_pages]: (titles {title: language codes}, {title: kinds}); [en_pages]: ({title: kinds}) or None."""
        self.report = {}
        self.fr_titles, self.fr_kinds = fr_pages
        self.en_kinds = en_pages or {}
        # foreign-word detection uses the titles of the French dump that are not French forms (§11.6 step 8)
        self.foreign_titles = {t for t, langs in self.fr_titles.items() if t == jlower(t) and "'" not in t
                               and "-" not in t and langs - {"fr", "conv"}}
        self.english_titles = {t for t, langs in self.fr_titles.items() if t == jlower(t) and "en" in langs}
        self._read_counts(freq_path)
        self._collect_pages()
        self.stats = None

    # ------------------------------------------------------------------ §11.1 counts

    def _read_counts(self, freq_path):
        self.counts = {}
        self.apostrophe_tokens = {}
        self.hyphen_tokens = {}
        self.prefix_counts = {p: 0 for p in PREFIXES}
        total = 0
        credits = 0
        dropped = {"digit": 0, "characters": 0, "hyphenNotLetters": 0}
        lines = 0
        hyphen = {"types": 0, "occurrences": 0, "splitTypes": 0, "splitOccurrences": 0, "creditedParts": 0}
        with open(freq_path, encoding="utf-8") as f:
            for line in f:
                parts = line.split()
                if len(parts) != 2:
                    continue
                lines += 1
                tok, c = parts[0].replace("’", "'"), int(parts[1])
                total += c
                if "-" in tok:
                    hyphen["types"] += 1
                    hyphen["occurrences"] += c
                if any(ch.isdigit() for ch in tok):
                    dropped["digit"] += c
                    continue
                if "'" in tok:
                    self.apostrophe_tokens[tok] = self.apostrophe_tokens.get(tok, 0) + c
                    continue
                if "-" in tok:
                    pieces = [p for p in tok.split("-") if p]
                    if not pieces or not all(LETTER_RUN.fullmatch(p) for p in pieces):
                        dropped["hyphenNotLetters"] += c
                        continue
                    hyphen["splitTypes"] += 1
                    hyphen["splitOccurrences"] += c
                    for p in pieces:
                        key = jlower(p)
                        credits += c
                        hyphen["creditedParts"] += c
                        if plain_key_ok(key):
                            self.counts[key] = self.counts.get(key, 0) + c
                    if len(pieces) >= 2 and "-" in tok.strip("-"):
                        ht = jlower(tok.strip("-"))
                        self.hyphen_tokens[ht] = self.hyphen_tokens.get(ht, 0) + c
                    continue
                key = jlower(tok)
                if key != tok or not plain_key_ok(key):
                    dropped["characters"] += c
                    continue
                self.counts[key] = self.counts.get(key, 0) + c
        self.list_total = total
        self.corpus_tokens = total + credits
        self.report.update({"frequencyListLines": lines, "frequencyListTokens": total, "hyphenCreditedParts": credits,
                            "corpusTokens": total + credits, "hyphenTokens": hyphen, "droppedTokenCounts": dropped})

    # ------------------------------------------------------------------ §11.2 page kinds per key

    def _collect_pages(self):
        self.forms_by_key = {}      # key -> cased titles that are French forms (letters only)
        self.form_dumps = {}
        self.pointer_target = {}
        self.letter_keys = set()
        self.inflected = {}
        self.phrase_pages = set()
        self.proper_only = set()
        self.compound_pages = {}    # lower-case hyphen title -> title (French forms with an inner hyphen)
        self.apostrophe_pages = {}  # lower-case title with an inner apostrophe (no elision) -> title
        self.elision_pages = set()
        dumps = [("fr", self.fr_kinds)]
        if self.en_kinds:
            dumps.append(("en", self.en_kinds))
        for dump, pages in dumps:
            for title, k in pages.items():
                key = jlower(title)
                if title.endswith("'"):
                    if dump == "fr" and (k["elision"] or k["form"]):
                        self.elision_pages.add(key)
                    continue
                if "-" in key:
                    if k["form"] and dump == "fr":
                        self.compound_pages.setdefault(key, title)
                    continue
                if "'" in key:
                    first = key.split("'")[0] + "'"
                    if k["form"] and first not in PREFIX_SET and dump == "fr":
                        self.apostrophe_pages.setdefault(key, title)
                    continue
                if not plain_key_ok(key):
                    continue
                if k["phrase"] and not k["form"]:
                    self.phrase_pages.add(key)
                if k["form"]:
                    self.forms_by_key.setdefault(key, set()).add(title)
                    self.form_dumps.setdefault(key, set()).add(dump)
                    self.inflected.setdefault(key, {})[dump] = k["inflected"]
                elif k["pointer"]:
                    self.pointer_target.setdefault(key, k["pointer"])
                elif k["letter"]:
                    self.letter_keys.add(key)
        for key in list(self.pointer_target):
            if key in self.forms_by_key:
                del self.pointer_target[key]
        self.letter_keys -= set(self.forms_by_key)
        self.accepted = {}
        self.report["wiktionaryPages"] = {
            "formKeys": len(self.forms_by_key),
            "formKeysFrenchDump": sum(1 for v in self.form_dumps.values() if "fr" in v),
            "formKeysEnglishDump": sum(1 for v in self.form_dumps.values() if "en" in v),
            "pointerKeys": len(self.pointer_target),
            "letterKeys": sorted(self.letter_keys),
            "fusedPhrasePages": sorted(self.phrase_pages)[:200],
            "compoundPages": len(self.compound_pages),
            "apostrophePages": len(self.apostrophe_pages),
        }
        # §11.2 item 5: every prefix of 05 §1.1 has an elision page
        self.report["elisionPrefixesWithoutPage"] = [p for p in PREFIXES if p not in self.elision_pages]
        self.report["elisionPagesNotPrefixes"] = sorted(p for p in self.elision_pages if p not in PREFIX_SET
                                                        and self.apostrophe_tokens.get(p, 0) >= 500)

    def is_form(self, key):
        return key in self.forms_by_key or key in self.accepted

    def form_in(self, key, dump):
        return dump in self.form_dumps.get(key, ())

    def lower_form(self, key):
        return key in self.forms_by_key.get(key, ()) or key in self.accepted

    def name_like(self, key):
        ev = self.stats.evidence(key)
        low = ev.get(key, 0)
        return sum(ev.values()) - low > low

    def count(self, key):
        return self.counts.get(key, 0)

    def apostrophe_word_candidates(self):
        """Lower-case apostrophe words (§11.5) for the tokeniser: French titles with an inner apostrophe whose first part
        is no elision prefix."""
        return set(self.apostrophe_pages)

    # ------------------------------------------------------------------ §11.1 apostrophe tokens

    def apostrophe_rules(self, stats):
        """§11.1 rules 1–4 for the tokens with an apostrophe (needs the sentence counts of [stats] for rule 2)."""
        self.stats = stats
        s = stats.s_word
        self.apostrophe_counts = {k: 0 for k in self.apostrophe_pages}
        cut = {}
        dropped = {}
        whole = {}
        firsts = {}
        for w in self.apostrophe_pages:
            firsts.setdefault(w.split("'")[0] + "'", []).append(w)
        for tok in sorted(self.apostrophe_tokens):
            c = self.apostrophe_tokens[tok]
            low = jlower(tok)
            if low in PREFIX_SET:
                self.prefix_counts[low] += c
                continue
            if low.endswith("'") and low.count("'") == 1 and low in firsts and len(low) >= 3:
                # (single letters + apostrophe such as "y'", "p'", "o'" are colloquial or name tokens: rule 4)
                # 2 the cut first part of apostrophe words: shared by their sentence counts, taken from the remainders
                words = firsts[low]
                weights = {w: s.get(w, 0) for w in words}
                tot = sum(weights.values())
                if tot > 0:
                    moved = []
                    for w in sorted(words):
                        share = c * weights[w] / tot
                        if share <= 0:
                            continue
                        self.apostrophe_counts[w] += share
                        rest = w.split("'", 1)[1]
                        if rest in self.counts:
                            self.counts[rest] = max(0, self.counts[rest] - share)
                        moved.append([w, round(share)])
                    cut[tok] = [c, moved]
                    continue
            if low in self.apostrophe_pages:
                # 3 a whole apostrophe word
                self.apostrophe_counts[low] += c
                whole[low] = whole.get(low, 0) + c
                continue
            # 4 everything else
            dropped[tok] = c
        for k in self.counts:
            if isinstance(self.counts[k], float):
                self.counts[k] = int(round(self.counts[k]))
        self.apostrophe_counts = {k: int(round(v)) for k, v in self.apostrophe_counts.items()}
        top_dropped = sorted(dropped.items(), key=lambda kv: -kv[1])
        self.report.update({
            "prefixCounts": dict(self.prefix_counts),
            "apostropheCutParts": cut,
            "apostropheWholeTokens": len(whole),
            "apostropheWholeTokensCount": sum(whole.values()),
            "apostropheTokensDropped": len(dropped),
            "apostropheTokensDroppedCount": sum(dropped.values()),
            "apostropheTokensDroppedExamples": [list(r) for r in top_dropped[:40]],
        })

    # ------------------------------------------------------------------ §11.4

    def spellings(self, stats):
        self.stats = stats
        self.before_merge = dict(self.counts)
        self._loss_rate(self.before_merge)
        self._merge_accents()
        self._dominated()
        self._loss_transfer()

    def _class_index(self):
        index = {}
        for k in set(self.counts) | set(self.forms_by_key):
            index.setdefault(accent_class(k), set()).add(k)
        return index

    def _class_forms(self, members):
        return [t for t in members if self.is_form(t) and (len(t) > 1 or t in ONE_LETTER_WORDS)]

    def _merge_accents(self):
        """§11.4 step 1: a key that is no French form goes to the form of its accent class with diacritics and
        ligatures at the same positions, else to the most frequent form of the class. Guards: an English title or a
        name (mostly capitalised in running text) only into a form at least as frequent; a spelling the sentence
        sources use at least as often as the target (as the Italian build)."""
        index = self._class_index()
        merged = {}
        kept_guard = []
        kept_running = []
        for key in sorted(self.counts):
            if self.is_form(key):
                continue
            options = [t for t in self._class_forms(index.get(accent_class(key), ())) if t != key]
            if not options:
                continue
            pos = diacritic_positions(key)
            same = [t for t in options if diacritic_positions(t) == pos and len(t) == len(key)]
            pool = same or options
            target = sorted(pool, key=lambda t: (-self.counts.get(t, 0), t))[0]
            if (key in self.english_titles or self.name_like(key)) and self.counts.get(target, 0) < self.counts[key]:
                kept_guard.append((key, target, self.counts[key], self.counts.get(target, 0)))
                continue
            rk = self.stats.running(key)
            rt = self.stats.running(target)
            if rk >= RUNNING_KEEP_MIN and rk >= rt:
                kept_running.append((key, target, self.counts[key], rk))
                continue
            merged[key] = target
        merged_counts = {}
        for key, target in sorted(merged.items()):
            merged_counts[key] = self.counts[key]
            self.counts[target] = self.counts.get(target, 0) + self.counts.pop(key)
        self.merged = merged
        lig = {k: v for k, v in merged.items() if "œ" in v or "æ" in v}
        self.report.update({
            "accentMerged": len(merged),
            "accentMergedPointerPages": sum(1 for k in merged if k in self.pointer_target),
            "accentMergedCount": sum(merged_counts.values()),
            "accentMergedLigatures": len(lig),
            "accentMergedLigaturesCount": sum(merged_counts[k] for k in lig),
            "accentMergedExamples": [[k, merged[k], merged_counts[k]] for k in sorted(merged, key=lambda k: (-merged_counts[k], k))[:80]],
            "accentMergedLigatureExamples": [[k, lig[k], merged_counts[k]] for k in sorted(lig, key=lambda k: (-merged_counts[k], k))[:30]],
            "accentMergeSkippedGuard": len(kept_guard),
            "accentMergeSkippedGuardExamples": [list(r) for r in sorted(kept_guard, key=lambda r: -r[2])[:40]],
            "accentMergeSkippedRunningText": len(kept_running),
            "accentMergeSkippedRunningTextExamples": [list(r) for r in sorted(kept_running, key=lambda r: -r[2])[:40]],
        })

    def _dominated(self):
        """§11.4 step 2: dominated spellings keep class 0 and give their counts to their counterparts."""
        index = self._class_index()
        s = self.stats.s_word
        el = self.stats.s_elided
        self.zero_forms = set()
        rows = []
        for key in sorted(self.counts):
            c = self.counts[key]
            if c < DOMINATED_MIN_COUNT or not self.is_form(key):
                continue
            counterparts = []
            members = [a for a in self._class_forms(index.get(accent_class(key), ())) if a != key]
            pos = diacritic_positions(key)
            if not pos:
                # (a) plain spelling: the accented forms of its class
                for a in members:
                    counterparts.append((a, s.get(a, 0), a))
            else:
                # (b) wrong accents: forms with diacritics at exactly the same positions
                for a in members:
                    if len(a) == len(key) and diacritic_positions(a) == pos:
                        counterparts.append((a, s.get(a, 0), a))
            # (c) elided spellings of its letters
            for letters, prefix in PREFIX_LETTERS.items():
                if key.startswith(letters) and len(key) > len(letters):
                    r = key[len(letters):]
                    if self.is_form(r) and starts_vowel_or_h(r) and (len(r) > 1 or r in ONE_LETTER_WORDS):
                        counterparts.append((r, el.get((prefix, r), 0), prefix + r))
            if not counterparts:
                continue
            total = sum(x[1] for x in counterparts)
            own = s.get(key, 0)
            if total < DOMINATED_MIN or total < DOMINATED_RATIO * own:
                continue
            for target, sc, _ in counterparts:
                if sc:
                    self.counts[target] = self.counts.get(target, 0) + c * sc / total
            self.counts[key] = 0
            self.zero_forms.add(key)
            rows.append((key, own, [[lab, sc] for _, sc, lab in counterparts if sc], c, "b" if pos else "ac"))
        for k in self.counts:
            if isinstance(self.counts[k], float):
                self.counts[k] = int(round(self.counts[k]))
        rows.sort(key=lambda r: -r[3])
        self.report.update({
            "dominatedSpellingsClass0": len(self.zero_forms),
            "dominatedSpellingsWrongAccent": sum(1 for r in rows if r[4] == "b"),
            "dominatedSpellingsList": [[k, own, cps, c] for k, own, cps, c, _ in rows],
        })

    def _loss_rate(self, counts):
        """§11.4 step 3 (as Italian §10.4 step 6): classes whose plain spelling is no form, with exactly one accented
        form and ≥ 1000 occurrences; ligature classes are left out (the subtitles write "oe" for "œ" about half of the
        time, which is no accent loss, §1.8)."""
        index = {}
        for k in set(counts) | set(self.forms_by_key):
            index.setdefault(accent_class(k), set()).add(k)
        plain_sum = acc_sum = classes = 0
        for cls, members in index.items():
            if self.is_form(cls) or len(cls) < 2:
                continue
            accented = [t for t in self._class_forms(members) if has_diacritics(t)]
            if len(accented) != 1 or len(accented[0]) != len(cls):
                continue
            p = counts.get(cls, 0)
            a = counts.get(accented[0], 0)
            if p + a < LOSS_CLASS_MIN:
                continue
            plain_sum += p
            acc_sum += a
            classes += 1
        self.loss_rate = plain_sum / (plain_sum + acc_sum) if plain_sum + acc_sum else 0.0
        self.report.update({"accentLossRate": round(self.loss_rate, 4), "accentLossClasses": classes,
                            "accentLossPlainOccurrences": plain_sum, "accentLossAccentedOccurrences": acc_sum})

    def _loss_transfer(self):
        f = self.loss_rate
        factor = f / (1 - f)
        index = self._class_index()
        moved_total = 0
        rows = []
        for cls in sorted(index):
            p = cls
            if p not in self.counts or not self.is_form(p) or p in self.zero_forms or self.counts[p] <= 0:
                continue
            accented = sorted(t for t in self._class_forms(index[cls]) if has_diacritics(t) and len(t) == len(p))
            if not accented:
                continue
            want = {a: int(round(self.counts.get(a, 0) * factor)) for a in accented}
            total = sum(want.values())
            if total <= 0:
                continue
            avail = self.counts[p]
            if total > avail:
                scale = avail / total
                want = {a: int(math.floor(v * scale)) for a, v in want.items()}
            for a, v in want.items():
                self.counts[a] = self.counts.get(a, 0) + v
                self.counts[p] -= v
                moved_total += v
            rows.append((p, sum(want.values()), [[a, v] for a, v in want.items()]))
        rows.sort(key=lambda r: -r[1])
        self.report.update({"accentLossMoved": moved_total, "accentLossTransfers": len(rows),
                            "accentLossExamples": [list(r) for r in rows[:40]]})

    # ------------------------------------------------------------------ §11.5

    def compounds(self):
        """§11.5: apostrophe words (count ≥ 40) and hyphen compounds (count ≥ 1000) under their alias keys."""
        # apostrophe words
        self.apostrophe_words = {}
        for w, c in sorted(self.apostrophe_counts.items()):
            if c >= APOSTROPHE_WORD_MIN and plausible(w) and all(ch in ALLOWED or ch == "'" for ch in w):
                self.apostrophe_words[w] = self.apostrophe_pages[w].replace("’", "'")
        # hyphen compounds: (a) a French page, (b) an inversion / postposed pronoun / demonstrative particle
        kinds = {}
        for tok in self.hyphen_tokens:
            parts = tok.split("-")
            if any(parts[i] == parts[i + 1] for i in range(len(parts) - 1)):
                continue
            if tok in self.compound_pages:
                kinds[tok] = ("page", self.compound_pages[tok])
                continue
            if all(self.is_form(p) for i, p in enumerate(parts) if not (p == "t" and 0 < i < len(parts) - 1)) and all(
                    p in POSTPOSED or (p == "t" and i + 1 < len(parts) and parts[i + 1] in EUPHONIC_BEFORE and i > 0)
                    for i, p in enumerate(parts) if i > 0) and parts[0] != "t":
                kinds[tok] = ("inversion", tok)
                continue
            # the tokeniser cut an elision prefix ("est-à-dire" of "c'est-à-dire")
            for p in PREFIXES:
                if p + tok in self.compound_pages:
                    kinds[tok] = ("page", self.compound_pages[p + tok])
                    break
        counts = {t: self.hyphen_tokens[t] for t in kinds}
        by_class = {}
        for t in kinds:
            by_class.setdefault(accent_class(t), []).append(t)
        lost = []
        for tok, c in sorted(self.hyphen_tokens.items()):
            if tok in kinds:
                continue
            targets = [t for t in by_class.get(accent_class(tok), ()) if t != tok]
            if targets:
                target = sorted(targets, key=lambda t: (-counts[t], t))[0]
                counts[target] += c
                lost.append([tok, target, c])
        self.compound_forms = {}
        rows = []
        for tok in sorted(kinds):
            if counts[tok] < COMPOUND_MIN:
                continue
            kind, title = kinds[tok]
            form = title.replace("’", "'")
            self.compound_forms[form] = counts[tok]
            rows.append([form, counts[tok], kind])
        rows.sort(key=lambda r: -r[1])
        self.report.update({
            "apostropheWords": len(self.apostrophe_words),
            "apostropheWordsList": sorted([w, self.apostrophe_counts[w]] for w in self.apostrophe_words),
            "hyphenCompounds": len(self.compound_forms),
            "hyphenCompoundsWithPage": sum(1 for r in rows if r[2] == "page"),
            "hyphenCompoundsList": rows,
            "hyphenLostAccentMerged": sorted(lost, key=lambda r: -r[2])[:60],
        })

    # ------------------------------------------------------------------ §11.3 case

    def case_decision(self, key):
        if key in self.zero_forms and key in self.forms_by_key.get(key, ()):
            return [(key, 1.0)]
        variants = {f: c for f, c in self.stats.evidence(key).items() if c >= 2}
        dict_forms = self.forms_by_key.get(key, set())
        if not dict_forms and key in self.accepted:
            dict_forms = {key}
        return casing.decide(key, variants, dict_forms)

    # ------------------------------------------------------------------ §11.6 step 6 candidates

    def _splits(self, key, part_min):
        out = []
        zero = getattr(self, "zero_forms", ())
        for i in range(1, len(key) - 1):
            a, b = key[:i], key[i:]
            if (len(a) == 1 and a not in ONE_LETTER_WORDS) or len(b) < 2:
                continue
            if (self.is_form(a) and self.is_form(b) and a not in zero and b not in zero
                    and self.count(a) >= part_min and self.count(b) >= part_min):
                out.append(a + " " + b)
        return out

    def phrase_candidates(self):
        out = set()
        for key, c in self.counts.items():
            if len(key) < 4 or c < KEEP_MIN_COUNT or self.is_form(key):
                continue
            out.update(self._splits(key, SPLIT_CANDIDATE_PART_MIN))
        return out

    def artefacts(self):
        """§11.6 steps 6 and 7: missing-space artefacts and fused elisions (non-forms only); names and foreign words
        are caught first (they follow step 8)."""
        s = self.stats.s_word
        self.fused = set()
        rows = []
        guarded = []
        alias_keys = {alias_of(f) for f in list(self.compound_forms) + list(self.apostrophe_words)}
        for key in sorted(self.counts):
            if len(key) < 4 or self.is_form(key) or self.counts[key] < KEEP_MIN_COUNT or key in alias_keys:
                continue
            t = s.get(key, 0)
            for phrase in self._splits(key, SPLIT_PART_MIN):
                p = self.stats.s_phrase.get(phrase, 0)
                if p >= PHRASE_MIN and p >= PHRASE_RATIO * t:
                    if self.name_like(key) or key in self.english_titles:
                        guarded.append([key, phrase, self.counts[key]])
                        break
                    self.fused.add(key)
                    rows.append([key, phrase, t, p, "phrase"])
                    break
        for key in sorted(self.phrase_pages):
            if key in self.counts and not self.is_form(key) and key not in self.fused and key not in alias_keys:
                self.fused.add(key)
                rows.append([key, "(phrase page)", s.get(key, 0), 0, "wiktionary"])
        rows.sort(key=lambda r: -self.count(r[0]))
        self.fused_elisions = set()
        frows = []
        el = self.stats.s_elided
        index = self._class_index()
        for key in sorted(self.counts):
            if self.is_form(key) or key in self.fused or key in alias_keys:
                continue
            for letters, prefix in PREFIX_LETTERS.items():
                if not key.startswith(letters) or len(key) <= len(letters):
                    continue
                r = key[len(letters):]
                # the elided phrase may carry the accent the fused spelling lost ("jusqua" → "jusqu'à")
                rs = [f for f in self._class_forms(index.get(accent_class(r), ())) if starts_vowel_or_h(f)]
                if not rs:
                    continue
                e = sum(el.get((prefix, f), 0) for f in rs)
                t = s.get(key, 0)
                if e >= FUSED_ELISION_MIN and e >= FUSED_ELISION_RATIO * t:
                    self.fused_elisions.add(key)
                    best = max(rs, key=lambda f: (el.get((prefix, f), 0), f))
                    frows.append([key, prefix + best, t, e, self.counts[key]])
                    break
        frows.sort(key=lambda r: -r[4])
        self.report.update({
            "missingSpaceDropped": len(self.fused),
            "missingSpaceDroppedExamples": rows[:100],
            "missingSpaceGuardedNamesAndForeign": guarded[:60],
            "fusedElisionsDropped": len(self.fused_elisions),
            "fusedElisionsDroppedList": frows[:200],
        })

    # ------------------------------------------------------------------ header and selection

    def finish_header(self):
        self.max_count = max(self.counts.values())
        self.max_key = max(self.counts, key=lambda k: (self.counts[k], k))
        self.scale = 255.0 / math.log(self.max_count)

    def select(self, key, dropped):
        count = self.count(key)
        form = self.is_form(key)
        zero = key in self.zero_forms
        n = len(key)
        if not plausible(key) and not (form and 2 <= n <= 27):
            dropped["implausible"] += 1
            return None
        if n == 1 and key not in ONE_LETTER_WORDS:
            dropped["oneLetter"] += 1
            return None
        if key in PREFIX_LETTER_KEYS or (key in PREFIX_LETTERS and not form):
            dropped["prefixLetters"] += 1
            return None
        if not form and any(c not in FRENCH_PLAIN for c in key):
            dropped["nonFrenchCharacters"] += 1
            return None
        if form:
            if count >= 2 or zero:
                return self.case_decision(key) or [(key, 1.0)]
            dropped["formBelowCount2"] += 1
            return None
        if key in self.fused:
            dropped["missingSpace"] += 1
            return None
        if key in self.fused_elisions:
            dropped["fusedElision"] += 1
            return None
        if count >= KEEP_MIN_COUNT:
            if key in self.foreign_titles and count < FOREIGN_MAX:
                dropped["foreign"] += 1
                if len(dropped["foreignExamples"]) < 40 and count >= 2000:
                    dropped["foreignExamples"].append(key)
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
        for t in self.fr_titles:
            if "'" in t or "-" in t:
                continue
            self.titles_by_key.setdefault(jlower(t), set()).add(t)
        dropped = {k: 0 for k in ("implausible", "oneLetter", "prefixLetters", "nonFrenchCharacters", "formBelowCount2",
                                  "missingSpace", "fusedElision", "foreign", "rare")}
        dropped["foreignExamples"] = []
        alias = {}
        for f, c in self.compound_forms.items():
            alias.setdefault(alias_of(f), []).append((f, c))
        for w in self.apostrophe_words:
            alias.setdefault(alias_of(w), []).append((self.apostrophe_words[w], self.apostrophe_counts[w]))
        # a frequency-list token that equals an alias key and is no word of its own adds its count (§11.5)
        alias_tokens = []
        for k, lst in alias.items():
            if k in self.counts and not self.is_form(k) and self.counts[k] > 0:
                f, c = lst[0]
                lst[0] = (f, c + self.counts[k])
                alias_tokens.append([k, f, self.counts[k]])
                del self.counts[k]
        entries = {}
        kept_unknown = 0
        for key in sorted(self.counts):
            n = len(key)
            if not 1 <= n <= 27:
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
                forms.append((f, 0 if key in self.zero_forms else freq_class(count * share, self.scale), count * share))
            forms.sort(key=lambda fc: -fc[1])
            if forms:
                entries[key] = forms
        # apostrophe words under their own key; compounds and apostrophe words under their alias keys
        for w, form in self.apostrophe_words.items():
            c = self.apostrophe_counts[w]
            entries[w] = [(form, freq_class(c, self.scale), c)]
        collisions = []
        for k, lst in sorted(alias.items()):
            items = [(f, freq_class(c, self.scale), c) for f, c in lst]
            if k in entries:
                real = entries[k]
                best_alias = max(c for _, _, c in items)
                real_count = sum(c for _, _, c in real)
                if real_count * ALIAS_RARER <= best_alias:
                    collisions.append([k, [f for f, _, _ in real], [f for f, _, _ in items], "real word dropped"])
                    entries[k] = items
                else:
                    collisions.append([k, [f for f, _, _ in real], [f for f, _, _ in items], "both kept"])
                    entries[k] = real + items
            else:
                entries[k] = items
        out = {}
        for k, forms in entries.items():
            forms = sorted(forms, key=lambda fc: (-fc[1], -fc[2], fc[0]))
            uniq = []
            seen = set()
            for f, cls, _ in forms:
                if f not in seen:
                    seen.add(f)
                    uniq.append((f, cls))
            out[k] = uniq
        self.report["dropped"] = dropped
        self.report["keptNotAForm"] = kept_unknown
        self.report["aliasCollisions"] = collisions
        self.report["aliasTokensAdded"] = alias_tokens
        self.report["keysClass0Dominated"] = sorted(k for k in out if k in self.zero_forms)
        self.prefix_table = [(p, freq_class(self.prefix_counts[p], self.scale)) for p in PREFIXES]
        self.report["prefixTable"] = [[p, self.prefix_counts[p], c] for p, c in self.prefix_table]
        return out

    # ------------------------------------------------------------------ coverage (§11.2)

    def coverage_raw(self, top=20000):
        """Share of the [top] most frequent keys of the frequency list that are French forms or would be merged into
        one (a simplified accent merge: a form in the key's accent class), before the spelling steps (§11.2: French
        dump alone about 95 %)."""
        index = self._class_index()
        keys = sorted(self.counts, key=lambda k: (-self.counts[k], k))[:top]
        out = {}
        for name in ("fr", "en", "both"):
            def known(k):
                if name == "both":
                    return self.is_form(k) or any(self.is_form(t) for t in index.get(accent_class(k), ()))
                return self.form_in(k, name) or any(self.form_in(t, name) for t in index.get(accent_class(k), ()))
            out[name] = sum(1 for k in keys if known(k)) / len(keys)
        return out

    def coverage(self, counts, top=20000):
        keys = sorted(counts, key=lambda k: (-counts[k], k))[:top]
        out = {}
        for name in ("fr", "en"):
            out[name] = sum(1 for k in keys if self.form_in(k, name)) / len(keys)
        out["both"] = sum(1 for k in keys if self.is_form(k)) / len(keys)
        return out


def stock_names(capitalised, tatoeba_tokens, subtitle_counts, top=30, scan=100, ratio=10.0):
    """§5 step 3 for fr: the base list plus translated stock names that are clearly over-represented in Tatoeba (the
    rule of the Spanish build)."""
    from pairs import ES_STOCK_BASE
    ranked = sorted(capitalised.items(), key=lambda kv: (-kv[1], kv[0]))
    sub_total = sum(subtitle_counts.values())
    rows = []
    for tok, c in ranked[:scan]:
        sub = subtitle_counts.get(jlower(tok), 0)
        rows.append((tok, c, sub, round((c / tatoeba_tokens) / max(sub / sub_total, 1e-12), 1)))
    added = sorted(tok for tok, _, _, r in rows if r >= ratio and tok not in ES_STOCK_BASE)
    return set(ES_STOCK_BASE) | set(added), [list(r) for r in rows[:top]], added
