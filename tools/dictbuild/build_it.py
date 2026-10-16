"""Italian word list, elision prefix table and pair profile (05b-dictionaries §1.7, §10).

Inputs: the FrequencyWords it list (counts, including the apostrophe tokens of §10.1), the classified pages of the
Italian and the English Wiktionary (wiktionary_it.py) and the sentence sources (Tatoeba, Common Voice), which give the
running-text case evidence (§10.3), the sentence counts of the accent and elision steps (§10.4, §10.5) and the pairs
(§5, §10.6). Every rule and threshold is recorded in `self.report`, which the driver writes to
tools/dictionary-build.json.
"""

import math
import re

import casing
from common import DAMPING, damped_max, freq_class, jlower, short_key_dropped
from wiktionary_pt import strip_diacritics

# 05 §1.1 ELISION_PREFIXES (notes on it-IT), in order of their frequency in the frequency source
PREFIXES = ("l' c' un' d' dell' all' cos' dov' nell' dall' quell' com' anch' quest' sull' n' dev' gliel' m' qualcos' "
            "quand' nient' s' bell' t' mezz' quant' brav' vent' neanch' tutt' senz' nessun' qualcun' trent' sott' v' "
            "sant' grand' quarant' cinquant'").split()
PREFIX_SET = set(PREFIXES)
PREFIX_LETTERS = {p[:-1]: p for p in PREFIXES}
# §10.1 rule 2: the closed list of truncated words with a final apostrophe
TRUNCATED = ["po'", "mo'", "be'", "to'", "di'", "da'", "fa'", "sta'", "va'"]
TRUNCATED_SET = set(TRUNCATED)

EXCLUDED_LETTERS = set("İıŉſĸªº")
ITALIAN_PLAIN = set("abcdefghijklmnopqrstuvwxyzàèéìòóù")
VOWELS = set("aeiouy" "äöü" "àáâãåæèéêëìíîïòóôõøœùúûýÿ")
ONE_LETTER_WORDS = ("a", "e", "è", "i", "o")

# §10.4 step 1: clitic sequences
SINGLE_CLITICS = ["mi", "ti", "si", "ci", "vi", "lo", "la", "li", "le", "gli", "ne"]
DOUBLE_CLITICS = [a + b for a in ("me", "te", "se", "ce", "ve", "glie") for b in ("lo", "la", "li", "le", "ne")]
CLITIC_SEQUENCES = sorted(set(SINGLE_CLITICS + DOUBLE_CLITICS), key=lambda c: (-len(c), c))
CLITIC_SEQUENCE_SET = set(CLITIC_SEQUENCES)
IMPERATIVES = ("di", "da", "fa", "sta", "va")

# thresholds of the first build (05b §10.4–§10.6, open point 13)
CLITIC_MIN_COUNT = 20
APOCOPIC_MIN_COUNT = 40
DOMINATED_MIN = 100
DOMINATED_RATIO = 100
DOMINATED_SUBTITLE_FACTOR = 1.5   # own guard, see docs/DATA-SOURCES.md and spec/QUESTIONS.md
LOSS_CLASS_MIN = 1000
SPLIT_PART_MIN = 20_000
SPLIT_CANDIDATE_PART_MIN = 10_000   # candidate phrases are counted before §10.4 changes the part counts
PHRASE_MIN = 10
PHRASE_RATIO = 10
FUSED_ELISION_MIN = 10
FUSED_ELISION_RATIO = 10
ELIDED_PAIR_RATIO = 10
FOREIGN_MAX = 100_000
KEEP_MIN_COUNT = 40
RUNNING_KEEP_MIN = 2
RUNNING_TARGET_MIN = 10

LETTER_RUN = re.compile(r"[^\W\d_]+")
IS_LETTER = re.compile(r"[^\W\d_]")
RUNNING_BREAK = set(".!?:\"“”«»([")


def _allowed_key_chars():
    chars = set(ITALIAN_PLAIN)
    for cp in range(0xC0, 0x180):
        ch = chr(cp)
        if not ch.isalpha() or ch in EXCLUDED_LETTERS:
            continue
        low = ch.lower()
        if len(low) == 1 and low not in EXCLUDED_LETTERS:
            chars.add(low)
    return chars


ALLOWED = _allowed_key_chars()


def letters_of(key):
    """The key without the final apostrophe of a truncated word."""
    return key[:-1] if key.endswith("'") else key


def plausible(key):
    k = letters_of(key)
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


def accent_class(key):
    """§10.4 step 4: equal after removing every diacritic and a final apostrophe."""
    return strip_diacritics(letters_of(key))


def diacritic_positions(key):
    k = letters_of(key)
    return frozenset(i for i, c in enumerate(k) if strip_diacritics(c) != c)


def has_diacritics(key):
    return bool(diacritic_positions(key))


def starts_vowel_or_h(word):
    return bool(word) and strip_diacritics(word[0].lower()) in "aeiouh"


def key_chars_ok(key):
    k = letters_of(key)
    return bool(k) and all(c in ALLOWED for c in k)


def tokens(text, truncated=TRUNCATED_SET):
    """§5 step 2 for Italian (’ → ' first): yields ("w", run), ("p", prefix), ("t", truncated word) and ("c", char)
    for every other non-space character. A letter run + apostrophe + letter whose run + ' is an elision prefix is a
    prefix token; a letter run + apostrophe not followed by a letter is a truncated word if it is one of [truncated];
    any other apostrophe is an ordinary character."""
    text = text.replace("’", "'")
    i = 0
    n = len(text)
    while i < n:
        m = LETTER_RUN.match(text, i)
        if m:
            run = m.group(0)
            j = m.end()
            if j < n and text[j] == "'":
                cand = jlower(run) + "'"
                nxt = j + 1 < n and IS_LETTER.match(text[j + 1])
                if nxt and cand in PREFIX_SET:
                    yield "p", cand
                    i = j + 1
                    continue
                if not nxt and cand in truncated:
                    yield "t", run + "'"
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
    """Pass over the sentence sources (§10.3, §10.4): running-text case evidence of letter runs (as §8.3), the
    weighted sentence counts s(x) of words and elided phrases (§5 step 2 tokens) and the counts of candidate phrases
    (two words separated by whitespace only). Stock names count as unknown words in Tatoeba only (§5 step 3)."""

    def __init__(self, stock, phrase_candidates):
        self.stock = stock
        self.phrases = set(phrase_candidates)
        self.phrase_first = {p.split(" ")[0] for p in self.phrases}
        self.rt_conv = {}
        self.rt_all = {}
        self.s_word = {}
        self.s_elided = {}
        self.s_phrase = {}
        self.s_total = 0

    def add(self, text, weight, conversational, tatoeba):
        # running-text case evidence: letter runs, not the first token, no stop character in between
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
        # sentence counts; two consecutive word tokens are separated by whitespace only (every other character is a
        # token of its own)
        prev_word = None
        prefix = None
        for kind, tok in tokens(text):
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

    def running(self, key):
        return sum(self.rt_all.get(f, 0) for f in self.forms_by_key.get(key, ()))


class ItalianBuilder:
    def __init__(self, freq_path, it_pages, en_pages, abbreviations=None):
        """[it_pages], [en_pages]: (titles, {title: kinds}) of the classified Wiktionary passes."""
        self.report = {}
        self.dumps = [("it", it_pages), ("en", en_pages)]
        self.titles = set()
        for _, (titles, _) in self.dumps:
            self.titles |= {t for t in titles if not t.endswith("'")}
        # foreign-word detection uses the titles of the Italian dump (as the Portuguese build, docs/DATA-SOURCES.md)
        self.foreign_titles = {t for t in it_pages[0] if t == jlower(t) and not t.endswith("'")}
        self._read_counts(freq_path)
        self._collect_pages()
        self._abbreviations(abbreviations or {})
        self.stats = None

    # ------------------------------------------------------------------ §10.1 counts

    def _read_counts(self, freq_path):
        self.counts = {}
        self.apostrophe_tokens = {}
        self.prefix_counts = {p: 0 for p in PREFIXES}
        total = 0
        dropped = {"digit": 0, "characters": 0}
        lines = 0
        with open(freq_path, encoding="utf-8") as f:
            for line in f:
                parts = line.split()
                if len(parts) != 2:
                    continue
                lines += 1
                tok, c = parts[0].replace("’", "'"), int(parts[1])
                total += c
                if any(ch.isdigit() for ch in tok):
                    dropped["digit"] += c
                    continue
                if "'" in tok:
                    self.apostrophe_tokens[tok] = self.apostrophe_tokens.get(tok, 0) + c
                    continue
                key = jlower(tok)
                if key != tok or not all(ch in ALLOWED for ch in key):
                    dropped["characters"] += c
                    continue
                self.counts[key] = self.counts.get(key, 0) + c
        self.corpus_tokens = total
        self.report.update({"frequencyListLines": lines, "corpusTokens": total, "droppedTokenCounts": dropped})

    # ------------------------------------------------------------------ §10.2 page kinds per key

    def _collect_pages(self):
        self.forms_by_key = {}      # key -> cased titles that are Italian forms
        self.form_dumps = {}        # key -> set of dumps with a form page
        self.pointer_target = {}    # key -> target (pages that only point to another spelling)
        self.letter_keys = set()
        self.apocopic_pages = {}    # key -> full word
        self.inflected = {}         # key -> {dump: inflected-only}
        self.phrase_pages = set()
        self.verb_lemmas = set()  # verb lemma pages (infinitives, §10.4 step 1)
        self.clitic_hosts = {"ecco"}  # imperative and gerund pages: words a clitic is attached to (§10.4 step 1)
        for dump, (titles, pages) in self.dumps:
            for title, k in pages.items():
                key = jlower(title)
                if not key_chars_ok(key) or ("'" in letters_of(key)):
                    continue
                # titles ending in an apostrophe count only as the truncated words of §10.1 (prefixes are no keys)
                if key.endswith("'") and key not in TRUNCATED_SET:
                    continue
                if k.get("host") and title == key:
                    self.clitic_hosts.add(key)
                if k.get("verb") and title == key:
                    self.verb_lemmas.add(key)
                if k["phrase"] and not k["form"]:
                    self.phrase_pages.add(key)
                if k["form"]:
                    self.forms_by_key.setdefault(key, set()).add(title)
                    self.form_dumps.setdefault(key, set()).add(dump)
                    self.inflected.setdefault(key, {})[dump] = k["inflected"]
                    if k["apocopic"]:
                        self.apocopic_pages[key] = k["apocopic"]
                elif k["pointer"]:
                    self.pointer_target.setdefault(key, k["pointer"])
                elif k["letter"]:
                    self.letter_keys.add(key)
        # a form in one dump wins over a pointer or letter page in the other (05b §9 open point 12, as Portuguese)
        for key in list(self.pointer_target):
            if key in self.forms_by_key:
                del self.pointer_target[key]
        self.letter_keys -= set(self.forms_by_key)
        self.accepted = {}          # key -> rule ("clitic", "apocopic", "truncated")
        self.report["wiktionaryPages"] = {
            "formKeys": len(self.forms_by_key),
            "formKeysItalianDump": sum(1 for v in self.form_dumps.values() if "it" in v),
            "formKeysEnglishDump": sum(1 for v in self.form_dumps.values() if "en" in v),
            "pointerKeys": len(self.pointer_target),
            "letterKeys": sorted(self.letter_keys),
            "apocopicPages": len(self.apocopic_pages),
            "fusedPhrasePages": sorted(self.phrase_pages),
        }
        # §10.2 item 4: the prefixes of 05 §1.1 should have an apocopic/elided page in at least one dump
        prefix_titles = set()
        for _, (titles, pages) in self.dumps:
            prefix_titles |= {t.lower() for t in titles if t.endswith("'")}
        self.report["elisionPrefixesWithoutPage"] = [p for p in PREFIXES if p not in prefix_titles]

    def _abbreviations(self, abbreviations):
        """§3.6 abbreviation and affix titles (0.6.7): {dump: forms} ("sig" of "sig.") are forms of their dump, not
        inflected; the keys that are forms only by this rule are recorded."""
        self.abbreviation_keys = set()
        used = {d for d, _ in self.dumps}
        for dump, forms in sorted(abbreviations.items()):
            if dump not in used:
                continue
            for form in sorted(forms):
                key = jlower(form)
                if not key_chars_ok(key) and "'" not in key:
                    continue
                if key not in self.forms_by_key:
                    self.abbreviation_keys.add(key)
                self.forms_by_key.setdefault(key, set()).add(form)
                self.form_dumps.setdefault(key, set()).add(dump)
                self.inflected.setdefault(key, {})[dump] = False  # an abbreviation page is no inflection
                self.pointer_target.pop(key, None)
                self.letter_keys.discard(key)

    def is_form(self, key):
        return key in self.forms_by_key or key in self.accepted

    def lower_form(self, key):
        """True if [key] is an Italian form written in lower case (a common word, not only a proper noun)."""
        return key in self.forms_by_key.get(key, ()) or key in self.accepted

    def name_like(self, key):
        """Running text writes the key mostly capitalised (a name of the dubbed films, not a truncated word)."""
        ev = self.stats.evidence(key)
        low = ev.get(key, 0)
        return sum(ev.values()) - low > low

    def count(self, key):
        return self.counts.get(key, 0)

    def form_in(self, key, dump):
        return dump in self.form_dumps.get(key, ()) or key in self.accepted

    # ------------------------------------------------------------------ §10.1 apostrophe tokens (needs §10.3 evidence)

    def apostrophe_rules(self, stats):
        """§10.1 rules 1–4 for the tokens with an apostrophe, in this order; also §10.3 needs [stats] for the share
        of the capitalised bare spelling ("Po")."""
        self.stats = stats
        moved_truncated = {}
        self.capital_bare = {}
        moved_accent = {}
        dropped = {}
        for tok in sorted(self.apostrophe_tokens):
            c = self.apostrophe_tokens[tok]
            low = jlower(tok)
            # 1 elision prefix
            if low in PREFIX_SET:
                self.prefix_counts[low] += c
                continue
            # 2 truncated word
            if low in TRUNCATED_SET:
                self.counts[low] = self.counts.get(low, 0) + c
                continue
            # 3 accent written as an apostrophe: letters + vowel + apostrophe
            if (len(low) >= 2 and low.endswith("'") and low.count("'") == 1 and low[-2] in "aeiou"
                    and all(ch in ALLOWED for ch in low[:-1])):
                base = low[:-2]
                options = [base + acc for acc in GRAVE_ACUTE[low[-2]] if self.is_form(base + acc)]
                if options:
                    target = max(options, key=lambda t: (self.counts.get(t, 0), t))
                    self.counts[target] = self.counts.get(target, 0) + c
                    moved_accent[tok] = (target, c)
                    continue
            # 4 everything else
            dropped[tok] = c
        # rule 2: the bare token's count, unless the bare token is a lower-case Italian form itself
        for t in TRUNCATED:
            bare = t[:-1]
            bc = self.counts.get(bare, 0)
            forms = self.forms_by_key.get(bare, set())
            if bc == 0 or bare in forms:
                continue
            if forms and all(f[0].isupper() for f in forms):
                self.capital_bare[bare] = set(forms)
                cap = sum(self.stats.rt_all.get(f, 0) for f in forms)
                low_rt = self.stats.rt_all.get(bare, 0)
                share = cap / (cap + low_rt) if cap + low_rt else 0.0
                keep = int(round(bc * share))
                self.counts[bare] = keep
                self.counts[t] = self.counts.get(t, 0) + bc - keep
                moved_truncated[t] = [bare, bc - keep, round(share, 4)]
            else:
                self.counts[t] = self.counts.get(t, 0) + bc
                del self.counts[bare]
                moved_truncated[t] = [bare, bc, 0.0]
        for t in TRUNCATED:
            if t in self.counts:
                self.accepted.setdefault(t, "truncated")
                self.forms_by_key.setdefault(t, set()).add(t)
        top_dropped = sorted(dropped.items(), key=lambda kv: -kv[1])
        self.report.update({
            "prefixCounts": dict(self.prefix_counts),
            "truncatedWords": {t: self.counts.get(t, 0) for t in TRUNCATED},
            "truncatedBareCountsMoved": moved_truncated,
            "accentApostropheTokensMerged": len(moved_accent),
            "accentApostropheTokensMergedCount": sum(c for _, c in moved_accent.values()),
            "accentApostropheExamples": [[k, v[0], v[1]] for k, v in sorted(moved_accent.items(), key=lambda kv: -kv[1][1])[:40]],
            "apostropheTokensDropped": len(dropped),
            "apostropheTokensDroppedCount": sum(dropped.values()),
            "apostropheTokensDroppedExamples": [list(r) for r in top_dropped[:40]],
        })

    # ------------------------------------------------------------------ §10.4 steps 1–2

    def _clitic(self, key):
        for c in CLITIC_SEQUENCES:
            if not key.endswith(c) or len(key) <= len(c):
                continue
            v = key[:-len(c)]
            # V = an imperative, a gerund or "ecco" (pages of that kind in a dump), lower case
            if len(v) >= 2 and (v in self.clitic_hosts or (v.endswith("ndo") and self.lower_form(v))):
                return v + "+" + c
            if (v + "e") in self.verb_lemmas and (v + "e")[-3:] in ("are", "ere", "ire"):
                return v + "(e)+" + c
            if v.endswith("r") and (v + "re") in self.verb_lemmas and (v + "re").endswith("rre"):
                return v + "(re)+" + c
        for imp in IMPERATIVES:
            if not key.startswith(imp):
                continue
            rest = key[len(imp):]
            if rest.startswith("gli") and rest in CLITIC_SEQUENCE_SET:
                return imp + "'+" + rest
            if len(rest) >= 3 and rest[0] == rest[1] and rest[0] not in "aeiou" and rest[1:] in CLITIC_SEQUENCE_SET:
                return imp + "'+" + rest[1:]
        return None

    def spellings(self, stats):
        """§10.4 steps 1–6 (needs the sentence counts of [stats])."""
        self.stats = stats
        # 1 clitic forms
        clitics = []
        for key in sorted(self.counts):
            if self.is_form(key) or self.counts[key] < CLITIC_MIN_COUNT:
                continue
            # guards of the earlier builds: names and English words of the dubbed films ("caroline", "diane")
            if key in self.foreign_titles or self.name_like(key):
                continue
            how = self._clitic(key)
            if how:
                self.accepted[key] = "clitic"
                clitics.append((key, how, self.counts[key]))
        # 2 apocopic forms without apostrophe
        apocopic = []
        for key in sorted(self.counts):
            if self.is_form(key) or self.counts[key] < APOCOPIC_MIN_COUNT or key in PREFIX_LETTERS:
                continue
            # guards of the earlier builds: names and English words of the dubbed films are no apocopic forms
            if key in self.foreign_titles or self.name_like(key):
                continue
            # an apocope drops the final vowel after a vowel + l, n, r or m ("aver", "nessun", "qual"; a double consonant
            # is simplified: quello → quel), so "sinistr" or "fornell" are fragments, not apocopic forms
            if not plausible(key) or len(key) < 3 or key[-2] not in "aeiouàèéìòóù" or key[-2] == key[-1]:
                if key not in ("gran", "san"):
                    continue
            if key in ("gran", "san") or (key[-1] in "lnrm" and (self.lower_form(key + "e") or self.lower_form(key + "o"))):
                self.accepted[key] = "apocopic"
                apocopic.append((key, self.counts[key]))
        clitics.sort(key=lambda r: -r[2])
        apocopic.sort(key=lambda r: -r[1])
        self.report.update({
            "cliticFormsAccepted": len(clitics),
            "cliticFormsAcceptedList": [list(r) for r in clitics[:200]],
            "apocopicFormsAccepted": len(apocopic),
            "apocopicFormsAcceptedList": [list(r) for r in apocopic[:120]],
        })
        before_merge = dict(self.counts)
        self.before_merge = before_merge
        self._loss_rate(before_merge)
        self._merge_accents()
        self._dominated()
        self._loss_transfer()

    def _class_index(self):
        index = {}
        for k in set(self.counts) | set(self.forms_by_key) | set(self.accepted):
            index.setdefault(accent_class(k), set()).add(k)
        return index

    def _class_forms(self, members):
        out = []
        for t in members:
            if not self.is_form(t):
                continue
            if len(letters_of(t)) == 1 and t not in ONE_LETTER_WORDS:
                continue
            out.append(t)
        return out

    # 4 accent merging
    def _merge_accents(self):
        index = self._class_index()
        merged = {}
        kept_running = []
        kept_foreign = []
        for key in sorted(self.counts):
            if self.is_form(key):
                continue
            options = [t for t in self._class_forms(index.get(accent_class(key), ())) if t != key]
            if not options:
                continue
            pos = diacritic_positions(key)
            same = [t for t in options if diacritic_positions(t) == pos]
            pool = same or options
            target = sorted(pool, key=lambda t: (-self.counts.get(t, 0), t))[0]
            # guards of the Portuguese build: names and English words of the dubbed films are no artefacts
            rk = self.stats.running(key)
            rt = self.stats.running(target) if "'" not in target else self.stats.s_word.get(target, 0)
            if rk >= RUNNING_KEEP_MIN and rk >= rt:
                kept_running.append((key, target, self.counts[key], rk))
                continue
            if key in self.foreign_titles and rt < RUNNING_TARGET_MIN:
                kept_foreign.append((key, target, self.counts[key], rt))
                continue
            merged[key] = target
        merged_counts = {}
        for key, target in sorted(merged.items()):
            merged_counts[key] = self.counts[key]
            self.counts[target] = self.counts.get(target, 0) + self.counts.pop(key)
        self.merged = merged
        self.report.update({
            "accentMerged": len(merged),
            "accentMergedPointerPages": sum(1 for k in merged if k in self.pointer_target),
            "accentMergedCount": sum(merged_counts.values()),
            "accentMergedExamples": [[k, merged[k], merged_counts[k]] for k in sorted(merged, key=lambda k: (-merged_counts[k], k))[:80]],
            "accentMergeSkippedRunningText": len(kept_running),
            "accentMergeSkippedRunningTextExamples": [list(r) for r in sorted(kept_running, key=lambda r: -r[2])[:40]],
            "accentMergeSkippedForeign": len(kept_foreign),
            "accentMergeSkippedForeignExamples": [list(r) for r in sorted(kept_foreign, key=lambda r: -r[2])[:40]],
        })

    # 5 dominated plain spellings
    def _dominated(self):
        index = self._class_index()
        s = self.stats.s_word
        el = self.stats.s_elided
        self.zero_forms = set()
        rows = []
        odds = self.loss_rate / (1 - self.loss_rate)
        guarded = []
        for key in sorted(self.counts):
            c = self.counts[key]
            if c <= 0 or not self.is_form(key) or "'" in key:
                continue
            counterparts = []   # (target key that receives the count, sentence count, label)
            if not has_diacritics(key):
                accented = [a for a in self._class_forms(index.get(accent_class(key), ())) if a != key and has_diacritics(a)]
                # guard: in the subtitles a plain spelling that only stands for its accented word occurs about
                # odds = f / (1 − f) times as often as that word; far more means a word of its own ("tento", "opero")
                sub = sum(self.before_merge.get(a, 0) for a in accented)
                if accented and self.before_merge.get(key, 0) > DOMINATED_SUBTITLE_FACTOR * odds * sub:
                    sa = sum(s.get(a, 0) for a in accented)
                    if sa >= DOMINATED_MIN and s.get(key, 0) * DOMINATED_RATIO <= sa:
                        guarded.append([key, self.before_merge.get(key, 0), sub])
                    accented = []
                for a in accented:
                    counterparts.append((a, s.get(a, 0), a))
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
            rows.append((key, own, [[lab, sc] for _, sc, lab in counterparts if sc], c))
        for k in self.counts:
            self.counts[k] = int(round(self.counts[k])) if isinstance(self.counts[k], float) else self.counts[k]
        rows.sort(key=lambda r: -r[3])
        self.report.update({
            "dominatedSubtitleGuardKept": guarded,
            "dominatedSpellingsClass0": len(self.zero_forms),
            "dominatedSpellingsList": [[k, own, cps, c] for k, own, cps, c in rows],
        })

    # 6 accent-loss transfer
    def _loss_rate(self, counts):
        index = {}
        for k in set(counts) | set(self.forms_by_key):
            index.setdefault(accent_class(k), set()).add(k)
        plain_sum = acc_sum = classes = 0
        for cls, members in index.items():
            if self.is_form(cls) or len(cls) < 2:
                continue
            accented = [t for t in self._class_forms(members) if has_diacritics(t)]
            if len(accented) != 1:
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
            accented = sorted(t for t in self._class_forms(index[cls]) if has_diacritics(t))
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

    # ------------------------------------------------------------------ §10.3 case

    def case_decision(self, key):
        if key in self.zero_forms and key in self.forms_by_key.get(key, ()):
            # a class-0 spelling (§10.4 step 5) has no running-text evidence of its own: its dictionary form
            return [(key, 1.0)]
        if key in self.capital_bare:
            # the bare letters of a truncated word ("po" of "po'"): its lower-case running text belongs to the truncated
            # word (§10.1 rule 2), so only the capitalised form stays ("Po")
            return [(f, 1.0) for f in sorted(self.capital_bare[key])][:1]
        variants = {f: c for f, c in self.stats.evidence(letters_of(key)).items() if c >= 2} if "'" not in key else {}
        dict_forms = self.forms_by_key.get(key, set())
        if not dict_forms and key in self.accepted:
            dict_forms = {key}
        return casing.decide(key, variants, dict_forms)

    # ------------------------------------------------------------------ §10.5 step 5 candidates

    def _splits(self, key, part_min):
        out = []
        for i in range(1, len(key) - 1):
            a, b = key[:i], key[i:]
            if (len(a) == 1 and a not in ONE_LETTER_WORDS) or len(b) < 2:
                continue
            if (self.is_form(a) and self.is_form(b) and a not in getattr(self, "zero_forms", ())
                    and b not in getattr(self, "zero_forms", ())
                    and self.count(a) >= part_min and self.count(b) >= part_min):
                out.append(a + " " + b)
        return out

    def phrase_candidates(self):
        """Two-word phrases whose counts the missing-space rule may need (keys kept only with count ≥ 40)."""
        out = set()
        for key, c in self.counts.items():
            if len(key) < 4 or c < KEEP_MIN_COUNT or self.is_form(key) or "'" in key:
                continue
            out.update(self._splits(key, SPLIT_CANDIDATE_PART_MIN))
        return out

    # ------------------------------------------------------------------ §10.5 selection

    def finish_header(self):
        self.max_count = max(self.counts.values())
        self.max_key = max(self.counts, key=lambda k: (self.counts[k], k))
        self.scale = 255.0 / math.log(self.max_count)

    def select(self, key, dropped):
        count = self.count(key)
        form = self.is_form(key)
        zero = key in self.zero_forms
        n = len(letters_of(key))
        if not plausible(key) and not (form and 2 <= n <= 27):
            dropped["implausible"] += 1
            return None
        if n == 1 and key not in ONE_LETTER_WORDS:
            dropped["oneLetter"] += 1
            return None
        if not form and any(c not in ITALIAN_PLAIN for c in key):
            dropped["nonItalianCharacters"] += 1
            return None
        if form:
            if count >= 2 or zero:
                return self.case_decision(key) or [(key, 1.0)]
            dropped["formBelowCount2"] += 1
            return None
        if key in PREFIX_LETTERS:
            # the letters of an elision prefix without its apostrophe ("dov", "quest", "nell"): a tokeniser fragment
            dropped["elisionFragment"] += 1
            return None
        if key in self.fused:
            dropped["missingSpace"] += 1
            return None
        if key in self.fused_elisions:
            dropped["fusedElision"] += 1
            return None
        if count >= KEEP_MIN_COUNT:
            sel = self._count_rule(key, count, dropped)
            if sel and short_key_dropped(n, count):
                # 05b §3.6 step 6a (0.6.7): a non-form of at most 3 letters needs count >= 20 000
                dropped["shortKeyStep6a"] += 1
                self.short_dropped.append(key)
                return None
            if sel:
                self.damped.add(key)  # 05b §3.6 damping (0.6.7): kept only by the count rule
            return sel
        dropped["rare"] += 1
        return None

    def _count_rule(self, key, count, dropped):
        """§10.5 count rule (German §3.6 step 7) for a non-form counted at least KEEP_MIN_COUNT times."""
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

    def effective_count(self, key):
        """The count used for classes and pairs: damped for keys kept only by the count rule (05b §3.6, 0.6.7)."""
        c = self.counts.get(key, 0)
        return c * DAMPING if key in self.damped else c

    def artefacts(self):
        """§10.5 steps 5 and 6: missing-space artefacts and fused elisions (non-forms only)."""
        s = self.stats.s_word
        self.fused = set()
        rows = []
        for key in sorted(self.counts):
            if len(key) < 4 or self.is_form(key) or "'" in key or self.counts[key] < KEEP_MIN_COUNT:
                continue
            t = s.get(key, 0)
            for phrase in self._splits(key, SPLIT_PART_MIN):
                p = self.stats.s_phrase.get(phrase, 0)
                if p >= PHRASE_MIN and p >= PHRASE_RATIO * t:
                    self.fused.add(key)
                    rows.append([key, phrase, t, p, "phrase"])
                    break
        for key in sorted(self.phrase_pages):
            if key in self.counts and not self.is_form(key) and key not in self.fused:
                self.fused.add(key)
                rows.append([key, "(phrase page)", s.get(key, 0), 0, "wiktionary"])
        rows.sort(key=lambda r: -self.count(r[0]))
        self.fused_elisions = set()
        frows = []
        el = self.stats.s_elided
        for key in sorted(self.counts):
            if self.is_form(key) or key in self.fused or "'" in key:
                continue
            for letters, prefix in PREFIX_LETTERS.items():
                if not key.startswith(letters) or len(key) <= len(letters):
                    continue
                r = key[len(letters):]
                if not (self.is_form(r) and starts_vowel_or_h(r)):
                    continue
                e = el.get((prefix, r), 0)
                t = s.get(key, 0)
                if e >= FUSED_ELISION_MIN and e >= FUSED_ELISION_RATIO * t:
                    self.fused_elisions.add(key)
                    frows.append([key, prefix + r, t, e, self.counts[key]])
                    break
        frows.sort(key=lambda r: -r[4])
        self.report.update({
            "missingSpaceDropped": len(self.fused),
            "missingSpaceDroppedExamples": rows[:80],
            "fusedElisionsDropped": len(self.fused_elisions),
            "fusedElisionsDroppedList": frows[:150],
        })

    def build(self):
        self.titles_by_key = {}
        for t in self.titles:
            self.titles_by_key.setdefault(jlower(t), set()).add(t)
        dropped = {k: 0 for k in ("implausible", "oneLetter", "nonItalianCharacters", "formBelowCount2",
                                  "elisionFragment", "missingSpace", "fusedElision", "foreign", "shortKeyStep6a",
                                  "rare")}
        dropped["foreignExamples"] = []
        self.damped = set()
        self.short_dropped = []
        selected = {}
        kept_unknown = 0
        for key in sorted(self.counts):
            n = len(letters_of(key))
            if not (2 <= n <= 27 or n == 1):
                continue
            sel = self.select(key, dropped)
            if not sel:
                continue
            if not self.is_form(key):
                kept_unknown += 1
            selected[key] = sel
        # S and the class formula after the damping (05b §3.6)
        self.max_count = damped_max(self.counts, self.damped)
        self.scale = 255.0 / math.log(self.max_count)
        entries = {}
        for key, sel in selected.items():
            count = self.effective_count(key)
            forms = []
            seen = set()
            for f, share in sel:
                if f in seen or jlower(f) != key:
                    continue
                seen.add(f)
                forms.append((f, 0 if key in self.zero_forms else freq_class(count * share, self.scale)))
            forms.sort(key=lambda fc: -fc[1])
            if forms:
                entries[key] = forms
        self.report["dropped"] = dropped
        self.report["keptNotAForm"] = kept_unknown
        self.report["keysClass0Dominated"] = sorted(k for k in entries if k in self.zero_forms)
        self.prefix_table = [(p, freq_class(self.prefix_counts[p], self.scale)) for p in PREFIXES]
        self.report["prefixTable"] = [[p, self.prefix_counts[p], c] for p, c in self.prefix_table]
        return entries

    # ------------------------------------------------------------------ coverage (§10.2)

    def coverage(self, counts, top=20000):
        keys = sorted(counts, key=lambda k: (-counts[k], k))[:top]
        out = {}
        for name in ("it", "en"):
            out[name] = sum(1 for k in keys if self.form_in(k, name)) / len(keys)
        out["both"] = sum(1 for k in keys if self.is_form(k)) / len(keys)
        return out


GRAVE_ACUTE = {"a": ("à", "á"), "e": ("è", "é"), "i": ("ì", "í"), "o": ("ò", "ó"), "u": ("ù", "ú")}


def stock_names(capitalised, tatoeba_tokens, subtitle_counts, top=30, scan=100, ratio=10.0):
    """§5 step 3 for it: the base list plus translated stock names that are clearly over-represented in Tatoeba (the
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
