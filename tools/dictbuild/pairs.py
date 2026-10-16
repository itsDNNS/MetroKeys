"""Word-pair tables (05b-dictionaries §5)."""

import re

from common import jlower, pair_class

DE_OPENERS = set("„“\"«»‚‘›‹([")
EN_OPENERS = set("“\"‘'«»([")
ES_OPENERS = set("«“\"‘([")

DE_STOCK = {"Tom", "Toms", "Maria", "Marias", "Mary", "Marys", "John", "Johns"}
EN_STOCK_BASE = ["Tom", "Mary", "John", "Sami", "Layla", "Rima", "Ziri", "Yanni", "Skura", "Mennad", "Fadil",
                 "Boston", "Australia", "Algeria", "Algerian", "Algerians", "Algiers", "Berber", "Kabylie"]
# 05b §5 step 3; the Spanish build adds the translated stock names it finds (build_es.stock_names)
ES_STOCK_BASE = ["Tom", "Mary", "John", "Sami", "Layla", "Mennad", "Fadil", "Ziri", "Yanni", "Skura", "Rima"]

DE_WORD = re.compile(r"[^\W\d_]+")
EN_WORD = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)*")

SENTENCE_START = ""


class PairProfile:
    """Language-specific characters of the pair count (05b §5 step 2, §7)."""

    def __init__(self, word_re, openers, sentence_end, stock, apostrophe):
        self.word_re = word_re
        self.openers = openers
        self.sentence_end = set(sentence_end)
        self.stock = stock            # token -> True if it is a Tatoeba stock name
        self.apostrophe = apostrophe  # replace ’ by ' before tokenising


PROFILES = {
    "de": PairProfile(DE_WORD, DE_OPENERS, ".!?:", lambda tok: tok in DE_STOCK, False),
    "en": PairProfile(EN_WORD, EN_OPENERS, ".!?:", lambda tok: tok.split("'")[0] in EN_STOCK_BASE, True),
    "es": PairProfile(DE_WORD, ES_OPENERS, ".!?:¿¡", lambda tok: tok in ES_STOCK_BASE, False),
    # pt: a hyphen between letter runs and the elided "d'" clear the context like any other character (05b §5 step 2)
    "pt": PairProfile(DE_WORD, ES_OPENERS, ".!?:", lambda tok: tok in ES_STOCK_BASE, False),
}


class PairCounter:
    def __init__(self, lang, entries, stock=None, damped=None, damping=1.0):
        self.lang = lang
        # German 05b §3.6 (0.6.4): the pair count c(prev, w) of a damped follower w counts with weight × damping; the
        # total c(prev, ·) is a count of prev and stays undamped (spec/QUESTIONS.md)
        self.damped = damped or set()
        self.damping = damping
        self.entries = entries
        profile = PROFILES[lang]
        self.word_re = profile.word_re
        self.openers = profile.openers
        self.sentence_end = profile.sentence_end
        self.apostrophe = profile.apostrophe
        self.is_stock = profile.stock if stock is None else (lambda tok: tok in stock)
        self.pairs = {}        # (prev, key) -> weighted count
        self.forms = {}        # (prev, key) -> {form: weighted count}
        self.totals = {}       # prev -> weighted count

    def add(self, text, weight, tatoeba):
        if self.apostrophe:
            text = text.replace("’", "'")
        prev = SENTENCE_START
        i = 0
        n = len(text)
        while i < n:
            m = self.word_re.match(text, i)
            if m:
                tok = m.group(0)
                i = m.end()
                key = jlower(tok)
                if (tatoeba and self.is_stock(tok)) or key not in self.entries:
                    prev = None
                    continue
                if prev is not None:
                    pk = (prev, key)
                    w = weight * self.damping if key in self.damped else weight
                    self.pairs[pk] = self.pairs.get(pk, 0) + w
                    self.totals[prev] = self.totals.get(prev, 0) + weight
                    forms = self.entries[key]
                    if prev != SENTENCE_START and len(forms) > 1 and any(f == tok for f, _ in forms):
                        d = self.forms.setdefault(pk, {})
                        d[tok] = d.get(tok, 0) + w
                prev = key
                continue
            ch = text[i]
            i += 1
            if ch.isspace():
                continue
            if ch in self.sentence_end:
                prev = SENTENCE_START
            elif ch in self.openers and prev == SENTENCE_START:
                pass
            else:
                prev = None

    def table(self, key_order):
        by_prev = {}
        for (prev, key), c in self.pairs.items():
            total = self.totals[prev]
            if c < 3 or total < 12:
                continue
            cls = pair_class(c / total)
            if cls is None:
                continue
            by_prev.setdefault(prev, []).append((key, cls))
        result = {}
        for prev in sorted(by_prev):
            followers = sorted(by_prev[prev], key=lambda kc: (-kc[1], key_order[kc[0]]))[:48]
            out = []
            for key, cls in followers:
                forms = [f for f, _ in self.entries[key]]
                seen = self.forms.get((prev, key))
                form = forms[0]
                if seen:
                    form = sorted(seen.items(), key=lambda fc: (-fc[1], forms.index(fc[0])))[0][0]
                out.append((key, cls, form))
            result[prev] = out
        return result


class ItalianPairCounter(PairCounter):
    """Pair count of 05b §5 step 2 for Italian: elision prefixes are previous words (and followers of the word before
    them), truncated words with a final apostrophe are tokens of their own, any other apostrophe clears the context.
    The table keeps up to [prefix_limit] followers per prefix after the elided-pair filter of §10.6."""

    def __init__(self, entries, prefixes, stock, prefix_limit=4000, elided_ratio=10):
        super().__init__("es", entries, stock)  # es profile: openers « “ " ‘ ( [, no ¿ ¡ (see below)
        self.sentence_end = set(".!?:")
        self.prefixes = list(prefixes)
        self.prefix_set = set(prefixes)
        self.prefix_limit = prefix_limit
        self.elided_ratio = elided_ratio
        self.truncated = {k for k in entries if k.endswith("'")}
        self.dropped_elided = []

    def add(self, text, weight, tatoeba):
        from build_it import tokens
        prev = SENTENCE_START
        for kind, tok in tokens(text, self.truncated):
            if kind == "c":
                if tok in self.sentence_end:
                    prev = SENTENCE_START
                elif tok in self.openers and prev == SENTENCE_START:
                    pass
                else:
                    prev = None
                continue
            if kind == "p":
                if prev is not None:
                    self._count(prev, tok, tok, weight)
                prev = tok
                continue
            key = jlower(tok)
            if (tatoeba and self.is_stock(tok)) or key not in self.entries:
                prev = None
                continue
            if prev is not None:
                self._count(prev, key, tok, weight)
            prev = key

    def _count(self, prev, key, tok, weight):
        pk = (prev, key)
        self.pairs[pk] = self.pairs.get(pk, 0) + weight
        self.totals[prev] = self.totals.get(prev, 0) + weight
        if key in self.prefix_set:
            return
        forms = self.entries[key]
        if prev != SENTENCE_START and len(forms) > 1 and any(f == tok for f, _ in forms):
            d = self.forms.setdefault(pk, {})
            d[tok] = d.get(tok, 0) + weight

    def table(self, key_order):
        order = dict(key_order)
        base = len(order)
        for i, p in enumerate(self.prefixes):
            order[p] = base + i
        by_prev = {}
        for (prev, key), c in self.pairs.items():
            total = self.totals[prev]
            if c < 3 or total < 12:
                continue
            if prev in self.prefix_set:
                # §10.6: the unelided phrase ("un altro" for (un', altro)) at least 10 times as frequent drops the pair
                unelided = self.pairs.get((prev[:-1], key), 0)
                if unelided >= self.elided_ratio * c:
                    self.dropped_elided.append([prev + key, c, unelided])
                    continue
            cls = pair_class(c / total)
            if cls is None:
                continue
            by_prev.setdefault(prev, []).append((key, cls))
        result = {}
        for prev in sorted(by_prev):
            limit = self.prefix_limit if prev in self.prefix_set else 48
            followers = sorted(by_prev[prev], key=lambda kc: (-kc[1], order[kc[0]]))[:limit]
            out = []
            for key, cls in followers:
                if key in self.prefix_set:
                    out.append((key, cls, key))
                    continue
                forms = [f for f, _ in self.entries[key]]
                seen = self.forms.get((prev, key))
                form = forms[0]
                if seen:
                    form = sorted(seen.items(), key=lambda fc: (-fc[1], forms.index(fc[0])))[0][0]
                out.append((key, cls, form))
            result[prev] = out
        self.dropped_elided.sort(key=lambda r: -r[2])
        return result


class FrenchPairCounter(ItalianPairCounter):
    """Pair count of 05b §5 step 2 for French: the elision prefixes are previous words (and followers of the word
    before them), the apostrophe words of §11.5 ("aujourd'hui") are one word, every other apostrophe and a hyphen
    between letter runs clear the context. Before the pruning a pair (prefix, F) is dropped (§11.7) if the unelided
    phrase is at least 10 times as frequent (rule 1) or another follower of the prefix with the same letters apart from
    diacritics is at least 10 times as frequent after it (rule 2)."""

    def __init__(self, entries, prefixes, unelided, apostrophe_words, stock, prefix_limit=4000, elided_ratio=10):
        super().__init__(entries, prefixes, stock, prefix_limit, elided_ratio)
        self.unelided = unelided
        self.apostrophe_words = apostrophe_words
        self.truncated = set()
        self.dropped_by_rule = {1: [], 2: []}

    def add(self, text, weight, tatoeba):
        from build_fr import tokens
        prev = SENTENCE_START
        for kind, tok in tokens(text, self.apostrophe_words):
            if kind == "c":
                if tok in self.sentence_end:
                    prev = SENTENCE_START
                elif tok in self.openers and prev == SENTENCE_START:
                    pass
                else:
                    prev = None
                continue
            if kind == "p":
                if prev is not None:
                    self._count(prev, tok, tok, weight)
                prev = tok
                continue
            key = jlower(tok)
            if (tatoeba and self.is_stock(tok)) or key not in self.entries:
                prev = None
                continue
            if prev is not None:
                self._count(prev, key, tok, weight)
            prev = key

    def table(self, key_order):
        from build_fr import expand
        order = dict(key_order)
        base = len(order)
        for i, p in enumerate(self.prefixes):
            order[p] = base + i
        twins = {}
        for (prev, key), c in self.pairs.items():
            if prev in self.prefix_set:
                twins.setdefault((prev, expand(key)), []).append((key, c))
        by_prev = {}
        for (prev, key), c in self.pairs.items():
            total = self.totals[prev]
            if c < 3 or total < 12:
                continue
            if prev in self.prefix_set:
                unelided = sum(self.pairs.get((full, key), 0) for full in self.unelided[prev])
                if unelided >= self.elided_ratio * c:
                    self.dropped_by_rule[1].append([prev + key, c, unelided])
                    continue
                rival = max((g for g in twins[(prev, expand(key))] if g[0] != key), key=lambda g: g[1], default=None)
                if rival is not None and rival[1] >= self.elided_ratio * c:
                    self.dropped_by_rule[2].append([prev + key, c, prev + rival[0], rival[1]])
                    continue
            cls = pair_class(c / total)
            if cls is None:
                continue
            by_prev.setdefault(prev, []).append((key, cls))
        result = {}
        for prev in sorted(by_prev):
            limit = self.prefix_limit if prev in self.prefix_set else 48
            followers = sorted(by_prev[prev], key=lambda kc: (-kc[1], order[kc[0]]))[:limit]
            out = []
            for key, cls in followers:
                if key in self.prefix_set:
                    out.append((key, cls, key))
                    continue
                forms = [f for f, _ in self.entries[key]]
                seen = self.forms.get((prev, key))
                form = forms[0]
                if seen:
                    form = sorted(seen.items(), key=lambda fc: (-fc[1], forms.index(fc[0])))[0][0]
                out.append((key, cls, form))
            result[prev] = out
        for r in self.dropped_by_rule.values():
            r.sort(key=lambda x: -x[1])
        self.dropped_elided = self.dropped_by_rule[1] + self.dropped_by_rule[2]
        return result
