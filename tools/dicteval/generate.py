"""Test-set generation of the dictionary evaluation (spec/05c-dictionary-evaluation.md §2, §3).

Deterministic: every choice is made with h(x) = the first 8 bytes of SHA-256 of
"<EVAL_VERSION>|<tag>|<purpose>|<EVAL_SALT>|<base key>|<extra>" (§3.1); the half of an item depends only on
"<EVAL_VERSION>|<tag>|half|<base key>" (§3.2). The sets depend only on the pinned inputs, never on a built word list.
"""

import gzip
import hashlib
import io
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import realerrors  # noqa: E402
import wordfreq  # noqa: E402
from languages import Language, strip_diacritics  # noqa: E402
from common import capitalize_first, jlower, jupper_char  # noqa: E402

EVAL_VERSION = 1
BANDS = [("B1", 1, 1000, 400), ("B2", 1001, 5000, 400), ("B3", 5001, 20000, 400)]
F1_TOP = 2000
F2_RANGE = (20001, 100000)
F2_N = 600
F3_N = 600
NAMES_N = 400
F5_MAX = 300
E7_N = 400
E8_N = 400
F6_N = {"it-IT": 300, "fr-FR": 300, "en-US": 200, "pt-BR": 100}
SENT_MIN, SENT_MAX = 4, 30
# the lexicon file keeps the trusted words up to the largest rank any set or lint check uses (F2, L3)
LEXICON_TRUSTED_MAX = 100000
COLUMNS = ["id", "half", "set", "band", "lengthBand", "variant", "sentenceId", "before", "expected", "baseKey"]


def h(tag, purpose, salt, base, extra=""):
    x = f"{EVAL_VERSION}|{tag}|{purpose}|{salt}|{base}|{extra}"
    return int.from_bytes(hashlib.sha256(x.encode("utf-8")).digest()[:8], "big")


def half_of(tag, base):
    x = f"{EVAL_VERSION}|{tag}|half|{base}"
    v = int.from_bytes(hashlib.sha256(x.encode("utf-8")).digest()[:8], "big")
    return "dev" if v % 2 == 0 else "test"


def pick_n(keys, n, hv):
    """Sort [keys] by hv(key) (ties: code-point order), take the first n."""
    return sorted(keys, key=lambda k: (hv(k), k))[:n]


def pick_one(lst, hv):
    return lst[hv % len(lst)] if lst else None


def band_of(rank):
    if rank is None:
        return "-"
    for name, lo, hi, _ in BANDS:
        if lo <= rank <= hi:
            return name
    return "-"


def length_band(key):
    n = sum(1 for c in key if c.isalpha())
    return "L1" if n <= 3 else "L2" if n <= 6 else "L3"


# ---------------------------------------------------------------------- edits on the lower-cased key with case flags


def _cells(form):
    """[(lower letter, upper flag, original index)] of a case form."""
    return [[jlower(c), c != jlower(c), i] for i, c in enumerate(form)]


def _text(cells):
    return "".join(jupper_char(c) if up else c for c, up, _ in cells)


def single_edits(cells, code, neighbours, key_letter):
    """All single edits of type [code] (E1 substitution, E2 omission, E2a double-letter omission, E3 insertion,
    E4 swap) on [cells]; returns [(typed text, touched original indices, new cells)]."""
    out = []
    n = len(cells)
    letters = [c for c, _, _ in cells]
    if code == "E1":
        for i in range(n):
            for nb in sorted(neighbours.get(key_letter(letters[i]), ())):
                if nb == letters[i]:
                    continue
                new = [list(c) for c in cells]
                new[i][0] = nb
                out.append((_text(new), {cells[i][2]}, new))
    elif code in ("E2", "E2a"):
        for i in range(n):
            double = i > 0 and letters[i] == letters[i - 1]
            if (code == "E2") == double:
                continue
            new = [list(c) for c in cells[:i] + cells[i + 1:]]
            out.append((_text(new), {cells[i][2]}, new))
    elif code == "E3":
        for i in range(n):
            for nb in sorted(neighbours.get(key_letter(letters[i]), ())):
                for at in (i, i + 1):
                    left = cells[at - 1][1] if at > 0 else cells[0][1]
                    new = [list(c) for c in cells[:at]] + [[nb, left, None]] + [list(c) for c in cells[at:]]
                    out.append((_text(new), {cells[i][2]}, new))
    elif code == "E4":
        for i in range(n - 1):
            if letters[i] == letters[i + 1]:
                continue
            new = [list(c) for c in cells]
            new[i][0], new[i + 1][0] = letters[i + 1], letters[i]
            out.append((_text(new), {cells[i][2], cells[i + 1][2]}, new))
    return out


class Generator:
    def __init__(self, lang, salt):
        self.lang = lang
        self.tag = lang.tag
        self.salt = salt
        self.report = {"evalVersion": EVAL_VERSION, "salt": salt, "language": lang.tag}

    def hv(self, purpose, base, extra=""):
        return h(self.tag, purpose, self.salt, base, extra)

    # ------------------------------------------------------------------ §2.1 trusted words, names, abbreviations

    def lexicon(self):
        L = self.lang
        b = L.builder
        universe = set(L.scowl) if L.tag == "en-US" else set(b.forms_by_key)
        universe |= L.names | L.abbreviation_keys
        groups = {}
        for k in universe:
            if "'" in k or "-" in k:
                continue
            f = wordfreq.fold(k)
            if f in L.freq:
                groups.setdefault(f, []).append(k)
        pw = {}
        for f, ks in groups.items():
            p = L.freq[f]
            total = sum(L.subtitle_count(k) for k in ks)
            for k in ks:
                pw[k] = p * (L.subtitle_count(k) / total if total else 1.0 / len(ks))
        self.pw = pw
        trusted = [k for k in pw if pw[k] > 0 and L.trusted_candidate(k)]
        trusted.sort(key=lambda k: (-pw[k], k))
        self.trusted = trusted
        self.rank = {k: i + 1 for i, k in enumerate(trusted)}
        self.case = {}
        for k in trusted:
            self.case[k] = L.case_forms(k)
        names = [k for k in L.names if pw.get(k, 0) > 0 and L.letters_ok(k)]
        names.sort(key=lambda k: (-pw[k], k))
        self.names = names
        self.name_rank = {k: i + 1 for i, k in enumerate(names)}
        for k in names:
            forms = L.case_forms(k)
            cap = [f for f in forms if f[:1] != jlower(f[:1])]
            self.case[k] = cap[:1] + [f for f in forms if f not in cap[:1]] if cap else [capitalize_first(k)] + forms
        abbr = [k for k in L.abbreviation_keys if pw.get(k, 0) > 0 and all(c.isalpha() for c in k)]
        abbr.sort(key=lambda k: (-pw[k], k))
        self.abbreviations = abbr[:F5_MAX]
        for k in self.abbreviations:
            self.case[k] = L.case_forms(k)
        self.abbreviations_all = len(abbr)

    def lexicon_report(self):
        self.report["trustedWords"] = len(self.trusted)
        self.report["trustedTop20"] = [f"{k} {self.case[k][0]}" for k in self.trusted[:20]]
        self.report["namesWithWrittenFrequency"] = len(self.names)
        self.report["namesTop20"] = [f"{k} {self.case[k][0]}" for k in self.names[:20]]
        self.report["abbreviationsWithWrittenFrequency"] = self.abbreviations_all
        self.report.update(self.context_report)

    def zipf(self, key):
        p = self.pw.get(key, 0)
        return math.log10(p) + 9 if p > 0 else 0.0

    # ------------------------------------------------------------------ §2.2 context occurrences

    def contexts(self):
        L = self.lang
        self.sentences = []
        occ = {}       # key -> [(sentence index, start, end)]
        elided = {}    # elided word key (letters) -> [(si, start, end)]
        pairs = {}     # (key a, key b) -> [(si, start a, end b)]
        special = {}   # en contractions, fr apostrophe words and compounds: key (letters) -> [(si, start, end)]
        held = 0
        skipped_stock = 0
        for sid, text, tatoeba in L.held_out():
            held += 1
            toks = L.tokens(text)
            words = [t for t in toks if t is not None]
            if not SENT_MIN <= len(words) <= SENT_MAX:
                continue
            if tatoeba and any(t[3] == "w" and t[2] in L.stock for t in words):
                skipped_stock += 1
                continue
            norm = text.replace("’", "'")
            si = len(self.sentences)
            self.sentences.append((sid, norm))
            local = 0
            seq = []   # (token, local index)
            for t in toks:
                if t is None:
                    local = 0
                    continue
                seq.append((t, local))
                local += 1
            for idx, ((s, e, tok, kind), li) in enumerate(seq):
                if li < 2:
                    continue
                before_ok = s > 0 and (norm[s - 1].isspace() or norm[s - 1] in "„“\"«»‚‘›‹([")
                if not before_ok:
                    continue
                if kind == "p":
                    if idx + 1 < len(seq):
                        (s2, e2, tok2, kind2), _ = seq[idx + 1]
                        if kind2 == "w" and s2 == e and self._after_ok(norm, e2) and "'" not in tok2:
                            elided.setdefault(jlower(tok + tok2).replace("'", ""), []).append((si, s, e2))
                    continue
                # hyphen compounds (fr): word-word(-word) joined by single hyphens
                if L.compounds:
                    j = idx
                    end = e
                    parts = [tok]
                    while j + 1 < len(seq) and len(parts) < 4:
                        (s3, e3, t3, k3), _ = seq[j + 1]
                        if k3 != "w" or norm[end:s3] != "-":
                            break
                        parts.append(t3)
                        end = e3
                        j += 1
                        joined = jlower("-".join(parts))
                        if joined in L.compounds and self._after_ok(norm, end):
                            special.setdefault(joined.replace("-", ""), []).append((si, s, end))
                if not self._after_ok(norm, e):
                    continue
                low = jlower(tok)
                if "'" in tok:
                    if (L.tag == "en-US" and tok in L.contractions) or low in L.apostrophe_words:
                        special.setdefault(low.replace("'", ""), []).append((si, s, e))
                    continue
                occ.setdefault(low, []).append((si, s, e))
                if idx > 0:
                    (sp, ep, tp, kp), lp = seq[idx - 1]
                    if kp == "w" and lp >= 2 and norm[ep:s] == " " and "'" not in tp and \
                            (sp > 0 and (norm[sp - 1].isspace() or norm[sp - 1] in "„“\"«»‚‘›‹([")):
                        pairs.setdefault((jlower(tp), low), []).append((si, sp, e))
        self.occ = occ
        self.elided = elided
        self.pairs = pairs
        self.special = special
        self.context_report = {"heldOutSentences": held, "contextSentences": len(self.sentences),
                               "sentencesSkippedStockName": skipped_stock}

    @staticmethod
    def _after_ok(text, e):
        return e >= len(text) or not (text[e].isalnum() or text[e] in "'-")

    def occurrences(self, key, forms):
        return [o for o in self.occ.get(key, ()) if self.sentences[o[0]][1][o[1]:o[2]] in forms]

    def pick_context(self, base, occs):
        occs = sorted(occs, key=lambda o: (self.sentences[o[0]][0], o[1]))
        return pick_one(occs, self.hv("context", base, "ctx"))

    # ------------------------------------------------------------------ items

    def add(self, set_code, base, rank, variant, sentence_id, before, expected, length_key=None):
        self.items.append({"half": half_of(self.tag, base), "set": set_code, "band": band_of(rank),
                           "lengthBand": length_band(length_key or base), "variant": variant,
                           "sentenceId": sentence_id or "-", "before": before.replace("\t", " "),
                           "expected": expected, "baseKey": base, "pw": self.pw.get(base, 0.0)})

    def generate(self):
        L = self.lang
        self.items = []
        stats = {"baseWordsWithoutContext": 0, "collisionsAcrossBaseWords": 0}
        key_letter = self._key_letter
        # §3.3 base words
        base_words = []
        for name, lo, hi, n in BANDS:
            pool = [k for k in self.trusted[lo - 1:hi]]
            base_words += pick_n(pool, n, lambda k, name=name: self.hv("base", k, name))
        base_words.sort(key=lambda k: self.rank[k])
        self.report["baseWords"] = len(base_words)
        error_items = []
        for k in base_words:
            c = self.case[k][0]
            forms = set(self.case[k])
            occs = self.occurrences(k, forms)
            ctx = self.pick_context(k, occs)
            if ctx is None:
                stats["baseWordsWithoutContext"] += 1
            letters = len(k)
            for code in ("E1", "E2", "E2a", "E3", "E4"):
                need = 4 if code in ("E2", "E2a") else 3
                if letters < need:
                    continue
                cands = single_edits(_cells(c), code, L.neighbours, key_letter)
                if not cands:
                    continue
                typed_list = sorted({t for t, _, _ in cands})
                typed = pick_one(typed_list, self.hv("edit", k, code))
                error_items.append((code, k, typed, c, ctx, self._edit_for(code, k, typed)))
            # E5 / F7
            stripped = strip_diacritics(c)
            if stripped != c:
                skey = jlower(stripped)
                if skey in self.rank:
                    error_items.append(("F7", k, stripped, stripped, ctx, None))
                else:
                    error_items.append(("E5", k, stripped, c, ctx, None))
            # E6 (German nouns)
            if L.tag == "de-DE" and c[:1] != jlower(c[:1]):
                cap_occs = [o for o in occs if self.sentences[o[0]][1][o[1]:o[2]] == c]
                error_items.append(("E6", k, jlower(c), c, self.pick_context(k, cap_occs), None))
            # E9
            if letters >= 6:
                first = []
                for code in ("E1", "E2", "E2a", "E3", "E4"):
                    first += [(t, touched, cells) for t, touched, cells in
                              single_edits(_cells(c), code, L.neighbours, key_letter)]
                first.sort(key=lambda x: x[0])
                f = pick_one(first, self.hv("edit", k, "E9a"))
                second = []
                for code in ("E1", "E2", "E2a", "E3", "E4"):
                    for t, touched, cells in single_edits(f[2], code, L.neighbours, key_letter):
                        if None in touched or touched & f[1]:
                            continue
                        second.append(t)
                second = sorted(set(second))
                if second:
                    error_items.append(("E9", k, pick_one(second, self.hv("edit", k, "E9b")), c, ctx, "E9"))
        # the same typed text from two base words: keep the base word with the higher pW
        best = {}
        for it in error_items:
            if it[0] == "F7":
                continue
            t, k = it[2], it[1]
            if t not in best or self.pw[k] > self.pw[best[t]]:
                best[t] = k
        for code, k, typed, c, ctx, _ in error_items:
            if code != "F7" and best.get(typed) != k:
                stats["collisionsAcrossBaseWords"] += 1
                continue
            rank = self.rank[k]
            self.add(code, k, rank, "N", None, ", " + typed, c if code != "F7" else typed)
            if ctx is not None:
                si, s, e = ctx
                sid, text = self.sentences[si]
                tok = text[s:e]
                if code == "F7":
                    styped = strip_diacritics(tok)
                    self.add(code, k, rank, "S", sid, text[:s] + styped, styped)
                    continue
                if code == "E6" and tok != c:
                    continue
                styped = self._apply_to_token(code, k, typed, c, tok)
                if styped is not None:
                    self.add(code, k, rank, "S", sid, text[:s] + styped, tok)
        # E6 names (other languages)
        if L.tag != "de-DE":
            for k in pick_n(self.names, NAMES_N, lambda k: self.hv("pick", k, "E6")):
                form = self.case[k][0]
                rank = self.name_rank[k]
                self.add("E6", k, rank, "N", None, ", " + jlower(form), form)
                ctx = self.pick_context(k, self.occurrences(k, {form}))
                if ctx is not None:
                    sid, text = self.sentences[ctx[0]]
                    self.add("E6", k, rank, "S", sid, text[:ctx[1]] + jlower(form), form)
        # E7 missing apostrophe or hyphen (en, fr, it)
        e7 = self._e7_candidates()
        if L.tag in ("en-US", "fr-FR", "it-IT"):
            for k in pick_n(sorted(e7), E7_N, lambda k: self.hv("pick", k, "E7")):
                ctx = self.pick_context(k, e7[k])
                sid, text = self.sentences[ctx[0]]
                word = text[ctx[1]:ctx[2]]
                typed = word.replace("'", "").replace("-", "")
                self.add("E7", k, None, "N", None, ", " + typed, word)
                self.add("E7", k, None, "S", sid, text[:ctx[1]] + typed, word)
            self.report["E7candidates"] = len(e7)
        # E8 missing space
        top = {k for k in self.trusted[:1000]}
        e8 = {}
        for (a, b2), occs in self.pairs.items():
            if a in top and b2 in top:
                e8[a + b2] = e8.get(a + b2, []) + occs
        for k in pick_n(sorted(e8), E8_N, lambda k: self.hv("pick", k, "E8")):
            ctx = self.pick_context(k, e8[k])
            sid, text = self.sentences[ctx[0]]
            pair = text[ctx[1]:ctx[2]]
            typed = pair.replace(" ", "")
            self.add("E8", k, None, "N", None, ", " + typed, pair, length_key=k)
            self.add("E8", k, None, "S", sid, text[:ctx[1]] + typed, pair, length_key=k)
        self.report["E8candidates"] = len(e8)
        # R real errors (variant N only)
        self._real_errors()
        # F1 frequent words, F2 rare words
        for k in self.trusted[:F1_TOP]:
            self._f_item("F1", k)
        lo, hi = F2_RANGE
        for k in sorted(pick_n(self.trusted[lo - 1:hi], F2_N, lambda k: self.hv("pick", k, "F2")), key=lambda k: self.rank[k]):
            self._f_item("F2", k)
        # F3 German nouns inside a sentence
        if L.tag == "de-DE":
            cands = {}
            for k in self.trusted:
                caps = {f for f in self.case[k] if f[:1] != jlower(f[:1])}
                if not caps:
                    continue
                o = self.occurrences(k, caps)
                if o:
                    cands[k] = o
            for k in sorted(pick_n(sorted(cands), F3_N, lambda k: self.hv("pick", k, "F3")), key=lambda k: self.rank[k]):
                ctx = self.pick_context(k, cands[k])
                sid, text = self.sentences[ctx[0]]
                tok = text[ctx[1]:ctx[2]]
                self.add("F3", k, self.rank[k], "S", sid, text[:ctx[1]] + tok, tok)
            self.report["F3candidates"] = len(cands)
        # F4 names
        for k in sorted(pick_n(self.names, NAMES_N, lambda k: self.hv("pick", k, "F4")), key=lambda k: self.name_rank[k]):
            form = self.case[k][0]
            rank = self.name_rank[k]
            self.add("F4", k, rank, "N", None, ", " + form, form)
            ctx = self.pick_context(k, self.occurrences(k, {form}))
            if ctx is not None:
                sid, text = self.sentences[ctx[0]]
                self.add("F4", k, rank, "S", sid, text[:ctx[1]] + form, form)
        # F5 abbreviations (without the period)
        for i, k in enumerate(self.abbreviations):
            form = self.case[k][0]
            self.add("F5", k, i + 1, "N", None, ", " + form, form)
        # F6 elisions, apostrophe and hyphen words (variant S)
        if L.tag in F6_N:
            pool = self._f6_candidates(e7)
            for k in pick_n(sorted(pool), F6_N[L.tag], lambda k: self.hv("pick", k, "F6")):
                ctx = self.pick_context(k, pool[k])
                sid, text = self.sentences[ctx[0]]
                word = text[ctx[1]:ctx[2]]
                self.add("F6", k, None, "S", sid, text[:ctx[1]] + word, word)
            self.report["F6candidates"] = len(pool)
        self.report.update(stats)
        counts = {}
        for it in self.items:
            key = f"{it['set']} {it['variant']} {it['half']}"
            counts[key] = counts.get(key, 0) + 1
        self.report["items"] = len(self.items)
        self.report["itemsBySetVariantHalf"] = dict(sorted(counts.items()))

    def _f_item(self, code, k):
        c = self.case[k][0]
        rank = self.rank[k]
        self.add(code, k, rank, "N", None, ", " + c, c)
        ctx = self.pick_context(k, self.occurrences(k, set(self.case[k])))
        if ctx is not None:
            sid, text = self.sentences[ctx[0]]
            tok = text[ctx[1]:ctx[2]]
            self.add(code, k, rank, "S", sid, text[:ctx[1]] + tok, tok)

    def _e7_candidates(self):
        """E7 candidates (by their letters): elided words whose remainder is a trusted word (it, fr), the apostrophe
        words and hyphen compounds of 05b §11.5 (fr) and the SCOWL contractions up to size 50 (en), each with its
        context occurrences."""
        L = self.lang
        out = {}
        if L.tag in ("fr-FR", "it-IT"):
            for k, occs in self.elided.items():
                if self._elided_remainder_trusted(occs):
                    out[k] = occs
        if L.tag in ("fr-FR", "en-US"):
            for k, occs in self.special.items():
                out.setdefault(k, [])
                out[k] = out[k] + occs
        return out

    def _elided_remainder_trusted(self, occs):
        si, s, e = occs[0]
        text = self.sentences[si][1][s:e]
        rest = jlower(text.split("'", 1)[1]) if "'" in text else ""
        return rest in self.rank

    def _f6_candidates(self, e7):
        L = self.lang
        if L.tag == "en-US":
            return {k: v for k, v in self.special.items()}
        if L.tag == "pt-BR":
            return {k: v for k, v in self.elided.items() if self._elided_remainder_trusted(v)}
        return {k: v for k, v in self.elided.items() if self._elided_remainder_trusted(v)}

    def _real_errors(self):
        L = self.lang
        base = os.path.join(L.src, "realerrors")
        manifest = json.load(open(os.path.join(HERE, "sources.json"), encoding="utf-8"))["sources"]
        entry = next((s for s in manifest if s["language"] == L.tag), None)
        if entry is None:
            self.report["realErrors"] = {"list": None}
            return
        with open(os.path.join(L.src, entry["path"]), encoding="utf-8") as f:
            content = json.load(f)["query"]["pages"][0]["revisions"][0]["slots"]["main"]["content"]
        digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
        if digest != entry["contentSha256"]:
            sys.exit(f"real-error list of {L.tag}: content hash mismatch")
        used = skipped_correction = skipped_form = skipped_shape = duplicate = 0
        seen = set()
        for wrong, right in realerrors.PARSERS[L.tag](content):
            if not L.letters_ok(jlower(wrong)) or not all(c.isalpha() for c in right) or not right:
                skipped_shape += 1
                continue
            rk = jlower(right)
            if rk not in self.rank:
                skipped_correction += 1
                continue
            wk = jlower(wrong)
            if L.is_form(wk) or wk in self.rank or wk in self.lang.names:
                skipped_form += 1
                continue
            if wrong in seen:
                duplicate += 1
                continue
            seen.add(wrong)
            used += 1
            self.add("R", rk, self.rank[rk], "N", None, ", " + wrong, right)
        self.report["realErrors"] = {"page": entry["page"], "revid": entry["revid"], "contentSha256": digest,
                                     "rule": realerrors.RULES[L.tag], "used": used,
                                     "skippedNotOneWordOfLetters": skipped_shape,
                                     "skippedCorrectionNotTrusted": skipped_correction,
                                     "skippedMisspellingIsForm": skipped_form, "skippedDuplicate": duplicate}

    def _key_letter(self, ch):
        """The letter whose key types [ch] on the language's letter page (05 §4): its own key, else its base letter."""
        if ch in self.lang.neighbours:
            return ch
        if ch == "ß":
            return "s"
        if ch in "œæ":
            return {"œ": "o", "æ": "a"}[ch]
        import unicodedata
        return unicodedata.normalize("NFD", ch)[0]

    def _edit_for(self, code, k, typed):
        return code

    def _apply_to_token(self, code, k, typed, c, tok):
        """The edit chosen on the case form C, applied to the context token (same letters, maybe another case)."""
        if tok == c:
            return typed
        if jlower(tok) != jlower(c) or len(tok) != len(c):
            return None
        if code == "E5":
            return strip_diacritics(tok)
        if code == "E6":
            return jlower(tok)
        # reapply the token's case pattern position by position to the typed letters
        cells = _cells(tok)
        tl = jlower(typed)
        if len(tl) == len(cells):
            return "".join(jupper_char(ch) if cells[i][1] else ch for i, ch in enumerate(tl))
        out = []
        for i, ch in enumerate(tl):
            j = min(i, len(cells) - 1)
            out.append(jupper_char(ch) if cells[j][1] else ch)
        return "".join(out)

    # ------------------------------------------------------------------ output

    def write(self, out_dir, input_hashes):
        os.makedirs(out_dir, exist_ok=True)
        lines = []
        seq = 0
        for it in self.items:
            seq += 1
            it["id"] = f"{seq:06d}"
            lines.append("\t".join(it[c] for c in COLUMNS))
        header = f"# EVAL_VERSION={EVAL_VERSION}\tsalt={self.salt}\tlanguage={self.tag}\tinputs=" + \
                 ",".join(f"{p}:{s}" for p, s in sorted(input_hashes.items()))
        path = os.path.join(out_dir, f"{self.tag}.{self.salt}.tsv")
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(header + "\n")
            f.write("\t".join(COLUMNS) + "\n")
            for line in lines:
                f.write(line + "\n")
        with open(os.path.join(out_dir, f"{self.tag}.{self.salt}.generation.json"), "w", encoding="utf-8",
                  newline="\n") as f:
            json.dump(self.report, f, indent=1, sort_keys=True, ensure_ascii=False)
            f.write("\n")
        return path

    def write_lexicon(self, out_dir):
        """The ranked trusted words (ranks 1…100 000), names and abbreviations with pW and case forms (read by the lint
        and the report; not packaged): kind (T, N, A), key, rank, pW (6 significant digits), case forms
        (comma-separated)."""
        os.makedirs(out_dir, exist_ok=True)
        buf = io.StringIO()
        buf.write(f"# EVAL_VERSION={EVAL_VERSION}\tlanguage={self.tag}\n")
        buf.write("kind\tkey\trank\tpW\tforms\n")
        for kind, keys in (("T", self.trusted[:LEXICON_TRUSTED_MAX]), ("N", self.names), ("A", self.abbreviations)):
            for i, k in enumerate(keys):
                buf.write(f"{kind}\t{k}\t{i + 1}\t{self.pw[k]:.6g}\t{','.join(self.case[k])}\n")
        data = buf.getvalue().encode("utf-8")
        out = io.BytesIO()
        with gzip.GzipFile(fileobj=out, mode="wb", compresslevel=9, mtime=0, filename="") as gz:
            gz.write(data)
        with open(os.path.join(out_dir, f"{self.tag}.lexicon.tsv.gz"), "wb") as f:
            f.write(out.getvalue())


def run(tags, salts, out_dir, lexicon_dir, input_hashes_fn):
    for tag in tags:
        lang = Language(tag)
        g = Generator(lang, salts[0])
        g.lexicon()
        g.contexts()
        g.write_lexicon(lexicon_dir)
        for salt in salts:
            g.salt = salt
            g.report = {"evalVersion": EVAL_VERSION, "salt": salt, "language": tag}
            g.lexicon_report()
            g.generate()
            path = g.write(out_dir, input_hashes_fn(tag))
            print(f"{tag} salt {salt}: {len(g.items)} items -> {path}", file=sys.stderr)
