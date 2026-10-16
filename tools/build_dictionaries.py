#!/usr/bin/env python3
"""Builds the MetroKeys word lists and word-pair tables from the free sources (spec/05b-dictionaries.md).

Usage (from the repository root):
    python3 tools/build_dictionaries.py download            # fetch missing sources into build-data/src, verify hashes
    python3 tools/build_dictionaries.py build [TAG ...]     # verify hashes, build the languages (default: all)
    python3 tools/build_dictionaries.py source-form VERSION # the source form of the data files for a release (05b §2)

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
import combined  # noqa: E402
import common  # noqa: E402
import licenses  # noqa: E402
import pairs  # noqa: E402
import names  # noqa: E402
import provenance  # noqa: E402
import spellcheck  # noqa: E402
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
# source-form text export of every build (05b §2 "Source form", used by the evaluation of 05c); not packaged
EXPORT = os.path.join(ROOT, "build-data", "export")
WIKT_DUMP = "wiktionary/dewiktionary-20261001-pages-articles.xml.bz2"
ES_WIKT_DUMP = "wiktionary/eswiktionary-20261001-pages-articles.xml.bz2"
EN_WIKT_DUMP = "wiktionary/enwiktionary-20261001-pages-articles.xml.bz2"
PT_WIKT_DUMP = "wiktionary/ptwiktionary-20261001-pages-articles.xml.bz2"
IT_WIKT_DUMP = "wiktionary/itwiktionary-20261001-pages-articles.xml.bz2"
FR_WIKT_DUMP = "wiktionary/frwiktionary-20261001-pages-articles.xml.bz2"
SCOWL = "scowl/scowl-2020.12.07.tar.gz"
# generic, carries no personal data
USER_AGENT = "MetroKeys-dictionary-build/0.7 (+https://github.com/itsDNNS/MetroKeys)"


def manifest():
    with open(os.path.join(HERE, "sources.json"), encoding="utf-8") as f:
        return json.load(f)["sources"]


def notices():
    """The copyright notices of the spelling dictionaries (05b §2.14), shipped with the licence texts (tools/notices.json;
    not build inputs of the data, so not part of the evaluation's input hashes)."""
    with open(os.path.join(HERE, "notices.json"), encoding="utf-8") as f:
        return json.load(f)["sources"]


def download():
    for s in manifest() + notices():
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
    for s in manifest() + notices():
        path = os.path.join(SRC, s["path"])
        if not os.path.exists(path):
            bad.append(f"missing: {s['path']}")
        elif common.sha256_file(path) != s["sha256"]:
            bad.append(f"hash mismatch: {s['path']}")
    if bad:
        sys.exit("source check failed:\n  " + "\n  ".join(bad))
    print("all source hashes verified")


# 05b §12.6 (0.7.0): the held-out sentences are the evaluation's context pool and are not counted for pairs
EVAL_HOLDOUT_MOD = 20
EVAL_HOLDOUT_REM = 7


def held_out_number(n):
    return n % EVAL_HOLDOUT_MOD == EVAL_HOLDOUT_REM


def tatoeba_sentences(name, lang, with_held=False):
    """The sentences of a Tatoeba export; with [with_held] (text, held out) pairs (05b §12.6: id mod 20 = 7)."""
    with bz2.open(os.path.join(SRC, "tatoeba", name), "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3 and parts[1] == lang:
                if with_held:
                    yield parts[2], parts[0].isdigit() and held_out_number(int(parts[0]))
                else:
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


def cv_files(lang, with_held=False):
    """(file, weight, lines) of the Common Voice pair sources; with [with_held] the lines are (text, held out) pairs
    (05b §12.6: the 1-based line number in its file mod 20 = 7)."""
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
            if with_held:
                yield fn, weight, [(line.strip(), held_out_number(i)) for i, line in enumerate(f, start=1) if line.strip()]
            else:
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


HELD_OUT = {}  # language tag -> {"tatoeba": n, "commonVoice": n} held-out sentences left out of the pair count


def count_held(tag, source):
    d = HELD_OUT.setdefault(tag, {"tatoeba": 0, "commonVoice": 0})
    d[source] += 1


def build_pairs(tag, lang, entries, keys, tatoeba_name, tatoeba_lang, cv_lang, stock=None):
    """Pair table of 05b §5 without the held-out sentences of §12.6."""
    counter = pairs.PairCounter(lang, entries, stock)
    for text, held in tatoeba_sentences(tatoeba_name, tatoeba_lang, with_held=True):
        if held:
            count_held(tag, "tatoeba")
            continue
        counter.add(text, 3, True)
    for _, weight, lines in cv_files(cv_lang, with_held=True):
        for text, held in lines:
            if held:
                count_held(tag, "commonVoice")
                continue
            counter.add(text, weight, False)
    order = {k: i for i, k in enumerate(keys)}
    return counter.table(order), counter


# 05b §6 size limits (0.7.0); MB = 10^6 bytes, as the reference sizes of §6
WORDS_LIMIT = 2_000_000
PAIRS_LIMIT = 2_500_000
TOTAL_LIMIT = 17_000_000
GROWTH_LIMIT = 0.25
# the data files of the previous release (copied there before the first build of a release; 05b §6, §12.7)
PREVIOUS_RELEASE = "0.6.9"
PREVIOUS = os.path.join(ROOT, "build-data", f"previous-{PREVIOUS_RELEASE}")
SIZE_PROBLEMS = []


def growth_limit(tag):
    """05b §6: 25 %, or the growth the owner approved for this language and release (tools/dictionary-approvals.json;
    a one-off MK_GROWTH_APPROVED=<tag,...> allows any growth for a trial build)."""
    if tag in set(filter(None, os.environ.get("MK_GROWTH_APPROVED", "").split(","))):
        return float("inf")
    with open(os.path.join(HERE, "dictionary-approvals.json"), encoding="utf-8") as f:
        a = json.load(f)["growth"].get(tag)
    if a and a["previous"] == PREVIOUS_RELEASE:
        return max(GROWTH_LIMIT, a["maxGrowth"])
    return GROWTH_LIMIT


def previous_list(tag):
    path = os.path.join(PREVIOUS, f"{tag}.words.gz")
    if not os.path.exists(path):
        return None
    return common.read_word_list(path)


def write_outputs(tag, scale, max_count, corpus_tokens, entries, table, stats, prefixes=None, unattested=None):
    """[unattested]: the keys kept by the colloquial path (05b §12.4; flagged, used by the evaluation of 05c); only
    those in [entries] are flagged. Checks the size limits of 05b §6 against the previous release."""
    os.makedirs(OUT, exist_ok=True)
    words_path = os.path.join(OUT, f"{tag}.words.gz")
    pairs_path = os.path.join(OUT, f"{tag}.pairs.gz")
    flagged = {k for k in (unattested or ()) if k in entries}
    digest, keys = common.write_word_list(words_path, tag, scale, max_count, corpus_tokens, entries, prefixes, flagged)
    common.write_pair_table(pairs_path, tag, digest, keys, entries, table, [p for p, _ in prefixes] if prefixes else None)
    words_bytes = os.path.getsize(words_path)
    pairs_bytes = os.path.getsize(pairs_path)
    sizes = {"wordsBytes": words_bytes, "pairsBytes": pairs_bytes, "wordsLimit": WORDS_LIMIT, "pairsLimit": PAIRS_LIMIT}
    prev_words = os.path.join(PREVIOUS, f"{tag}.words.gz")
    prev_pairs = os.path.join(PREVIOUS, f"{tag}.pairs.gz")
    if os.path.exists(prev_words):
        pw, pp = os.path.getsize(prev_words), os.path.getsize(prev_pairs)
        sizes.update({"previousRelease": PREVIOUS_RELEASE, "previousWordsBytes": pw, "previousPairsBytes": pp,
                      "wordsGrowth": round(words_bytes / pw - 1, 4), "pairsGrowth": round(pairs_bytes / pp - 1, 4)})
        growth = words_bytes / pw - 1
        limit = growth_limit(tag)
        sizes["wordsGrowthLimit"] = None if limit == float("inf") else limit
        if growth > limit:
            SIZE_PROBLEMS.append(f"{tag} word list grows by {100 * growth:.1f} % against {PREVIOUS_RELEASE} (limit "
                                 f"{100 * limit:.0f} %; above 25 % the owner's approval is recorded in "
                                 f"tools/dictionary-approvals.json)")
    if words_bytes > WORDS_LIMIT:
        SIZE_PROBLEMS.append(f"{tag} word list {words_bytes} bytes > {WORDS_LIMIT}")
    if pairs_bytes > PAIRS_LIMIT:
        SIZE_PROBLEMS.append(f"{tag} pair table {pairs_bytes} bytes > {PAIRS_LIMIT}")
    stats[tag] = {
        "S": scale,
        "maxCount": int(round(max_count)),
        "corpusTokens": int(corpus_tokens),
        "keys": len(keys),
        "unattestedKeys": len(flagged),
        "forms": sum(len(v) for v in entries.values()),
        "pairPrevWords": len(table),
        "pairs": sum(len(v) for v in table.values()),
        "wordsPayloadSha256": digest.hex(),
        "wordsFile": {"bytes": words_bytes, "sha256": common.sha256_file(words_path)},
        "pairsFile": {"bytes": pairs_bytes, "sha256": common.sha256_file(pairs_path)},
        "sizes": sizes,
        "heldOutSentences": HELD_OUT.get(tag, {}),
    }
    print(tag, json.dumps(stats[tag], indent=1))
    return keys


def check_total_size():
    """05b §6: all data files of all languages together at most 17 MB."""
    total = sum(os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT) if f.endswith((".words.gz", ".pairs.gz")))
    if total > TOTAL_LIMIT:
        SIZE_PROBLEMS.append(f"all data files {total} bytes > {TOTAL_LIMIT}")
    return total


def forms_text(forms):
    return " ".join(f"{f}:{c}" for f, c in forms)


def short_nonforms(entries, is_form, count):
    """05b §3.6 "Other languages" (0.6.4): kept keys of 2-3 letters that are no form of the language, with their count
    and the build's decision (the stored forms with their classes), most frequent first."""
    rows = [k for k in entries if 2 <= sum(1 for ch in k if ch.isalpha()) <= 3 and not is_form(k)]
    rows.sort(key=lambda k: (-count(k), k))
    return [f"{k} {int(count(k))} {forms_text(entries[k])}" for k in rows]


def short_key_report(builder):
    """05b §3.6 step 6a: the short keys dropped (count ≥ 40, most frequent first)."""
    count = builder.count
    return {
        "shortKeysDroppedByStep6a": len(builder.short_dropped),
        "shortKeysDroppedByStep6aList": [f"{k} {count(k)}" for k in sorted(builder.short_dropped, key=lambda k: (-count(k), k))],
    }


_english_keys = None


def english_keys():
    """05b §12.4 condition 5: the keys of the en-US word list (English keys do not change in 0.7.0, 05b version note;
    the list is built first when all languages are built)."""
    global _english_keys
    if _english_keys is None:
        _english_keys = frozenset(common.read_word_list(os.path.join(OUT, "en-US.words.gz"))[1])
    return _english_keys


_foreign = {}


def foreign_lower(tag):
    """05b §12.4 item 7: the lower-case forms of the word lists of the other languages of §7 (the lists in the output
    directory, without their unattested keys, so the result does not depend on the order of the builds)."""
    if tag not in _foreign:
        out = set()
        for other in PROFILES:
            path = os.path.join(OUT, f"{other}.words.gz")
            if other == tag or not os.path.exists(path):
                continue
            _, entries, unattested, _ = common.read_word_list(path)
            out.update(k for k, forms in entries.items() if k not in unattested and any(f == k for f, _ in forms))
        _foreign[tag] = frozenset(out)
    return _foreign[tag]


def attested_inputs(tag):
    """The spelling dictionary (05b §2.14) and the written-language list (§2.15) of a language."""
    return spellcheck.SpellingIndex(tag, SRC, CACHE), combined.written_list(tag, SRC)


def top_rows(keys, count, n, extra=None):
    rows = sorted(keys, key=lambda k: (-count(k), k))[:n]
    return [f"{k} {int(count(k))}" + (f" {extra(k)}" if extra else "") for k in rows]


def report_070(tag, b, entries, raw_counts=None):
    """05b §12.7 build report of a builder with the AttestedRules (German, Spanish, Portuguese, Italian, French)."""
    count = b.count
    total = b.corpus_tokens
    keys = [k for k in entries if b.forms_by_key is not None]
    plain = [k for k in keys if combined.letters_only(k)]
    b.spelling.prefetch(k for k in plain if b.wiktionary_form(k))
    wikt_only = [k for k in plain if b.wiktionary_form(k) and not b.spelling.knows(k)]
    spell_only = [k for k in plain if b.spelling_form(k)]
    both = [k for k in plain if b.wiktionary_form(k) and b.spelling.knows(k)]
    src_counts = raw_counts or b.counts
    top = sorted(src_counts, key=lambda k: (-src_counts[k], k))[:20000]
    b.spelling.prefetch(top)
    cov = {
        "wiktionary": sum(1 for k in top if b.wiktionary_form(k)) / len(top),
        "spellingDictionary": sum(1 for k in top if b.spelling.knows(k) and k not in b.spelling_excluded) / len(top),
        "union": sum(1 for k in top if b.language_form(k)) / len(top),
    }
    coll_kept = sorted((k for k in b.colloquial if k in entries), key=lambda k: (-count(k), k))
    coll_dropped = sorted(b.colloquial_dropped, key=lambda k: (-count(k), k))
    reasons = {}
    for k, r in b.colloquial_dropped.items():
        reasons[r] = reasons.get(r, 0) + 1
    names = sorted((k for k in b.names if k in entries), key=lambda k: (-count(k), k))
    prev = previous_list(tag)
    changed = None
    lost = gained = None
    if prev:
        old = prev[1]
        changed = sum(1 for k, fs in entries.items() if k in old and abs(fs[0][1] - old[k][0][1]) > 20)
        lost = sorted(set(old) - set(entries))
        gained = sorted(set(entries) - set(old))
    out = {
        "keysWiktionaryOnly": len(wikt_only),
        "keysSpellingDictionaryOnly": len(spell_only),
        "keysBoth": len(both),
        "keysWiktionaryOnlyTop100": top_rows(wikt_only, count, 100),
        "keysSpellingDictionaryOnlyTop100": top_rows(spell_only, count, 100),
        "coverageTop20000Subtitles": {k: round(v, 4) for k, v in cov.items()},
        "writtenAdmitted": sum(1 for k in b.written_admitted if k in entries),
        "colloquialKept": len(coll_kept),
        "colloquialKeptTop100": [combined.describe(k, count(k), total, b.written(k)) for k in coll_kept[:100]],
        "colloquialDropped": len(coll_dropped),
        "colloquialDroppedByCondition": reasons,
        "colloquialDroppedTop100": [combined.describe(k, count(k), total, b.written(k)) + " " + b.colloquial_dropped[k]
                                    for k in coll_dropped[:100]],
        "colloquialDroppedNearFormTop100": [f"{k} {int(count(k))} -> {b.near_form[k]}" for k in coll_dropped
                                            if b.colloquial_dropped[k] == "nearForm"][:100],
        "colloquialDroppedOtherLanguageTop100": [f"{k} {int(count(k))}" for k in coll_dropped
                                                 if b.colloquial_dropped[k] == "otherLanguage"][:100],
        "colloquialDroppedApostropheFragmentTop100": [f"{k} {int(count(k))}" for k in coll_dropped
                                                      if b.colloquial_dropped[k] == "apostropheFragment"][:100],
        "names": len(names),
        "namesTop50": [f"{k} {int(count(k))} {forms_text(entries[k])}" for k in names[:50]],
        "S": b.scale,
        "maxCount": round(b.max_count, 3),
        "classChangedBy20": changed,
        "keysLostAgainstPrevious": len(lost) if lost is not None else None,
        "keysNewAgainstPrevious": len(gained) if gained is not None else None,
    }
    out.update(combined.gap_report(plain, count, b.pw, total))
    return out


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
            f.write(licenses.text(tag, notice, SRC))


def build_german(stats, dry=False):
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
    spelling, written = attested_inputs("de-DE")
    de = build_de.GermanBuilder(os.path.join(SRC, "fw", "de_full.txt"), wiktionary_de.load(wikt_cache), de_abbr,
                                de_prefixes, spelling, written, english_keys())
    de.setup_foreign(foreign_lower("de-DE"))
    pos = wiktionary_de.load_pos(pos_cache)
    rule = names.NameRule(names.german(pos), set(de.forms_by_key), de.abbreviation_keys, spelling)
    de_entries = de.build(os.path.join(ROOT, "spec", "data", "layouts-de.json"), rule.is_name)
    spelling.save()
    if dry:
        return {"builder": de, "entries": de_entries, "abbreviations": de_abbr, "stock": set(pairs.DE_STOCK)}

    # §3.7 dictionary-only German lemmas (0.6.6; 0.7.0: only those without a written-language frequency), class 1
    lemmas = build_de.lemma_keys(pos, de_abbr)
    unseen = sum(1 for k in de.dictionary_only_keys(de_entries, lemmas) if de.count(k) == 0 and not de.has_written(k, 0))
    de_entries = dict(de_entries)
    added = de.add_dictionary_only(de_entries, lemmas)
    de_keys = sorted(de_entries)
    de_table, de_counter = build_pairs("de-DE", "de", de_entries, de_keys, "deu_sentences.tsv.bz2", "deu", "de")
    dict_only = {
        "variantUsed": "lemmas without subtitle evidence (count 0 or 1) and without a written-language frequency",
        "addedKeys": len(added), "addedUnseen": unseen, "addedSeenOnce": len(added) - unseen,
        "addedSample": [f"{k} {de.count(k)} {forms_text(de_entries[k])}" for k in added[::max(1, len(added) // 100)][:100]],
    }
    stats["de-DE-extra"] = {
        "umlautMerged": len(de.umlaut_merged),
        "umlautMergedTop100": [f"{k} {de.merged_counts[k]} -> {t} {de.count(t) - de.merged_into[t]}"
                               for k, t in sorted(de.umlaut_merged.items(), key=lambda kt: (-de.merged_counts[kt[0]], kt[0]))[:100]],
        # 0.7.0: the merges that need the transliteration ae/oe/ue (05b §3.6)
        "umlautMergedTransliterated": sum(1 for k, t in de.umlaut_merged.items()
                                          if build_de.fold_umlauts(k) != build_de.fold_umlauts(t)),
        "umlautMergedTransliteratedTop100": [f"{k} {de.merged_counts[k]} -> {t}" for k, t in sorted(
            ((k, t) for k, t in de.umlaut_merged.items() if build_de.fold_umlauts(k) != build_de.fold_umlauts(t)),
            key=lambda kt: (-de.merged_counts[kt[0]], kt[0]))[:100]],
        "shortKeysDroppedByStep6a": len(de.short_dropped),
        "shortKeysDroppedByStep6aList": [f"{k} {de.count(k)}" for k in sorted(de.short_dropped, key=lambda k: (-de.count(k), k))],
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
        **report_070("de-DE", de, de_entries),
    }
    spelling.save()
    write_outputs("de-DE", de.scale, de.max_count, de.corpus_tokens, de_entries, de_table, stats, unattested=de.colloquial)
    provenance.write(EXPORT, "de-DE", de, de_entries, SRC, lemma_keys=set(added))
    provenance.write_pairs(EXPORT, "de-DE", de_entries, de_table, de_counter)
    write_licenses(tags=["de-DE"])


def build_english(stats, dry=False):
    final, copyright_path = scowl_final()
    st = build_en.SentenceStats()
    for text in tatoeba_sentences("eng_sentences.tsv.bz2", "eng"):
        st.add_sentence(text, 3, True)
    for _, weight, lines in cv_files("en"):
        for text in lines:
            st.add_sentence(text, weight, weight == 3)
    st.finish()
    en = build_en.EnglishBuilder(os.path.join(SRC, "fw", "en_full.txt"), final, st, combined.written_list("en-US", SRC))
    en_entries = en.build()
    if dry:
        return {"builder": en, "entries": en_entries, "scowl": final, "stock": set(pairs.EN_STOCK_BASE)}
    en_keys = sorted(en_entries)
    en_table, en_counter = build_pairs("en-US", "en", en_entries, en_keys, "eng_sentences.tsv.bz2", "eng", "en")
    stats["en-US-extra"] = {
        "aliasKeys": getattr(en, "alias_added", 0),
        "aliasKeysReplacingAWord": getattr(en, "alias_replaced", 0),
        "possessiveForms": sum(1 for k in en_entries if k.endswith("'s") and k not in build_en.PRONOUN_S),
        # 05b §3.6 (0.6.4): a form of English = a SCOWL key
        "shortKeysNotForms": short_nonforms(en_entries, lambda k: k in en.main_keys or k in en.large_forms, en.count),
        # 05b §12.7 for English (§4.6: classes only)
        "names": len(en.names),
        "namesTop50": [f"{k} {forms_text(en_entries[k])}" for k in sorted(en.names, key=lambda k: (-en.combined_counts[k], k))[:50]],
        "writtenFrequencyKeys": sum(1 for k in en_entries if en.pw.get(k, 0) > 0),
        "S": en.scale,
        "maxCount": round(en.max_count, 3),
    }
    en_count = lambda k: sum(c for _, c in en.selected[k])
    stats["en-US-extra"].update(combined.gap_report([k for k in en_entries if k not in en.weight0], en_count, en.pw,
                                                    en.corpus_tokens))
    prev = previous_list("en-US")
    if prev:
        stats["en-US-extra"]["classChangedBy20"] = sum(1 for k, fs in en_entries.items()
                                                       if k in prev[1] and abs(fs[0][1] - prev[1][k][0][1]) > 20)
        stats["en-US-extra"]["keysLostAgainstPrevious"] = len(set(prev[1]) - set(en_entries))
        stats["en-US-extra"]["keysNewAgainstPrevious"] = len(set(en_entries) - set(prev[1]))
    write_outputs("en-US", en.scale, en.max_count, en.corpus_tokens, en_entries, en_table, stats)
    provenance.write(EXPORT, "en-US", en, en_entries, SRC)
    provenance.write_pairs(EXPORT, "en-US", en_entries, en_table, en_counter)
    write_licenses(copyright_path, tags=["en-US"])


def build_spanish(stats, dry=False):
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
        for text, held in tatoeba_sentences("spa_sentences.tsv.bz2", "spa", with_held=True):
            yield text, True, True, held
        for _, weight, lines in cv_files("es", with_held=True):
            for text, held in lines:
                yield text, weight == 3, False, held

    st = build_es.SentenceStats()
    for text, conversational, tatoeba, _ in sentences():
        st.add_sentence(text, conversational, tatoeba)
    st.finish()
    cv_used = [[fn, weight] for fn, weight, _ in cv_files("es")]
    freq = os.path.join(SRC, "fw", "es_full.txt")
    # §8.2 point 3: the English dump is used if the Spanish dump alone recognises < 95 % of the top 20 000 keys
    es_abbr = abbreviations("es", ["es", "en"])
    es_alone = build_es.SpanishBuilder(freq, es_pass, None, st, es_abbr["es"]).coverage()
    use_en = es_alone < 0.95
    es_abbr_used = set(es_abbr["es"]) | (set(es_abbr["en"]) if use_en else set())
    spelling, written = attested_inputs("es-ES")
    es = build_es.SpanishBuilder(freq, es_pass, en_pass if use_en else None, st, es_abbr_used, spelling, written,
                                 english_keys())
    es.setup_foreign(foreign_lower("es-ES"))
    with_en = es.coverage()
    phrases = build_es.PhraseCounter(p for _, p in es.fused_candidates())
    for text, _, _, _ in sentences():
        phrases.add(text)
    es.set_phrase_counts(phrases.tokens, phrases.pairs, phrases.total)
    dumps = ["es", "en"] if use_en else ["es"]
    if not names.spanish_cache_ready(CACHE, dumps):
        import wiktionary_pos
        for d in dumps:
            if not os.path.exists(wiktionary_pos.cache_path(CACHE, d)):
                print(f"{d} Wiktionary pass, Spanish part-of-speech headings")
                wiktionary_pos.run_spanish(os.path.join(SRC, ES_WIKT_DUMP if d == "es" else EN_WIKT_DUMP),
                                           wiktionary_pos.cache_path(CACHE, d), d)
    rule = names.NameRule(names.from_headings(names.spanish_headings(CACHE, dumps)), set(es.forms_by_key),
                          es.abbreviation_keys, spelling)
    es_entries = es.build(rule.is_name)
    spelling.save()
    es_keys = sorted(es_entries)
    stock, top30, added = build_es.stock_names(st, es.counts)
    if dry:
        return {"builder": es, "entries": es_entries, "stock": set(stock), "englishDump": use_en}
    es_table, es_counter = build_pairs("es-ES", "es", es_entries, es_keys, "spa_sentences.tsv.bz2", "spa", "es", stock)
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
    report["shortKeysNotForms"] = short_nonforms(es_entries, es.language_form, es.count)  # 05b §3.6 (0.6.4)
    report.update(short_key_report(es))
    report.update(abbreviation_report(es.abbreviation_keys, es_entries, es.count, len(es_abbr_used), "es", es.is_form,
                                      dumps))
    report.update(report_070("es-ES", es, es_entries))
    spelling.save()
    stats["es-ES-extra"] = report
    write_outputs("es-ES", es.scale, es.max_count, es.corpus_tokens, es_entries, es_table, stats, unattested=es.colloquial)
    provenance.write(EXPORT, "es-ES", es, es_entries, SRC)
    provenance.write_pairs(EXPORT, "es-ES", es_entries, es_table, es_counter)
    write_licenses(tags=["es-ES"])


def build_portuguese(stats, dry=False):
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
    proper = names.from_headings([("pt", names.headings_of(pt_secs)), ("en", names.headings_of(en_secs))])
    del pt_secs, en_secs
    pt_abbr = abbreviations("pt", ["pt", "en"])
    spelling, written = attested_inputs("pt-BR")
    pt = build_pt.PortugueseBuilder(os.path.join(SRC, "fw", "pt_br_full.txt"), os.path.join(SRC, "fw", "pt_br_ignored.txt"),
                                    pt_pages, en_pages, pt_abbr, spelling, written, english_keys())
    pt.setup_foreign(foreign_lower("pt-BR"))

    # §9.7: the Brazilian filter of the sentence sources (pairs and running-text case evidence)
    markers = pt.european_marker_keys()
    dropped_sentences = {"tatoeba": 0, "commonVoice": 0}
    kept = []
    for text, held in tatoeba_sentences("por_sentences.tsv.bz2", "por", with_held=True):
        if pt.european_sentence(text, markers):
            dropped_sentences["tatoeba"] += 1
        else:
            kept.append((text, 3, True, held))
    cv_used = []
    for fn, weight, lines in cv_files("pt", with_held=True):
        cv_used.append([fn, weight])
        for text, held in lines:
            if pt.european_sentence(text, markers):
                dropped_sentences["commonVoice"] += 1
            else:
                kept.append((text, weight, False, held))
    st = build_pt.SentenceStats()
    for text, weight, tatoeba, _ in kept:
        st.add_sentence(text, weight == 3, tatoeba)
    st.finish()
    pt.finish_spellings(st)
    phrases = build_pt.PhraseCounter(p for _, p in pt.phrase_candidates())
    for text, weight, _, _ in kept:
        phrases.add(text, weight)
    pt.set_phrase_counts(phrases)
    rule = names.NameRule(proper, set(pt.forms_by_key), pt.abbreviation_keys, spelling)
    pt_entries = pt.build(rule.is_name)
    spelling.save()
    pt_keys = sorted(pt_entries)
    stock, top30, added = build_es.stock_names(st, pt.counts)
    if dry:
        return {"builder": pt, "entries": pt_entries, "stock": set(stock), "markers": markers}
    counter = pairs.PairCounter("pt", pt_entries, stock)
    for text, weight, tatoeba, held in kept:
        if held:
            count_held("pt-BR", "tatoeba" if tatoeba else "commonVoice")
            continue
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
    report["shortKeysNotForms"] = short_nonforms(pt_entries, pt.language_form, pt.count)  # 05b §3.6 (0.6.4)
    report.update(short_key_report(pt))
    report.update(abbreviation_report(pt.abbreviation_keys, pt_entries, pt.count, len(set(pt_abbr["pt"]) | set(pt_abbr["en"])), "pt",
                                      pt.is_form))
    report.update(report_070("pt-BR", pt, pt_entries))
    spelling.save()
    stats["pt-BR-extra"] = report
    write_outputs("pt-BR", pt.scale, pt.max_count, pt.corpus_tokens, pt_entries, pt_table, stats, unattested=pt.colloquial)
    provenance.write(EXPORT, "pt-BR", pt, pt_entries, SRC)
    provenance.write_pairs(EXPORT, "pt-BR", pt_entries, pt_table, counter)
    write_licenses(tags=["pt-BR"])


def build_italian(stats, dry=False):
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
    proper = names.from_headings([("it", names.headings_of(it_secs)), ("en", names.headings_of(en_secs))])
    del it_secs, en_secs
    it_abbr = abbreviations("it", ["it", "en"])
    spelling, written = attested_inputs("it-IT")
    it = build_it.ItalianBuilder(os.path.join(SRC, "fw", "it_full.txt"), it_pages, en_pages, it_abbr, spelling, written,
                                 english_keys())
    it.setup_foreign(foreign_lower("it-IT"))
    cov_before = it.coverage(it.counts)

    def sentences():
        for text, held in tatoeba_sentences("ita_sentences.tsv.bz2", "ita", with_held=True):
            yield text, 3, True, True, held
        for _, weight, lines in cv_files("it", with_held=True):
            for text, held in lines:
                yield text, weight, weight == 3, False, held

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
    for text, weight, conversational, tatoeba, _ in sentences():
        st.add(text, weight, conversational, tatoeba)
    st.finish()
    it.apostrophe_rules(st)
    it.spellings(st)
    it.artefacts()
    it.finish_header()
    rule = names.NameRule(proper, set(it.forms_by_key), it.abbreviation_keys, spelling)
    it_entries = it.build(rule.is_name)
    spelling.save()
    if dry:
        return {"builder": it, "entries": it_entries, "stock": set(stock)}
    it_keys = sorted(it_entries)
    counter = pairs.ItalianPairCounter(it_entries, build_it.PREFIXES, stock)
    for text, weight, _, tatoeba, held in sentences():
        if held:
            count_held("it-IT", "tatoeba" if tatoeba else "commonVoice")
            continue
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
    report["shortKeysNotForms"] = short_nonforms(it_entries, it.language_form, it.count)  # 05b §3.6 (0.6.4)
    report.update(short_key_report(it))
    report.update(abbreviation_report(it.abbreviation_keys, it_entries, it.count, len(set(it_abbr["it"]) | set(it_abbr["en"])), "it",
                                      it.is_form))
    report.update(report_070("it-IT", it, it_entries, subtitle))
    spelling.save()
    stats["it-IT-extra"] = report
    write_outputs("it-IT", it.scale, it.max_count, it.corpus_tokens, it_entries, it_table, stats, it.prefix_table,
                  unattested=it.colloquial)
    provenance.write(EXPORT, "it-IT", it, it_entries, SRC)
    provenance.write_pairs(EXPORT, "it-IT", it_entries, it_table, counter)
    provenance.write_prefixes(EXPORT, "it-IT", it.prefix_table, it.prefix_counts)
    write_licenses(tags=["it-IT"])


def build_french(stats, dry=False):
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
        for text, held in tatoeba_sentences("fra_sentences.tsv.bz2", "fra", with_held=True):
            yield text, 3, True, True, held
        for _, weight, lines in cv_files("fr", with_held=True):
            for text, held in lines:
                yield text, weight, weight == 3, False, held

    fr_abbr = abbreviations("fr", ["fr", "en"])

    spelling, written = attested_inputs("fr-FR")

    def run(en_pages):
        fr = build_fr.FrenchBuilder(freq, fr_pages, en_pages, fr_abbr, spelling, written, english_keys())
        fr.setup_foreign(foreign_lower("fr-FR"))
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
        for text, weight, conversational, tatoeba, _ in sentences():
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
    rule = names.NameRule(names.french(fr.fr_kinds), set(fr.forms_by_key), fr.abbreviation_keys, spelling)
    fr_entries = fr.build(rule.is_name)
    spelling.save()
    if dry:
        return {"builder": fr, "entries": fr_entries, "stock": set(stock)}
    fr_keys = sorted(fr_entries)
    counter = pairs.FrenchPairCounter(fr_entries, build_fr.PREFIXES, build_fr.UNELIDED, set(fr.apostrophe_words), stock)
    for text, weight, _, tatoeba, held in sentences():
        if held:
            count_held("fr-FR", "tatoeba" if tatoeba else "commonVoice")
            continue
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
    report["shortKeysNotForms"] = short_nonforms(fr_entries, fr.language_form, fr.count)  # 05b §3.6 (0.6.4)
    capitalised, tatoeba_tokens, subtitle = fr.stock_inputs
    report.update(short_key_report(fr))
    report.update(abbreviation_report(fr.abbreviation_keys, fr_entries, fr.count,
                                      len(set(fr_abbr["fr"]) | (set(fr_abbr["en"]) if use_en else set())), "fr", fr.is_form,
                                      ["fr", "en"] if use_en else ["fr"]))
    report.update(report_070("fr-FR", fr, fr_entries, subtitle))
    spelling.save()
    stats["fr-FR-extra"] = report
    write_outputs("fr-FR", fr.scale, fr.max_count, fr.corpus_tokens, fr_entries, fr_table, stats, fr.prefix_table,
                  unattested=fr.colloquial)
    provenance.write(EXPORT, "fr-FR", fr, fr_entries, SRC)
    provenance.write_pairs(EXPORT, "fr-FR", fr_entries, fr_table, counter)
    provenance.write_prefixes(EXPORT, "fr-FR", fr.prefix_table, fr.prefix_counts)
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
    # MK_STATS_PATH: a separate report file (languages built in parallel processes; merged afterwards)
    stats_path = os.environ.get("MK_STATS_PATH") or os.path.join(HERE, "dictionary-build.json")
    stats = {}
    if os.path.exists(stats_path):
        with open(stats_path, encoding="utf-8") as f:
            stats = json.load(f)
    tags = list(tags or PROFILES)
    for tag in tags:
        if tag not in PROFILES:
            sys.exit(f"unknown language {tag}; known: {', '.join(PROFILES)}")
    # the other languages read the keys of the en-US word list (05b §12.4 condition 5)
    tags.sort(key=lambda t: (t != "en-US", t))
    for tag in tags:
        PROFILES[tag](stats)
    total = check_total_size()
    stats["allDataFiles"] = {"bytes": total, "limit": TOTAL_LIMIT,
                             "files": {f: os.path.getsize(os.path.join(OUT, f)) for f in sorted(os.listdir(OUT))
                                       if f.endswith((".words.gz", ".pairs.gz"))}}
    with open(stats_path, "w", encoding="utf-8") as f:
        json.dump(stats, f, indent=1, sort_keys=True, ensure_ascii=False)
        f.write("\n")
    if SIZE_PROBLEMS:
        sys.exit("size limits of 05b §6 exceeded:\n  " + "\n  ".join(SIZE_PROBLEMS))
    lint(tags)


RELEASE = os.path.join(ROOT, "build-data", "release")


def decoded_pairs(tag):
    """The text form of a shipped pair table decoded from the data files (previous, follower, form, pair count "-",
    class), for a language whose files come from an earlier build."""
    _, entries, _, prefixes = common.read_word_list(os.path.join(OUT, f"{tag}.words.gz"))
    keys = sorted(entries)
    table = common.read_pair_table(os.path.join(OUT, f"{tag}.pairs.gz"), keys, [p for p, _ in prefixes or ()])
    lines = []
    for prev in sorted(table):
        for key, cls, fi in table[prev]:
            form = key if key not in entries else entries[key][fi][0]
            lines.append("\t".join([prev, key, form, "-", str(cls)]))
    return common.deterministic_gzip(("\n".join(lines) + "\n").encode("utf-8"))


def source_form(version):
    """05b §2 "Source form": the files to publish next to a release, per language: the word list and pair table as
    text (the exports of the last build), the prefix table, the spelling-dictionary input files exactly as used with
    their notices, and the build report with the input hashes. Writes build-data/release/
    metrokeys-<version>-dictionary-source.tar.gz (deterministic: sorted members, fixed time stamps)."""
    import hashlib
    import io
    name = f"metrokeys-{version}-dictionary-source"
    files = []  # (archive path, bytes)
    for tag in sorted(PROFILES):
        for suffix in (".forms.tsv.gz", ".pairs.tsv.gz", ".prefixes.tsv"):
            path = os.path.join(EXPORT, tag + suffix)
            if os.path.exists(path):
                with open(path, "rb") as f:
                    files.append((f"{name}/{tag}/{tag}{suffix}", f.read()))
            elif suffix == ".pairs.tsv.gz":
                # a pair table shipped from an earlier build (no export): decoded, without the pair counts
                files.append((f"{name}/{tag}/{tag}{suffix}", decoded_pairs(tag)))
            elif suffix != ".prefixes.tsv":
                sys.exit(f"missing export {path}: build the dictionaries first")
        for rel in spellcheck.input_files(tag) + [s["path"] for s in notices() if s["language"] == tag]:
            with open(os.path.join(SRC, rel), "rb") as f:
                files.append((f"{name}/{tag}/spelling/{os.path.basename(rel)}", f.read()))
        with open(os.path.join(OUT, f"LICENSE-{tag}.txt"), "rb") as f:
            files.append((f"{name}/{tag}/LICENSE-{tag}.txt", f.read()))
    for rel in ("tools/dictionary-build.json", "tools/sources.json", "tools/notices.json"):
        with open(os.path.join(ROOT, rel), "rb") as f:
            files.append((f"{name}/build-report/{os.path.basename(rel)}", f.read()))
    readme = (f"MetroKeys {version}: source form of the dictionary data files (spec 05b §2).\n\n"
              "Per language: <tag>.forms.tsv.gz (key, form, subtitle count, written-language frequency, combined count, "
              "class, flags, merged spellings), <tag>.pairs.tsv.gz (previous word, follower, form, pair count, class), "
              "<tag>.prefixes.tsv (elision prefix, count, class; it-IT and fr-FR), spelling/ (the spelling-dictionary "
              "files exactly as used, with their notices) and the licence text. build-report/ holds the build report "
              "with the SHA-256 of every input. The build tools are in the source repository "
              "(tools/build_dictionaries.py); the data files are distributed under the GNU GPL version 3.\n")
    files.append((f"{name}/README.txt", readme.encode("utf-8")))
    files.append((f"{name}/SHA256SUMS", "".join(f"{hashlib.sha256(b).hexdigest()}  {p[len(name) + 1:]}\n"
                                                  for p, b in sorted(files)).encode("utf-8")))
    os.makedirs(RELEASE, exist_ok=True)
    out = os.path.join(RELEASE, name + ".tar.gz")
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.PAX_FORMAT) as tar:
        for path, data in sorted(files):
            info = tarfile.TarInfo(path)
            info.size = len(data)
            info.mtime = 0
            info.mode = 0o644
            info.uname = info.gname = ""
            tar.addfile(info, io.BytesIO(data))
    with open(out, "wb") as f:
        f.write(common.deterministic_gzip(buf.getvalue()))
    print(out, os.path.getsize(out), common.sha256_file(out))


def lint(tags):
    """Tier T0 of the dictionary evaluation (05c §10): the lint of every built language, run through Gradle
    (engine/build/evaluation/lint/report.md). Skipped with MK_EVAL_LINT=0 or when no JDK is configured."""
    import subprocess
    if os.environ.get("MK_EVAL_LINT") == "0" or not os.environ.get("JAVA_HOME"):
        print("dictionary lint skipped (set JAVA_HOME, or run ./gradlew :engine:evalLint)")
        return
    subprocess.run([os.path.join(ROOT, "gradlew"), "-q", ":engine:evalLint", "-PevalLanguages=" + ",".join(tags)],
                   cwd=ROOT, check=False)


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
    elif cmd == "source-form":
        source_form(sys.argv[2] if len(sys.argv) > 2 else "unreleased")
    else:
        sys.exit(__doc__)
