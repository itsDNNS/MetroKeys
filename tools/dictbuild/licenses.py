"""Licence and attribution texts shipped next to the data files (spec 05b §2.5)."""

CC_BY_SA = ("These data files are licensed under the Creative Commons Attribution-ShareAlike 4.0 International "
            "licence (CC BY-SA 4.0), https://creativecommons.org/licenses/by-sa/4.0/. "
            "The licence applies to the data files only, not to the MetroKeys program code.")

PAIRS = ("Word pairs: counted from sentences of Tatoeba (tatoeba.org, authors see sentence pages, CC BY 2.0 FR) "
         "and Mozilla Common Voice (CC0). Modified (pair counts, probability classes), CC BY-SA 4.0.")

DE_WORDS = ("Word list: FrequencyWords (c) Hermit Dave (OpenSubtitles 2018), CC BY-SA 4.0; spellings from "
            "Wiktionary (de.wiktionary.org), authors see page histories, CC BY-SA 4.0. Modified (filtered, case, "
            "frequency classes).")

EN_WORDS = ("Word list: FrequencyWords (c) Hermit Dave (OpenSubtitles 2018), CC BY-SA 4.0; SCOWL (c) Kevin Atkinson "
            "and others (wordlist.aspell.net), see the notice below; sentence statistics from Tatoeba (CC BY 2.0 FR) "
            "and Mozilla Common Voice (CC0). Modified (filtered, spelling, contractions, frequency classes), "
            "CC BY-SA 4.0.")

ES_WORDS = ("Word list: FrequencyWords (c) Hermit Dave (OpenSubtitles 2018), CC BY-SA 4.0; spellings from Wiktionary "
            "(es.wiktionary.org and the Spanish sections of en.wiktionary.org), authors see page histories, "
            "CC BY-SA 4.0; sentence statistics from Tatoeba (CC BY 2.0 FR) and Mozilla Common Voice (CC0). Modified "
            "(filtered, accents, case, frequency classes), CC BY-SA 4.0.")

WORDS = {"de": DE_WORDS, "en": EN_WORDS, "es": ES_WORDS}


def text(tag, scowl_notice=None):
    lines = [f"MetroKeys dictionary {tag} ({tag}.words.gz, {tag}.pairs.gz)", ""]
    lines.append(WORDS[tag.split("-")[0]])
    lines += ["", PAIRS, "", CC_BY_SA, ""]
    out = "\n".join(lines)
    if scowl_notice is not None:
        out += "\n--- SCOWL notice (Copyright file of scowl-2020.12.07, verbatim) ---\n\n" + scowl_notice
    return out
