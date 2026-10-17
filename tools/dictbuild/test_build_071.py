"""Unit tests of the 0.7.1 build rules (spec/05b-dictionaries.md §1.1 derived data and abbreviation bit, §2.16 register
labels, §3.6 real-word typos).

Run from the repository root: python3 -m unittest discover -s tools/dictbuild -p 'test_*.py'
"""

import os
import sys
import types
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import build_de  # noqa: E402
import common  # noqa: E402
import wiktionary_register  # noqa: E402


class RealWordTyposAndAbbreviations(unittest.TestCase):
    """05b §3.6 real-word typos (0.7.1 owner decision): abbreviations are not exempted; they carry the abbreviation
    bit of §1.1, which changes no class."""

    def test_abbreviation_is_a_real_word_typo(self):
        counts = {"zi": 96, "zu": 1424706, "heure": 10, "heute": 900000}
        fake = types.SimpleNamespace(count=lambda k: counts.get(k, 0))
        near = {"i": {"u", "o"}, "u": {"i", "z"}, "r": {"t", "e"}, "t": {"r", "z"}}
        selected = {k: [(k, 1.0)] for k in counts}
        out = build_de.GermanBuilder.real_word_typos(fake, set(counts), near, selected)
        self.assertEqual(out, {"zi": "zu", "heure": "heute"})


class AbbreviationBit(unittest.TestCase):
    """05b §1.1 abbreviation bit: one bit per key (bit 6 of the form count of a version-3 list)."""

    def write_and_read(self, entries, bit, unattested=()):
        import tempfile
        derived = common.derived_data("de-DE", entries, lambda k, f, c: float(c))
        with tempfile.TemporaryDirectory(dir=os.path.join(os.path.dirname(__file__), "..", "..", "build-data")) as d:
            path = os.path.join(d, "t.words.gz")
            common.write_word_list(path, "de-DE", 16.0, 1000, 100000, entries, None, set(unattested),
                                   derived=derived[:3], abbreviation=set(bit))
            return common.read_word_list(path)

    def test_round_trip(self):
        entries = {"zi": [("zi", 0), ("Zi", 0)], "usw": [("usw", 120)], "haus": [("Haus", 200)]}
        header, back, unattested, _ = self.write_and_read(entries, {"zi", "usw"}, {"usw"})
        self.assertEqual(back, entries)
        self.assertEqual(header["abbreviation"], {"zi", "usw"})
        self.assertEqual(unattested, {"usw"})
        # the bit changes no class
        self.assertEqual(back["zi"][0][1], 0)

    def test_needs_an_entry_and_version_3(self):
        with self.assertRaises(ValueError):
            self.write_and_read({"haus": [("Haus", 200)]}, {"zi"})
        with self.assertRaises(ValueError):
            common.write_word_list(os.devnull, "de-DE", 16.0, 1000, 100000, {"zi": [("zi", 0)]}, abbreviation={"zi"})


class DerivedData(unittest.TestCase):
    """05b §1.1: frequency ranks, cost anchors, offered bits, guard class, spelling classes."""

    def test_small_list(self):
        entries = {"a": [("a", 250)], "b": [("b", 240), ("B", 239)], "c": [("c", 100)], "d": [("d", 0)]}
        counts = {("a", "a"): 1000.0, ("b", "b"): 500.0, ("b", "B"): 400.0, ("c", "c"): 10.0, ("d", "d"): 0.5}
        anchors, guard, bits, report, rank = common.derived_data("de-DE", entries, lambda k, f, c: counts[(k, f)], {"B"})
        # ranks count the forms with class >= 1 by form count
        self.assertEqual(rank, {("a", "a"): 1, ("b", "b"): 2, ("b", "B"): 3, ("c", "c"): 4})
        # anchors at ranks 1 and 3 (de costs 112, 118), then the lowest class >= 1 with LM_TAIL_COST
        self.assertEqual(anchors, [(250, 112), (239, 118), (100, 254)])
        # fewer forms than AC_SPLIT_RANK: the lowest class >= 1
        self.assertEqual(guard, 100)
        # offered: class >= 1 within SUGGEST_MAX_RANK; the class-0 form is not offered
        self.assertTrue(bits[("a", "a")] & 4)
        self.assertFalse(bits[("d", "d")] & 4)
        # do-not-suggest bit 5 on the flagged form only
        self.assertTrue(bits[("b", "B")] & 32)
        self.assertFalse(bits[("b", "b")] & 32)
        # spelling class (bits 3-4): all ranks <= SPELL_RANK_1 = 3250 → class 1
        self.assertEqual((bits[("d", "d")] >> 3) & 3, 1)

    def test_french_spelling_classes(self):
        # fr: no class 1 or 2; the least frequent 2.4 % get class 3
        entries = {f"w{i:03d}": [(f"w{i:03d}", 200)] for i in range(100)}
        count = lambda k, f, c: 1000.0 - int(f[1:])
        _, _, bits, report, _ = common.derived_data("fr-FR", entries, count)
        classes = [(bits[(f"w{i:03d}", f"w{i:03d}")] >> 3) & 3 for i in range(100)]
        self.assertEqual(classes.count(3), 3)
        self.assertEqual(classes.count(0), 97)
        self.assertEqual(classes[-3:], [3, 3, 3])

    def test_version_3_round_trip(self):
        import tempfile
        entries = {"haus": [("Haus", 200)], "dont": [("don't", 150)], "zz": [("zz", 0)]}
        derived = common.derived_data("de-DE", entries, lambda k, f, c: float(c))
        with tempfile.TemporaryDirectory(dir=os.path.join(os.path.dirname(__file__), "..", "..", "build-data")) as d:
            path = os.path.join(d, "t.words.gz")
            common.write_word_list(path, "de-DE", 16.0, 1000, 100000, entries, None, set(), derived=derived[:3])
            header, back, unattested, prefixes = common.read_word_list(path)
        self.assertEqual(header["version"], 3)
        self.assertEqual(back, entries)
        self.assertIsNone(prefixes)
        self.assertEqual(header["anchors"], derived[0])


class RegisterLabels(unittest.TestCase):
    """05b §2.16: sense lines, label templates, form-of lines, flagged titles."""

    def test_classify(self):
        lines = ["# {{lb|de|vulgar}} cunt", "#: an example", "# {{plural of|de|Arsch}}",
                 "## {{lb|de|figurative|_|pejorative}} idiot"]
        self.assertEqual(wiktionary_register.classify(lines, "de"), (3, 2, 0, ["Arsch"], "vulgar"))
        # a neutral sense keeps the title unflagged
        self.assertEqual(wiktionary_register.classify(["# {{lb|de|colloquial}} tail", "# {{lb|de|vulgar}} penis"], "de")[2], 1)
        # the label must name the language's code; named arguments are no labels
        self.assertEqual(wiktionary_register.classify(["# {{lb|en|vulgar}} x"], "de")[1], 0)
        self.assertEqual(wiktionary_register.classify(["# {{lb|de|sort=vulgar}} x"], "de")[1], 0)
        # whole words only
        self.assertEqual(wiktionary_register.classify(["# {{lb|en|slurs}} x"], "en")[1], 0)
        self.assertEqual(wiktionary_register.classify(["# {{lb|en|ethnic slur}} x"], "en")[1], 1)

    def test_flagged(self):
        pages = {
            "Arsch": (1, 1, 0, [], "vulgar"),
            "Ärsche": (1, 0, 0, ["Arsch"], ""),
            "Schwanz": (2, 1, 1, [], "vulgar"),
            "Schwänze": (1, 0, 0, ["Schwanz"], ""),
            "Haus": (1, 0, 1, [], ""),
            # one level only: a form of a form-of page does not chain
            "Ärschen": (1, 0, 0, ["Ärsche"], ""),
        }
        f = wiktionary_register.flagged(pages)
        self.assertEqual(set(f), {"Arsch", "Ärsche"})

    def test_sections(self):
        s = wiktionary_register._sections("==English==\n# a\n==German==\n# b\n===Noun===\n# c\n==Dutch==\n# d")
        self.assertEqual(s, {"en": ["# a"], "de": ["# b", "===Noun===", "# c"]})


if __name__ == "__main__":
    unittest.main()
