"""German word list (05b-dictionaries §3)."""

import math

from common import capitalize_first, freq_class, jlower

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


def case_kind_rank(form):
    """capitalised before mixed case before all caps"""
    if len(form) > 1 and form == form.upper():
        return 2
    if form[0].isupper() and form[1:] == form[1:].lower():
        return 0
    return 1


class GermanBuilder:
    def __init__(self, freq_path, wikt):
        self.titles, self.de_forms, counts = wikt
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
        self.max_count = max(self.counts.values())
        self.scale = 255.0 / math.log(self.max_count)
        self._case_cache = {}

    def is_german_form(self, key):
        return key in self.forms_by_key

    def count(self, key):
        return self.counts.get(key, 0)

    # §3.4
    def case_decision(self, key):
        if key in self._case_cache:
            return self._case_cache[key]
        dict_forms = self.forms_by_key.get(key, set())
        evidence = dict(self.variants.get(key, {}))
        for f in dict_forms:
            evidence.setdefault(f, 0)
        total = sum(evidence.values())
        result = None
        if total >= 3:
            ranked = sorted(evidence.items(), key=lambda kv: (-kv[1], kv[0]))
            chosen = []
            for i, (form, c) in enumerate(ranked):
                share = c / total
                if form == key or form in dict_forms:
                    if i == 0 or share >= 0.1:
                        chosen.append([form, share])
                elif i == 0 and share >= 0.5:
                    chosen.append([form, share])
            lower_idx = next((i for i, (f, _) in enumerate(chosen) if f == key), None)
            if lower_idx is not None and lower_idx > 0 and chosen[0][1] < 0.75:
                # swap the forms, the shares stay in place (lower case takes the first share)
                chosen[0][0], chosen[lower_idx][0] = chosen[lower_idx][0], chosen[0][0]
            if chosen:
                result = [(f, s) for f, s in chosen]
        elif dict_forms:
            if key in dict_forms:
                result = [(key, 0.75)] + [(f, 0.25) for f in sorted(dict_forms)
                                          if f != key and f[0].isupper() and case_kind_rank(f) == 0]
            else:
                best = sorted(dict_forms, key=lambda f: (case_kind_rank(f), f))[0]
                result = [(best, 1.0)]
        self._case_cache[key] = result
        return result

    def primary_form(self, key):
        dec = self.case_decision(key)
        return dec[0][0] if dec else key

    def has_capitalised_variant(self, key):
        dec = self.case_decision(key) or []
        return any(f[0].isupper() for f, _ in dec) or any(f[0].isupper() for f in self.forms_by_key.get(key, ()))

    def _compound_part_ok(self, part):
        return self.is_german_form(part) and self.count(part) >= 20 and part not in FUNCTION_WORDS

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
                if len(stem) < 3 or not self._compound_part_ok(stem):
                    continue
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
        count = self.count(key)
        german = self.is_german_form(key)
        if not plausible(key) and not (german and 2 <= len(key) <= 27):
            return None
        if not german and any(c not in GERMAN_PLAIN for c in key):
            return None
        if not german and key.startswith("l") and self.is_german_form("i" + key[1:]):
            return None
        if german:
            if count >= 2:
                return self.case_decision(key) or [(key, 1.0)]
            return None
        if len(key) >= 6 and count >= 4:
            comp = self.compound_case(key)
            if comp is not None:
                return self.case_decision(key) or [(comp, 1.0)]
        if count >= 40:
            titles = self.titles_by_key.get(key, set())
            if key in self.titles and count < 3000:
                return None
            dec = self.case_decision(key)
            if dec:
                return dec
            if key in titles or not titles:
                return [(key, 1.0)]
            best = sorted(titles, key=lambda f: (case_kind_rank(f), f))[0]
            return [(best, 1.0)]
        return None

    def build(self):
        entries = {}
        for key in sorted(self.counts):
            if not 2 <= len(key) <= 27:
                continue
            sel = self.select(key)
            if not sel:
                continue
            count = self.counts[key]
            forms = []
            seen = set()
            for form, share in sel:
                if form in seen or jlower(form) != key:
                    continue
                seen.add(form)
                forms.append((form, freq_class(count * share, self.scale)))
            # stable sort by class descending (case-decision order on ties)
            forms.sort(key=lambda fc: -fc[1])
            entries[key] = forms
        return entries
