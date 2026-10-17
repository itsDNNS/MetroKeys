"""The 0.7.0 rules shared by the German, Spanish, Portuguese, Italian and French builds (spec/05b-dictionaries.md §12):
the validity union of the Wiktionary pass and the spelling dictionary (§12.1), admission without subtitle evidence
(§12.3), the colloquial path that replaced the count rule (§12.4), names (§12.5) and the classes from the combined
frequency (§12.2).

A builder that mixes this in has `forms_by_key` (the forms of its Wiktionary pass and of the abbreviation rule),
optionally `accepted` (forms its own spelling steps derive from Wiktionary forms: clitic, pre-clitic, apocopic and
truncated forms), `counts` (subtitle counts after its merges and credits), `corpus_tokens`, `case_decision` and
`titles_by_key`. Naming convention of the methods:

- `wiktionary_form(key)`: a form of the Wiktionary pass, of the abbreviation rule or of the build's own derived forms;
  the junk rules of §12.1 item 4 use only these;
- `spelling_form(key)`: the key is no such form, but the spelling dictionary knows it (and the build does not exclude
  it, Portuguese §9.4 steps 2–3);
- `language_form(key)`: either of the two ("a form of the language", §12.1 item 1).
"""

import combined
from common import jlower


class AttestedRules:
    # set by setup_attested()
    spelling = None
    freq = None
    english_keys = frozenset()
    name_rule = None

    def setup_attested(self, spelling, freq, english_keys):
        """[spelling]: SpellingIndex of the language; [freq]: the written-language list; [english_keys]: the keys of the
        en-US word list (§12.4 condition 5)."""
        self.spelling = spelling
        self.freq = freq
        self.english_keys = english_keys
        self.spelling_excluded = set()
        self.colloquial = set()          # keys kept by the colloquial path (flagged unattested)
        self.colloquial_dropped = {}     # key -> the first condition of §12.4 it failed
        self.written_admitted = set()    # forms kept with a subtitle count of 0 or 1 through §12.3
        self.foreign_lower = frozenset()  # lower-case forms of the other languages' lists (§12.4 item 7)
        self.near_form = {}              # key dropped by §12.4 item 6 -> its near form
        self.name_rule = None

    def setup_foreign(self, foreign_lower):
        """[foreign_lower]: the lower-case forms of the word lists of the other languages (05b §12.4 item 7)."""
        self.foreign_lower = frozenset(foreign_lower)

    # ------------------------------------------------------------------ forms (§12.1)

    def wiktionary_form(self, key):
        return key in self.forms_by_key or key in getattr(self, "accepted", ())

    def spelling_form(self, key):
        if self.spelling is None or self.wiktionary_form(key) or key in self.spelling_excluded:
            return False
        return self.spelling.knows(key)

    def spelling_protects(self, key):
        """"Is itself a form, never merged" (§12.1 item 5) for the spelling dictionary: it accepts the key as written,
        or only with a capital and running text writes the key mostly capitalised. A key the dictionary knows only as
        a capitalised word while the subtitles' key stands for a lower-case spelling (a place name "Dificil" next to
        the accent-less "dificil") is no form that keeps the subtitle tokens from their accented word."""
        if self.spelling is None or key in self.spelling_excluded:
            return False
        import spellcheck
        b = self.spelling.casing_bits(key)
        if b & spellcheck.LOWER:
            return True
        if not b:
            return False
        ev = self.case_evidence(key)
        if not ev:
            return False
        top = sorted(ev.items(), key=lambda fc: (-fc[1], fc[0]))[0][0]
        return top[:1] != top[:1].lower()

    def case_evidence(self, key):
        """Running-text counts of the key's cased variants (the build's case evidence)."""
        stats = getattr(self, "stats", None)
        return stats.evidence(key) if stats is not None else {}

    def prefetch_spelling(self, keys):
        if self.spelling is not None:
            self.spelling.prefetch(keys)

    def language_form(self, key):
        return self.wiktionary_form(key) or self.spelling_form(key)

    def dictionary_forms(self, key):
        """The cased dictionary forms of the case decision: Wiktionary's, else the spelling dictionary's (§12.1 item
        6)."""
        forms = self.forms_by_key.get(key)
        if forms:
            return forms
        if self.spelling is None or key in self.spelling_excluded:
            return set()
        return self.spelling.forms(key)

    # ------------------------------------------------------------------ written-language frequency (§12.2, §12.3)

    def written(self, key):
        if self.freq is None:
            return 0.0
        return combined.written_raw(self.freq, key)

    def admitted(self, key, count):
        """§12.3: a form is kept with count ≥ 2, or with count 0 or 1 when it occurs in the written-language list."""
        if count >= 2:
            return True
        if self.has_written(key, count):
            self.written_admitted.add(key)
            return True
        return False

    def has_written(self, key, count):
        """pW(k) > 0: the written frequency of fold(k) shared among the forms with that fold in proportion to their
        subtitle counts (§12.2 item 2) — a key without a subtitle count gets no share when another form of its fold
        has one ("gefaßter" next to "gefasster")."""
        if self.written(key) <= 0:
            return False
        if count == 0:
            f = combined.fold(key)
            if any(v != key and self.count(v) > 0 and self.language_form(v) for v in fold_variants(f, self.has_sharp_s())):
                return False
        return True

    def written_candidates(self, key_ok, extra=()):
        """Keys without a subtitle count that are forms of the language and occur in the written-language list:
        the Wiktionary forms (and [extra]) whose fold is in the list, and the list's words (with ß for ss where the
        language has ß) that the spelling dictionary knows. [key_ok]: the build's alphabet and length test."""
        merged = set(getattr(self, "merged", None) or ()) | set(getattr(self, "umlaut_merged", None) or ())
        out = set()
        for k in list(self.forms_by_key) + list(extra):
            if k not in self.counts and k not in merged and key_ok(k) and combined.letters_only(k) and self.written(k) > 0:
                out.add(k)
        spell = set()
        for w in self.freq:
            if not combined.letters_only(w):
                continue
            for v in fold_variants(w, self.has_sharp_s()):
                if v in self.counts or v in out or self.wiktionary_form(v) or not key_ok(v) or jlower(v) != v:
                    continue
                if v in self.spelling_excluded or v in merged:
                    continue
                spell.add(v)
        self.spelling.prefetch(spell)
        out |= {v for v in spell if self.spelling.knows(v)}
        return out

    def has_sharp_s(self):
        return False

    # ------------------------------------------------------------------ colloquial path (§12.4)

    def same_fold_form(self, key):
        f = combined.fold(key)
        for v in fold_variants(f, self.has_sharp_s()):
            if v != key and self.language_form(v):
                return True
        return False

    def colloquial_path(self, key, count):
        """§12.4 for a key that reached the count-rule step (its foreign-title clause already applied). Records the
        decision; returns True if the key is kept."""
        ok, reason = combined.colloquial(key, count, self.corpus_tokens, self.written(key), self.same_fold_form(key),
                                         key in self.english_keys)
        if ok:
            reason = self.colloquial_amendments(key)
            ok = reason is None
        if ok:
            self.colloquial.add(key)
        else:
            self.colloquial_dropped[key] = reason
        return ok

    def key_letters(self):
        """The letters of the build's key alphabet (the edits of §12.4 item 6)."""
        import sys
        letters = getattr(sys.modules[type(self).__module__], "ALLOWED", None)
        return sorted(ch for ch in (letters or "abcdefghijklmnopqrstuvwxyz") if ch.isalpha())

    def plain_form(self, f):
        """A form of the language, lower case and not flagged name (§12.4 item 6)."""
        if self.wiktionary_form(f):
            forms = self.forms_by_key.get(f)
            if forms and f not in forms:
                return False
        elif self.spelling_form(f):
            import spellcheck
            if not self.spelling.casing_bits(f) & spellcheck.LOWER:
                return False
        else:
            return False
        return not (self.name_rule and self.name_rule(f))

    def apostrophe_prefixes(self):
        """The parts before an apostrophe of the language's forms with an apostrophe (§12.4 item 8)."""
        if getattr(self, "_apostrophe_prefixes", None) is None:
            forms = set()
            for k, fs in self.forms_by_key.items():
                forms.add(k)
                forms.update(fs)
            forms.update(getattr(self, "accepted", ()))
            for attr in ("apostrophe_pages", "apostrophe_words"):
                forms.update(getattr(self, attr, None) or ())
            out = set()
            for f in forms:
                low = jlower(f)
                for i, ch in enumerate(low):
                    if ch == "'" and i > 0:
                        out.add(low[:i])
            self._apostrophe_prefixes = out
        return self._apostrophe_prefixes

    def colloquial_amendments(self, key):
        """§12.4 items 6–8 (0.7.0 amendments): None if the key passes, else the failed item."""
        letters = self.key_letters()
        need = combined.COLLOQ_TYPO_RATIO * self.written(key)
        near = sorted(c for c in combined.edits1(key, letters) if self.written(c) >= need and self.plain_form(c))
        if near and not any(self.plain_form(e) for e in sorted(combined.end_variants(key, letters))):
            self.near_form[key] = near[0]
            return "nearForm"
        if key in self.foreign_lower:
            return "otherLanguage"
        if key in self.apostrophe_prefixes():
            return "apostropheFragment"
        return None

    # ------------------------------------------------------------------ classes (§12.2)

    def combined_entries(self, selected, zero=(), fixed=None, weight0=(), names=None, extra_max=0):
        """Classes of the selected keys (key -> [(form, share)]) from their combined counts. [zero]: class-0 keys;
        [fixed]: key -> fixed class; [weight0]: keys that use the subtitles alone; [names]: the name rule's test.
        Sets max_count, scale, pw, combined_counts and names; returns key -> [(form, class)], best first."""
        count = self.count
        excluded = set(weight0) | {k for k in selected if not combined.letters_only(k)}
        self.pw = combined.shared_written(self.freq, list(selected), count, excluded)
        names = names or (lambda k: False)
        self.names = {k for k in selected if k not in excluded and names(k)}
        cc = {}
        for k in selected:
            cc[k] = combined.combined_count(count(k), self.corpus_tokens, self.pw.get(k, 0.0), k in self.names,
                                            k not in excluded)
        self.combined_counts = cc
        classes = combined.Classes({k: v for k, v in cc.items() if not fixed or k not in fixed}, extra_max)
        self.max_count = classes.max_count
        self.scale = classes.scale
        entries = {}
        # 0.7.1: the form counts (combined count x form share) for the frequency ranks of 05b §1.1
        self.form_counts = {}
        for key, sel in selected.items():
            pairs = []
            seen = set()
            for f, share in sel:
                if f in seen or jlower(f) != key:
                    continue
                seen.add(f)
                pairs.append((f, share))
                self.form_counts[(key, f)] = cc[key] * share
            forms = classes.forms(pairs, cc[key], key not in self.colloquial, key in zero,
                                  fixed.get(key) if fixed else None)
            forms.sort(key=lambda fc: -fc[1])
            if forms:
                entries[key] = forms
        return entries


def fold_variants(folded, sharp_s):
    """Every lower-case spelling whose fold is [folded]: with ß for any "ss" where the language writes ß."""
    if not sharp_s or "ss" not in folded:
        return [folded]
    out = [""]
    i = 0
    n = len(folded)
    while i < n:
        if folded.startswith("ss", i):
            out = [o + "ss" for o in out] + [o + "ß" for o in out]
            i += 2
        else:
            out = [o + folded[i] for o in out]
            i += 1
        if len(out) > 64:
            break
    return out
