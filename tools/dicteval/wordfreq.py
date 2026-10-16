"""Written-language frequencies of wordfreq (spec/05b-dictionaries.md §2.15), read without the project's code.

The file is a gzip-compressed MessagePack array: element 0 is a header map (format "cB", version 1); element i >= 1 is
the array of words whose frequency per token is 10^(-(i-1)/100). Only the MessagePack types that occur there are
decoded (arrays, maps, strings, integers, nil, booleans); anything else is an error.
"""

import gzip
import struct
import unicodedata


class _Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def _take(self, n):
        b = self.data[self.pos:self.pos + n]
        if len(b) != n:
            raise ValueError("truncated MessagePack data")
        self.pos += n
        return b

    def value(self):
        t = self._take(1)[0]
        if t <= 0x7F:
            return t
        if 0x80 <= t <= 0x8F:
            return self._map(t & 0x0F)
        if 0x90 <= t <= 0x9F:
            return self._array(t & 0x0F)
        if 0xA0 <= t <= 0xBF:
            return self._take(t & 0x1F).decode("utf-8")
        if t == 0xC0:
            return None
        if t == 0xC2:
            return False
        if t == 0xC3:
            return True
        if t == 0xCC:
            return self._take(1)[0]
        if t == 0xCD:
            return struct.unpack(">H", self._take(2))[0]
        if t == 0xCE:
            return struct.unpack(">I", self._take(4))[0]
        if t == 0xD9:
            return self._take(self._take(1)[0]).decode("utf-8")
        if t == 0xDA:
            return self._take(struct.unpack(">H", self._take(2))[0]).decode("utf-8")
        if t == 0xDB:
            return self._take(struct.unpack(">I", self._take(4))[0]).decode("utf-8")
        if t == 0xDC:
            return self._array(struct.unpack(">H", self._take(2))[0])
        if t == 0xDD:
            return self._array(struct.unpack(">I", self._take(4))[0])
        if t == 0xDE:
            return self._map(struct.unpack(">H", self._take(2))[0])
        if t >= 0xE0:
            return t - 0x100
        raise ValueError(f"unsupported MessagePack type 0x{t:02x}")

    def _array(self, n):
        return [self.value() for _ in range(n)]

    def _map(self, n):
        out = {}
        for _ in range(n):
            k = self.value()
            out[k] = self.value()
        return out


def load(path):
    """Returns {word: frequency per token} of a wordfreq "large_<code>.msgpack.gz" file."""
    with gzip.open(path, "rb") as f:
        data = _Reader(f.read()).value()
    header = data[0]
    if header.get("format") != "cB" or header.get("version") != 1:
        raise ValueError(f"unexpected wordfreq header {header!r}")
    out = {}
    for i, words in enumerate(data[1:], start=1):
        freq = 10.0 ** (-(i - 1) / 100.0)
        for w in words:
            if w not in out:
                out[w] = freq
    return out


def fold(key):
    """05b §12.2: lower case, every "ß" written "ss", NFC."""
    return unicodedata.normalize("NFC", key.lower().replace("ß", "ss"))
