"""Shared helpers of the dictionary build: Java-compatible case mapping, frequency classes and the binary
runtime formats read by the engine (app.metrokeys.engine.lexicon.LexiconReader / PairTableReader).

Word list format (gzip of the payload below; all integers big-endian or unsigned LEB128 "varint"):

    "MKWL" u8 version=1
    u8 len + ASCII language tag
    f32 S, varint maxCount, varint corpusTokens, varint keyCount, varint formCount
    keyCount records, keys in ascending UTF-16 order:
        varint shared prefix length with the previous key (chars)
        varint suffix byte length, suffix (UTF-8)
        u8 number of forms, then per form (best first):
            u8 class
            u8 kind: 0 = the key itself, 1 = key with the first char upper-cased,
                     2 = key with every char upper-cased (per char), 3 = explicit: varint byte length + UTF-8
    version 2 only (languages with an elision prefix table, 05b §1.1, §10.6), after the keys:
        varint prefixCount, per prefix: varint byte length + UTF-8 text (lower case, ASCII apostrophe), u8 class

Pair table format (gzip):

    "MKPT" u8 version=1
    u8 len + ASCII language tag
    binding: varint keyCount, varint formCount, 32 bytes SHA-256 of the uncompressed word-list payload
    varint number of previous words
    per previous word, ascending by encoded index (0 = sentence start, otherwise key index + 1):
        varint delta of the encoded index to the previous one
        u8 number of followers, then per follower (best class first):
            varint key index, u8 pair class, u8 form index (into the follower key's forms)

    Version 2 (bound to a version-2 word list with an elision prefix table): the same, except
        encoded previous-word index keyCount + 1 + p = elision prefix p of the word list's prefix table;
        the number of followers is a varint (an elision prefix keeps up to 4000);
        follower index keyCount + p = elision prefix p (form index 0).
"""

import gzip
import hashlib
import io
import math
import struct


def round_half_away(x):
    return int(math.floor(abs(x) + 0.5)) * (1 if x >= 0 else -1)


def jlower(s):
    """Character.toLowerCase applied char by char (BMP only), so the key has the length of the form."""
    out = []
    for ch in s:
        low = ch.lower()
        out.append(low if len(low) == 1 else ch)
    return "".join(out)


def jupper_char(ch):
    up = ch.upper()
    return up if len(up) == 1 else ch


def jupper(s):
    return "".join(jupper_char(c) for c in s)


def capitalize_first(s):
    return jupper_char(s[0]) + s[1:] if s else s


def freq_class(count, scale):
    if count < 1:
        return 0
    return max(0, min(255, round_half_away(math.log(count) * scale)))


PAIR_B = 255.0 / math.log(1e5)


def pair_class(p):
    """Returns the pair class 0..255 of probability p, or None if it would be negative."""
    c = round_half_away(255 + math.log(p) * PAIR_B)
    if c < 0:
        return None
    return min(255, c)


def _varint(out, v):
    if v < 0:
        raise ValueError(v)
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.write(bytes([b | 0x80]))
        else:
            out.write(bytes([b]))
            return


def _utf8(out, s):
    b = s.encode("utf-8")
    _varint(out, len(b))
    out.write(b)


def form_kind(key, form):
    if form == key:
        return 0
    if form == capitalize_first(key):
        return 1
    if form == jupper(key):
        return 2
    return 3


def deterministic_gzip(payload):
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9, mtime=0, filename="") as gz:
        gz.write(payload)
    return buf.getvalue()


def write_word_list(path, lang, scale, max_count, corpus_tokens, entries, prefixes=None):
    """entries: dict key -> list of (form, class), best first; prefixes: None, or a list of (elision prefix, class)
    written as a version-2 prefix table. Returns (payload sha256 bytes, keys list)."""
    keys = sorted(entries)
    out = io.BytesIO()
    out.write(b"MKWL")
    out.write(bytes([1 if prefixes is None else 2]))
    tag = lang.encode("ascii")
    out.write(bytes([len(tag)]))
    out.write(tag)
    out.write(struct.pack(">f", scale))
    _varint(out, int(max_count))
    _varint(out, int(corpus_tokens))
    _varint(out, len(keys))
    _varint(out, sum(len(entries[k]) for k in keys))
    prev = ""
    for key in keys:
        if jlower(key) != key:
            raise ValueError(f"key not lower case: {key!r}")
        shared = 0
        limit = min(len(prev), len(key))
        while shared < limit and prev[shared] == key[shared]:
            shared += 1
        _varint(out, shared)
        _utf8(out, key[shared:])
        forms = entries[key]
        if not 1 <= len(forms) <= 255:
            raise ValueError(key)
        out.write(bytes([len(forms)]))
        for form, cls in forms:
            if jlower(form) != key and jlower(form).replace("'", "").replace("-", "") != key:
                raise ValueError(f"form {form!r} does not belong to key {key!r}")
            kind = form_kind(key, form)
            out.write(bytes([cls, kind]))
            if kind == 3:
                _utf8(out, form)
        prev = key
    if prefixes is not None:
        _varint(out, len(prefixes))
        for text, cls in prefixes:
            if text in entries or not text.endswith("'") or jlower(text) != text:
                raise ValueError(f"bad elision prefix {text!r}")
            _utf8(out, text)
            out.write(bytes([cls]))
    payload = out.getvalue()
    with open(path, "wb") as f:
        f.write(deterministic_gzip(payload))
    return hashlib.sha256(payload).digest(), keys


def write_pair_table(path, lang, word_hash, keys, entries, pairs, prefixes=None):
    """pairs: dict prev_key ('' = sentence start) -> list of (follower key, class, form) best first.
    With [prefixes] (the elision prefixes of the version-2 word list, in its order) a previous word or a follower may
    also be an elision prefix (its form is the prefix itself); the table is written as version 2."""
    index = {k: i for i, k in enumerate(keys)}
    pindex = {p: i for i, p in enumerate(prefixes or ())}
    out = io.BytesIO()
    out.write(b"MKPT")
    out.write(bytes([1 if prefixes is None else 2]))
    tag = lang.encode("ascii")
    out.write(bytes([len(tag)]))
    out.write(tag)
    _varint(out, len(keys))
    _varint(out, sum(len(entries[k]) for k in keys))
    out.write(word_hash)
    def prev_index(p):
        if p == "":
            return 0
        if p in pindex:
            return len(keys) + 1 + pindex[p]
        return index[p] + 1

    encoded = sorted((prev_index(p), p) for p in pairs)
    _varint(out, len(encoded))
    last = 0
    for enc, prev in encoded:
        _varint(out, enc - last)
        last = enc
        followers = pairs[prev]
        if prefixes is None:
            out.write(bytes([len(followers)]))
        else:
            _varint(out, len(followers))
        for key, cls, form in followers:
            if key in pindex:
                _varint(out, len(keys) + pindex[key])
                out.write(bytes([cls, 0]))
                continue
            forms = [f for f, _ in entries[key]]
            _varint(out, index[key])
            out.write(bytes([cls, forms.index(form)]))
    with open(path, "wb") as f:
        f.write(deterministic_gzip(out.getvalue()))


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()
