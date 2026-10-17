"""Source-form text exports of the data files (spec/05b-dictionaries.md §2 "Source form", items 1 and 2), written next
to every build into build-data/export/ and published with each release (tools/build_dictionaries.py source-form). Also
read by the dictionary evaluation of 05c (lint and worst cases). They do not influence the data files.

Word list `<tag>.forms.tsv.gz`: one line per cased form, sorted by key then form order, tab-separated:
    key, form, subtitle count, written-language frequency (per token, 6 significant digits, 0 if none),
    combined count (05b §12.2, 3 decimals), class, flags, merged spellings.

Flags (comma-separated): spelling (the spelling dictionary knows the key; en-US: a SCOWL key), wiktionary (a form of the
Wiktionary pass or of the build's own spelling steps), abbreviation, name, colloquial (kept by the colloquial path,
flagged unattested), merged (received the counts of merged spellings), class0-typo, class0-dominated (dominated and
former spellings), lemma-only (German dictionary-only lemma, class 1), alias; from 0.7.1 nosuggest (the do-not-suggest
flag of 05b §2.16, on the form's line).

Pair table `<tag>.pairs.tsv.gz`: previous word (empty = sentence start; an elision prefix ends with '), follower,
stored form, weighted pair count, class; per previous word in table order. Prefix table `<tag>.prefixes.tsv`: prefix,
count, class.
"""

import gzip
import os

import combined
from common import jlower


def _g6(x):
    return "0" if not x else f"{x:.6g}"


def write(export_dir, tag, builder, entries, src, lemma_keys=(), nosuggest=()):
    """[nosuggest]: the forms with the do-not-suggest flag of 05b §2.16 (flag `nosuggest`, 0.7.1; a per-form flag, given
    on the lines of those forms)."""
    os.makedirs(export_dir, exist_ok=True)
    count = getattr(builder, "count", None) or (lambda k: builder.counts.get(k, 0))
    english = tag == "en-US"
    if english:
        count = lambda k: sum(c for _, c in builder.selected.get(k, ())) if k in builder.selected else builder.count(k)
    abbreviation = getattr(builder, "abbreviation_keys", set())
    colloquial = getattr(builder, "colloquial", set())
    name_keys = getattr(builder, "names", set())
    zero = getattr(builder, "zero_forms", set())
    typos = getattr(builder, "typos", {})
    merged_from = {}
    for src_key, target in (getattr(builder, "merged", None) or getattr(builder, "umlaut_merged", None) or {}).items():
        merged_from.setdefault(target, []).append(src_key)
    alias = set(getattr(builder, "alias_keys", ()) or ())
    pw = getattr(builder, "pw", {})
    cc = getattr(builder, "combined_counts", {})
    total = builder.corpus_tokens
    path = os.path.join(export_dir, f"{tag}.forms.tsv.gz")
    tmp = path + ".tmp"
    with gzip.GzipFile(tmp, "wb", compresslevel=9, mtime=0) as gz:
        for key in sorted(entries):
            flags = []
            if english:
                if key in getattr(builder, "main_keys", ()) or key in getattr(builder, "large_forms", {}):
                    flags.append("spelling")
            else:
                if builder.spelling.knows(key) and key not in builder.spelling_excluded and combined.letters_only(key):
                    flags.append("spelling")
                if builder.wiktionary_form(key) and key not in abbreviation:
                    flags.append("wiktionary")
            if key in abbreviation:
                flags.append("abbreviation")
            if key in name_keys:
                flags.append("name")
            if key in colloquial:
                flags.append("colloquial")
            if key in merged_from:
                flags.append("merged")
            if key in typos:
                flags.append("class0-typo")
            if key in zero:
                flags.append("class0-dominated")
            if key in lemma_keys:
                flags.append("lemma-only")
            forms = entries[key]
            if key in alias or any(jlower(f) != key for f, _ in forms):
                flags.append("alias")
            sub = count(key)
            c = cc.get(key)
            if c is None:
                c = combined.combined_count(sub, total, pw.get(key, 0.0), key in name_keys, combined.letters_only(key))
            merged = ",".join(sorted(merged_from.get(key, ())))
            for form, cls in forms:
                form_flags = flags + (["nosuggest"] if form in nosuggest else [])
                line = "\t".join([key, form, str(int(round(sub))), _g6(pw.get(key, 0.0)), f"{float(c):.3f}", str(cls),
                                  ",".join(form_flags), merged])
                gz.write((line + "\n").encode("utf-8"))
    os.replace(tmp, path)


def write_pairs(export_dir, tag, entries, table, counter, prefixes=None):
    """[table]: previous -> [(follower, class, form)]; [counter]: the pair counter with the weighted pair counts;
    [prefixes]: the elision prefix table [(prefix, class)] with [counter]-independent counts in prefix_counts."""
    os.makedirs(export_dir, exist_ok=True)
    path = os.path.join(export_dir, f"{tag}.pairs.tsv.gz")
    tmp = path + ".tmp"
    with gzip.GzipFile(tmp, "wb", compresslevel=9, mtime=0) as gz:
        for prev in sorted(table):
            for key, cls, form in table[prev]:
                c = counter.pairs.get((prev, key), 0)
                gz.write("\t".join([prev, key, form, f"{c:g}", str(cls)]).encode("utf-8") + b"\n")
    os.replace(tmp, path)


def write_prefixes(export_dir, tag, prefix_table, prefix_counts):
    os.makedirs(export_dir, exist_ok=True)
    with open(os.path.join(export_dir, f"{tag}.prefixes.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for p, cls in prefix_table:
            f.write(f"{p}\t{prefix_counts.get(p, 0)}\t{cls}\n")
