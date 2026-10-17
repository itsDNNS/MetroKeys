"""Shared helpers of the dictionary build: Java-compatible case mapping, frequency classes and the binary
runtime formats read by the engine (app.metrokeys.engine.lexicon.LexiconReader / PairTableReader).

Word list format (gzip of the payload below; all integers big-endian or unsigned LEB128 "varint"):

    "MKWL" u8 version=1
    u8 len + ASCII language tag
    f32 S, varint maxCount, varint corpusTokens, varint keyCount, varint formCount
    keyCount records, keys in ascending UTF-16 order:
        varint shared prefix length with the previous key (chars)
        varint suffix byte length, suffix (UTF-8)
        u8 number of forms (bits 0-6; bit 7 set = the key is unattested: since 0.7.0 kept by the colloquial path of
           05b §12.4 without being a form of the language; 0.6.9: kept by the count rule; never set before 0.6.9),
           then per form (best first):
            u8 class
            u8 kind: 0 = the key itself, 1 = key with the first char upper-cased,
                     2 = key with every char upper-cased (per char), 3 = explicit: varint byte length + UTF-8
    version 2 only (languages with an elision prefix table, 05b §1.1, §10.6), after the keys:
        varint prefixCount, per prefix: varint byte length + UTF-8 text (lower case, ASCII apostrophe), u8 class

    Version 3 (0.7.1, every language; the derived data of 05b §1.1): after varint formCount the header holds
        u8 anchorCount, per cost anchor (05 §3.2): u8 class, u8 cost; then u8 guard class (05 §10.1 step 7a);
    the kind byte of every form carries the per-form data in its upper bits:
        bits 0-1 kind as above, bit 2 offered (suggestion lexicon), bits 3-4 spelling class (05d §6),
        bit 5 do-not-suggest (05b §2.16);
    and the prefix table always follows the keys (varint prefixCount, possibly 0).

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


# 05b §3.6 step 6a, as applied by the Spanish, Portuguese, Italian and French builds (0.6.7; the German build has its
# own copy of the same values since 0.6.4): a key of at most SHORT_KEY_LEN letters that is no form of the language is
# kept only if its count is >= SHORT_KEY_MIN. (The damping of 0.6.4–0.6.9 is withdrawn in 0.7.0, 05b §3.6.)
SHORT_KEY_LEN = 3
SHORT_KEY_MIN = 20000


def short_key_dropped(letters, count):
    """05b §3.6 step 6a: a non-form key with [letters] letters and subtitle [count] is dropped."""
    return letters <= SHORT_KEY_LEN and count < SHORT_KEY_MIN


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


# 05 §1.1 cost anchors (0.7.1): the ranks and, per anchor row, the costs; 05 §1.2 LM_TAIL_COST, SUGGEST_MAX_RANK,
# AC_SPLIT_RANK; 05d §6 spelling-class ranks
LM_ANCHOR_RANKS = (1, 3, 10, 30, 100, 300, 1000, 3000, 10000, 30000, 50000)
LM_ANCHOR_COSTS = {
    "de": (112, 118, 122, 129, 140, 153, 166, 178, 192, 210, 222),
    "en": (99, 113, 130, 142, 153, 163, 172, 182, 199, 222, 235),
    "fr": (107, 111, 117, 126, 139, 156, 173, 187, 205, 225, 234),
    "median": (91, 105, 117, 126, 140, 157, 172, 182, 199, 222, 234),
}
# 05b §7 profile: which anchor row a language uses
ANCHOR_ROW = {"de-DE": "de", "en-US": "en", "fr-FR": "fr", "es-ES": "median", "pt-BR": "median", "it-IT": "median"}
LM_TAIL_COST = 254
SUGGEST_MAX_RANK = 90000
AC_SPLIT_RANK = 5000
# 05d §6: (SPELL_RANK_1, SPELL_RANK_2, SPELL_TAIL_3)
SPELL_CLASSES = {"de-DE": (3250, 8550, 0.044), "en-US": (1000, 38000, 0.11), "es-ES": (1000, 38000, 0.11),
                 "pt-BR": (1000, 38000, 0.11), "it-IT": (1000, 38000, 0.11), "fr-FR": (0, 0, 0.024)}


def derived_data(lang, entries, form_count, nosuggest=frozenset()):
    """The derived data of 05b §1.1 (0.7.1): frequency ranks, cost anchors, offered bits, guard class, spelling classes.
    [entries]: key -> [(form, class)]; [form_count](key, form, class): the form count (combined count x form share);
    [nosuggest]: forms with the do-not-suggest flag. Returns (anchors [(class, cost)], guard class,
    {(key, form): flags byte bits 2-5}, report dict)."""
    items = [(key, form, cls, form_count(key, form, cls)) for key, forms in entries.items() for form, cls in forms]
    # frequency rank: forms with class >= 1 by form count descending, ties by the form (then the key)
    ranked = sorted((it for it in items if it[2] >= 1), key=lambda it: (-it[3], it[1], it[0]))
    rank = {(it[0], it[1]): i + 1 for i, it in enumerate(ranked)}
    # cost anchors: forms with class >= 1 by class descending
    classes = sorted((it[2] for it in ranked), reverse=True)
    n = len(classes)
    costs = LM_ANCHOR_COSTS[ANCHOR_ROW[lang]]
    anchors = []
    for r, c in zip(LM_ANCHOR_RANKS, costs):
        if r >= n:
            break
        k = classes[r - 1]
        if anchors and anchors[-1][0] == k:
            continue
        anchors.append((k, c))
    k_min = classes[-1] if classes else 1
    if anchors and anchors[-1][0] == k_min:
        anchors[-1] = (k_min, LM_TAIL_COST)
    else:
        anchors.append((k_min, LM_TAIL_COST))
    guard = ranked[AC_SPLIT_RANK - 1][2] if n >= AC_SPLIT_RANK else k_min
    # spelling classes: every form (class 0 included) by form count descending, ties by the form
    every = sorted(items, key=lambda it: (-it[3], it[1], it[0]))
    r1, r2, tail = SPELL_CLASSES[lang]
    total = len(every)
    spelling = {}
    for i, it in enumerate(every):
        r = i + 1
        if r <= r1:
            sc = 1
        elif r <= r2:
            sc = 2
        elif r > total * (1 - tail):
            sc = 3
        else:
            sc = 0
        spelling[(it[0], it[1])] = sc
    flags = {}
    offered = 0
    last_offered = None
    for key, form, cls, _ in items:
        rk = rank.get((key, form))
        off = cls >= 1 and rk is not None and rk <= SUGGEST_MAX_RANK
        if off:
            offered += 1
            if rk == min(n, SUGGEST_MAX_RANK):
                last_offered = cls
        bits = (4 if off else 0) | (spelling[(key, form)] << 3) | (32 if form in nosuggest else 0)
        flags[(key, form)] = bits
    report = {
        "costAnchors": [list(a) for a in anchors],
        "guardClass": guard,
        "offeredForms": offered,
        "lastOfferedClass": last_offered,
        "formsPerSpellingClass": {str(c): sum(1 for v in spelling.values() if v == c) for c in range(4)},
    }
    return anchors, guard, flags, report, rank


def deterministic_gzip(payload):
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9, mtime=0, filename="") as gz:
        gz.write(payload)
    return buf.getvalue()


def write_word_list(path, lang, scale, max_count, corpus_tokens, entries, prefixes=None, unattested=None, derived=None,
                    abbreviation=None):
    """entries: dict key -> list of (form, class), best first; prefixes: None, or a list of (elision prefix, class)
    written as a version-2 prefix table; unattested: keys flagged as unattested (0.6.9); derived: (anchors, guard class,
    {(key, form): flag bits}) of derived_data(), which writes a version-3 list (0.7.1); abbreviation: keys with the
    abbreviation bit (05b §1.1, 0.7.1; bit 6 of the form count, version 3 only, which then allows at most 63 forms per
    key). Returns (payload sha256 bytes, keys list)."""
    unattested = unattested or set()
    unknown = set(unattested) - set(entries)
    if unknown:
        raise ValueError(f"unattested keys without entry: {sorted(unknown)[:5]}")
    abbreviation = abbreviation or set()
    if abbreviation and derived is None:
        raise ValueError("the abbreviation bit needs a version-3 list")
    unknown = set(abbreviation) - set(entries)
    if unknown:
        raise ValueError(f"abbreviation keys without entry: {sorted(unknown)[:5]}")
    max_forms = 0x3F if derived is not None else 0x7F
    keys = sorted(entries)
    out = io.BytesIO()
    out.write(b"MKWL")
    out.write(bytes([3 if derived is not None else 1 if prefixes is None else 2]))
    tag = lang.encode("ascii")
    out.write(bytes([len(tag)]))
    out.write(tag)
    out.write(struct.pack(">f", scale))
    _varint(out, int(max_count))
    _varint(out, int(corpus_tokens))
    _varint(out, len(keys))
    _varint(out, sum(len(entries[k]) for k in keys))
    if derived is not None:
        anchors, guard, flag_bits = derived
        out.write(bytes([len(anchors)]))
        for k, c in anchors:
            out.write(bytes([k, c]))
        out.write(bytes([guard]))
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
        if not 1 <= len(forms) <= max_forms:
            raise ValueError(key)
        out.write(bytes([len(forms) | (0x80 if key in unattested else 0) | (0x40 if key in abbreviation else 0)]))
        for form, cls in forms:
            if jlower(form) != key and jlower(form).replace("'", "").replace("-", "") != key:
                raise ValueError(f"form {form!r} does not belong to key {key!r}")
            kind = form_kind(key, form)
            bits = flag_bits[(key, form)] if derived is not None else 0
            out.write(bytes([cls, kind | bits]))
            if kind == 3:
                _utf8(out, form)
        prev = key
    if prefixes is not None or derived is not None:
        _varint(out, len(prefixes or ()))
        for text, cls in prefixes or ():
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


def _read_varint(buf, pos):
    v = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        v |= (b & 0x7F) << shift
        if not b & 0x80:
            return v, pos
        shift += 7


def read_word_list(path):
    """Decodes a word list written by write_word_list: (header dict, {key: [(form, class)]}, unattested keys,
    prefixes [(text, class)] or None). Used for the en-US keys of 05b §12.4 condition 5 and the comparison with the
    previous release (§12.7)."""
    with gzip.open(path, "rb") as f:
        buf = f.read()
    if buf[:4] != b"MKWL":
        raise ValueError(f"not a word list: {path}")
    version = buf[4]
    n = buf[5]
    tag = buf[6:6 + n].decode("ascii")
    pos = 6 + n
    scale = struct.unpack(">f", buf[pos:pos + 4])[0]
    pos += 4
    max_count, pos = _read_varint(buf, pos)
    corpus, pos = _read_varint(buf, pos)
    key_count, pos = _read_varint(buf, pos)
    form_count, pos = _read_varint(buf, pos)
    anchors = guard = None
    if version == 3:
        na = buf[pos]
        pos += 1
        anchors = [(buf[pos + 2 * i], buf[pos + 2 * i + 1]) for i in range(na)]
        pos += 2 * na
        guard = buf[pos]
        pos += 1
    form_bits = {}
    entries = {}
    unattested = set()
    abbreviation = set()
    prev = ""
    for _ in range(key_count):
        shared, pos = _read_varint(buf, pos)
        ln, pos = _read_varint(buf, pos)
        key = prev[:shared] + buf[pos:pos + ln].decode("utf-8")
        pos += ln
        nf = buf[pos]
        pos += 1
        if nf & 0x80:
            unattested.add(key)
        if version == 3 and nf & 0x40:
            abbreviation.add(key)
        forms = []
        for _ in range(nf & (0x3F if version == 3 else 0x7F)):
            cls, kind_byte = buf[pos], buf[pos + 1]
            kind = kind_byte & 3
            pos += 2
            if kind == 0:
                form = key
            elif kind == 1:
                form = capitalize_first(key)
            elif kind == 2:
                form = jupper(key)
            else:
                ln, pos = _read_varint(buf, pos)
                form = buf[pos:pos + ln].decode("utf-8")
                pos += ln
            forms.append((form, cls))
            if version == 3:
                form_bits[(key, form)] = kind_byte & 0xFC
        entries[key] = forms
        prev = key
    prefixes = None
    if version in (2, 3):
        prefixes = []
        cnt, pos = _read_varint(buf, pos)
        for _ in range(cnt):
            ln, pos = _read_varint(buf, pos)
            text = buf[pos:pos + ln].decode("utf-8")
            pos += ln
            prefixes.append((text, buf[pos]))
            pos += 1
    if version == 3 and not prefixes:
        prefixes = None
    header = {"tag": tag, "version": version, "S": scale, "maxCount": max_count, "corpusTokens": corpus,
              "keys": key_count, "forms": form_count, "anchors": anchors, "guardClass": guard, "formBits": form_bits,
              "abbreviation": abbreviation}
    return header, entries, unattested, prefixes


def read_pair_table(path, keys, prefixes=None):
    """Decodes a pair table written by write_pair_table: {previous ('' = sentence start, or a key or prefix):
    [(follower key or prefix, class, form index)]}. [keys]: the bound word list's keys in order."""
    with gzip.open(path, "rb") as f:
        buf = f.read()
    if buf[:4] != b"MKPT":
        raise ValueError(f"not a pair table: {path}")
    version = buf[4]
    n = buf[5]
    pos = 6 + n
    kc, pos = _read_varint(buf, pos)
    _, pos = _read_varint(buf, pos)
    pos += 32
    prev_count, pos = _read_varint(buf, pos)
    prefixes = prefixes or []
    out = {}
    enc = 0
    for _ in range(prev_count):
        d, pos = _read_varint(buf, pos)
        enc += d
        if enc == 0:
            prev = ""
        elif enc <= kc:
            prev = keys[enc - 1]
        else:
            prev = prefixes[enc - kc - 1]
        if version == 1:
            nf = buf[pos]
            pos += 1
        else:
            nf, pos = _read_varint(buf, pos)
        followers = []
        for _ in range(nf):
            idx, pos = _read_varint(buf, pos)
            cls, fi = buf[pos], buf[pos + 1]
            pos += 2
            followers.append((keys[idx] if idx < kc else prefixes[idx - kc], cls, fi))
        out[prev] = followers
    return out
