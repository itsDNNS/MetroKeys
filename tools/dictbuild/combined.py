"""Written-language frequencies, the combined frequency, the colloquial path and the classes of the 0.7.0 builds
(spec/05b-dictionaries.md §2.15, §12.2–§12.4, §12.7).

The written-language list (wordfreq v3.2) is read by tools/dicteval/wordfreq.py without the project's code. Nothing in
this module names a word: every decision follows from the pinned sources and the constants below.
"""

import math
import os
import sys

from common import freq_class

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "dicteval"))

import wordfreq  # noqa: E402

WORDFREQ = {"de-DE": "de", "en-US": "en", "es-ES": "es", "pt-BR": "pt", "it-IT": "it", "fr-FR": "fr"}

# 05b §12.2, §12.4
WRITTEN_WEIGHT = 0.5
COLLOQ_MIN_WRITTEN = 1e-6
COLLOQ_MAX_RATIO = 4.0
COLLOQ_MIN_COUNT = 40
# 05b §12.4 item 6 (0.7.0 amendment): the near form's written frequency at least this many times the key's
COLLOQ_TYPO_RATIO = 5
# the floors of §12.2 item 3
W_FLOOR = 0.5e-8

_cache = {}


def written_list(tag, src):
    """{folded word: frequency per token} of the language's wordfreq file (loaded once per process)."""
    if tag not in _cache:
        _cache[tag] = wordfreq.load(os.path.join(src, "wordfreq", f"large_{WORDFREQ[tag]}.msgpack.gz"))
    return _cache[tag]


fold = wordfreq.fold


def letters_only(key):
    return bool(key) and all(ch.isalpha() for ch in key)


def written_raw(freq, key):
    """pW of a key before the share among keys with the same fold (keys made of letters only)."""
    if not letters_only(key):
        return 0.0
    return freq.get(fold(key), 0.0)


def shared_written(freq, keys, count, excluded=()):
    """05b §12.2 item 2: pW per key, the frequency of fold(key) divided among the kept keys with the same fold in
    proportion to their subtitle counts (equal parts if all are 0); keys with an apostrophe or hyphen and the keys in
    [excluded] (alias keys) get 0."""
    groups = {}
    for k in keys:
        if k in excluded or not letters_only(k):
            continue
        groups.setdefault(fold(k), []).append(k)
    out = {}
    for f, ks in groups.items():
        p = freq.get(f, 0.0)
        if not p:
            continue
        total = sum(count(k) for k in ks)
        for k in ks:
            out[k] = p * (count(k) / total if total else 1.0 / len(ks))
    return out


def combined_count(count_s, total, p_w, name=False, written=True):
    """05b §12.2 items 3–5: the combined count countC = p · T_S. [written] False = rule 3 with weight 0 (apostrophe
    and hyphen keys, alias keys, elision prefixes: subtitles alone)."""
    ls = math.log10(max(count_s / total, 0.5 / total))
    if not written:
        return 10 ** ls * total
    lw = math.log10(max(p_w, W_FLOOR))
    if name:
        lp = min(ls, lw)
    else:
        lp = (1 - WRITTEN_WEIGHT) * ls + WRITTEN_WEIGHT * lw
    return 10 ** lp * total


def edits1(key, letters):
    """Every spelling one substitution, insertion, deletion or swap of two adjacent letters away from [key]."""
    out = set()
    n = len(key)
    for i in range(n):
        out.add(key[:i] + key[i + 1:])
        if i + 1 < n and key[i] != key[i + 1]:
            out.add(key[:i] + key[i + 1] + key[i] + key[i + 2:])
        for c in letters:
            if c != key[i]:
                out.add(key[:i] + c + key[i + 1:])
    for i in range(n + 1):
        for c in letters:
            out.add(key[:i] + c + key[i:])
    out.discard(key)
    return out


def end_variants(key, letters):
    """The spellings that differ from [key] only at its end (05b §12.4 item 6): its last letter removed, one letter
    added at its end, or its last letter replaced."""
    out = {key[:-1]} if len(key) > 1 else set()
    for c in letters:
        out.add(key + c)
        if key and c != key[-1]:
            out.add(key[:-1] + c)
    out.discard(key)
    return out


def colloquial(key, count_s, total, p_w, same_fold_form, english_key):
    """05b §12.4 conditions 1–5 for a key that is no form and reached its build's count-rule step (the foreign-title
    clause of that step is checked by the caller). Returns (kept, reason) with reason the first failed condition."""
    if count_s < COLLOQ_MIN_COUNT:
        return False, "count"
    if p_w < COLLOQ_MIN_WRITTEN:
        return False, "written"
    if (count_s / total) / p_w > COLLOQ_MAX_RATIO:
        return False, "ratio"
    if same_fold_form:
        return False, "fold"
    if english_key:
        return False, "english"
    return True, None


class Classes:
    """Classes of a word list from combined counts (05b §12.2 items 5 and 6)."""

    def __init__(self, combined, extra_max=0):
        """[combined]: key -> combined count of every kept key whose class it gives; [extra_max]: the largest count of
        further kept keys that use the subtitles alone (French alias keys)."""
        self.max_count = max(max(combined.values()), extra_max)
        self.scale = 255.0 / math.log(self.max_count)

    def forms(self, sel, count_c, attested, zero=False, fixed=None):
        """Classes of the forms of one key from (form, share) pairs: countC × share, at least class 1 for an attested
        key, 0 for the class-0 spellings, or the [fixed] class (German dictionary-only lemmas)."""
        out = []
        for form, share in sel:
            if zero:
                cls = 0
            elif fixed is not None:
                cls = fixed
            else:
                cls = freq_class(count_c * share, self.scale)
                if attested and cls < 1:
                    cls = 1
            out.append((form, cls))
        return out


def gap_report(keys, count, pw, total, n=100):
    """05b §12.7: the keys with the largest |log10 pS − log10 pW| in each direction (both frequencies > 0)."""
    rows = []
    for k in keys:
        c = count(k)
        p = pw.get(k, 0.0)
        if c <= 0 or p <= 0:
            continue
        rows.append((math.log10(c / total) - math.log10(p), k, c, p))
    rows.sort(key=lambda r: (-r[0], r[1]))
    fmt = lambda r: f"{r[1]} pS {r[2] / total:.3g} pW {r[3]:.3g} ratio {10 ** r[0]:.3g}"
    sub = [fmt(r) for r in rows[:n]]
    rows.sort(key=lambda r: (r[0], r[1]))
    wri = [fmt(r) for r in rows[:n]]
    return {"subtitlesOverWrittenTop100": sub, "writtenOverSubtitlesTop100": wri}


def describe(key, count_s, total, p_w):
    ratio = (count_s / total) / p_w if p_w else float("inf")
    return f"{key} {int(count_s)} pS {count_s / total:.3g} pW {p_w:.3g} ratio {ratio:.3g}"
