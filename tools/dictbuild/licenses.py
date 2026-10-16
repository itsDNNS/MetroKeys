"""Licence and attribution texts shipped next to the data files (spec/05b-dictionaries.md §2, §2.5).

From 0.7.0 the word lists, pair tables and prefix tables are distributed under the GNU GPL version 3 only, like the
app; the CC BY-SA 4.0 sources are adapted under the CC BY-SA 4.0 / GPLv3 compatibility and keep their attribution.
Releases up to 0.6.9 kept CC BY-SA 4.0 for their data files.
"""

import os
import zipfile

FREQUENCIES = ("FrequencyWords (c) Hermit Dave (OpenSubtitles 2018{variant}), CC BY-SA 4.0; written-language frequencies "
               "from wordfreq (c) Robyn Speer, CC BY-SA 4.0, which contains data from Wikipedia, OPUS OpenSubtitles 2018, "
               "SUBTLEX (Brysbaert et al.), Google Books Ngrams and the further sources named in its README")

SENTENCES = ("Tatoeba (tatoeba.org, authors see sentence pages, CC BY 2.0 FR) and Mozilla Common Voice (CC0)")

GPL_CLOSING = ("Distributed under the GNU GPL version 3; the CC BY-SA 4.0 material is used under the CC BY-SA 4.0 / "
               "GPLv3 compatibility.")


def wiktionary(editions):
    return f"spellings from Wiktionary ({editions}), authors see page histories, CC BY-SA 4.0"


SPELLING = {
    "de": ("spelling dictionary igerman98 (c) Björn Jacke with the frami additions (c) Franz Michael Baumann, GNU GPL "
           "version 2 or 3, used under GPL 3"),
    "es": ("spelling dictionaries RLA-ES (c) Santiago Bosio and contributors, GNU GPL version 3 or later (also LGPL 3+ / "
           "MPL 1.1+), used under GPL 3"),
    "pt": ("spelling dictionary VERO (c) Raimundo Santos Moura and contributors, GNU LGPL version 3 (also MPL)"),
    "it": ("spelling dictionary Dizionario italiano (c) Gianluca Turconi, Davide Prina, Andrea Pescetti, LibreItalia, GNU "
           "GPL version 3"),
    "fr": ("spelling dictionary Dicollecte (all-variants edition) (c) Olivier R. and contributors (grammalecte.net), "
           "Mozilla Public License 2.0"),
}

WORDS = {
    "de": (FREQUENCIES.format(variant="") + "; " + wiktionary("de.wiktionary.org") + "; " + SPELLING["de"] +
           ". Modified (filtered, case, frequency classes)."),
    "en": (FREQUENCIES.format(variant="") + "; SCOWL (c) Kevin Atkinson and others (wordlist.aspell.net), see the notice "
           "below; sentence statistics from " + SENTENCES + ". Modified (filtered, spelling, contractions, frequency "
           "classes)."),
    "es": (FREQUENCIES.format(variant="") + "; " + wiktionary("es.wiktionary.org and the Spanish sections of "
                                                              "en.wiktionary.org") + "; " + SPELLING["es"] +
           "; sentence statistics from " + SENTENCES + ". Modified (filtered, accents, case, frequency classes)."),
    "pt": (FREQUENCIES.format(variant=", Brazilian Portuguese") + "; " +
           wiktionary("pt.wiktionary.org and the Portuguese sections of en.wiktionary.org") + "; " + SPELLING["pt"] +
           "; sentence statistics from " + SENTENCES + ". Modified (filtered, hyphenated forms split, spelling reform "
           "and accents, case, frequency classes)."),
    "it": (FREQUENCIES.format(variant="") + "; " + wiktionary("it.wiktionary.org and en.wiktionary.org") + "; " +
           SPELLING["it"] + "; sentence statistics from " + SENTENCES + ". Modified (filtered, accents restored, "
           "elisions separated, case, frequency classes)."),
    "fr": (FREQUENCIES.format(variant="") + "; " + wiktionary("fr.wiktionary.org") + "; " + SPELLING["fr"] +
           "; sentence statistics from " + SENTENCES + ". Modified (filtered, accents and ligatures restored, elisions "
           "separated, hyphenated forms split, compounds, case, frequency classes)."),
}

PAIRS = ("Word pairs: counted from sentences of " + SENTENCES + ". Modified (pair counts, probability classes).")

LICENCE = ("Licence: the word list, the word pairs and the elision prefixes of this language are distributed under the "
           "GNU General Public License, version 3 only (https://www.gnu.org/licenses/gpl-3.0.html), the licence of "
           "MetroKeys; the CC BY-SA 4.0 material (Creative Commons Attribution-ShareAlike 4.0 International, "
           "https://creativecommons.org/licenses/by-sa/4.0/) is used under the one-way compatibility of CC BY-SA 4.0 "
           "with GPLv3 declared by Creative Commons. Up to version 0.6.9 the data files were licensed under CC BY-SA 4.0.")

SOURCE = ("Source form: the word lists and word pairs as text, the spelling-dictionary files exactly as used and the "
          "build report with the hashes of every input are published with each release at "
          "https://github.com/itsDNNS/MetroKeys/releases; the build tools are part of the source code "
          "(tools/build_dictionaries.py).")

# the notices of 05b §2.14 below build-data/src (the French one inside the Grammalecte archive)
NOTICES = {
    "de": [("hunspell/de/README_de_DE_frami.txt", "latin-1", None), ("hunspell/de/de_DE_frami.aff", "latin-1", "aff")],
    "es": [("hunspell/es/LICENSE.md", "utf-8", None), ("hunspell/es/README_hunspell_es.txt", "utf-8", None)],
    "pt": [("hunspell/pt_BR/README_pt_BR.txt", "utf-8", None), ("hunspell/pt_BR/README_en.txt", "utf-8", None)],
    "it": [("hunspell/it_IT/README_it_IT.txt", "utf-8", "part")],
    "fr": [("hunspell/fr/hunspell-french-dictionaries-v7.7.zip#README_dict_fr.txt", "utf-8", None)],
}


def _notice_text(src, rel, encoding, mode):
    if "#" in rel:
        archive, member = rel.split("#", 1)
        with zipfile.ZipFile(os.path.join(src, archive)) as z:
            raw = z.read(member)
    else:
        with open(os.path.join(src, rel), "rb") as f:
            raw = f.read()
    text = raw.decode(encoding).replace("\r\n", "\n")
    lines = text.split("\n")
    if mode == "aff":
        # the copyright lines of the affix file: its leading comment block
        out = []
        for line in lines:
            if not line.startswith("#"):
                break
            out.append(line)
        lines = out
    elif mode == "part":
        # the notice of the dictionary package before the first separator line (the thesaurus notice and the copy of
        # the GPL follow; the GPL text is shown once for the app)
        for i, line in enumerate(lines):
            if len(line.strip()) >= 10 and set(line.strip()) == {"-"}:
                lines = lines[:i]
                break
    return "\n".join(lines).strip() + "\n"


def text(tag, scowl_notice=None, src=None):
    lang = tag.split("-")[0]
    lines = [f"MetroKeys dictionary {tag} ({tag}.words.gz, {tag}.pairs.gz)", ""]
    lines.append("Word list: " + WORDS[lang] + " " + GPL_CLOSING)
    lines += ["", PAIRS + " Distributed under the GNU GPL version 3.", "", LICENCE, "", SOURCE, ""]
    out = "\n".join(lines)
    if src is not None:
        for rel, enc, mode in NOTICES.get(lang, ()):
            name = rel.split("#")[-1].split("/")[-1]
            what = "copyright lines of" if mode == "aff" else "notice"
            out += f"\n--- Spelling dictionary {what} {name} (verbatim{', dictionary part' if mode == 'part' else ''}) ---\n\n"
            out += _notice_text(src, rel, enc, mode)
    if scowl_notice is not None:
        out += "\n--- SCOWL notice (Copyright file of scowl-2020.12.07, verbatim) ---\n\n" + scowl_notice
    return out
