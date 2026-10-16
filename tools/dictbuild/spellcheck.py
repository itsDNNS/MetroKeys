"""Spelling-dictionary membership for the 0.7.0 builds (spec/05b-dictionaries.md §2.14, §12.1 item 2).

The lookup itself is the reverse affix lookup of tools/dicteval/hunspell.py (never an expansion of the dictionary,
no compounding). This module adds what a build needs on top of it: one shared instance per language, the three
casings of §12.1 item 2 per key, a parallel prefetch of many keys (the lookup is pure Python; the build asks for
about a million keys per language) and a cache file keyed by the SHA-256 of the spelling inputs, so a rebuild with
unchanged inputs does not repeat the lookups. The cache only stores results of the lookup; deleting it changes
nothing but the build time.
"""

import gzip
import hashlib
import multiprocessing
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "dicteval"))

import spelling  # noqa: E402

# bit 0: accepted as written (lower case), bit 1: with the first letter upper-cased, bit 2: fully upper-cased
LOWER, CAPITAL, UPPER = 1, 2, 4

_worker_dict = None


def _work(keys):
    out = []
    for k in keys:
        c = _worker_dict.casings(k)
        out.append((k, (LOWER if c[0] else 0) | (CAPITAL if c[1] else 0) | (UPPER if c[2] else 0)))
    return out


class SpellingIndex:
    """Casings of keys in the spelling dictionaries of one language (None for en-US, whose spelling source is SCOWL)."""

    def __init__(self, tag, src, cache_dir):
        self.tag = tag
        self.src = src
        self.dicts = spelling.load(src, tag)
        self.bits = {}
        self.cache_path = None
        if self.dicts is None:
            return
        self.cache_path = os.path.join(cache_dir, f"spelling-{tag}-{self._input_hash()[:16]}.tsv.gz")
        if os.path.exists(self.cache_path):
            with gzip.open(self.cache_path, "rt", encoding="utf-8") as f:
                for line in f:
                    k, _, b = line.rstrip("\n").partition("\t")
                    self.bits[k] = int(b)
        self._dirty = False

    def _input_hash(self):
        h = hashlib.sha256()
        for rel in sorted(input_files(self.tag)):
            with open(os.path.join(self.src, rel), "rb") as f:
                h.update(rel.encode("utf-8") + b"\0" + hashlib.sha256(f.read()).digest())
        lookup = os.path.join(os.path.dirname(HERE), "dicteval", "hunspell.py")
        with open(lookup, "rb") as f:
            h.update(hashlib.sha256(f.read()).digest())
        return h.hexdigest()

    def prefetch(self, keys, workers=None):
        """Computes the casings of every key not yet known, in parallel (fork; the dictionaries are shared)."""
        if self.dicts is None:
            return
        todo = sorted({k for k in keys if k not in self.bits})
        if not todo:
            return
        global _worker_dict
        _worker_dict = self.dicts
        workers = workers or max(1, min(16, os.cpu_count() or 1))
        chunk = 2000
        parts = [todo[i:i + chunk] for i in range(0, len(todo), chunk)]
        if len(todo) < 5000 or workers == 1:
            results = [_work(p) for p in parts]
        else:
            ctx = multiprocessing.get_context("fork")
            with ctx.Pool(workers) as pool:
                results = pool.map(_work, parts)
        for part in results:
            for k, b in part:
                self.bits[k] = b
        self._dirty = True
        self.save()

    def save(self):
        if self.dicts is None or not self._dirty:
            return
        tmp = self.cache_path + ".tmp"
        with gzip.GzipFile(tmp, "wb", compresslevel=6, mtime=0) as gz:
            for k in sorted(self.bits):
                gz.write(f"{k}\t{self.bits[k]}\n".encode("utf-8"))
        os.replace(tmp, self.cache_path)
        self._dirty = False

    def casing_bits(self, key):
        if self.dicts is None:
            return 0
        b = self.bits.get(key)
        if b is None:
            c = self.dicts.casings(key)
            b = (LOWER if c[0] else 0) | (CAPITAL if c[1] else 0) | (UPPER if c[2] else 0)
            self.bits[key] = b
            self._dirty = True
        return b

    def knows(self, key):
        """05b §12.1 item 2: the dictionary accepts the key as written, capitalised or fully upper-cased."""
        return self.casing_bits(key) != 0

    def capital_only(self, key):
        """Accepted with its first letter upper-cased but not as written (05b §12.5 "accepted only with a capital",
        the rule of the evaluation's name test)."""
        b = self.casing_bits(key)
        return not (b & LOWER) and bool(b & CAPITAL)

    def forms(self, key):
        """The accepted casings as dictionary forms (05b §12.1 item 6)."""
        b = self.casing_bits(key)
        out = set()
        if b & LOWER:
            out.add(key)
        if b & CAPITAL:
            out.add(key[:1].upper() + key[1:] if len(key[:1].upper()) == 1 else key)
        if b & UPPER:
            out.add("".join(c.upper() if len(c.upper()) == 1 else c for c in key))
        return out


def input_files(tag):
    """The spelling-dictionary input files of a language below build-data/src (05b §2.14), as used."""
    if tag == "de-DE":
        return ["hunspell/de/de_DE_frami.aff", "hunspell/de/de_DE_frami.dic"]
    if tag == "pt-BR":
        return ["hunspell/pt_BR/pt_BR.aff", "hunspell/pt_BR/pt_BR.dic"]
    if tag == "it-IT":
        return ["hunspell/it_IT/it_IT.aff", "hunspell/it_IT/it_IT.dic"]
    if tag == "fr-FR":
        return [spelling.FR_ZIP]
    if tag == "es-ES":
        return [f"hunspell/es/{v}.{ext}" for v in spelling.ES_VARIANTS for ext in ("aff", "dic")]
    return []
