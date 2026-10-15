#!/usr/bin/env python3
"""Builds the MetroKeys word lists and word-pair tables from the free sources (spec/05b-dictionaries.md).

Usage (from the repository root):
    python3 tools/build_dictionaries.py download            # fetch missing sources into build-data/src, verify hashes
    python3 tools/build_dictionaries.py build [TAG ...]     # verify hashes, build the languages (default: all)

Every language has a build profile (05b §7) in PROFILES: its sources, its word-list builder and its pair sources.

Downloads are untrusted input: archives are read in place (tarfile/bz2), nothing in them is executed.
The output is deterministic: identical inputs give identical bytes.
"""

import bz2
import json
import os
import sys
import tarfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "dictbuild"))

import build_de  # noqa: E402
import build_en  # noqa: E402
import build_es  # noqa: E402
import common  # noqa: E402
import licenses  # noqa: E402
import pairs  # noqa: E402
import wiktionary_de  # noqa: E402
import wiktionary_es  # noqa: E402

ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "build-data", "src")
CACHE = os.path.join(ROOT, "build-data", "cache")
OUT = os.path.join(ROOT, "engine", "src", "main", "resources", "app", "metrokeys", "engine", "dict")
WIKT_DUMP = "wiktionary/dewiktionary-20261001-pages-articles.xml.bz2"
ES_WIKT_DUMP = "wiktionary/eswiktionary-20261001-pages-articles.xml.bz2"
EN_WIKT_DUMP = "wiktionary/enwiktionary-20261001-pages-articles.xml.bz2"
SCOWL = "scowl/scowl-2020.12.07.tar.gz"


def manifest():
    with open(os.path.join(HERE, "sources.json"), encoding="utf-8") as f:
        return json.load(f)["sources"]


def download():
    for s in manifest():
        path = os.path.join(SRC, s["path"])
        if os.path.exists(path):
            continue
        os.makedirs(os.path.dirname(path), exist_ok=True)
        print("downloading", s["url"])
        urllib.request.urlretrieve(s["url"], path + ".part")
        os.replace(path + ".part", path)
    verify()


def verify():
    bad = []
    for s in manifest():
        path = os.path.join(SRC, s["path"])
        if not os.path.exists(path):
            bad.append(f"missing: {s['path']}")
        elif common.sha256_file(path) != s["sha256"]:
            bad.append(f"hash mismatch: {s['path']}")
    if bad:
        sys.exit("source check failed:\n  " + "\n  ".join(bad))
    print("all source hashes verified")


def tatoeba_sentences(name, lang):
    with bz2.open(os.path.join(SRC, "tatoeba", name), "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3 and parts[1] == lang:
                yield parts[2]


# Common Voice files of the pair sources with their weights (05b §2.2, §2.4, §2.7); None = every *.txt file of the
# directory except Europarl, wiki.* with weight 1 and the others with weight 3
CV_FILES = {
    "de": None,
    "en": None,
    "es": {"sentence-collector.txt": 3, "wiki.es.txt": 1},
}


def cv_files(lang):
    d = os.path.join(SRC, "cv", lang)
    chosen = CV_FILES[lang]
    for fn in sorted(os.listdir(d)):
        if chosen is not None:
            if fn not in chosen:
                continue
            weight = chosen[fn]
        else:
            if not fn.endswith(".txt") or fn.startswith("europarl"):
                continue
            weight = 1 if fn.startswith("wiki.") else 3
        with open(os.path.join(d, fn), encoding="utf-8") as f:
            yield fn, weight, [line.strip() for line in f if line.strip()]


def scowl_final():
    """Extracts only the plain word lists and the notice of the SCOWL archive (no scripts)."""
    target = os.path.join(CACHE, "scowl-2020.12.07")
    final = os.path.join(target, "final")
    if not os.path.isdir(final):
        os.makedirs(final, exist_ok=True)
        with tarfile.open(os.path.join(SRC, SCOWL)) as tar:
            for m in tar.getmembers():
                parts = m.name.split("/")
                if not m.isfile() or len(parts) < 2:
                    continue
                if parts[1] == "final" and len(parts) == 3:
                    dest = os.path.join(final, parts[2])
                elif m.name.endswith("/Copyright") and len(parts) == 2:
                    dest = os.path.join(target, "Copyright")
                else:
                    continue
                with tar.extractfile(m) as src, open(dest, "wb") as out:
                    out.write(src.read())
    return final, os.path.join(target, "Copyright")


def build_pairs(lang, entries, keys, tatoeba_name, tatoeba_lang, cv_lang, stock=None):
    counter = pairs.PairCounter(lang, entries, stock)
    for text in tatoeba_sentences(tatoeba_name, tatoeba_lang):
        counter.add(text, 3, True)
    for _, weight, lines in cv_files(cv_lang):
        for text in lines:
            counter.add(text, weight, False)
    order = {k: i for i, k in enumerate(keys)}
    return counter.table(order)


def write_outputs(tag, scale, max_count, corpus_tokens, entries, table, stats):
    os.makedirs(OUT, exist_ok=True)
    words_path = os.path.join(OUT, f"{tag}.words.gz")
    pairs_path = os.path.join(OUT, f"{tag}.pairs.gz")
    digest, keys = common.write_word_list(words_path, tag, scale, max_count, corpus_tokens, entries)
    common.write_pair_table(pairs_path, tag, digest, keys, entries, table)
    stats[tag] = {
        "S": scale,
        "maxCount": int(max_count),
        "corpusTokens": int(corpus_tokens),
        "keys": len(keys),
        "forms": sum(len(v) for v in entries.values()),
        "pairPrevWords": len(table),
        "pairs": sum(len(v) for v in table.values()),
        "wordsPayloadSha256": digest.hex(),
        "wordsFile": {"bytes": os.path.getsize(words_path), "sha256": common.sha256_file(words_path)},
        "pairsFile": {"bytes": os.path.getsize(pairs_path), "sha256": common.sha256_file(pairs_path)},
    }
    print(tag, json.dumps(stats[tag], indent=1))


def write_licenses(copyright_path=None, tags=None):
    tags = tags or [t for t in PROFILES if t != "en-US" or copyright_path]
    scowl_notice = None
    if copyright_path:
        with open(copyright_path, "rb") as f:
            scowl_notice = f.read()
        with open(os.path.join(OUT, "SCOWL-Copyright.txt"), "wb") as f:
            f.write(scowl_notice)
    for tag in tags:
        notice = scowl_notice.decode("latin-1") if tag == "en-US" else None
        with open(os.path.join(OUT, f"LICENSE-{tag}.txt"), "w", encoding="utf-8", newline="\n") as f:
            f.write(licenses.text(tag, notice))


def build_german(stats):
    wikt_cache = os.path.join(CACHE, "dewiktionary-20261001.tsv.gz")
    if not os.path.exists(wikt_cache):
        print("Wiktionary pass (a few minutes)")
        wiktionary_de.run(os.path.join(SRC, WIKT_DUMP), wikt_cache)
    de = build_de.GermanBuilder(os.path.join(SRC, "fw", "de_full.txt"), wiktionary_de.load(wikt_cache))
    de_entries = de.build()
    de_keys = sorted(de_entries)
    de_table = build_pairs("de", de_entries, de_keys, "deu_sentences.tsv.bz2", "deu", "de")
    write_outputs("de-DE", de.scale, de.max_count, de.corpus_tokens, de_entries, de_table, stats)
    write_licenses(tags=["de-DE"])


def build_english(stats):
    final, copyright_path = scowl_final()
    st = build_en.SentenceStats()
    for text in tatoeba_sentences("eng_sentences.tsv.bz2", "eng"):
        st.add_sentence(text, 3, True)
    for _, weight, lines in cv_files("en"):
        for text in lines:
            st.add_sentence(text, weight, weight == 3)
    st.finish()
    en = build_en.EnglishBuilder(os.path.join(SRC, "fw", "en_full.txt"), final, st)
    en_entries = en.build()
    en_keys = sorted(en_entries)
    en_table = build_pairs("en", en_entries, en_keys, "eng_sentences.tsv.bz2", "eng", "en")
    stats["en-US-extra"] = {
        "aliasKeys": getattr(en, "alias_added", 0),
        "aliasKeysReplacingAWord": getattr(en, "alias_replaced", 0),
        "possessiveForms": sum(1 for k in en_entries if k.endswith("'s") and k not in build_en.PRONOUN_S),
    }
    write_outputs("en-US", en.scale, en.max_count, en.corpus_tokens, en_entries, en_table, stats)
    write_licenses(copyright_path, tags=["en-US"])


def build_spanish(stats):
    es_cache = os.path.join(CACHE, "eswiktionary-20261001.tsv.gz")
    en_cache = os.path.join(CACHE, "enwiktionary-20261001-spanish.tsv.gz")
    if not os.path.exists(es_cache):
        print("Spanish Wiktionary pass")
        wiktionary_es.run_es(os.path.join(SRC, ES_WIKT_DUMP), es_cache)
    if not os.path.exists(en_cache):
        print("English Wiktionary pass, Spanish sections (about 15 minutes)")
        wiktionary_es.run_en_spanish(os.path.join(SRC, EN_WIKT_DUMP), en_cache)
    es_pass = wiktionary_es.load(es_cache)
    en_pass = wiktionary_es.load(en_cache)

    def sentences():
        for text in tatoeba_sentences("spa_sentences.tsv.bz2", "spa"):
            yield text, True, True
        for _, weight, lines in cv_files("es"):
            for text in lines:
                yield text, weight == 3, False

    st = build_es.SentenceStats()
    for text, conversational, tatoeba in sentences():
        st.add_sentence(text, conversational, tatoeba)
    st.finish()
    cv_used = [[fn, weight] for fn, weight, _ in cv_files("es")]
    freq = os.path.join(SRC, "fw", "es_full.txt")
    # §8.2 point 3: the English dump is used if the Spanish dump alone recognises < 95 % of the top 20 000 keys
    es_alone = build_es.SpanishBuilder(freq, es_pass, None, st).coverage()
    use_en = es_alone < 0.95
    es = build_es.SpanishBuilder(freq, es_pass, en_pass if use_en else None, st)
    with_en = es.coverage()
    phrases = build_es.PhraseCounter(p for _, p in es.fused_candidates())
    for text, _, _ in sentences():
        phrases.add(text)
    es.set_phrase_counts(phrases.tokens, phrases.pairs, phrases.total)
    es_entries = es.build()
    es_keys = sorted(es_entries)
    stock, top30, added = build_es.stock_names(st, es.counts)
    es_table = build_pairs("es", es_entries, es_keys, "spa_sentences.tsv.bz2", "spa", "es", stock)
    report = dict(es.report)
    report.update({
        "coverageTop20000SpanishDumpOnly": round(es_alone, 4),
        "coverageTop20000WithEnglishDump": round(with_en, 4),
        "englishDumpUsed": use_en,
        "spanishWiktionary": {k: len(v) for k, v in es_pass.items()},
        "englishWiktionarySpanishSections": {k: len(v) for k, v in en_pass.items() if k != "T"},
        "commonVoiceFiles": cv_used,
        "tatoebaStockNames": sorted(stock),
        "tatoebaStockNamesAdded": added,
        "tatoebaTop30Capitalised": top30,
        "formsKeptWithTwoCases": sum(1 for v in es_entries.values() if len(v) > 1),
        "properNounKeys": sum(1 for v in es_entries.values() if v[0][0][0].isupper()),
    })
    stats["es-ES-extra"] = report
    write_outputs("es-ES", es.scale, es.max_count, es.corpus_tokens, es_entries, es_table, stats)
    write_licenses(tags=["es-ES"])


# build profiles (05b §7): language tag -> builder
PROFILES = {
    "de-DE": build_german,
    "en-US": build_english,
    "es-ES": build_spanish,
}


def build(tags=None):
    verify()
    os.makedirs(CACHE, exist_ok=True)
    stats_path = os.path.join(HERE, "dictionary-build.json")
    stats = {}
    if os.path.exists(stats_path):
        with open(stats_path, encoding="utf-8") as f:
            stats = json.load(f)
    for tag in tags or list(PROFILES):
        if tag not in PROFILES:
            sys.exit(f"unknown language {tag}; known: {', '.join(PROFILES)}")
        PROFILES[tag](stats)
    with open(stats_path, "w", encoding="utf-8") as f:
        json.dump(stats, f, indent=1, sort_keys=True, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "build"
    if cmd == "download":
        download()
    elif cmd == "verify":
        verify()
    elif cmd == "build":
        build(sys.argv[2:])
    elif cmd == "licenses":
        write_licenses(scowl_final()[1])
    else:
        sys.exit(__doc__)
