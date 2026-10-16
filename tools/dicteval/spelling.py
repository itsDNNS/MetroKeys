"""Loads the pinned spelling dictionaries of spec/05b-dictionaries.md §2.14 per language (read in place)."""

import hashlib
import os
import zipfile

from hunspell import SpellingDictionaries

ES_VARIANTS = ["es_ES", "es_AR", "es_BO", "es_CL", "es_CO", "es_CR", "es_CU", "es_DO", "es_EC", "es_GT", "es_HN",
               "es_MX", "es_NI", "es_PA", "es_PE", "es_PR", "es_PY", "es_SV", "es_US", "es_UY", "es_VE"]
FR_ZIP = "hunspell/fr/hunspell-french-dictionaries-v7.7.zip"


def _read(src, rel):
    with open(os.path.join(src, rel), "rb") as f:
        return f.read()


def load(src, tag):
    """[src]: build-data/src. Returns SpellingDictionaries, or None for en-US (SCOWL is its spelling source)."""
    if tag == "de-DE":
        groups = [(_read(src, "hunspell/de/de_DE_frami.aff"), [_read(src, "hunspell/de/de_DE_frami.dic")])]
    elif tag == "pt-BR":
        groups = [(_read(src, "hunspell/pt_BR/pt_BR.aff"), [_read(src, "hunspell/pt_BR/pt_BR.dic")])]
    elif tag == "it-IT":
        groups = [(_read(src, "hunspell/it_IT/it_IT.aff"), [_read(src, "hunspell/it_IT/it_IT.dic")])]
    elif tag == "fr-FR":
        with zipfile.ZipFile(os.path.join(src, FR_ZIP)) as z:
            groups = [(z.read("fr-toutesvariantes.aff"), [z.read("fr-toutesvariantes.dic")])]
    elif tag == "es-ES":
        # the 21 files share 8 distinct affix files; word files with the same affix file are merged
        by_aff = {}
        for v in ES_VARIANTS:
            aff = _read(src, f"hunspell/es/{v}.aff")
            by_aff.setdefault(hashlib.sha256(aff).hexdigest(), (aff, []))[1].append(_read(src, f"hunspell/es/{v}.dic"))
        groups = [by_aff[h] for h in sorted(by_aff)]
    else:
        return None
    return SpellingDictionaries(groups)
