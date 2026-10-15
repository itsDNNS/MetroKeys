"""Word-pair tables (05b-dictionaries §5)."""

import re

from common import jlower, pair_class

DE_OPENERS = set("„“\"«»‚‘›‹([")
EN_OPENERS = set("“\"‘'«»([")
SENTENCE_END = set(".!?:")

DE_STOCK = {"Tom", "Toms", "Maria", "Marias", "Mary", "Marys", "John", "Johns"}
EN_STOCK_BASE = ["Tom", "Mary", "John", "Sami", "Layla", "Rima", "Ziri", "Yanni", "Skura", "Mennad", "Fadil",
                 "Boston", "Australia", "Algeria", "Algerian", "Algerians", "Algiers", "Berber", "Kabylie"]

DE_WORD = re.compile(r"[^\W\d_]+")
EN_WORD = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)*")

SENTENCE_START = ""


class PairCounter:
    def __init__(self, lang, entries):
        self.lang = lang
        self.entries = entries
        self.word_re = DE_WORD if lang == "de" else EN_WORD
        self.openers = DE_OPENERS if lang == "de" else EN_OPENERS
        self.pairs = {}        # (prev, key) -> weighted count
        self.forms = {}        # (prev, key) -> {form: weighted count}
        self.totals = {}       # prev -> weighted count

    def is_stock(self, tok):
        if self.lang == "de":
            return tok in DE_STOCK
        base = tok.split("'")[0]
        return base in EN_STOCK_BASE

    def add(self, text, weight, tatoeba):
        if self.lang == "en":
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
                    self.pairs[pk] = self.pairs.get(pk, 0) + weight
                    self.totals[prev] = self.totals.get(prev, 0) + weight
                    forms = self.entries[key]
                    if prev != SENTENCE_START and len(forms) > 1 and any(f == tok for f, _ in forms):
                        d = self.forms.setdefault(pk, {})
                        d[tok] = d.get(tok, 0) + weight
                prev = key
                continue
            ch = text[i]
            i += 1
            if ch.isspace():
                continue
            if ch in SENTENCE_END:
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
