#!/usr/bin/env python3
"""Builds the MetroKeys word lists and word-pair tables from the free sources (spec/05b-dictionaries.md).

Usage (from the repository root):
    python3 tools/build_dictionaries.py download   # fetch missing sources into build-data/src and verify hashes
    python3 tools/build_dictionaries.py build      # verify hashes, build de-DE and en-US into the engine resources

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
import common  # noqa: E402
import licenses  # noqa: E402
import pairs  # noqa: E402
import wiktionary_de  # noqa: E402

ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "build-data", "src")
CACHE = os.path.join(ROOT, "build-data", "cache")
OUT = os.path.join(ROOT, "engine", "src", "main", "resources", "app", "metrokeys", "engine", "dict")
WIKT_DUMP = "wiktionary/dewiktionary-20261001-pages-articles.xml.bz2"
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


def cv_files(lang):
    d = os.path.join(SRC, "cv", lang)
    for fn in sorted(os.listdir(d)):
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


def build_pairs(lang, entries, keys, tatoeba_name, tatoeba_lang, cv_lang):
    counter = pairs.PairCounter(lang, entries)
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


def write_licenses(copyright_path):
    with open(copyright_path, "rb") as f:
        scowl_notice = f.read()
    with open(os.path.join(OUT, "SCOWL-Copyright.txt"), "wb") as f:
        f.write(scowl_notice)
    with open(os.path.join(OUT, "LICENSE-de-DE.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(licenses.text("de-DE"))
    with open(os.path.join(OUT, "LICENSE-en-US.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(licenses.text("en-US", scowl_notice.decode("latin-1")))


def build():
    verify()
    os.makedirs(CACHE, exist_ok=True)
    stats = {}

    # German
    wikt_cache = os.path.join(CACHE, "dewiktionary-20261001.tsv.gz")
    if not os.path.exists(wikt_cache):
        print("Wiktionary pass (a few minutes)")
        wiktionary_de.run(os.path.join(SRC, WIKT_DUMP), wikt_cache)
    de = build_de.GermanBuilder(os.path.join(SRC, "fw", "de_full.txt"), wiktionary_de.load(wikt_cache))
    de_entries = de.build()
    de_keys = sorted(de_entries)
    de_table = build_pairs("de", de_entries, de_keys, "deu_sentences.tsv.bz2", "deu", "de")
    write_outputs("de-DE", de.scale, de.max_count, de.corpus_tokens, de_entries, de_table, stats)

    # English
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

    write_licenses(copyright_path)
    with open(os.path.join(HERE, "dictionary-build.json"), "w", encoding="utf-8") as f:
        json.dump(stats, f, indent=1, sort_keys=True)
        f.write("\n")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "build"
    if cmd == "download":
        download()
    elif cmd == "verify":
        verify()
    elif cmd == "build":
        build()
    elif cmd == "licenses":
        write_licenses(scowl_final()[1])
    else:
        sys.exit(__doc__)
