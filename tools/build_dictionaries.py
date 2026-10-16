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
import build_fr  # noqa: E402
import build_it  # noqa: E402
import build_pt  # noqa: E402
import common  # noqa: E402
import licenses  # noqa: E402
import pairs  # noqa: E402
import wiktionary_affix  # noqa: E402
import wiktionary_de  # noqa: E402
import wiktionary_es  # noqa: E402
import wiktionary_fr  # noqa: E402
import wiktionary_it  # noqa: E402
import wiktionary_pt  # noqa: E402

ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "build-data", "src")
CACHE = os.path.join(ROOT, "build-data", "cache")
OUT = os.path.join(ROOT, "engine", "src", "main", "resources", "app", "metrokeys", "engine", "dict")
WIKT_DUMP = "wiktionary/dewiktionary-20261001-pages-articles.xml.bz2"
ES_WIKT_DUMP = "wiktionary/eswiktionary-20261001-pages-articles.xml.bz2"
EN_WIKT_DUMP = "wiktionary/enwiktionary-20261001-pages-articles.xml.bz2"
PT_WIKT_DUMP = "wiktionary/ptwiktionary-20261001-pages-articles.xml.bz2"
IT_WIKT_DUMP = "wiktionary/itwiktionary-20261001-pages-articles.xml.bz2"
FR_WIKT_DUMP = "wiktionary/frwiktionary-20261001-pages-articles.xml.bz2"
SCOWL = "scowl/scowl-2020.12.07.tar.gz"
# generic, carries no personal data
USER_AGENT = "MetroKeys-dictionary-build/0.6 (+https://github.com/itsDNNS/MetroKeys)"


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
        req = urllib.request.Request(s["url"], headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req) as resp, open(path + ".part", "wb") as out:
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk)
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
    "pt": {"sentence-collector.txt": 3},
    "it": {"frasi.txt": 3, "sentence-collector.txt": 3, "wiki.it.txt": 1, "wiki.it.rerun-2021-10.txt": 1},
    "fr": {"sentence-collector.txt": 3, "global-sprint-fr.txt": 3, "vfvv1.1.txt": 3, "vfvv2.txt": 3, "vfvv3.txt": 3,
           "vfvv4.txt": 3, "vfvv5.txt": 3, "vfvv6.txt": 3, "issue2259_deleted_export_readd_fixed.txt": 1,
           "wiki-1.fr.txt": 1, "wiki-2.fr.txt": 1},
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


def build_pairs(lang, entries, keys, tatoeba_name, tatoeba_lang, cv_lang, stock=None, damped=None, damping=1.0):
    counter = pairs.PairCounter(lang, entries, stock, damped, damping)
    for text in tatoeba_sentences(tatoeba_name, tatoeba_lang):
        counter.add(text, 3, True)
    for _, weight, lines in cv_files(cv_lang):
        for text in lines:
            counter.add(text, weight, False)
    order = {k: i for i, k in enumerate(keys)}
    return counter.table(order)


def write_outputs(tag, scale, max_count, corpus_tokens, entries, table, stats, prefixes=None, unattested=None):
    """[unattested]: the keys kept by the count rule without being a form (the damped keys, 05 §10.1 step 5, 0.6.9);
    only those that are in [entries] are flagged."""
    os.makedirs(OUT, exist_ok=True)
    words_path = os.path.join(OUT, f"{tag}.words.gz")
    pairs_path = os.path.join(OUT, f"{tag}.pairs.gz")
    flagged = {k for k in (unattested or ()) if k in entries}
    digest, keys = common.write_word_list(words_path, tag, scale, max_count, corpus_tokens, entries, prefixes, flagged)
    common.write_pair_table(pairs_path, tag, digest, keys, entries, table, [p for p, _ in prefixes] if prefixes else None)
    stats[tag] = {
        "S": scale,
        "maxCount": int(max_count),
        "corpusTokens": int(corpus_tokens),
        "keys": len(keys),
        "unattestedKeys": len(flagged),
        "forms": sum(len(v) for v in entries.values()),
        "pairPrevWords": len(table),
        "pairs": sum(len(v) for v in table.values()),
        "wordsPayloadSha256": digest.hex(),
        "wordsFile": {"bytes": os.path.getsize(words_path), "sha256": common.sha256_file(words_path)},
        "pairsFile": {"bytes": os.path.getsize(pairs_path), "sha256": common.sha256_file(pairs_path)},
    }
    print(tag, json.dumps(stats[tag], indent=1))


def forms_text(forms):
    return " ".join(f"{f}:{c}" for f, c in forms)


def short_nonforms(entries, is_form, count):
    """05b §3.6 "Other languages" (0.6.4): kept keys of 2-3 letters that are no form of the language, with their count
    and the build's decision (the stored forms with their classes), most frequent first."""
    rows = [k for k in entries if 2 <= sum(1 for ch in k if ch.isalpha()) <= 3 and not is_form(k)]
    rows.sort(key=lambda k: (-count(k), k))
    return [f"{k} {int(count(k))} {forms_text(entries[k])}" for k in rows]


def nonform_report(builder, entries, stock, stock_damped):
    """05b §3.6 "Other languages" (0.6.7) build report: the short keys dropped by step 6a (that the count rule would have
    kept) and the keys damped as kept only by the count rule, most frequent first; [stock_damped] is the stock-name list
    the §5 step 3 rule would give with the damped subtitle counts (report only, not used)."""
    count = builder.count
    top = sorted(builder.damped, key=lambda k: (-count(k), k))
    return {
        "shortKeysDroppedByStep6a": len(builder.short_dropped),
        "shortKeysDroppedByStep6aList": [f"{k} {count(k)}" for k in sorted(builder.short_dropped, key=lambda k: (-count(k), k))],
        "dampedKeys": len(builder.damped),
        "dampedKeysClass0": sum(1 for k in builder.damped if k in entries and all(c == 0 for _, c in entries[k])),
        "dampedTop50": [f"{k} {count(k)} {forms_text(entries.get(k, []))}" for k in top[:50]],
        "stockNamesWithDampedSubtitleCounts": sorted(stock_damped),
        "stockNamesChangedByDampedCounts": sorted(set(stock) ^ set(stock_damped)),
    }


def damped_counts(counts, builder):
    return {k: c * common.DAMPING if k in builder.damped else c for k, c in counts.items()}


ENGLISH_GUARD_RANK = 5000
_english_top = None


def english_top():
    """05b §3.6 English guard (0.6.7): the ENGLISH_GUARD_RANK most frequent keys of the pinned English FrequencyWords
    list (line order, lower-cased with Java case mapping, duplicates counted once)."""
    global _english_top
    if _english_top is None:
        keys = []
        seen = set()
        with open(os.path.join(SRC, "fw", "en_full.txt"), encoding="utf-8") as f:
            for line in f:
                parts = line.split()
                if len(parts) != 2:
                    continue
                k = common.jlower(parts[0])
                if k not in seen:
                    seen.add(k)
                    keys.append(k)
                    if len(keys) == ENGLISH_GUARD_RANK:
                        break
        _english_top = set(keys)
    return _english_top


def abbreviations(lang, dumps, ending="."):
    """05b §3.6 "Abbreviation and affix titles" (0.6.7): {dump: {form: parts of speech}} of the titles ending in
    [ending] ("." abbreviations, which are forms; "-" prefixes, German compound parts only) with a section of [lang] in
    each of [dumps] (one cached pass per dump)."""
    out = {}
    for dump in dumps:
        path = wiktionary_affix.cache_path(CACHE, dump)
        if not os.path.exists(path):
            print(f"{dump} Wiktionary pass, abbreviation and affix titles")
            wiktionary_affix.extract(os.path.join(SRC, f"wiktionary/{dump}wiktionary-20261001-pages-articles.xml.bz2"),
                                     path, dump)
        out[dump] = wiktionary_affix.load([path], lang, ending)
    if ending == ".":
        # English guard: an abbreviation title of a frequent English key makes no form
        guard = english_top()
        for d, forms in out.items():
            GUARDED.setdefault(lang, {}).setdefault(d, set()).update(common.jlower(f) for f in forms
                                                                     if common.jlower(f) in guard)
        out = {d: {f: p for f, p in forms.items() if common.jlower(f) not in guard} for d, forms in out.items()}
    return out


GUARDED = {}  # language -> dump -> keys whose abbreviation titles the English guard ignored (report)


def abbreviation_report(keys, entries, count, titles, lang=None, is_form=None, dumps=None):
    """Build report of the keys that are forms only by the abbreviation rule, most frequent first, and of the keys the
    English guard kept from becoming forms (with their decision: stored forms, or dropped)."""
    rows = sorted(keys, key=lambda k: (-count(k), k))
    kept = [k for k in rows if k in entries]
    by_dump = GUARDED.get(lang, {})
    pool = set().union(*(v for d, v in by_dump.items() if dumps is None or d in dumps)) if by_dump else set()
    guarded = sorted((k for k in pool if is_form and not is_form(k)), key=lambda k: (-count(k), k))
    return {
        "englishGuardKeys": len(guarded),
        "englishGuardKeysList": [f"{k} {int(count(k))} {forms_text(entries[k]) if k in entries else '(dropped)'}"
                                 for k in guarded],
        "abbreviationTitles": titles,
        "abbreviationKeys": len(keys),
        "abbreviationKeysInWordList": len(kept),
        "abbreviationKeysTop60": [f"{k} {int(count(k))} {forms_text(entries[k])}" for k in kept[:60]],
    }


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


def trial_sizes(tag, builder, entries, table):
    """Sizes of the word list and pair table of [entries] written to a scratch directory (§3.7 build report)."""
    tmp = os.path.join(CACHE, "trial")
    os.makedirs(tmp, exist_ok=True)
    words_path = os.path.join(tmp, f"{tag}.words.gz")
    pairs_path = os.path.join(tmp, f"{tag}.pairs.gz")
    digest, keys = common.write_word_list(words_path, tag, builder.scale, builder.max_count, builder.corpus_tokens, entries)
    common.write_pair_table(pairs_path, tag, digest, keys, entries, table)
    return {"keys": len(keys), "forms": sum(len(v) for v in entries.values()),
            "wordsBytes": os.path.getsize(words_path), "pairsBytes": os.path.getsize(pairs_path),
            "pairs": sum(len(v) for v in table.values())}


def build_german(stats):
    wikt_cache = os.path.join(CACHE, "dewiktionary-20261001.tsv.gz")
    if not os.path.exists(wikt_cache):
        print("Wiktionary pass (a few minutes)")
        wiktionary_de.run(os.path.join(SRC, WIKT_DUMP), wikt_cache)
    pos_cache = os.path.join(CACHE, "dewiktionary-20261001-pos.tsv.gz")
    if not os.path.exists(pos_cache):
        print("Wiktionary part-of-speech pass (a few minutes)")
        wiktionary_de.pos_run(os.path.join(SRC, WIKT_DUMP), pos_cache)
    de_abbr = abbreviations("de", ["de"])["de"]
    de_prefixes = abbreviations("de", ["de"], "-")["de"]
    de = build_de.GermanBuilder(os.path.join(SRC, "fw", "de_full.txt"), wiktionary_de.load(wikt_cache), de_abbr,
                                de_prefixes)
    de_entries = de.build(os.path.join(ROOT, "spec", "data", "layouts-de.json"))

    # §3.7 dictionary-only German lemmas (0.6.6): lemmas without a subtitle occurrence or with exactly one, class 1
    base_size = trial_sizes("de-DE", de, de_entries, build_pairs("de", de_entries, sorted(de_entries), "deu_sentences.tsv.bz2",
                                                                  "deu", "de", damped=de.damped, damping=build_de.DAMPING))
    lemmas = build_de.lemma_keys(wiktionary_de.load_pos(pos_cache), de_abbr)
    unseen = sum(1 for k in de.dictionary_only_keys(de_entries, lemmas) if de.count(k) == 0)
    de_entries = dict(de_entries)
    added = de.add_dictionary_only(de_entries, lemmas)
    de_keys = sorted(de_entries)
    de_table = build_pairs("de", de_entries, de_keys, "deu_sentences.tsv.bz2", "deu", "de",
                           damped=de.damped, damping=build_de.DAMPING)
    dict_only = {
        "variantUsed": "lemmas",
        "before": base_size,
        "after": dict(trial_sizes("de-DE", de, de_entries, de_table), addedKeys=len(added),
                      addedUnseen=unseen, addedSeenOnce=len(added) - unseen),
        "addedSample": [f"{k} {de.count(k)} {forms_text(de_entries[k])}" for k in added[::max(1, len(added) // 100)][:100]],
    }
    # 0.6.4 report: step 6a and the damping of non-German keys (§3.6)
    top = sorted(de.damped, key=lambda k: (-de.count(k), k))
    stats["de-DE-extra"] = {
        "umlautMerged": len(de.umlaut_merged),
        "umlautMergedTop100": [f"{k} {de.merged_counts[k]} -> {t} {de.count(t) - de.merged_into[t]}"
                               for k, t in sorted(de.umlaut_merged.items(), key=lambda kt: (-de.merged_counts[kt[0]], kt[0]))[:100]],
        "shortKeysDroppedByStep6a": len(de.short_dropped),
        "shortKeysDroppedByStep6aList": [f"{k} {de.count(k)}" for k in sorted(de.short_dropped, key=lambda k: (-de.count(k), k))],
        "dampedKeys": len(de.damped),
        "dampedTop50": [f"{k} {de.count(k)} {forms_text(de_entries[k])}" for k in top[:50]],
        "shortKeysNotForms": short_nonforms(de_entries, de.is_german_form, de.count),
        "realWordTypos": len(de.typos),
        "realWordTyposTop150": [f"{k} {de.count(k)} -> {w} {de.count(w)}"
                                for k, w in sorted(de.typos.items(), key=lambda kw: (-de.count(kw[0]), kw[0]))[:150]],
        "dictionaryOnlyForms": dict_only,
        **abbreviation_report(de.abbreviation_keys, de_entries, de.count, len(de_abbr), "de", de.is_german_form),
        "abbreviationKeysDictionaryOnly": sorted(k for k in added if k in de.abbreviation_keys),
        "prefixParts": len(de.prefix_parts),
        "keysByPrefixCompounds": len(de.prefix_compounds),
        "keysByPrefixCompoundsTop60": [f"{k} {de.count(k)} {forms_text(de_entries[k])}"
                                       for k in sorted(de.prefix_compounds, key=lambda k: (-de.count(k), k))[:60]],
    }
    write_outputs("de-DE", de.scale, de.max_count, de.corpus_tokens, de_entries, de_table, stats, unattested=de.damped)
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
        # 05b §3.6 (0.6.4): a form of English = a SCOWL key
        "shortKeysNotForms": short_nonforms(en_entries, lambda k: k in en.main_keys or k in en.large_forms, en.count),
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
    es_abbr = abbreviations("es", ["es", "en"])
    es_alone = build_es.SpanishBuilder(freq, es_pass, None, st, es_abbr["es"]).coverage()
    use_en = es_alone < 0.95
    es_abbr_used = set(es_abbr["es"]) | (set(es_abbr["en"]) if use_en else set())
    es = build_es.SpanishBuilder(freq, es_pass, en_pass if use_en else None, st, es_abbr_used)
    with_en = es.coverage()
    phrases = build_es.PhraseCounter(p for _, p in es.fused_candidates())
    for text, _, _ in sentences():
        phrases.add(text)
    es.set_phrase_counts(phrases.tokens, phrases.pairs, phrases.total)
    es_entries = es.build()
    es_keys = sorted(es_entries)
    stock, top30, added = build_es.stock_names(st, es.counts)
    es_table = build_pairs("es", es_entries, es_keys, "spa_sentences.tsv.bz2", "spa", "es", stock,
                           damped=es.damped, damping=common.DAMPING)
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
    report["shortKeysNotForms"] = short_nonforms(es_entries, es.is_form, es.count)  # 05b §3.6 (0.6.4)
    report.update(nonform_report(es, es_entries, stock, build_es.stock_names(st, damped_counts(es.counts, es))[0]))
    report.update(abbreviation_report(es.abbreviation_keys, es_entries, es.count, len(es_abbr_used), "es", es.is_form,
                                      ["es", "en"] if use_en else ["es"]))
    stats["es-ES-extra"] = report
    write_outputs("es-ES", es.scale, es.max_count, es.corpus_tokens, es_entries, es_table, stats, unattested=es.damped)
    write_licenses(tags=["es-ES"])


def build_portuguese(stats):
    pt_cache = os.path.join(CACHE, "ptwiktionary-20261001-pt.tsv.gz")
    en_cache = os.path.join(CACHE, "enwiktionary-20261001-pt.tsv.gz")
    if not os.path.exists(pt_cache):
        print("Portuguese Wiktionary pass")
        wiktionary_pt.extract(os.path.join(SRC, PT_WIKT_DUMP), pt_cache, "pt")
    if not os.path.exists(en_cache):
        print("English Wiktionary pass, Portuguese sections (about 15 minutes)")
        wiktionary_pt.extract(os.path.join(SRC, EN_WIKT_DUMP), en_cache, "en")
    pt_titles, pt_secs = wiktionary_pt.load_extract(pt_cache)
    en_titles, en_secs = wiktionary_pt.load_extract(en_cache)
    pt_pages = (pt_titles, {t: wiktionary_pt.classify_pt(t, s) for t, s in pt_secs.items()})
    en_pages = (en_titles, {t: wiktionary_pt.classify_en(t, s) for t, s in en_secs.items()})
    del pt_secs, en_secs
    pt_abbr = abbreviations("pt", ["pt", "en"])
    pt = build_pt.PortugueseBuilder(os.path.join(SRC, "fw", "pt_br_full.txt"), os.path.join(SRC, "fw", "pt_br_ignored.txt"),
                                    pt_pages, en_pages, pt_abbr)

    # §9.7: the Brazilian filter of the sentence sources (pairs and running-text case evidence)
    markers = pt.european_marker_keys()
    dropped_sentences = {"tatoeba": 0, "commonVoice": 0}
    kept = []
    for text in tatoeba_sentences("por_sentences.tsv.bz2", "por"):
        if pt.european_sentence(text, markers):
            dropped_sentences["tatoeba"] += 1
        else:
            kept.append((text, 3, True))
    cv_used = []
    for fn, weight, lines in cv_files("pt"):
        cv_used.append([fn, weight])
        for text in lines:
            if pt.european_sentence(text, markers):
                dropped_sentences["commonVoice"] += 1
            else:
                kept.append((text, weight, False))
    st = build_pt.SentenceStats()
    for text, weight, tatoeba in kept:
        st.add_sentence(text, weight == 3, tatoeba)
    st.finish()
    pt.finish_spellings(st)
    phrases = build_pt.PhraseCounter(p for _, p in pt.phrase_candidates())
    for text, weight, _ in kept:
        phrases.add(text, weight)
    pt.set_phrase_counts(phrases)
    pt_entries = pt.build()
    pt_keys = sorted(pt_entries)
    stock, top30, added = build_es.stock_names(st, pt.counts)
    counter = pairs.PairCounter("pt", pt_entries, stock, pt.damped, common.DAMPING)
    for text, weight, tatoeba in kept:
        counter.add(text, weight, tatoeba)
    order = {k: i for i, k in enumerate(pt_keys)}
    pt_table = counter.table(order)
    report = dict(pt.report)
    cov = pt.coverage()
    report.update({
        "coverageTop20000PortugueseDump": round(cov["pt"], 4),
        "coverageTop20000EnglishDump": round(cov["en"], 4),
        "coverageTop20000BothDumps": round(cov["both"], 4),
        "portugueseWiktionaryTitles": len(pt_titles),
        "portugueseWiktionaryPortugueseSections": len(pt_pages[1]),
        "englishWiktionaryPortugueseSections": len(en_pages[1]),
        "commonVoiceFiles": cv_used,
        "sentencesDropped": dropped_sentences,
        "sentencesKept": len(kept),
        "europeanMarkerKeys": len(markers),
        "tatoebaStockNames": sorted(stock),
        "tatoebaStockNamesAdded": added,
        "tatoebaTop30Capitalised": top30,
        "formsKeptWithTwoCases": sum(1 for v in pt_entries.values() if len(v) > 1),
        "properNounKeys": sum(1 for v in pt_entries.values() if v[0][0][0].isupper()),
    })
    report["shortKeysNotForms"] = short_nonforms(pt_entries, pt.is_form, pt.count)  # 05b §3.6 (0.6.4)
    report.update(nonform_report(pt, pt_entries, stock, build_es.stock_names(st, damped_counts(pt.counts, pt))[0]))
    report.update(abbreviation_report(pt.abbreviation_keys, pt_entries, pt.count, len(set(pt_abbr["pt"]) | set(pt_abbr["en"])), "pt",
                                      pt.is_form))
    stats["pt-BR-extra"] = report
    write_outputs("pt-BR", pt.scale, pt.max_count, pt.corpus_tokens, pt_entries, pt_table, stats, unattested=pt.damped)
    write_licenses(tags=["pt-BR"])


def build_italian(stats):
    it_cache = os.path.join(CACHE, "itwiktionary-20261001-it.tsv.gz")
    en_cache = os.path.join(CACHE, "enwiktionary-20261001-it.tsv.gz")
    if not os.path.exists(it_cache):
        print("Italian Wiktionary pass")
        wiktionary_it.extract(os.path.join(SRC, IT_WIKT_DUMP), it_cache, "it")
    if not os.path.exists(en_cache):
        print("English Wiktionary pass, Italian sections (about 15 minutes)")
        wiktionary_it.extract(os.path.join(SRC, EN_WIKT_DUMP), en_cache, "en")
    it_titles, it_secs = wiktionary_it.load_extract(it_cache)
    en_titles, en_secs = wiktionary_it.load_extract(en_cache)
    it_pages = (it_titles, {t: wiktionary_it.classify_it(t, s) for t, s in it_secs.items()})
    en_pages = (en_titles, {t: wiktionary_it.classify_en(t, s) for t, s in en_secs.items()})
    del it_secs, en_secs
    it_abbr = abbreviations("it", ["it", "en"])
    it = build_it.ItalianBuilder(os.path.join(SRC, "fw", "it_full.txt"), it_pages, en_pages, it_abbr)
    cov_before = it.coverage(it.counts)

    def sentences():
        for text in tatoeba_sentences("ita_sentences.tsv.bz2", "ita"):
            yield text, 3, True, True
        for _, weight, lines in cv_files("it"):
            for text in lines:
                yield text, weight, weight == 3, False

    # §5 step 3: stock names from the capitalised Tatoeba tokens
    capitalised = {}
    tatoeba_tokens = 0
    for text in tatoeba_sentences("ita_sentences.tsv.bz2", "ita"):
        for kind, tok in build_it.tokens(text):
            if kind != "w":
                continue
            tatoeba_tokens += 1
            if tok[0].isupper():
                capitalised[tok] = capitalised.get(tok, 0) + 1
    stock, top30, added = build_it.stock_names(capitalised, tatoeba_tokens, it.counts)
    subtitle = dict(it.counts)
    # sentence counts and running-text evidence (§10.3, §10.4); phrase candidates of §10.5 step 5
    st = build_it.SentenceStats(stock, it.phrase_candidates())
    for text, weight, conversational, tatoeba in sentences():
        st.add(text, weight, conversational, tatoeba)
    st.finish()
    it.apostrophe_rules(st)
    it.spellings(st)
    it.artefacts()
    it.finish_header()
    it_entries = it.build()
    it_keys = sorted(it_entries)
    counter = pairs.ItalianPairCounter(it_entries, build_it.PREFIXES, stock, damped=it.damped, damping=common.DAMPING)
    for text, weight, _, tatoeba in sentences():
        counter.add(text, weight, tatoeba)
    it_table = counter.table({k: i for i, k in enumerate(it_keys)})
    cov = it.coverage(it.counts)
    report = dict(it.report)
    elided = {p: len(it_table.get(p, ())) for p in build_it.PREFIXES}
    report.update({
        "maxCountKey": it.max_key,
        "coverageTop20000ItalianDump": round(cov["it"], 4),
        "coverageTop20000EnglishDump": round(cov["en"], 4),
        "coverageTop20000BothDumps": round(cov["both"], 4),
        "coverageTop20000BeforeSpellingStepsBothDumps": round(cov_before["both"], 4),
        "italianWiktionaryTitles": len(it_titles),
        "italianWiktionaryItalianSections": len(it_pages[1]),
        "englishWiktionaryItalianSections": len(en_pages[1]),
        "commonVoiceFiles": [[fn, weight] for fn, weight, _ in cv_files("it")],
        "sentenceTokensWeighted": st.s_total,
        "tatoebaStockNames": sorted(stock),
        "tatoebaStockNamesAdded": added,
        "tatoebaTop30Capitalised": top30,
        "formsKeptWithTwoCases": sum(1 for v in it_entries.values() if len(v) > 1),
        "properNounKeys": sum(1 for v in it_entries.values() if v[0][0][0].isupper()),
        "elidedPairs": sum(elided.values()),
        "elidedPairsPerPrefix": elided,
        "elidedPairsDropped": len(counter.dropped_elided),
        "elidedPairsDroppedList": counter.dropped_elided[:150],
        "prefixesAsFollowers": sum(1 for v in it_table.values() for k, _, _ in v if k in build_it.PREFIX_SET),
    })
    report["shortKeysNotForms"] = short_nonforms(it_entries, it.is_form, it.count)  # 05b §3.6 (0.6.4)
    report.update(nonform_report(it, it_entries, stock,
                                 build_it.stock_names(capitalised, tatoeba_tokens, damped_counts(subtitle, it))[0]))
    report.update(abbreviation_report(it.abbreviation_keys, it_entries, it.count, len(set(it_abbr["it"]) | set(it_abbr["en"])), "it",
                                      it.is_form))
    stats["it-IT-extra"] = report
    write_outputs("it-IT", it.scale, it.max_count, it.corpus_tokens, it_entries, it_table, stats, it.prefix_table,
                  unattested=it.damped)
    write_licenses(tags=["it-IT"])


def build_french(stats):
    fr_cache = os.path.join(CACHE, "frwiktionary-20261001-fr.tsv.gz")
    en_cache = os.path.join(CACHE, "enwiktionary-20261001-fr.tsv.gz")
    if not os.path.exists(fr_cache):
        print("French Wiktionary pass (about 10 minutes)")
        wiktionary_fr.extract(os.path.join(SRC, FR_WIKT_DUMP), fr_cache)
    fr_titles, fr_secs = wiktionary_fr.load_extract(fr_cache)
    fr_pages = (fr_titles, {t: wiktionary_fr.classify_fr(t, s) for t, s in fr_secs.items()})
    del fr_secs
    freq = os.path.join(SRC, "fw", "fr_full.txt")

    def sentences():
        for text in tatoeba_sentences("fra_sentences.tsv.bz2", "fra"):
            yield text, 3, True, True
        for _, weight, lines in cv_files("fr"):
            for text in lines:
                yield text, weight, weight == 3, False

    fr_abbr = abbreviations("fr", ["fr", "en"])

    def run(en_pages):
        fr = build_fr.FrenchBuilder(freq, fr_pages, en_pages, fr_abbr)
        cov_before = fr.coverage(fr.counts)
        capitalised = {}
        tatoeba_tokens = 0
        apostrophe_words = fr.apostrophe_word_candidates()
        for text in tatoeba_sentences("fra_sentences.tsv.bz2", "fra"):
            for kind, tok in build_fr.tokens(text, apostrophe_words):
                if kind != "w":
                    continue
                tatoeba_tokens += 1
                if tok[0].isupper():
                    capitalised[tok] = capitalised.get(tok, 0) + 1
        # subtitle counts of the §5 tokens: the plain keys and the apostrophe words ("Aujourd'hui", "Quelqu'un"), whole
        # or cut after the apostrophe by the subtitle tokeniser (§11.1 rules 2 and 3)
        subtitle = dict(fr.counts)
        for tok, c in fr.apostrophe_tokens.items():
            low = tok.lower()
            for w in ([low] if low in apostrophe_words else [w for w in apostrophe_words if low.endswith("'") and
                                                             len(low) >= 3 and w.startswith(low)]):
                subtitle[w] = subtitle.get(w, 0) + c
        stock, top30, added = build_fr.stock_names(capitalised, tatoeba_tokens, subtitle)
        st = build_fr.SentenceStats(stock, fr.phrase_candidates(), fr.apostrophe_word_candidates())
        for text, weight, conversational, tatoeba in sentences():
            st.add(text, weight, conversational, tatoeba)
        st.finish(fr.forms_by_key)
        fr.stats = st
        fr.stock_inputs = (capitalised, tatoeba_tokens, subtitle)
        return fr, st, cov_before, stock, top30, added

    # §11.2 point 3 (the rule of the Spanish build): the English dump's French sections are added only if the French
    # dump alone recognises less than 95 % of the 20 000 most frequent keys
    fr_alone = build_fr.FrenchBuilder(freq, fr_pages, None, fr_abbr).coverage_raw()
    use_en = fr_alone["fr"] < build_fr.COVERAGE_ENGLISH_DUMP
    en_pages = None
    cov_en = None
    if os.path.exists(en_cache) or use_en:
        if not os.path.exists(en_cache):
            print("English Wiktionary pass, French sections (about 15 minutes)")
            wiktionary_fr.extract_en(os.path.join(SRC, EN_WIKT_DUMP), en_cache)
        _, en_secs = wiktionary_fr.load_extract(en_cache)
        en_kinds = {t: wiktionary_fr.classify_en(t, s) for t, s in en_secs.items()}
        del en_secs
        cov_en = build_fr.FrenchBuilder(freq, fr_pages, en_kinds, fr_abbr).coverage_raw()
        if use_en:
            en_pages = en_kinds
    fr, st, cov_before, stock, top30, added = run(en_pages)
    fr.apostrophe_rules(st)
    fr.spellings(st)
    fr.compounds()
    fr.artefacts()
    fr.finish_header()
    fr_entries = fr.build()
    fr_keys = sorted(fr_entries)
    counter = pairs.FrenchPairCounter(fr_entries, build_fr.PREFIXES, build_fr.UNELIDED, set(fr.apostrophe_words), stock,
                                      damped=fr.damped, damping=common.DAMPING)
    for text, weight, _, tatoeba in sentences():
        counter.add(text, weight, tatoeba)
    fr_table = counter.table({k: i for i, k in enumerate(fr_keys)})
    cov = fr.coverage(fr.counts)
    report = dict(fr.report)
    elided = {p: len(fr_table.get(p, ())) for p in build_fr.PREFIXES}
    report.update({
        "maxCountKey": fr.max_key,
        "coverageTop20000FrenchDumpSimplifiedMerge": round(fr_alone["fr"], 4),
        "coverageTop20000WithEnglishDumpSimplifiedMerge": round(cov_en["both"], 4) if cov_en else None,
        "englishDumpUsed": use_en,
        "coverageTop20000FrenchDump": round(cov["fr"], 4),
        "coverageTop20000BeforeSpellingSteps": round(cov_before["both"], 4),
        "frenchWiktionaryTitles": len(fr_titles),
        "frenchWiktionaryFrenchSections": len(fr_pages[1]),
        "commonVoiceFiles": [[fn, weight] for fn, weight, _ in cv_files("fr")],
        "sentenceTokensWeighted": st.s_total,
        "runningTextCapitalsMoved": st.capitals_moved,
        "tatoebaStockNames": sorted(stock),
        "tatoebaStockNamesAdded": added,
        "tatoebaTop30Capitalised": top30,
        "formsKeptWithTwoCases": sum(1 for v in fr_entries.values() if len(v) > 1),
        "properNounKeys": sum(1 for v in fr_entries.values() if v[0][0][0].isupper()),
        "elidedPairs": sum(elided.values()),
        "elidedPairsPerPrefix": elided,
        "elidedPairsDroppedRule1": len(counter.dropped_by_rule[1]),
        "elidedPairsDroppedRule1List": counter.dropped_by_rule[1][:100],
        "elidedPairsDroppedRule2": len(counter.dropped_by_rule[2]),
        "elidedPairsDroppedRule2List": counter.dropped_by_rule[2][:200],
        "prefixesAsFollowers": sum(1 for v in fr_table.values() for k, _, _ in v if k in build_fr.PREFIX_SET),
    })
    report["shortKeysNotForms"] = short_nonforms(fr_entries, fr.is_form, fr.count)  # 05b §3.6 (0.6.4)
    capitalised, tatoeba_tokens, subtitle = fr.stock_inputs
    report.update(nonform_report(fr, fr_entries, stock,
                                 build_fr.stock_names(capitalised, tatoeba_tokens, damped_counts(subtitle, fr))[0]))
    report.update(abbreviation_report(fr.abbreviation_keys, fr_entries, fr.count,
                                      len(set(fr_abbr["fr"]) | (set(fr_abbr["en"]) if use_en else set())), "fr", fr.is_form,
                                      ["fr", "en"] if use_en else ["fr"]))
    stats["fr-FR-extra"] = report
    write_outputs("fr-FR", fr.scale, fr.max_count, fr.corpus_tokens, fr_entries, fr_table, stats, fr.prefix_table,
                  unattested=fr.damped)
    write_licenses(tags=["fr-FR"])


# build profiles (05b §7): language tag -> builder
PROFILES = {
    "de-DE": build_german,
    "en-US": build_english,
    "es-ES": build_spanish,
    "fr-FR": build_french,
    "it-IT": build_italian,
    "pt-BR": build_portuguese,
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
