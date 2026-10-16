"""Spelling-dictionary membership by lookup (spec/05b-dictionaries.md §12.1 item 2).

A Hunspell dictionary (an affix file .aff and a word file .dic) is read as text; nothing shipped with it is executed.
A candidate is checked by applying the affix rules in reverse: at most one prefix and at most two levels of suffixes are
removed, the stripped text is restored, each rule's condition, the flags of the stem and of the affixes, the
cross-product permission and the continuation flags are checked, and the candidate is accepted when a stem of the .dic
file carries the required flags. The dictionary is never expanded. Flags that forbid a form standing alone
(NEEDAFFIX/PSEUDOROOT, ONLYINCOMPOUND, FORBIDDENWORD) are honoured, CIRCUMFIX affixes need each other, the flag formats
(single character, long, numeric, UTF-8, AF aliases) and the input conversion table (ICONV) are supported. Compounding
is not used.
"""

import re


class _Affix:
    __slots__ = ("flag", "cross", "strip", "add", "cont", "cond", "prefix")

    def __init__(self, flag, cross, strip, add, cont, cond, prefix):
        self.flag = flag
        self.cross = cross
        self.strip = strip
        self.add = add
        self.cont = cont
        self.cond = cond
        self.prefix = prefix

    def matches(self, root):
        """The rule's condition at the start (prefix) or end (suffix) of [root]."""
        cond = self.cond
        n = len(cond)
        if n == 0:
            return True
        if len(root) < n:
            return False
        part = root[:n] if self.prefix else root[-n:]
        for ch, (neg, chars) in zip(part, cond):
            if chars is None:
                continue
            if (ch in chars) == neg:
                return False
        return True


def _parse_condition(text):
    """Hunspell condition: "." any character, "[abc]" a class, "[^abc]" a negated class, else literal characters.
    Returns a list of (negated, set or None for any)."""
    if text == ".":
        return []
    out = []
    i = 0
    while i < len(text):
        c = text[i]
        if c == "[":
            j = text.index("]", i)
            body = text[i + 1:j]
            neg = body.startswith("^")
            if neg:
                body = body[1:]
            out.append((neg, set(body)))
            i = j + 1
        elif c == ".":
            out.append((False, None))
            i += 1
        else:
            out.append((False, {c}))
            i += 1
    return out


class Dictionary:
    def __init__(self, aff_bytes, dic_bytes_list):
        """[dic_bytes_list]: one or more .dic files that share this affix file (their stems are merged)."""
        if aff_bytes.startswith(b"\xef\xbb\xbf"):
            aff_bytes = aff_bytes[3:]
        head = aff_bytes[:4096].decode("latin-1")
        m = re.search(r"(?m)^\s*SET\s+(\S+)", head)
        self.encoding = (m.group(1) if m else "ISO8859-1").strip()
        enc = {"ISO8859-1": "latin-1", "ISO8859-15": "iso8859-15", "UTF-8": "utf-8"}.get(self.encoding.upper(),
                                                                                        self.encoding)
        self._enc = enc
        aff = aff_bytes.decode(enc, errors="replace").lstrip("\ufeff")
        self.flag_mode = "char"
        self.aliases = []
        self.needaffix = self.forbidden = self.onlyincompound = self.circumfix = self.keepcase = None
        self.fullstrip = False
        self.iconv = []
        self.prefixes = {}   # add -> [rules]
        self.suffixes = {}
        self.affix_count = 0
        headers = {}
        lines = aff.splitlines()
        # flag mode and aliases first: rules depend on them
        for line in lines:
            parts = line.split()
            if parts and parts[0] == "FLAG" and len(parts) > 1:
                self.flag_mode = {"long": "long", "num": "num", "UTF-8": "char"}.get(parts[1], "char")
        for line in lines:
            parts = line.split()
            if len(parts) >= 2 and parts[0] == "AF" and not parts[1].isdigit():
                self.aliases.append(self._flags(parts[1], raw=True))
        for line in lines:
            parts = line.split()
            if not parts or parts[0].startswith("#"):
                continue
            kw = parts[0]
            if kw in ("NEEDAFFIX", "PSEUDOROOT") and len(parts) > 1:
                self.needaffix = self._flag1(parts[1])
            elif kw == "FORBIDDENWORD" and len(parts) > 1:
                self.forbidden = self._flag1(parts[1])
            elif kw == "ONLYINCOMPOUND" and len(parts) > 1:
                self.onlyincompound = self._flag1(parts[1])
            elif kw == "CIRCUMFIX" and len(parts) > 1:
                self.circumfix = self._flag1(parts[1])
            elif kw == "KEEPCASE" and len(parts) > 1:
                self.keepcase = self._flag1(parts[1])
            elif kw == "FULLSTRIP":
                self.fullstrip = True
            elif kw == "ICONV" and len(parts) == 3:
                self.iconv.append((parts[1], parts[2]))
            elif kw in ("PFX", "SFX") and len(parts) >= 4:
                flag = self._flag1(parts[1])
                if (kw, flag) not in headers and parts[2] in ("Y", "N") and parts[3].isdigit():
                    headers[(kw, flag)] = parts[2] == "Y"
                    continue
                if len(parts) < 4:
                    continue
                strip = "" if parts[2] == "0" else parts[2]
                add_field = parts[3]
                cont = frozenset()
                if "/" in add_field:
                    add_field, cont_text = add_field.split("/", 1)
                    cont = self._flags(cont_text)
                add = "" if add_field == "0" else add_field
                cond = _parse_condition(parts[4]) if len(parts) > 4 else []
                rule = _Affix(flag, headers.get((kw, flag), False), strip, add, cont, cond, kw == "PFX")
                (self.prefixes if kw == "PFX" else self.suffixes).setdefault(add, []).append(rule)
                self.affix_count += 1
        self.iconv.sort(key=lambda p: -len(p[0]))
        self.stems = {}
        for dic in dic_bytes_list:
            if dic.startswith(b"\xef\xbb\xbf"):
                dic = dic[3:]
            self._read_dic(dic.decode(enc, errors="replace"))
        self._max_add_p = max((len(a) for a in self.prefixes), default=0)
        self._max_add_s = max((len(a) for a in self.suffixes), default=0)

    # ------------------------------------------------------------------ flags

    def _flags(self, text, raw=False):
        if not raw and self.aliases and text.isdigit():
            i = int(text)
            return self.aliases[i - 1] if 1 <= i <= len(self.aliases) else frozenset()
        if self.flag_mode == "long":
            return frozenset(text[i:i + 2] for i in range(0, len(text) - len(text) % 2, 2))
        if self.flag_mode == "num":
            return frozenset(f for f in text.split(",") if f)
        return frozenset(text)

    def _flag1(self, text):
        if self.flag_mode == "long":
            return text[:2]
        if self.flag_mode == "num":
            return text
        return text[0]

    def _read_dic(self, text):
        lines = text.splitlines()
        for line in lines[1:]:
            if not line or line[0] in "\t#":
                continue
            field = line.split()[0] if line.split() else ""
            # escaped slashes belong to the word
            word, flags = field, frozenset()
            idx = -1
            i = 0
            while i < len(field):
                if field[i] == "\\":
                    i += 2
                    continue
                if field[i] == "/":
                    idx = i
                    break
                i += 1
            if idx >= 0:
                word = field[:idx]
                flags = self._flags(field[idx + 1:])
            word = word.replace("\\/", "/")
            if not word:
                continue
            self.stems.setdefault(word, []).append(flags)

    # ------------------------------------------------------------------ lookup

    def _convert(self, word):
        if not self.iconv:
            return word
        out = []
        i = 0
        while i < len(word):
            for a, b in self.iconv:
                if word.startswith(a, i):
                    out.append(b)
                    i += len(a)
                    break
            else:
                out.append(word[i])
                i += 1
        return "".join(out)

    def _entries(self, root):
        return self.stems.get(root, ())

    def _alone_ok(self, flags):
        return not ((self.onlyincompound and self.onlyincompound in flags) or
                    (self.forbidden and self.forbidden in flags))

    def _suffix_steps(self, word):
        n = len(word)
        for i in range(max(0, n - self._max_add_s), n + 1):
            rules = self.suffixes.get(word[i:])
            if not rules:
                continue
            base = word[:i]
            if not base and not self.fullstrip:
                continue
            for r in rules:
                root = base + r.strip
                if root and r.matches(root):
                    yield root, r

    def _prefix_steps(self, word):
        n = len(word)
        for i in range(0, min(n, self._max_add_p) + 1):
            rules = self.prefixes.get(word[:i])
            if not rules:
                continue
            rest = word[i:]
            if not rest and not self.fullstrip:
                continue
            for r in rules:
                root = r.strip + rest
                if root and r.matches(root):
                    yield root, r

    def _circ(self, affixes):
        """CIRCUMFIX: an affix with the circumfix flag needs a prefix and a suffix that both carry it."""
        c = self.circumfix
        if not c:
            return True
        marked = [a for a in affixes if c in a.cont]
        if not marked:
            return True
        return any(a.prefix for a in marked) and any(not a.prefix for a in marked)

    def _need(self, affixes):
        """An affix carrying NEEDAFFIX in its continuation flags cannot be the only real affix."""
        n = self.needaffix
        if not n:
            return True
        return any(n not in a.cont for a in affixes)

    def _suffixed(self, word, prefix=None):
        """True if [word] is a stem with one or two suffixes (and the prefix [prefix] already removed)."""
        for mid, s2 in self._suffix_steps(word):
            # one suffix
            for flags in self._entries(mid):
                if s2.flag in flags and self._alone_ok(flags) and self._prefix_ok(prefix, flags, (s2,)):
                    affixes = (s2,) if prefix is None else (prefix, s2)
                    if self._need(affixes) and self._circ(affixes):
                        return True
            # two suffixes: the inner suffix s1 allows s2 as continuation
            for root, s1 in self._suffix_steps(mid):
                if s2.flag not in s1.cont:
                    continue
                for flags in self._entries(root):
                    if s1.flag in flags and self._alone_ok(flags) and self._prefix_ok(prefix, flags, (s1, s2)):
                        affixes = (s1, s2) if prefix is None else (prefix, s1, s2)
                        if self._need(affixes) and self._circ(affixes):
                            return True
        return False

    @staticmethod
    def _prefix_ok(prefix, flags, suffixes):
        if prefix is None:
            return True
        if not prefix.cross or not suffixes[0].cross:
            return False
        return prefix.flag in flags or any(prefix.flag in s.cont for s in suffixes)

    def accepts(self, candidate):
        """True if the dictionary accepts [candidate] exactly as written (no case variants)."""
        word = self._convert(candidate)
        entries = self._entries(word)
        if any(self.forbidden and self.forbidden in f for f in entries):
            return False
        for flags in entries:
            if self._alone_ok(flags) and not (self.needaffix and self.needaffix in flags):
                return True
        if self._suffixed(word):
            return True
        for rest, p in self._prefix_steps(word):
            for flags in self._entries(rest):
                if p.flag in flags and self._alone_ok(flags) and self._need((p,)) and self._circ((p,)):
                    return True
            if self._suffixed(rest, p):
                return True
        return False


class SpellingDictionaries:
    """One or more dictionaries of a language (Spanish: Spain and the American variants); a candidate is accepted if
    any of them accepts it. [groups]: list of (aff bytes, [dic bytes])."""

    def __init__(self, groups):
        self.dicts = [Dictionary(aff, dics) for aff, dics in groups]
        self._cache = {}

    def accepts(self, candidate):
        r = self._cache.get(candidate)
        if r is None:
            r = any(d.accepts(candidate) for d in self.dicts)
            self._cache[candidate] = r
        return r

    def casings(self, key):
        """Which of the key as written, with its first letter upper-cased, fully upper-cased the dictionaries accept
        (05b §12.1 item 2): a tuple of three booleans."""
        cap = key[:1].upper() + key[1:] if key else key
        up = "".join(c.upper() if len(c.upper()) == 1 else c for c in key)
        return (self.accepts(key), cap != key and self.accepts(cap), up != cap and up != key and self.accepts(up))

    def knows(self, key):
        return any(self.casings(key))
