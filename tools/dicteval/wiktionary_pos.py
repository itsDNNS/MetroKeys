"""Part-of-speech headings of the Wiktionary sections of a language, for the name rule of spec/05b-dictionaries.md
§12.5 ("its Wiktionary sections of the language are all proper-noun sections").

The Portuguese and Italian passes of the build cache the raw sections (both dumps), the French pass classifies proper
nouns itself and the German pass records the parts of speech; only the Spanish sections need a pass of their own over
the Spanish dump and the Spanish sections of the English dump (cached as gzip TSV "title TAB heading|heading…").
The dumps are untrusted data: they are only parsed as text, templates are never expanded.
"""

import gzip
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "dictbuild"))

import wiktionary_es  # noqa: E402

HEADING = re.compile(r"^(=+)\s*(.*?)\s*=+\s*$")
LINE_TEMPLATE = re.compile(r"^\{\{\s*-([^|{}]+?)-\s*(\|[^{}]*)?\}\}")
TEMPLATE = re.compile(r"\{\{\s*([^|{}]+?)\s*(\|[^{}]*)?\}\}")
SUP = re.compile(r"<sup>.*?</sup>|<[^>]+>|\d+")


def headings(section):
    """Normalised headings of a section text: heading lines (template name or text, lower case, without numbering)
    and the line-start templates "{{-name-|xx}}" of the Italian edition."""
    out = []
    for line in section.split("\n"):
        line = line.rstrip()
        m = HEADING.match(line)
        if m:
            text = m.group(2)
            t = TEMPLATE.search(text)
            text = t.group(1) if t else text
            text = SUP.sub("", text).strip().lower()
            if text:
                out.append(text)
            continue
        m = LINE_TEMPLATE.match(line)
        if m:
            out.append("-" + m.group(1).strip().lower() + "-")
    return out


# keywords per edition: a heading that contains a proper-noun keyword is a proper-noun section; a heading that contains
# a part-of-speech keyword (and no proper-noun keyword) is another part of speech; other headings are ignored
PROPER = {
    "es": ["propio"],
    "pt": ["próprio", "proprio", "antropônimo", "topônimo", "antropónimo", "topónimo"],
    "it": ["-nome-", "nome proprio"],
    "en": ["proper noun"],
}
POS = {
    "es": ["sustantivo", "adjetivo", "verbo", "adverbio", "pronombre", "preposición", "conjunción", "interjección",
           "artículo", "determinante", "numeral", "onomatopeya", "contracción", "abreviatura", "sigla", "acrónimo",
           "locución", "forma", "participio", "afijo", "sufijo", "prefijo", "símbolo", "letra", "frase", "expresión",
           "refrán", "postposición"],
    "pt": ["substantivo", "adjetivo", "verbo", "advérbio", "pronome", "preposição", "conjunção", "interjeição",
           "artigo", "numeral", "contração", "abreviatura", "sigla", "acrónimo", "acrônimo", "locução", "expressão",
           "prefixo", "sufixo", "símbolo", "letra", "onomatopeia", "partícula", "forma"],
    "it": ["-sost-", "-agg-", "-verb-", "-avv-", "-pron-", "-prep-", "-cong-", "-inter-", "-art-", "-acron-",
           "-abbr-", "-sigla-", "-loc-", "-suff-", "-pref-", "-card-", "-ord-", "-lett-", "-sim-", "-agg num-",
           "-pref-", "-espr-", "-inflessioni-", "-voce verb-", "sostantivo", "aggettivo", "verbo", "avverbio"],
    "en": ["noun", "verb", "adjective", "adverb", "pronoun", "preposition", "conjunction", "interjection", "article",
           "determiner", "numeral", "particle", "contraction", "abbreviation", "initialism", "acronym", "phrase",
           "prefix", "suffix", "letter", "symbol", "participle", "postposition", "proverb", "prepositional phrase"],
}


def section_kind(heads, edition):
    """'proper' if the section's parts of speech are all proper nouns, 'other' if it has another part of speech,
    None if no part of speech is recognised."""
    proper = other = False
    for h in heads:
        if any(k in h for k in PROPER[edition]):
            proper = True
        elif any(k == h or h.startswith(k + " ") or (k in h and not k.startswith("-")) for k in POS[edition]):
            other = True
    if other:
        return "other"
    return "proper" if proper else None


def cache_path(cache, dump):
    return os.path.join(cache, f"{dump}wiktionary-20261001-spanish-headings.tsv.gz")


def run_spanish(dump_path, out_path, dump):
    pattern = wiktionary_es.ES_SECTION if dump == "es" else wiktionary_es.EN_SPANISH_SECTION
    level2 = wiktionary_es._is_es_level2 if dump == "es" else wiktionary_es._is_en_level2
    rows = {}
    for title, ns, text in wiktionary_es._pages(dump_path, dump + "wiktionary"):
        if ns != "0" or title is None or not wiktionary_es.SINGLE_WORD.match(title):
            continue
        if dump == "en" and "==Spanish==" not in text.replace(" ", ""):
            continue
        lines = wiktionary_es._section(text, pattern, level2)
        if lines is None:
            continue
        rows[title] = headings("\n".join(lines))
    tmp = out_path + ".tmp"
    with gzip.open(tmp, "wt", encoding="utf-8") as out:
        for t in sorted(rows):
            out.write(t + "\t" + "|".join(h.replace("|", "/").replace("\t", " ") for h in rows[t]) + "\n")
    os.replace(tmp, out_path)


def load_headings(path):
    out = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            t, _, h = line.rstrip("\n").partition("\t")
            out[t] = [x for x in h.split("|") if x]
    return out


if __name__ == "__main__":
    run_spanish(sys.argv[1], sys.argv[2], sys.argv[3])
