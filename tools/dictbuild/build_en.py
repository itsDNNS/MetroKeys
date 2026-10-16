"""US-English word list (05b-dictionaries §4)."""

import math
import os
import re

import combined
from common import freq_class, jlower

US_SPELLINGS = {"english", "american"}
NON_US_PREFIXES = ("british", "canadian", "australian", "variant_")
CATEGORIES = {"words", "upper", "proper-names", "contractions", "abbreviations"}
REMAINDERS = ["'s", "'t", "'m", "'re", "'ll", "'ve", "'d"]
CONTRACTION_ENDINGS = ("n't", "'m", "'re", "'ve", "'ll", "'d")
PRONOUN_S = set("""it's he's she's that's what's there's here's who's where's how's when's why's let's one's
everybody's everyone's somebody's someone's nobody's nothing's something's everything's""".split())
CHAT = set("lol omg btw idk tbh imo brb lmao wtf thx pls plz ttyl smh fyi np ty ily jk".split())
POETIC = {"e'er", "ne'er", "ta'en", "h'm", "o'er", "e'en"}
ALIAS_EXTRA = {"ma'am", "o'clock", "y'all"}
VOWELS = set("aeiouy" "àáâãåæèéêëìíîïòóôõøœùúûýÿ")
TOKEN = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)*")
RUNNING_BREAK = set(".!?:\"“”«»([")


def plausible(key):
    k = key.replace("'", "")
    if len(k) == 1:
        return k in ("a", "i")
    if not 2 <= len(k) <= 27:
        return False
    if not any(c in VOWELS for c in k):
        return False
    for i in range(len(k) - 2):
        if k[i] == k[i + 1] == k[i + 2]:
            return False
    return True


def is_contraction(key, scowl_category=None):
    if scowl_category == "contractions":
        return True
    return key.endswith(CONTRACTION_ENDINGS) or key in PRONOUN_S


def case_mode_rank(form):
    if form == jlower(form):
        return 0
    if len(form) > 1 and form == form.upper():
        return 3
    if form[0].isupper() and form[1:] == jlower(form[1:]):
        return 1
    return 2


def best_form_order(form):
    """capitalised before mixed case before all caps"""
    r = case_mode_rank(form)
    return {1: 0, 2: 1, 3: 2, 0: 3}[r], form


class SentenceStats:
    """Weighted token counts and running-text counts of the sentence sources."""

    def __init__(self):
        self.weighted = {}       # token (case kept) -> weighted count
        self.rt_conv = {}        # token -> running-text count, conversational sources
        self.rt_all = {}         # token -> running-text count, all sources

    def add_sentence(self, text, weight, conversational):
        text = text.replace("’", "'")
        last_end = None
        for m in TOKEN.finditer(text):
            tok = m.group(0)
            self.weighted[tok] = self.weighted.get(tok, 0) + weight
            if last_end is not None:
                between = text[last_end:m.start()]
                if not any(c in RUNNING_BREAK for c in between):
                    self.rt_all[tok] = self.rt_all.get(tok, 0) + 1
                    if conversational:
                        self.rt_conv[tok] = self.rt_conv.get(tok, 0) + 1
            last_end = m.end()

    def finish(self):
        self.wkey = {}
        self.forms_by_key = {}
        for tok, c in self.weighted.items():
            k = jlower(tok)
            self.wkey[k] = self.wkey.get(k, 0) + c
            self.forms_by_key.setdefault(k, set()).add(tok)

    def evidence(self, key):
        forms = self.forms_by_key.get(key, ())
        conv = {f: self.rt_conv[f] for f in forms if f in self.rt_conv}
        if sum(conv.values()) >= 10:
            return conv
        return {f: self.rt_all[f] for f in forms if f in self.rt_all}


def read_scowl(final_dir):
    us = {}            # form -> (smallest size, category)
    non_us_keys = set()
    for name in sorted(os.listdir(final_dir)):
        base, _, size = name.rpartition(".")
        if not size.isdigit():
            continue
        spelling, _, category = base.partition("-")
        if category not in CATEGORIES:
            continue
        size = int(size)
        with open(os.path.join(final_dir, name), encoding="latin-1") as f:
            words = [w.strip() for w in f if w.strip()]
        if spelling in US_SPELLINGS:
            for w in words:
                old = us.get(w)
                if old is None or size < old[0]:
                    us[w] = (size, category)
        elif spelling.startswith(NON_US_PREFIXES):
            for w in words:
                non_us_keys.add(jlower(w))
    return us, non_us_keys


class EnglishBuilder:
    def __init__(self, freq_path, scowl_final, stats, written=None):
        """[written]: the written-language list of 05b §2.15; with it the classes come from the combined frequency of
        §4.6 / §12.2 (0.7.0), without it from the subtitle counts alone (up to 0.6.9)."""
        self.stats = stats
        self.written = written
        self.us, self.non_us = read_scowl(scowl_final)
        self.us_lower_forms = set(self.us)
        self.main_forms = {}   # key -> {form: (size, cat)}
        self.large_forms = {}
        for form, (size, cat) in self.us.items():
            key = jlower(form)
            n = len(key.replace("'", ""))
            if not (2 <= n <= 27 or key in ("a", "i")):
                continue
            if size <= 70:
                self.main_forms.setdefault(key, {})[form] = (size, cat)
        for form, (size, cat) in self.us.items():
            key = jlower(form)
            if 71 <= size <= 80 and key not in self.main_forms and "'" not in key and 2 <= len(key) <= 27:
                self.large_forms.setdefault(key, {})[form] = (size, cat)
        self.main_keys = set(self.main_forms)

        # subtitle counts
        self.raw_total = 0
        self.remainder_counts = {}
        apostrophe_initial = 0
        self.sub = {}
        with open(freq_path, encoding="utf-8") as f:
            for line in f:
                parts = line.split()
                if len(parts) != 2:
                    continue
                tok, c = parts[0], int(parts[1])
                self.raw_total += c
                if tok.startswith("'"):
                    apostrophe_initial += c
                    self.remainder_counts[tok] = self.remainder_counts.get(tok, 0) + c
                    continue
                if "'" in tok or not tok.isalpha() or jlower(tok) != tok:
                    continue
                self.sub[tok] = self.sub.get(tok, 0) + c
        self.corpus_tokens = self.raw_total - apostrophe_initial
        self._split_apostrophes()
        self._noise()

    # §4.2
    def _split_apostrophes(self):
        stats = self.stats
        families = {}
        for key in stats.wkey:
            if "'" in key:
                stem = key.split("'", 1)[0]
                if stem and stem in self.sub:
                    families.setdefault(stem, []).append(key)
        est = {}
        for stem, members in families.items():
            cfam = stats.wkey.get(stem, 0) + sum(stats.wkey[m] for m in members)
            for m in members:
                est[m] = self.sub[stem] * stats.wkey[m] / cfam
        for r in REMAINDERS:
            total = sum(v for k, v in est.items() if k[k.index("'"):] == r)
            if total > 0 and r in self.remainder_counts:
                factor = self.remainder_counts[r] / total
                for k in est:
                    if k[k.index("'"):] == r:
                        est[k] *= factor
        self.counts = dict(self.sub)
        self.contraction_share = {}
        for stem, members in families.items():
            f = self.sub[stem]
            c = sum(est[m] for m in members)
            if c > 0.998 * f:
                scale = 0.998 * f / c
                for m in members:
                    est[m] *= scale
                c = 0.998 * f
            self.counts[stem] = f - c
            self.contraction_share[stem] = c / f if f else 0
        for k, v in est.items():
            self.counts[k] = v

    def _noise(self):
        for key in list(self.counts):
            if key.startswith("l") and "'" not in key:
                twin = "i" + key[1:]
                if twin in self.main_keys and self.sub.get(twin, 0) >= 20 * self.sub.get(key, 0):
                    self.counts[key] = 0
        for r in REMAINDERS:
            k = r[1:]
            if k in self.counts and self.remainder_counts.get(r, 0) >= 10 * self.sub.get(k, 0):
                self.counts[k] = 0

    def count(self, key):
        return self.counts.get(key, 0)

    def evidence_total(self, key):
        return sum(self.stats.evidence(key).values())

    # §4.3
    def is_junk(self, key):
        if key in self.alias_keys:
            return True
        if self.contraction_share.get(key, 0) >= 0.5:
            return True
        for r in REMAINDERS:
            if r[1:] == key and self.remainder_counts.get(r, 0) >= 10 * max(self.sub.get(key, 0), 1):
                return True
        if key.endswith("s") and (key[:-1] + "'s") in self.us_lower_keys:
            return True
        if "i" in key:
            positions = [i for i, c in enumerate(key) if c == "i"]
            variants = {key.replace("i", "l")}
            for p in positions:
                variants.add(key[:p] + "l" + key[p + 1:])
            if any(v in self.main_keys for v in variants):
                return True
        if key.endswith("in") and (key + "g") in self.main_keys:
            return True
        for i in range(1, len(key)):
            a, b = key[:i], key[i:]
            if a in self.main_keys and b in self.main_keys and \
                    self.sub.get(a, 0) >= 200000 and self.sub.get(b, 0) >= 200000:
                return True
        return False

    def build(self):
        stats = self.stats
        self.us_lower_keys = {jlower(f) for f in self.us}
        # apostrophe-less alias keys of contractions (§4.3 first rule)
        contraction_keys = {k for k in self.main_keys if "'" in k and
                            is_contraction(k, self._main_category(k))}
        contraction_keys |= {k for k in stats.wkey if "'" in k and k.endswith(CONTRACTION_ENDINGS)}
        self.alias_keys = {k.replace("'", "") for k in contraction_keys} - self.main_keys

        selected = {}   # key -> list of (form, count)

        # SCOWL main
        for key in sorted(self.main_keys):
            forms = self.main_forms[key]
            cat = self._main_category(key)
            count = self.count(key)
            smallest = min(s for s, _ in forms.values())
            if "'" in key and not is_contraction(key, cat):
                pass  # possessive: keep
            elif "'" in key or cat == "contractions":
                if stats.wkey.get(key, 0) < 3:
                    continue
            elif key in self.non_us and key not in self.us_lower_forms:
                continue
            elif count == 0 and smallest > 70:
                continue
            elif len(key) <= 2 and smallest > 40 and count < 5000:
                continue
            selected[key] = self._scowl_case(key, list(forms), count)

        # SCOWL large (71..80)
        for key in sorted(self.large_forms):
            count = self.count(key)
            if count < 200 or self.is_junk(key):
                continue
            forms = self.large_forms[key]
            if key in self.non_us and key not in forms:
                continue
            ev = stats.evidence(key)
            ev_total = sum(ev.values())
            if any(f != key for f in forms):
                ev_large = sum(ev.get(f, 0) for f in forms)
                if ev_large < max(3, 0.5 * ev_total):
                    continue
            selected[key] = self._scowl_case(key, list(forms), count)

        # apostrophe forms not in SCOWL
        for key in sorted(k for k in stats.wkey if "'" in k):
            if key in selected or key in self.main_keys:
                continue
            if not key.endswith(CONTRACTION_ENDINGS) or not plausible(key) or key.count("'") != 1:
                continue
            base = key[:-3] if key.endswith("n't") else key.split("'")[0]
            if base not in self.us_lower_forms or stats.wkey[key] < 60:
                continue
            form = self._unknown_case(key, apostrophe=True)
            if not (form[0].islower() or form.startswith(("i'", "I'"))):
                continue
            if key not in self.counts:
                continue
            selected[key] = [(form, self.count(key))]

        # other unknown words
        for key in sorted(self.sub):
            if key in selected or key in self.main_keys or key in self.large_forms:
                continue
            if not key.isascii() or not plausible(key) or key in self.non_us or self.is_junk(key):
                continue
            count = self.count(key)
            ev_total = self.evidence_total(key)
            if key in CHAT:
                if count < 100:
                    continue
                selected[key] = [(key, count)]
                continue
            if ev_total >= 3:
                if count < (20000 if len(key) <= 3 else 2000):
                    continue
                selected[key] = [(self._unknown_case(key), count)]
            else:
                if count < 10000:
                    continue
                selected[key] = [(key, count)]

        self._aliases(selected)
        self.selected = selected
        if self.written is not None:
            return self._combined_entries(selected)
        max_count = max(sum(c for _, c in forms) for forms in selected.values())
        self.max_count = max_count
        self.scale = 255.0 / math.log(max_count)
        entries = {}
        for key in sorted(selected):
            forms = [(f, freq_class(c, self.scale)) for f, c in selected[key]]
            forms.sort(key=lambda fc: -fc[1])
            entries[key] = forms
        return entries

    # §4.6 (0.7.0)
    def is_name(self, key):
        """05b §12.5 English: a key whose only SCOWL category is proper-names."""
        cats = {c for _, c in self.main_forms.get(key, {}).values()} | {c for _, c in self.large_forms.get(key, {}).values()}
        return cats == {"proper-names"}

    def _combined_entries(self, selected):
        """Classes from the combined count of 05b §12.2 with the English subtitle count (after the apostrophe split)
        as countS; contractions, possessives and alias keys use countS alone; names the lower of the two; every key is
        attested (at least class 1)."""
        key_count = {k: sum(c for _, c in forms) for k, forms in selected.items()}
        weight0 = {k for k, forms in selected.items() if "'" in k or any(jlower(f) != k for f, _ in forms)}
        self.weight0 = weight0
        self.pw = combined.shared_written(self.written, list(selected), lambda k: key_count[k], weight0)
        self.names = {k for k in selected if k not in weight0 and self.is_name(k)}
        cc = {k: combined.combined_count(key_count[k], self.corpus_tokens, self.pw.get(k, 0.0), k in self.names,
                                         k not in weight0) for k in selected}
        self.combined_counts = cc
        self.max_count = max(cc.values())
        self.scale = 255.0 / math.log(self.max_count)
        entries = {}
        for key in sorted(selected):
            total = key_count[key]
            forms = []
            for f, c in selected[key]:
                share = c / total if total > 0 else 1.0 / len(selected[key])
                forms.append((f, max(1, freq_class(cc[key] * share, self.scale))))
            forms.sort(key=lambda fc: -fc[1])
            entries[key] = forms
        return entries

    def _main_category(self, key):
        cats = {c for _, c in self.main_forms.get(key, {}).values()}
        if "contractions" in cats:
            return "contractions"
        return next(iter(sorted(cats)), None)

    # §4.5
    def _scowl_case(self, key, forms, count):
        if len(forms) == 1:
            return [(forms[0], count)]
        ev = self.stats.evidence(key)
        ev_forms = {f: ev.get(f, 0) for f in forms}
        total = sum(ev_forms.values())
        if total < 3:
            if key in forms:
                return [(key, count)]
            return [(sorted(forms, key=best_form_order)[0], count)]
        ranked = sorted(forms, key=lambda f: (-ev_forms[f], case_mode_rank(f), f))
        shares = [ev_forms[f] / total for f in ranked]
        if key in ranked and ranked[0] != key and shares[0] < 0.75:
            i = ranked.index(key)
            ranked[0], ranked[i] = ranked[i], ranked[0]
        kept = [(ranked[0], shares[0])]
        for f, s in zip(ranked[1:], shares[1:]):
            if ev_forms[f] >= 3 and s >= (0.03 if f == key else 0.30):
                kept.append((f, s))
        norm = sum(s for _, s in kept)
        return [(f, count * s / norm) for f, s in kept]

    def _unknown_case(self, key, apostrophe=False):
        ev = self.stats.evidence(key)
        total = sum(ev.values())
        if total == 0 and apostrophe:
            base = key.split("'")[0]
            if base == "i":
                return "I" + key[1:]
            bev = self.stats.evidence(base)
            btotal = sum(bev.values())
            if btotal:
                top = max(sorted(bev), key=lambda f: bev[f])
                if top != base and bev[top] / btotal >= 0.75 and top[0].isupper():
                    return top[0] + key[1:]
            return key
        if total:
            top = sorted(ev, key=lambda f: (-ev[f], f))[0]
            if top != key and ev[top] / total >= 0.75:
                return top
        return key

    def _aliases(self, selected):
        for key in sorted(selected):
            if "'" not in key:
                continue
            if key in POETIC:
                continue
            ok = key in ALIAS_EXTRA or (key.endswith(("n't", "'re", "'ve", "'ll", "'d")) or key in PRONOUN_S
                                         or key == "i'm")
            if not ok or key.endswith("'m") and key != "i'm":
                continue
            alias = key.replace("'", "")
            if len(alias) < 2:
                continue
            contraction_form, _ = selected[key][0]
            ccount = sum(c for _, c in selected[key])
            existing = selected.get(alias)
            if existing is None:
                selected[alias] = [(contraction_form, ccount)]
                self.alias_added = getattr(self, "alias_added", 0) + 1
                continue
            ecount = sum(c for _, c in existing)
            if ecount * 100 <= ccount:
                selected[alias] = [(contraction_form, ccount)]
                self.alias_replaced = getattr(self, "alias_replaced", 0) + 1
                self.alias_added = getattr(self, "alias_added", 0) + 1
            elif alias not in self.us_lower_forms and not any(f == alias for f, _ in existing):
                selected[alias] = [(contraction_form, ccount)] + existing
                self.alias_added = getattr(self, "alias_added", 0) + 1
