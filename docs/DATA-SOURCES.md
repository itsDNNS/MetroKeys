# Dictionary data sources

The word lists and word-pair tables in `engine/src/main/resources/app/metrokeys/engine/dict/` are built by
`tools/build_dictionaries.py` from the free sources named in the specification (`spec/05b-dictionaries.md`, not published)
§2. No other data is used. The machine-readable list of every source file with its URL and SHA-256 is
[tools/sources.json](../tools/sources.json); the build refuses to run if a hash does not match.

## Licence of the data files

From 0.7.0 the derived data files (`*.words.gz`, `*.pairs.gz`, including the elision prefix tables) are distributed
under the **GNU General Public License, version 3 only** (GPL-3.0-only), like the app (spec 05b §2): the spelling
dictionaries are used under GPL-3.0 (igerman98/frami, RLA-ES), LGPL-3.0 (VERO) and MPL-2.0 (Dicollecte, combined
under GPL-3.0 as secondary licence); the CC BY-SA 4.0 sources (FrequencyWords, Wiktionary, wordfreq) are adapted
under the one-way CC BY-SA 4.0 / GPLv3 compatibility and keep their attribution; Tatoeba (CC BY 2.0 FR), Common Voice
(CC0) and SCOWL permit the combination with attribution. Up to 0.6.9 the data files were CC BY-SA 4.0, and releases up
to 0.6.9 keep that licence.

A licence/attribution text accompanies each data file (`LICENSE-<tag>.txt`, with the spelling dictionary's notice
verbatim, and `SCOWL-Copyright.txt`); the engine exposes it through `Dictionary.bundledLicenseText(tag)` for the app's
licences screen, which also shows the GPL 3, LGPL 3 and MPL 2.0 texts (`app/licenses/`) and the CC BY-SA 4.0 reference.
The notices come from the pinned sources listed in [tools/notices.json](../tools/notices.json) (the README files of the
LibreOffice dictionaries at the pinned commit; the French one from the Grammalecte archive).

**Source form** (05b §2): `python3 tools/build_dictionaries.py source-form <version>` writes
`build-data/release/metrokeys-<version>-dictionary-source.tar.gz` (deterministic) with, per language, the word list as
text (`<tag>.forms.tsv.gz`: key, form, subtitle count, written-language frequency, combined count, class, flags, merged
spellings), the pair table as text (`<tag>.pairs.tsv.gz`: previous word, follower, form, pair count, class), the prefix
table (`<tag>.prefixes.tsv`), the spelling-dictionary input files exactly as used with their notices, the licence text,
and the build report with every input hash. The archive is attached to the GitHub release (it is not committed).

## Sources

| Source | Version | File | SHA-256 | Licence | Used for |
|---|---|---|---|---|---|
| hermitdave/FrequencyWords (https://github.com/hermitdave/FrequencyWords) | commit `525f9b560de45753a5ea01069454e72e9aa541c6` | `content/2018/de/de_full.txt` | `a5fc13f4efaaf70fd9226d11295fd3cd4c2264852ee1fad22c0abaf64acec8dc` | content CC BY-SA 4.0 | German words and counts (OpenSubtitles 2018 via OPUS) |
| hermitdave/FrequencyWords | same commit | `content/2018/en/en_full.txt` | `7fea67ab954e2c01df6c608c9826e594cf36f8823b3243554f88245fb75dc506` | content CC BY-SA 4.0 | English words and counts |
| German Wiktionary (https://de.wiktionary.org/) | dump `20261001` | `dewiktionary-20261001-pages-articles.xml.bz2` (sha1 `43a36bb5198f327297431d34650d7eab5ccc1adc`) | `d72285187a6420e25f7e8c1c0949df3e64ce56116937aa9b31e98f9727e0b4cf` | CC BY-SA 4.0 (also GFDL) | German spelling, case and validity (no frequencies) |
| Tatoeba (https://tatoeba.org/) | export of 2026-10-10 (Last-Modified `Sat, 10 Oct 2026 06:30:50 GMT`) | `per_language/deu/deu_sentences.tsv.bz2` | `7fb0b466dfaf4610cc3841b9e7f0f352b7fa3e3665bf8fd0558945d32e5ecbfa` | CC BY 2.0 FR (some CC0) | German pairs (weight 3) |
| Tatoeba | same export | `per_language/eng/eng_sentences.tsv.bz2` | `0eabb52f54350bbb39807190920bfddaf9fee2ba50ca75af67e11a22ec475c29` | CC BY 2.0 FR | English pairs (weight 3), apostrophe split, running-text case |
| SCOWL, Kevin Atkinson (http://wordlist.aspell.net/) | 2020.12.07 (classic SCOWL v1) | `scowl-2020.12.07.tar.gz` | `5587667caa20c4891390c2d42dbb4d5c4c3f41bee77af1457ece3ba23fb859cc` | SCOWL licence (notice shipped verbatim) | English validity, US spelling, case |
| SCOWL `Copyright` (inside the archive) | – | `scowl-2020.12.07/Copyright` | `283326a422e29c510e2ba6805518c418ce3c35ed1fadc3eec83d2da4f6c5a055` | – | shipped as `SCOWL-Copyright.txt` |
| Mozilla Common Voice (https://github.com/common-voice/common-voice) | commit `e983ce07e3f4a935b7958c77224c77b7bdc8079a`, `server/data/de/` (12 files, see sources.json) | conversational files weight 3, `wiki.*` weight 1, Europarl not used | see [sources.json](../tools/sources.json) | CC0 1.0 (`server/data/LICENSE`, sha256 `bf474cdfd02e162603358e4e3615182018cad27593d2bf5e75ca13e4a5a6af0c`) | German pairs |
| Mozilla Common Voice | same commit, `server/data/en/` (23 files) | `wiki.*` weight 1, others weight 3 | see sources.json | CC0 1.0 | English pairs and sentence statistics |
| hermitdave/FrequencyWords | same commit as de/en | `content/2018/es/es_full.txt` | `b4794c425f3500c21a0ff9367175e36795a978c928a36670e9ad6ae206fb7728` | content CC BY-SA 4.0 | Spanish words and counts (1 202 520 lines, 423 290 924 tokens) |
| Spanish Wiktionary (https://es.wiktionary.org/) | dump `20261001` | `eswiktionary-20261001-pages-articles.xml.bz2` (sha1 `c0729c68e30caf723988711137224c3500550836`) | `b10214827500ab323e06e59ceeb783e04c8be051f184b4ffe9b59efcb5f54f20` | CC BY-SA 4.0 (also GFDL) | Spanish spelling, validity, proper-noun case, titles of all languages (no frequencies) |
| English Wiktionary, Spanish sections (https://en.wiktionary.org/) | dump `20261001` | `enwiktionary-20261001-pages-articles.xml.bz2` (sha1 `f9ed426c69c6b21d026613bd0f200c2aaa1145e9`) | `37e414002bdf72ccf6518b83116ec36df36af4aabbf4477d49871849b737de42` | CC BY-SA 4.0 (also GFDL) | further Spanish forms (verb forms, plurals); used because the Spanish dump alone knows only 87.9 % of the top 20 000 keys (05b §8.2 point 3) |
| Tatoeba | export of 2026-10-10 (Last-Modified `Sat, 10 Oct 2026 06:30:50 GMT`, downloaded 2026-10-15) | `per_language/spa/spa_sentences.tsv.bz2` (442 544 sentences) | `d1f7aa997368571e8bbba341ab60f9145fefe14970f21065bd97be8e03c8e6fb` | CC BY 2.0 FR | Spanish pairs (weight 3), running-text case evidence, stock-name check |
| Mozilla Common Voice | same commit, `server/data/es/` | `sentence-collector.txt` weight 3 (`0a125f0d1cd892c5d727abb8006f5fb964ef9fe1e41bdf4afd3c4b845c47286b`), `wiki.es.txt` weight 1 (`26dfe9d9993cf7279ebf7cdffb2b086b4995982200415110903ba5530d1fc47d`); `singleword-benchmark.txt` not used | see left | CC0 1.0 | Spanish pairs and running-text case evidence |
| hermitdave/FrequencyWords | same commit as de/en/es | `content/2018/pt_br/pt_br_full.txt` (848 043 lines, 426 260 247 tokens) | `533b3abaab3b1ea9cec7a150fc8aaf8d428c6f241979cc2224b7ee82f8a369b2` | content CC BY-SA 4.0 | Brazilian Portuguese words and counts; `content/2018/pt/` (European) is not used |
| hermitdave/FrequencyWords | same commit | `content/2018/pt_br/pt_br_ignored.txt` (314 902 lines) | `1ac69c22ec5df9530841adeef021c2132a427c6414bd77b043318eee5aa38278` | content CC BY-SA 4.0 | only its hyphen tokens: 214 590 types, 3 703 820 tokens, 7 483 329 credited part counts |
| Portuguese Wiktionary (https://pt.wiktionary.org/) | dump `20261001` | `ptwiktionary-20261001-pages-articles.xml.bz2` (70 114 946 bytes, sha1 `b0972df4b1532d7cf9ed27a6387a4bd4eefa528a`) | `7d44d78e55c4318ae09e0232582628f700e1b1a4e3856e4acb9bbda9422f7e02` | CC BY-SA 4.0 (also GFDL) | Portuguese spelling, validity, pre-reform and Portugal notices, proper-noun case (no frequencies) |
| English Wiktionary, Portuguese sections | dump `20261001` (the file of the Spanish row) | `enwiktionary-20261001-pages-articles.xml.bz2` | `37e414002bdf72ccf6518b83116ec36df36af4aabbf4477d49871849b737de42` | CC BY-SA 4.0 (also GFDL) | inflected forms, pre-reform and regional labels (required, 05b §9.2) |
| Tatoeba | export of 2026-10-10 (Last-Modified `Sat, 10 Oct 2026 06:30:50 GMT`, downloaded 2026-10-16) | `per_language/por/por_sentences.tsv.bz2` | `396a9416796b494c889e845075a5e2fa49a34f58831fd6e73d8bf38b7d926845` | CC BY 2.0 FR | Portuguese pairs (weight 3) and running-text case evidence, after the Brazilian filter |
| Mozilla Common Voice | same commit, `server/data/pt/` | `sentence-collector.txt` weight 3 (1 670 250 bytes); `singleword-benchmark.txt` not used | `bdabc8258ab0955aada07f75f31fae9fad8b1ca5d40899b92074610bb5aed13d` | CC0 1.0 | Portuguese pairs and running-text case evidence, after the Brazilian filter |
| hermitdave/FrequencyWords | same commit as de/en/es/pt | `content/2018/it/it_full.txt` (798 017 lines, 247 073 874 tokens) | `b23b0c6a3f59c1da1c7caa667b9df5699e95323fee20a05f343c7d7dae73c4be` | content CC BY-SA 4.0 | Italian words and counts, counts of the elision prefixes and apostrophe tokens (§10.1); `it_ignored.txt` is not used |
| Italian Wiktionary (https://it.wiktionary.org/) | dump `20261001` | `itwiktionary-20261001-pages-articles.xml.bz2` (71 493 528 bytes, sha1 `b34c28860741cf2e71eceac61dd9f27ad3385c62`) | `a7a334de675ab98eef6948cd029248f20ebc185773a8984f392f2bb2d793237e` | CC BY-SA 4.0 (also GFDL) | Italian spelling, validity, proper-noun case, titles for the foreign-word rule (no frequencies) |
| English Wiktionary, Italian sections | dump `20261001` (the file of the Spanish row) | `enwiktionary-20261001-pages-articles.xml.bz2` | `37e414002bdf72ccf6518b83116ec36df36af4aabbf4477d49871849b737de42` | CC BY-SA 4.0 (also GFDL) | inflected, clitic and apocopic forms, misspelling and obsolete-spelling pages (required, 05b §10.2) |
| Tatoeba | export of 2026-10-10 (Last-Modified `Sat, 10 Oct 2026 06:30:50 GMT`, downloaded 2026-10-16) | `per_language/ita/ita_sentences.tsv.bz2` (988 826 sentences) | `9456a860f6528d21429b6b58ee92aa144b4cd7e698532648aeb8a72d006f9fa8` | CC BY 2.0 FR | Italian pairs (weight 3), running-text case evidence, sentence counts of §10.4/§10.5 |
| Mozilla Common Voice | same commit, `server/data/it/` (exactly four files) | `frasi.txt` weight 3 (`5655a5eb1d8245d6db25bd430d7e11a8673e064cb0f03c8a08f3f0cb896a4be2`), `sentence-collector.txt` weight 3 (`5917d71dc7e6e245becc1b50df04a650aea6877737bca3a9b60541b3f230c86e`), `wiki.it.txt` weight 1 (`007b50b609b01788ee380e7087584681a6945cd8844a3408c0ed2e03fa093e88`), `wiki.it.rerun-2021-10.txt` weight 1 (`499cfa27a09ee41e405ae818100a951f2960cb87aaa1e62f90f37c03ba4b5d44`) | see left | CC0 1.0 | Italian pairs, running-text case evidence, sentence counts |

| hermitdave/FrequencyWords | same commit as de/en/es/pt/it | `content/2018/fr/fr_full.txt` (834 768 lines, 318 214 450 tokens) | `dda23c3025fc195b3a7996af26371dcf3abd1aa2cd7218dbbb7c92a49780ab0c` | content CC BY-SA 4.0 | French words and counts, the counts of the elision prefixes, apostrophe and hyphen tokens (§11.1); `fr_ignored.txt` is not used |
| French Wiktionary (https://fr.wiktionary.org/) | dump `20261001` | `frwiktionary-20261001-pages-articles.xml.bz2` (884 182 778 bytes, sha1 `ecd5d75d7a13205c4fceb04bb9d4a1087fc980b5`) | `f2e66b90f1686c5862f5aa7e73ed60d5d33575bf9f13dd2f264c9addd0de7b5d` | CC BY-SA 4.0 (also GFDL) | French spelling, validity, pointer, letter and elision pages, compounds and apostrophe words, proper-noun case, titles for the foreign-word rule (no frequencies) |
| English Wiktionary, French sections | dump `20261001` (the file of the Spanish row) | `enwiktionary-20261001-pages-articles.xml.bz2` | `37e414002bdf72ccf6518b83116ec36df36af4aabbf4477d49871849b737de42` | CC BY-SA 4.0 (also GFDL) | optional (§11.2 point 3): read for the coverage report only; not used for the data, because the French dump alone recognises 95.05 % of the top 20 000 keys |
| Tatoeba | export of 2026-10-10 (Last-Modified `Sat, 10 Oct 2026 06:30:50 GMT`, downloaded 2026-10-16) | `per_language/fra/fra_sentences.tsv.bz2` | `fac31d82b216c95affee7dffcee6c0ae59c45f254ebbfbbf8abb9e66da86ee8f` | CC BY 2.0 FR | French pairs (weight 3), running-text case evidence, sentence counts of §11.4–§11.7 |
| Mozilla Common Voice | same commit, `server/data/fr/` (463 files, 11 used) | `sentence-collector.txt`, `global-sprint-fr.txt`, `vfvv1.1.txt`, `vfvv2.txt` … `vfvv6.txt` weight 3; `issue2259_deleted_export_readd_fixed.txt`, `wiki-1.fr.txt`, `wiki-2.fr.txt` weight 1 (hashes as in spec 05b §2.13 and sources.json) | see sources.json | CC0 1.0 | French pairs, running-text case evidence, sentence counts |
All hashes equal the ones listed in the specification (for the Wiktionary dumps the specification gives the sha1 of
the dump status; the sha256 values above were recorded at download). The directory `server/data/es/` of Common Voice
holds exactly the three files listed, `server/data/pt/` exactly the two named above, `server/data/it/` exactly the four
named above, `server/data/fr/` the 463 files of 05b §2.13 (checked with the GitHub contents API at that commit). The Tatoeba exports downloaded on 2026-10-15 still carried
the reference export date (2026-10-10) and the reference hashes. The Wiktionary dump and the Tatoeba exports will
disappear from their servers; keep archived copies (with the hashes above) to rebuild byte-identical data.

## Attribution texts (shown in the app)

From 0.7.0 (05b §2.5; generated by `tools/dictbuild/licenses.py`), every text ends with "Distributed under the GNU GPL
version 3; the CC BY-SA 4.0 material is used under the CC BY-SA 4.0 / GPLv3 compatibility.":

- Frequencies (every word list): FrequencyWords © Hermit Dave (OpenSubtitles 2018), CC BY-SA 4.0; written-language
  frequencies from wordfreq © Robyn Speer, CC BY-SA 4.0, which contains data from Wikipedia, OPUS OpenSubtitles 2018,
  SUBTLEX (Brysbaert et al.), Google Books Ngrams and the further sources named in its README.
- German word list: frequencies; spellings from Wiktionary (de.wiktionary.org); spelling dictionary igerman98 ©
  Björn Jacke with the frami additions © Franz Michael Baumann, GNU GPL version 2 or 3, used under GPL 3. Modified
  (filtered, case, frequency classes).
- English word list: frequencies; SCOWL © Kevin Atkinson and others, see the notice (complete `Copyright` file
  verbatim); sentence statistics from Tatoeba (CC BY 2.0 FR) and Mozilla Common Voice (CC0). Modified (filtered,
  spelling, contractions, frequency classes).
- Spanish word list: frequencies; Wiktionary (es.wiktionary.org and the Spanish sections of en.wiktionary.org);
  spelling dictionaries RLA-ES © Santiago Bosio and contributors, GNU GPL version 3 or later (also LGPL 3+ / MPL 1.1+),
  used under GPL 3; sentence statistics. Modified (filtered, accents, case, frequency classes).
- Portuguese (Brazil) word list: frequencies (Brazilian Portuguese); Wiktionary (pt.wiktionary.org and the Portuguese
  sections of en.wiktionary.org); spelling dictionary VERO © Raimundo Santos Moura and contributors, GNU LGPL version 3
  (also MPL); sentence statistics. Modified (filtered, hyphenated forms split, spelling reform and accents, case,
  frequency classes).
- Italian word list: frequencies; Wiktionary (it.wiktionary.org and en.wiktionary.org); spelling dictionary
  Dizionario italiano © Gianluca Turconi, Davide Prina, Andrea Pescetti, LibreItalia, GNU GPL version 3; sentence
  statistics. Modified (filtered, accents restored, elisions separated, case, frequency classes).
- French word list: frequencies; Wiktionary (fr.wiktionary.org); spelling dictionary Dicollecte (all-variants edition)
  © Olivier R. and contributors (grammalecte.net), Mozilla Public License 2.0; sentence statistics. Modified
  (filtered, accents and ligatures restored, elisions separated, hyphenated forms split, compounds, case, frequency
  classes).
- Word pairs (every language): counted from sentences of Tatoeba (CC BY 2.0 FR) and Mozilla Common Voice (CC0).
  Modified (pair counts, probability classes). Distributed under the GNU GPL version 3.
- Each text closes with the licence paragraph and where the source form is published.

Up to 0.6.9: "German word list: FrequencyWords © Hermit Dave
(OpenSubtitles 2018), CC BY-SA 4.0; spellings from Wiktionary (de.wiktionary.org), authors see page histories, CC BY-SA
4.0. Modified (filtered, case, frequency classes)" and the pairs "Modified (pair counts, probability classes), CC BY-SA
4.0"; the other languages correspondingly.

## Build result

Recorded by the build in [tools/dictionary-build.json](../tools/dictionary-build.json). Two runs on the same inputs
produced identical bytes (also after the 0.6 restructuring into per-language profiles: the German and English files are
byte-identical to 0.5.2, and two Spanish runs gave identical bytes).

| | de-DE | reference (05b) | en-US | reference (05b) |
|---|---:|---:|---:|---:|
| keys | 320 804 (0.6.6; 857 274 in 0.6.5, 236 223 in 0.6.4, 236 559 before) | 236 823 | 163 405 | 163 419 |
| cased forms | 326 931 (864 361; 241 563; 241 914) | 242 784 | 164 040 | 164 114 |
| S | 16.357883 | 16.357883 | 14.976348 | 14.976349 |
| maxCount | 5 890 279 | – | 24 812 233 | – |
| corpusTokens | 156 169 536 | 156 169 536 | 695 682 540 | 695 682 540 |
| alias keys (replacing a word) | – | – | 79 (11) | 79 (11) |
| possessive forms | – | – | 34 855 | 34 883 |
| pair previous words | 54 840 (55 267; 52 820; 53 004) | 53 012 | 42 124 | 42 131 |
| pairs | 565 578 (566 548; 558 993; 561 563) | 561 595 | 590 533 | 590 661 |

| File | Bytes | SHA-256 |
|---|---:|---|
| `de-DE.words.gz` (0.6.6) | 1 335 116 | `2a03d6556b3874740ec56166e069894b43e2b3b980b48288e25c46e4ba9a9b21` |
| `de-DE.pairs.gz` (0.6.6) | 1 889 786 | `5244e4acae6efcc1ea6290357ec5131dbbf697ccacd41ff258d8aa67caf35ca8` |
| `en-US.words.gz` | 519 514 | `0d0595b450b30387511006ce39691e8c248e5d7175041f50d65899bb2e5b1f9b` |
| `en-US.pairs.gz` | 1 927 135 | `2dde9255fc17e317a7bec8ad1aa7f24ceb6c0b66379299c1ae25472518457e91` |

The SHA-256 of the uncompressed word-list payloads (stored in the pair tables as binding) are
`96b4fb91…5183a730` (de-DE, 0.6.5; 0.6.4 `47f6f56f…57033d9`, before `c51a843f…c8637ad`) and `ceac596e…ec3fb43f` (en-US), see dictionary-build.json.

### Spanish (es-ES, first build, 0.6.0)

| | es-ES |
|---|---:|
| keys | 247 985 |
| cased forms | 250 743 (2 756 keys with two case forms, 17 824 keys whose best form is capitalised) |
| S | 15.466736 (spec: expected ≈ 15.4669; `maxCount` is 14 461 415 instead of 14 459 520 because cut-off tokens such as "de-" are counted for "de") |
| maxCount | 14 461 415 ("de") |
| corpusTokens | 423 290 924 |
| A / Bc | −8.63 / 0.02808 |
| pair previous words | 38 819 |
| pairs | 385 432 |
| tokens with a leading/trailing hyphen added to the bare token | 20 113 |
| clitic forms accepted (§8.4 step 1) | 2 727 of 3 855 candidates |
| accent-less spellings merged (§8.4 step 2) | 29 151, of them 220 variant pages and 1 649 inflected forms (see below) |
| missing-space artefacts dropped | 5 715 (70 of them Spanish forms, e.g. aveces, osea, enserio, talvez, aver, depronto) |
| dropped as foreign words | 4 366 |
| dropped as wrongly accented ("vámos") | 364 |
| kept without being a Spanish form (count ≥ 40) | 22 548 |
| coverage of the top 20 000 keys (Spanish forms) | 87.94 % Spanish dump alone, 89.92 % with the English dump (the rest are mostly names and English words of dubbed films) |
| Tatoeba stock names | Tom, Mary, John, Sami, Layla, Mennad, Fadil, Ziri, Yanni, Skura, Rima + over-represented María, Tomás, Boston, Japón |

| File | Bytes | SHA-256 |
|---|---:|---|
| `es-ES.words.gz` | 817 685 | `45f69b37ce57b6015792d1568bfbd5db995aec75790a873d59681f8dc7788e5c` |
| `es-ES.pairs.gz` | 1 221 224 | `85edda4b17312dd31e10402f83c39ef8b83d08e7cfb1de031171e1e293ba4f8e` |

Word-list payload SHA-256 `637aec8a…1ed989`. Size in the APK per language (word list + pairs; gzip already, so the APK cannot compress them
further): de-DE 2.83 MB, en-US 2.45 MB, es-ES 2.04 MB. The release APK grew from about 7 MB (0.5.2) to 9.1 MB.

Rules of the Spanish build that the specification leaves to the implementation (05b §8, open point 5; all are in
`tools/dictbuild/build_es.py` and `wiktionary_es.py`):

- **Wiktionary page kinds.** Both passes record, besides the Spanish forms, pages whose Spanish definitions only point
  to another spelling (obsolete, substandard, misspelling, or the same word with accents: "tambien", "aqui", "sólo"),
  pages that are only inflections (verb forms, plurals) and pages that call the title a misspelling or alternative
  form of a phrase ("osea" → "o sea", "talvez" → "tal vez"). Templates are only read as text, never expanded.
- **Accent merge** (§8.4 step 2) also merges a variant page ("tambien" → "también"; a variant page that is not merged
  stays a word: "sólo", "éste"), and an inflected form without a lemma of its own if the accented word is at least
  10 times as frequent ("dias" → "días", "llamame" → "llámame"); the pairs of §1.5 ("esta"/"está", "ano"/"año" …)
  stay.
- **Clitic forms** compete when they differ only by acute accents: the most frequent is accepted ("dámelo" over
  "damelo"), unless a Spanish form of that spelling without accents is at least as frequent ("díme" is no clitic form).
- **Missing-space artefacts** (§8.5 step 5) also include Spanish forms that a Wiktionary page calls a fused phrase,
  and inflected forms without a lemma whose two-word phrase is at least 10 times as frequent as the form in the
  sentence sources while the form's share there is below 0.2 of its subtitle share ("aveces" of avezar); a second
  part that is a clitic pronoun never counts ("pasarla", "dejala" stay).
- **Wrongly accented tokens** that are not forms and whose spelling without the accent is a form are dropped
  ("vámos"; §8.4 step 3 expects them to fall out).
- **Stock names**: the 30 most frequent capitalised Tatoeba tokens are recorded; a token among the 100 most frequent
  whose Tatoeba share is at least 10 times its subtitle share is added.
- The Wikipedia weight stays 1: weights 0.5, 0.25 and 0.1 were measured and change neither "a veces" nor the order
  after "muchas" (see spec/QUESTIONS.md), but lose up to 27 % of the pairs.

The small differences to the reference counts come from details of the Wiktionary markup cleaning and the
tokenisation that the specification describes only functionally; all classes the worked examples of spec 05 §17
rely on are reproduced exactly (verified by `WorkedExamplesTest`).

### Portuguese (pt-BR, first build, 0.6.1)

Rebuilding all four languages after adding Portuguese left the German, English and Spanish files byte-identical
(SHA-256 compared before and after); two Portuguese runs gave identical bytes.

| | pt-BR |
|---|---:|
| keys | 173 200 |
| cased forms | 174 752 (1 552 keys with two case forms, 14 311 keys whose best form is capitalised) |
| S | 15.429650 (spec: expected ≈ 15.4297) |
| maxCount | 15 045 998 ("que", main list 15 044 152 plus hyphen credits) |
| corpusTokens | 433 743 576 (426 260 247 main list + 7 483 329 credited hyphen parts) |
| A / Bc | −8.64 / 0.02815 |
| pair previous words | 21 100 |
| pairs | 279 747 |
| pre-clitic verb forms accepted (§9.4 step 1) | 1 236 (ajudá, encontrá, pegá, colocá, tirá, mantê, trazê …) |
| pre-reform pages | 706 pt.wiktionary notices, 303 pre-reform variants listed on reformed pages, 664 en.wiktionary (1990 only) |
| former Brazilian spellings kept with class 0 | 271 (pára, pêlo, idéia, vôo, agüentar, tranqüilo, jóia, platéia …), 319 474 counts moved |
| former European spellings dropped | 329 (acção, óptimo, actividades …), 134 104 counts moved |
| pages pre-reform in one dump and a form in the other (open point 12) | 212 (mostly European spellings of the 1911–1990 reforms) |
| keys with only European-only pages | 4 135 |
| European forms dropped (§9.4 step 3) | 405 (bebé, facto, connosco, prémio, autocarro, camião, género, acabámos, planear, humidade …) |
| accent-less / wrongly accented spellings merged (§9.4 step 4) | 32 884, of them 492 pointer pages and 1 240 inflected-only forms (ultima, noticias, ate, amanha, agua …) |
| merges skipped: spelling used in the sentence sources | 78 (thomas, carter, oliver, angela, jessica …) |
| merges skipped: foreign word with a rare target | 528 (he, place, porter, les …) |
| missing-space artefacts dropped | 4 518 (porfavor, denovo, devolta, porisso, apartir, anoite, quejá, oque …) |
| dropped as foreign words | 4 709 (12 847 more would be dropped with the English dump's titles, mostly names) |
| dropped for a misplaced cedilla | 415 (começ …) |
| kept without being a Portuguese form (count ≥ 40) | 27 990 |
| coverage of the top 20 000 keys (Portuguese forms) | 72.82 % Portuguese dump alone, 85.13 % English dump alone, 86.43 % both |
| sentences dropped by the Brazilian filter (§9.7) | Tatoeba 2 992, Common Voice 75 (483 288 kept) |
| Tatoeba stock names | Tom, Mary, John, Sami, Layla, Mennad, Fadil, Ziri, Yanni, Skura, Rima + over-represented Austrália, Boston, Brasil, João, Maria, Tatoeba |

| File | Bytes | SHA-256 |
|---|---:|---|
| `pt-BR.words.gz` | 593 768 | `fe074a9443b4f5ce51791923b9031698e3c27442de6ce0174569541c47f22c62` |
| `pt-BR.pairs.gz` | 864 062 | `7d42d0c627a5c97fae055fd02433e9cb4aa78a4e6c7229ed6eb120059b092e80` |

Word-list payload SHA-256 `a95326a7…a7060`. Size in the APK: 1.46 MB (word list + pairs). All lists of accepted and
dropped keys are in `tools/dictionary-build.json` (`pt-BR-extra`).

Rules of the Portuguese build that the specification leaves to the implementation (05b §9, open point 9; all in
`tools/dictbuild/wiktionary_pt.py` and `build_pt.py`, questions in spec/QUESTIONS.md):

- **Two-stage Wiktionary passes.** Each dump is read once and its Portuguese sections are cached raw
  (`build-data/cache/*-pt.tsv.gz`); the page kinds are classified from the cache on every build. Templates are only
  read as text, never expanded.
- **Page kinds.** pt.wiktionary: `{{Anterior ao Acordo de 1990|…}}` = pre-reform page, `{{grafiaPtpt|…}}` = Portugal
  spelling with its Brazilian counterpart, senses labelled "grafia lusitana", "grafia portuguesa" or "Portugal" (not a
  place label such as "Geografia|Portugal") = European senses, "vide"/"o mesmo que" definitions to a spelling that
  differs only by diacritics or case, or on pre-1911 pages = pointer pages. en.wiktionary: `pt-pre-reform` with
  `br=90` or `pt=90` = pre-reform page (older reforms are obsolete spellings, i.e. pointer pages), obsolete,
  misspelling, nonstandard, eye-dialect and superseded spellings = pointers, "Portugal"/"European Portuguese" labels and
  "standard spelling of … from=European Portuguese" = European senses, alternative forms labelled Brazil = counterpart.
  Inflected-only = inflection and spelling pointers only, no definition of its own. Extensions: pre-reform variants
  listed on a reformed pt.wiktionary page count as pre-reform pages; an English-dump inflected form whose alternative
  form labelled Brazil differs only by diacritics is European-only with that counterpart ("falámos" → "falamos").
- **Hyphen tokens.** Parts with characters outside the key alphabet are skipped (the corpus total counts every part).
- **Accent merge guards.** Not merged: a key the filtered sentence sources use at least twice and at least as often as
  its target (names), and a lower-case title of the Portuguese dump whose target occurs fewer than 10 times there
  (English words). Inflected-only forms are merged only into a target at least 10 times as frequent that adds
  diacritics to plain letters and is not a pre-clitic verb form, so "têm"/"tem", "séria"/"seria" and "faze"/"fazê" stay
  two words. "esta" stays because "está" is only about 8.5 times as frequent.
- **Missing-space artefacts.** Unknown keys as specified (parts ≥ 20 000 each, phrase ≥ 10 and ≥ 10 × the fused
  form, weighted); pointer pages to a phrase ("denovo"); inflected-only forms by the Spanish rule (phrase ≥ 20 and
  ≥ 10 × fused, sentence share < 0.2) only when the sentence sources should contain the form at least 5 times and
  never for infinitive + "em" (personal infinitive).
- **Foreign words** use the titles of the Portuguese dump only.
- **Cedilla.** A key that is not a Portuguese form and has a ç not followed by a, o or u is dropped.
- **Brazilian filter** (§9.7): marker keys are the European forms and spellings dropped by §9.4 plus the keys with only
  European-only pages and fewer than 2 counts (4 299 keys); the European progressive is a form of "estar" + "a" + a
  word ending in -ar/-er/-ir/-or, all separated by spaces only.

### Italian (it-IT, first build, 0.6.2)

Rebuilding German, English, Spanish and Portuguese after adding Italian left all eight files byte-identical (SHA-256
compared before and after); two Italian runs gave identical bytes. The Italian files use version 2 of both formats
(ENGINE.md): the word list ends with the elision prefix table, the pair table holds the prefixes as previous words
(up to 4000 followers) and as followers. The other languages stay at version 1.

| | it-IT |
|---|---:|
| keys | 185 711 |
| cased forms | 188 355 (2 638 keys with two case forms, 16 169 keys whose best form is capitalised) |
| S | 16.294616 (spec: expected ≈ 16.2947) |
| maxCount | 6 257 813 ("non"; after the accent-loss transfer "e" has about 4.97 million and "è" about 5.09 million) |
| corpusTokens | 247 073 874 (every count of the list, including the apostrophe tokens) |
| A / Bc | −8.39 / 0.02665 |
| pair previous words | 37 566 (of them 41 elision prefixes) |
| pairs | 405 570 (6 593 elided pairs with a prefix as previous word: l' 2 255, dell' 957, all' 643, un' 608, nell' 475, dall' 425, d' 379, sull' 234, quell' 185 …; 12 912 pairs with a prefix as follower) |
| elided pairs dropped (§10.6) | 19: un'altro, un'uomo, un'errore, un'albero, un'ottimo, un'orologio, qualcun'altro, un'esame, un'albergo, un'aumento, nessun'altro, un'incontro, un'agente, un'ex, un'ingegnere, un'accento, un'impegno, un'eventuale, un'enigma |
| prefix table | 41 prefixes: l' 1 819 882 → class 235, c' 219, un' 209, d' 206, dell' 202, all' 201 … cinquant' 579 → 104 |
| prefixes without an elided/apocopic page in either dump (§10.2 item 4) | brav', vent', trent', quarant' |
| truncated words (§10.1 rule 2) | po' 290 082 (276 177 of "po" moved; "Po" keeps 2.35 %, its share in running text), da' 640, fa' 249, di' 164, va' 132, sta' 102, be' 61, mo' 50, to' 11 |
| accent written as an apostrophe (§10.1 rule 3) | 4 347 tokens, 289 080 counts (e' → è 77 610, piu' → più, perche' → perché, cosi' → così …) |
| other apostrophe tokens dropped (§10.1 rule 4) | 16 651 tokens, 183 030 counts (i', o', qual', ch', English contractions, leading apostrophes) |
| clitic forms accepted (§10.4 step 1) | 2 219 (potercela, scopriamolo, ignoralo, lasciartelo, usalo, parlane, vedila, goditela, dimmelo-type forms …) |
| apocopic forms accepted (§10.4 step 2) | 72 (affar, negar, tentar, commendator, ragionier, valor, ebben, alfin, fedel, nobil, lontan …) |
| accent merges (§10.4 step 4) | 20 613 keys, 1 677 306 counts (piu → più 394 239, puo → può, cio → ciò, perchè → perché 71 521, é → è 31 392 …), 54 of them pointer pages |
| merges skipped by the guards | 91 spellings used in the sentence sources (mae, abel, ultra, raja, wurstel …), 156 foreign titles with a rare target (souffle, sofa, tiramisu, poke …) |
| class-0 spellings (§10.4 step 5) | 51: abilita, andro, caffe, capacita, causo, cavita, cerano, citta, conquisto, cosi, cè, dazione, destate, difficolta, diro, disabilita, disputo, doro, emigro, eredito, eta, finche, gia, giu, influenzo, labbia, laria, latto, lauto, liberta, lora, niccolo, paghera, parlera, perche, pero, portera, puli, questora, santagostino, santantonio, saro, sposero, staro, suscito, trovero, umidita, usci, utilita, velocita, verro |
| kept as ordinary words by the subtitle guard (below) | 15: acconsenti, aggiudico, annoto, arruolo, catturo, classifico, collaboro, consegui, funziono, opero, provoco, risposo, rivelo, specializzo, tento |
| accent-loss rate f (§10.4 step 6) | 0.4760 (1 314 456 plain and 1 447 276 accented occurrences in 264 classes; spec, Italian dump only: 0.487); 3 448 244 counts moved in 2 365 classes (e → è 2 421 106, si → sì 513 694, sara → sarà, li → lì, papa → papà, la → là …) |
| missing-space artefacts dropped (§10.5 step 5) | 85 (perfavore, perte, peril, perme, avolte, afare, latua, pertutti, perquesto, hola, hale …) |
| fused elisions dropped (§10.5 step 6) | 366 (lex, daccordo, mezzora, questuomo, nientaltro, dacqua, lha, lho, lhai, damore, questanno, quandè, anchio …) |
| elision fragments dropped (own rule, below) | 21 (dov, quest, anch, nell, dell, sull …) |
| dropped as foreign words | 4 508 |
| kept without being an Italian form (count ≥ 40) | 15 623 |
| coverage of the top 20 000 keys (Italian forms) | 77.01 % Italian dump alone, 85.12 % English dump alone, 86.56 % both |
| Tatoeba stock names | Tom, Mary, John, Sami, Layla, Mennad, Fadil, Ziri, Yanni, Skura, Rima + over-represented Argentina, Australia, Boston, Cina, Germania, Giappone, Irlanda, Italia, Russia, Spagna, Ucraina |

| File | Bytes | SHA-256 |
|---|---:|---|
| `it-IT.words.gz` | 618 669 | `c4a58067f682167d6a6419b3f3949cd2bdb8ae954f656ba74018999b3f6a2e52` |
| `it-IT.pairs.gz` | 1 285 257 | `fe6e4b15d8c78e39d3266b16016f60c539bfca64c19c6ae4643b5581dcd694ae` |

Word-list payload SHA-256 `1f45d1db…e4d603`. Size in the APK: 1.90 MB (word list + pairs). All lists of accepted and
dropped keys are in `tools/dictionary-build.json` (`it-IT-extra`).

Rules of the Italian build that the specification leaves to the implementation (05b §10, open point 13; all in
`tools/dictbuild/wiktionary_it.py` and `build_it.py`, questions in spec/QUESTIONS.md):

- **Two-stage Wiktionary passes** as for Portuguese: raw Italian sections cached in
  `build-data/cache/itwiktionary-20261001-it.tsv.gz` and `enwiktionary-20261001-it.tsv.gz`, classified on every build.
  Page kinds: pointer pages are definitions that only point to another spelling (it.wiktionary "variante grafica /
  ricercata / errata …" with an obsolete or rare label, a misspelling wording or a target that differs only by
  diacritics, case or apostrophes; en.wiktionary misspelling, obsolete, archaic, nonstandard and superseded spellings
  and alternative spellings that differ only by diacritics, case or apostrophes, so "daccordo" points to
  "d'accordo"); apocopic pages ("forma tronca", "troncamento", `apocopic form of`); letter pages (a Letter section
  only); a form in one dump wins over a pointer or letter page in the other.
- **Titles with a final apostrophe** count only as the truncated words of §10.1; the prefix titles ("l'", "dell'")
  are no keys.
- **Clitic hosts** (§10.4 step 1): V is a lower-case page with an imperative or gerund sense in a dump (or "ecco"), and
  the infinitive branch needs a verb lemma page (so "marla" is not "mare" + "la"); names and foreign titles are
  skipped ("caroline", "diane").
- **Apocopic forms** (§10.4 step 2): only keys whose last two letters are a vowel and one of l, n, r, m (an apocope
  leaves a single consonant: "quel", not "quell"), the full word a lower-case form; names (mostly capitalised in
  running text) and foreign titles are skipped.
- **Accent merge guards** of the Portuguese build (sentence-source use, foreign titles); for a target with an
  apostrophe ("pò" → "po'") the sentence count is used instead of the letter-run count.
- **Dominated plain spellings** (§10.4 step 5): the elided counterparts also apply to forms with a diacritic ("cè" →
  "c'è"); truncated words ("da'", "di'") are not plain spellings. Own guard: an accented counterpart counts only if
  the plain spelling's subtitle count is at most 1.5 · f/(1 − f) times the accented forms' subtitle count (an
  accent-less spelling occurs about f/(1 − f) times as often as its accented word); far more means a word of its own
  that Wikipedia's past tenses outvote ("tento" 520 against "tentò" 235, "opero" 189 against 20) — 15 keys stay
  ordinary words.
- **Elision fragments**: a key that is the letters of an elision prefix without its apostrophe and not a form
  ("dov", "quest", "nell") is dropped.
- **Case** of a class-0 spelling is its dictionary form; the bare letters of a truncated word ("po") keep only their
  capitalised form ("Po"), since their lower-case running text belongs to the truncated word.
- **Pair count** (§5 step 2): totals c(prev, ·) include the dropped elided pairs; a prefix as follower takes one of
  the 48 places of a word's followers.

### French (fr-FR, first build, 0.6.3)

Rebuilding all six languages after adding French left the German, English, Spanish, Portuguese and Italian files
byte-identical (SHA-256 compared before and after); two French runs on the same inputs gave identical bytes. The
French files use version 2 of both formats, like Italian (word list with the elision prefix table, pair table with
the prefixes as previous words and followers).

| | fr-FR |
|---|---:|
| keys | 174 316 |
| cased forms | 179 237 (4 916 keys with two or more forms, 27 311 keys whose best form is capitalised) |
| S | 15.988646 (spec: expected ≈ 15.989) |
| maxCount | 8 442 744 ("de" after the hyphen credits; "je" 8 442 097) |
| corpusTokens | 328 850 678 (318 214 450 list counts + 10 636 228 credited hyphen parts) |
| A / Bc | −8.52 / 0.02716 |
| pair previous words | 41 447 (of them 14 elision prefixes) |
| pairs | 490 266 (14 473 elided pairs: d' 3 654, l' 3 604, s' 2 128, n' 1 515, m' 1 293, t' 841, qu' 713, j' 665, lorsqu' 17, c' 13, jusqu' 10, puisqu' 10, quoiqu' 8, ç' 2; 28 993 pairs with a prefix as follower) |
| elided pairs dropped (§11.7) | rule 1 (unelided phrase ≥ 10 ×) 68: j'te, j'suis, ç'a, j'sais, j'vais, j'me, l'à, j'vous …; rule 2 (accent twin ≥ 10 ×) 45: l'ile (l'île), jusqu'a (jusqu'à), d'ou (d'où), l'aine (l'aîné), l'age (l'âge), d'entraineur (d'entraîneur), l'ignoré (l'ignore), d'âpres (d'après) … |
| prefix table | c' 4 184 576 → 244, l' 3 675 406 → 242, j' 2 981 242 → 238, d' 2 603 041 → 236, qu' 2 520 219 → 236, n' 2 300 252 → 234, t' 1 257 857 → 225, m' 1 141 238 → 223, s' 1 037 629 → 221, jusqu' 133 102 → 189, lorsqu' 8 126 → 144, puisqu' 2 684 → 126, quoiqu' 1 695 → 119, ç' 1 348 → 115 |
| hyphen tokens (§11.1) | 165 174 types, 5 247 362 occurrences; split 155 804 types, 5 196 423 occurrences, 10 636 228 credited part counts (as specified) |
| apostrophe tokens (§11.1) | prefixes counted for the prefix table; cut first parts shared out (quelqu' → quelqu'un, aujourd' → aujourd'hui …); 11 whole apostrophe words (117 335 counts); 11 935 other tokens dropped (106 794 counts: va-t', o', y', goa', p', don', teal', you' …) |
| apostrophe words (§11.5) | 17: quelqu'un 265 020, aujourd'hui 117 611, quelqu'une 266, zo'é 254, prem's 236, na'vi 157, mam'zelle 113, presqu'île 109, preum's 101, inch'allah 77, sotheby's 64, o'brien 61 … |
| hyphen compounds (§11.5) | 495 (139 with a page of their own): est-ce 707 361, peut-être 310 312, avez-vous 88 869, est-il 82 366, là-bas 78 393, as-tu 73 228, a-t-il 67 120, vas-y 61 167, êtes-vous 57 514, excusez-moi 53 755, es-tu 48 906, allons-y 47 883, puis-je 42 969, laisse-moi 42 021 … |
| alias collisions | 36: aton: Aton + a-t-on (both kept); baseball: baseball + base-ball (both kept); bienaimé: Bienaimé + bien-aimé (real word dropped); bienaimée: bienaimée + bien-aimée (real word dropped); bienêtre: bienêtre + bien-être (real word dropped); chti: chti + ch'ti (both kept); cowboy: cowboy + cow-boy (both kept); email: email + e-mail (both kept) … |
| accent and ligature merges (§11.4 step 1) | 31 534 keys, 688 178 counts (coeur → cœur, ecoute → écoute, soeur → sœur, ecoutez → écoutez, oeil → œil, etre → être, etes → êtes, â → à, etait → était, etat → état …), 180 of them pointer pages; ligatures 203 keys, 133 124 counts (coeur → cœur, soeur → sœur, oeil → œil, oeuvre → œuvre, soeurs → sœurs, oeuf → œuf, voeux → vœux, coeurs → cœurs …) |
| merges skipped by the guards | 877 English titles or names with a rarer target (jim, pete, leo, come, zoe, media, abigail, here …), 161 spellings used in the sentence sources (kung, tai, daphne, che, romeo, cui …) |
| dominated spellings, class 0 (§11.4 step 2) | 146 (18 with a wrong accent): ca, meme, trés, tres, oeufs, na, jen, age, mere, ete, jai, cote, envoye, cest, facon, pres, problême, bebe, drole, lai, sen, sil, foret, dune, remplaces, transformes, lumiere, espece, brule, sy, obliges, nai, organises, tète, alle, dun, reves, cameras, surs, problêmes, role, colere, lest, reserve, gouter, enfermes, inspires, cites, cle, moitie, neo, edimbourg, reponds, hè, repondre, memoire, systême, carriere, symptomes, réglement, dor, crême, delle, enterres, nas, mème, riviere, blème, frontiere, goute, cheque, nes, siecle, ly, mele, bibliotheque, cles, ere, congres, repondez, condamnes, francois, identifies, piége, prives, distribues, laches, lor, menage, reel, adaptes, cree, pates, carriére, associes, alles, entraines, hê, repond, repondu, dart, rassembles, exploites, moise, cretin, coutent, passee, seche, publies, paques, reparer, fache, gache, sincere, lair, lamour, forets, reglement, composes, pélerinage, particulierement, imprimes, lumieres, situes, cense, largent, bibliothéque, signales, poete, premierement, resoudre, defend, pecher, conge, nen, supprimes, grece, parlè, collégues, males, cultives, comite, defi, siecles, symptome, géné |
| accent-loss rate f (§11.4 step 3) | 0.022 (237 497 plain and 10 550 793 accented occurrences in 1544 classes; spec, French dump only: 0.032); 252 148 counts moved in 4017 classes (a → à 93 258, la → là 19 407, ou → où 14 634, sur → sûr 6 133, voila → voilà 4 286, passe → passé 3 879 …) |
| missing-space artefacts dropped (§11.6 step 6) | 74 (etje, queje, cece, quelquechose, biensur, niles, biensûr, ilsa, maisje, estjuste, missa, ajamais …); kept by the name / English-word guard: aqui, deen, dema, depaul, dude, dudes, larue, late, lavie, leclair … |
| fused elisions dropped (§11.6 step 7) | 1 394 (men, tai, ny, dex, len, jaime, ten, dou, quest, lon, nest, quil, cétait, lestrade, quon, lun …) |
| letters of the prefixes dropped (§11.6 step 3) | qu, jusqu, lorsqu, puisqu, quoiqu |
| dropped as foreign words | 4 993 |
| kept without being a French form (count ≥ 40) | 8 941 |
| coverage of the top 20 000 keys | 95.05 % French dump alone with a simplified accent merge (95.16 % with the English dump, which is therefore not used); 94.77 % of the final counts |
| Tatoeba stock names | Algérie, Australie, Boston, Fadil, John, Layla, Marie, Mary, Mennad, Rima, Sami, Skura, Tatoeba, Tom, Yanni, Youba, Ziri |

| File | Bytes | SHA-256 |
|---|---:|---|
| `fr-FR.words.gz` | 610 829 | `641c12cbe0d814861fc35971e7323134ff9e61f8aa1be8fb7563a50086eadd96` |
| `fr-FR.pairs.gz` | 1 510 396 | `b46e1120965b7c74a78c3e9a05ce6870809ce8b90b52be5417e17d982126f5a7` |

Word-list payload SHA-256 `f8a658a8…d26ac4`. Size in the APK: 2.12 MB (word list + pairs). All lists of accepted and
dropped keys are in `tools/dictionary-build.json` (`fr-FR-extra`).

Rules of the French build that the specification leaves to the implementation (05b §11, open point 19; all in
`tools/dictbuild/wiktionary_fr.py` and `build_fr.py`, questions in spec/QUESTIONS.md):

- **Two-stage Wiktionary pass**: the dump is read once; per word title (letters, inner hyphens or apostrophes, or a
  final apostrophe) its language codes and the French section reduced to headings, lines of form and definitions are
  cached (`build-data/cache/frwiktionary-20261001-fr.tsv.gz`, 6.58 million titles, 1.89 million French sections) and
  classified on every build. A title seen with `’` and with `'` (a page and its redirect) keeps the union of its
  languages. Templates are only read as text, never expanded.
- **Page kinds**: pointer pages are French sections whose every sense is a "variante typographique" section, an old or
  wrong spelling ("Ancienne orthographe de", "Graphie ancienne", "Mauvaise orthographe", "Écriture … avec omission du
  signe diacritique", "Variante par contrainte typographique", a variant labelled désuet / vieilli / avant 1835 /
  archaïsme, or a variant marked "(incorrecte)"); the 1990 reform variants (`{{variante orthographique de}}` with
  `{{ortho1990}}`) and other variants stay forms. Letter pages: a "lettre" section or senses that only name a letter
  ("qu"). Elision pages: titles ending in an apostrophe whose French senses define an elision (all 14 prefixes have one;
  "quelqu'" and "y'" are elision pages that are no prefixes). Senses in a `flexion` section are inflections.
- **Coverage and the English dump**: the rule of the Spanish build (the English dump only below 95 %), measured with a
  simplified accent merge (a key counts if a key of its accent class is a French form).
- **Apostrophe tokens**: a cut first part is shared out only if it has two or more letters before the apostrophe
  ("quelqu'", "aujourd'", "presqu'", "entr'"); single letters + apostrophe ("y'", "p'", "o'") follow rule 4.
- **Hyphen tokens**: empty parts ("est-", "-ce") are allowed, every non-empty part must be letters (this reproduces the
  counts of §11.1 exactly). A token without a page of its own becomes the compound "prefix + token" when that is a French
  page ("est-à-dire" → "c'est-à-dire"). A compound with a page keeps the page's spelling ("Jean-Pierre"); the euphonic
  "t" need not be a form.
- **Accent merge** (§11.4 step 1): a key goes to a form with diacritics and ligatures at the same positions and of the
  same length, else to the most frequent form of the class; guards: an English title of the French dump or a name
  (mostly capitalised in running text) only into a form at least as frequent (§11.4), and a spelling the sentence
  sources use at least twice and at least as often as the target (the guard of the Italian build).
- **Dominated spellings** (§11.4 step 2): without the Italian build's subtitle guard; kind (b) needs the same length
  and diacritics at the same positions. Acronyms and names under the key of a fused elision ("CEST", "JAI") become
  class-0 forms; typed in lower case they are case errors.
- **Accent-loss rate** (§11.4 step 3): classes whose accented form has a ligature are left out.
- **Fused elisions** (§11.6 step 7): the elided-phrase counts of every French form of the remainder's accent class are
  summed ("jusqua" → jusqu'à, "cetait" → c'était).
- **Missing-space artefacts** (§11.6 step 6): names and English titles of the French dump are caught first and follow
  step 8; other foreign titles ("etje") do not protect a key.
- **Running-text case evidence** (§11.3): a capitalised token whose first capital lacks its accent ("Etat") counts for the
  form with the accented capital when its own key is no French form.
- **Pairs** (§11.7): rule 2 compares followers whose letters are equal after removing diacritics and expanding ligatures;
  the totals c(prev, ·) include the dropped pairs, and a prefix as follower takes one of the 48 places, as in Italian.
- **Stock names** (§5 step 3): counted on the §5 tokens of the Tatoeba sentences (prefix tokens are no capitalised
  words, apostrophe words are one token); the subtitle share of an apostrophe word uses its whole-word count.

### German rebuild (0.6.4)

05b §3.6 step 6a, the damping of non-German keys and the real-word typo rule. Rebuilding the other five languages with
the changed build code (into a separate directory) gave byte-identical files for en-US, es-ES, pt-BR, it-IT and fr-FR
(word lists, pair tables, licence texts); only their build reports gained the list below.

- **Step 6a** (a non-German key of at most 3 letters needs count ≥ 20 000): 336 keys that
  step 7 kept before are dropped, among them ähm (15268), joe (14326), you (13710), nen (11732), and (10618), jim (9391), of (9163), al (8751), seh (7880), ray (7470), nem (6491), ner (5901), ted (5700), geb (5126), ed (4703), amy (4651), big (4587), roy (4181), yo (3700), bye (3517), mum (3322), huh (3190), for (3064), jay (2920), bud (2394), liz (2219), ned (2191), peg (2023), vic (1765), ian (1719), ike (1698), ooh (1686), sid (1557), sdi (1425), oz (1375), wei (1374), uld (1327), jax (1123), joy (1024), ctu (957) … (full list `shortKeysDroppedByStep6aList`). Kept: dad (46 472),
  the (28 674), wär (23 099). "zi" (96) is not reached: "Zi" (abbreviation of "Zimmer") is a German page, so the key
  is a German form (step 4); the real-word typo rule below gives it class 0 (main form "zi", share 0.56; "zu" one key
  aside).
- **Damping**: 9228 keys kept by step 7 count with count / 100 for their classes; S and maxCount are
  unchanged (the most frequent key is German). The 50 most frequent: dad (46472), the (28674), captain (27491), jack (25967), wär (23099), mike (15058), mary (13042), colonel (10446), james (10388), euren (10288), yeah (10043), detective (9333), jimmy (8905), billy (8897), tony (8834), lieutenant (8811), danny (8723), charles (8532), eurer (7961), jane (7863), clark (7724), johnny (7562), eddie (7172), claire (7090), ryan (6876), steve (6726), chuck (6479), dean (6400), commander (6353), amanda (6306), roger (6221), carter (6213), emily (6207), louis (6065), eurem (6037), lily (5937), jake (5835), scott (5658), officer (5576), andy (5575), robin (5524), pete (5416), joey (5299), fuck (5233), jenny (5231), lois (5221), eric (5179), chief (5176), elizabeth (5084), jerry (5016). With the class formula, the 5 641 damped
  keys counted below 100 times get class 0 and, since German hides class-0 forms (0.6.4), are valid but no longer
  offered. In the pair table a pair whose follower is damped counts with weight / 100;
  the total c(prev, ·) is a count of the previous word and stays undamped (spec/QUESTIONS.md).
- **Step 6** (glued German forms) is still not applied (it never was; spec/QUESTIONS.md): applied as written it would
  drop 613 keys, mostly names that split into two short German forms ("jane" = ja + ne, "dean" = de + an).
- **Real-word typos**: a German-form key gets class 0 for all its forms when its subtitle count is below 100, its most
  frequent form (largest share of the case decision, §3.4; on a tie the first in case-decision order) is lower case
  (nouns, names, acronyms and abbreviations whose main form is capitalised are never hit), and a one-key-aside
  substitution (touch-model neighbours of the German letter page, proximity ≤ `NEAR_MAX` at the key centres; a letter
  without a key sits on its base letter's key) or one swap gives a German-form key at least 5000 times as frequent:
  115 keys, all listed here (count → dominating key, count): ibn 97 → bin 576589; zi 96 → zu 1424706; ak 92 → an 627600; os 87 → so 813903; bim 77 → bin 576589; aux 68 → auf 881418; cas 67 → das 3122198; sp 62 → so 813903; dh 58 → du 2947020; hon 56 → von 706490; bab 50 → hab 252697; dis 50 → die 2484854; rs 49 → es 2303025; mos 38 → los 242543; sus 33 → aus 425184; biet 29 → bist 381498; gor 29 → vor 247840; heure 29 → heute 154162; rann 28 → dann 429424; eich 25 → dich 688780; jein 24 → nein 641479; seim 21 → sein 440142; sann 20 → dann 429424; zag 19 → tag 103559; lb 17 → ob 97001; fas 16 → das 3122198; anch 14 → nach 326208; meinet 13 → meiner 86264; sichre 13 → sicher 145360; lein 12 → mein 384765; machet 11 → machst 67926; neide 10 → beide 53120; ces 9 → des 169182; germ 9 → gern 52147; bessre 7 → besser 92556; dorr 7 → dort 79951; gehr 7 → geht 290919; kh 7 → oh 284455; letzet 7 → letzte 44246; lieft 7 → liegt 36313; ons 7 → ins 80529; ud 7 → du 2947020; gei 6 → bei 277087; hag 6 → hat 799017; könnet 6 → könnte 117870; sollet 6 → sollte 98826; abre 5 → aber 854492; blad 5 → bald 42834; disse 5 → diese 208607; dz 5 → du 2947020; gele 5 → geld 98052; hakten 5 → halten 51690; nagst 5 → angst 67249; neine 5 → meine 420280; netzt 5 → jetzt 435411; nir 5 → mir 1019451; seif 5 → seit 78517; seihen 5 → seinen 50311; sohl 5 → wohl 100488; tos 5 → tod 34408; vot 5 → vor 247840; dünge 4 → dinge 42560; fag 4 → tag 103559; fier 4 → vier 31390; fiesem 4 → diesem 74234; petzte 4 → letzte 44246; seih 4 → sein 440142; seihst 4 → siehst 51444; seiht 4 → sieht 75968; watten 4 → warten 56911; weitre 4 → weiter 79598; wiest 4 → wirst 83791; wollet 4 → wollte 148643; allel 3 → allem 22790; altre 3 → alter 32101; basse 3 → hasse 15536; beaten 3 → besten 25164; eina 3 → eins 42157; einet 3 → einer 202097; fachte 3 → dachte 92867; geig 3 → zeig 15162; geit 3 → zeit 160393; geren 3 → gerne 38739; himmle 3 → himmel 17755; laxen 3 → laden 15400; leiben 3 → lieben 22407; röten 3 → töten 42735; scheibt 3 → scheint 26026; scheid 3 → scheiß 21719; weid 3 → weiß 357869; weiger 3 → weiter 79598; atzt 2 → arzt 19080; beschichte 2 → geschichte 33070; beschütten 2 → beschützen 10577; einzöge 2 → einzige 37724; erriechen 2 → erreichen 10556; fehlet 2 → fehler 22947; gerb 2 → gern 52147; hagt 2 → habt 66045; künd 2 → kind 50922; lassem 2 → lassen 131393; liebem 2 → lieben 22407; lieht 2 → liegt 36313; nist 2 → bist 381498; petzten 2 → letzten 34029; safen 2 → sagen 236702; schöbe 2 → schöne 16144; tuet 2 → tust 26635; vergrauen 2 → vertrauen 18459; verstehet 2 → verstehst 19139; wünschet 2 → wünschte 12812; zage 2 → tage 29111; zause 2 → hause 73059; zue 2 → zur 115474; übet 2 → über 196855.
- **Kept 2–3-letter keys that are no form of the language** (report only, `shortKeysNotForms`; a form = German / Spanish /
  Portuguese / Italian / French Wiktionary form, English = SCOWL key): de-DE 3 keys; most frequent: dad (46472), the (28674), wär (23099); en-US 8 keys; most frequent: ohh (26185), erm (14625), im (6553), ive (993), lol (867), omg (557), ily (188), itd (13);
  es-ES 685 keys; most frequent: joe (45636), tom (43486), jim (27653), amy (19799), ted (13688), tim (12307), roy (9331), ian (7992), lou (7704), liz (7187), vic (4958), ias (4613), sid (4547), meg (4422), eve (4386); pt-BR 864 keys; most frequent: sam (60584), max (36139), fbi (27285), amy (23627), huh (17350), ed (14455), ted (14177), mac (10695), pré (10582), ooh (10010), roy (9655), lou (9325), gus (7669), doc (7603), vic (5651); it-IT 677 keys; most frequent: sam (33675), joe (24476), jim (15377), amy (13630), ted (9437), sig (8992), huh (5998), ian (5311), gus (5255), ron (5122), bla (4740), liz (4444), lou (4427), uhm (3852), eun (3508); fr-FR 131 keys; most frequent: ooh (7428), ohh (2236), déj (2021), jae (1743), iui (1736), axl (1174), hwa (630), atf (584), ème (568), ctu (550), ryu (509), jeb (465), uhh (443), unh (421), fda (419).

### German rebuild (0.6.5)

05b §3.7 "Dictionary-only German forms": every German form of the German Wiktionary (titles with a German section and
the single-token forms of the German overview tables, §3.2) whose key never occurs in the subtitle list (count 0) and
consists of 2–27 letters of the §3.1 alphabet is added with **class 1** for each form of the §3.4 case decision (no
damping). Steps 1–8, the damping and the real-word typos are computed before and are unchanged (9228 damped keys, 115
real-word typos, 336 short keys dropped by step 6a, identical lists). The other languages were not rebuilt; their build
code and files are unchanged.

The build writes both variants of the rule (`dictionaryOnlyForms` in dictionary-build.json; the lemma variant needs a
second pass over the German dump, cached as `build-data/cache/dewiktionary-20261001-pos.tsv.gz`: the parts of speech
`{{Wortart|…|Deutsch}}` of every German section; a lemma is a title with a part of speech other than an inflected form
– Deklinierte Form, Konjugierte Form, Dekliniertes Gerundivum, Komparativ, Superlativ, Partizip I, Partizip II,
Erweiterter Infinitiv):

| | keys | added keys | forms | pairs | `de-DE.words.gz` | `de-DE.pairs.gz` |
|---|---:|---:|---:|---:|---:|---:|
| before (0.6.4) | 236 223 | – | 241 563 | 558 993 | 962 464 | 1 850 717 |
| all forms (**used**) | 857 274 | 621 051 | 864 361 | 566 548 | 1 936 431 (+973 967) | 1 942 877 (+92 160) |
| lemmas only | 306 019 | 69 796 | 311 950 | 563 134 | 1 282 788 (+320 324) | 1 879 141 (+28 424) |

The word list grows by 0.97 MB < 1.5 MB, so all forms are added. Samples of added keys: Aabenraas, auskochtest,
deportier, festgetretenes, herangenaht, kompromisslosesten, Nauruerin, Rückenschwimmens, thürmst, vorstossendem,
performant. The pair table changes too, because the pair sources (Tatoeba, Common Voice, §5) now count the new keys:
"vielen" has more counted followers, so after "Vielen " "anderen" costs 53 and "Fällen" 55 (52 and 54 before, order
unchanged) and "Das" at the text start 50 (49); all other German worked examples are unchanged. Memory: the German tree
has ≈ 1 781 000 nodes (717 000); the keyboard with de+en peaks at 87.3 MB of Dalvik heap on the emulator (63.0 MB
before), de+en+fr at 101.2 MB (78.0 MB), see APP.md and spec/QUESTIONS.md.

### German rebuild (0.6.6): lemmas only

05b §3.7 now adds only **lemmas** (the lemma definition above), and also lemmas whose key occurs **exactly once** in the
subtitle list (step 4 drops keys counted once). Inflected forms without a subtitle occurrence are no longer added. Steps
1–8, the damping and the real-word typos are unchanged; the other languages were not rebuilt (their files are
byte-identical, checked with SHA-256). Report `dictionaryOnlyForms` in dictionary-build.json:

| | keys | added keys | forms | pairs | `de-DE.words.gz` | `de-DE.pairs.gz` |
|---|---:|---:|---:|---:|---:|---:|
| before (0.6.4) | 236 223 | – | 241 563 | 558 993 | 962 464 | 1 850 717 |
| 0.6.5, all forms | 857 274 | 621 051 | 864 361 | 566 548 | 1 936 431 | 1 942 877 |
| **0.6.6, lemmas (unseen or seen once)** | 320 804 | 84 581 (69 796 unseen, 14 785 seen once) | 326 931 | 565 578 | 1 335 116 (+372 652) | 1 889 786 (+39 069) |

Samples of added keys (count in the subtitles, stored forms): Abfertigungsdienst 0, Altertümlichkeit 1, bauseitig 0,
bosseln 1, Fazilität 0, Finanztransaktionssteuer 0, Hauptkonkurrentin 1, Kagran 1, manufakturieren 0, nachwirken 1,
performant. The German tree has ≈ 1 089 000 nodes. Next-word costs: after "Vielen " anderen 52 and Fällen 54 (the values
of 05 §17.7 again), "Das" at the text start 50 (05 §17.7: 49). Memory on the emulator: de+en 68.1 MB of Dalvik heap at
the peak (0.6.5: 87.3, 0.6.4: 63.0), de+en+fr 81.5 MB after a swipe (0.6.5: 101.2, 0.6.4: 78.0); APP.md.

### Spanish, Portuguese, Italian and French rebuild (0.6.7): short-key guard and damping

05b §3.6 "Other languages": the Spanish, Portuguese, Italian and French builds apply **step 6a** (a key of at most 3
letters that is no form of the language is kept only with a count ≥ 20 000) and the **damping** (a key kept only by the
count rule — §8.5, §9.5, §10.5, §11.6, the rule of German §3.6 step 7 — counts with count / 100 for its classes and for
the pair table; the pair total c(prev, ·) stays undamped, as in German). The real-word typo rule stays German only, the
English build is unchanged. Implementation: `_count_rule`, `effective_count` and the `selected` pass in each
`build_*.py` (S and maxCount are recomputed after the damping, `common.damped_max`), `common.SHORT_KEY_LEN`,
`SHORT_KEY_MIN`, `DAMPING`, and the `damped` argument of `PairCounter`, `ItalianPairCounter` and `FrenchPairCounter`.
A key counts for step 6a only if the count rule would have kept it (keys dropped as foreign words or as too rare keep
their reasons). Step 6a counts letters (Italian keys without the apostrophe of a truncated word, which are forms anyway).

Rebuilding de-DE and en-US with the changed build code gave byte-identical files and identical report entries. Every
key that disappeared is one of the step-6a keys, every key whose classes changed is a damped key (all lower, forms
unchanged), and S and maxCount are unchanged in all four languages (their most frequent key is a form). Reports in
`tools/dictionary-build.json` (`<tag>-extra`: `shortKeysDroppedByStep6a`, `shortKeysDroppedByStep6aList`, `dampedKeys`,
`dampedKeysClass0`, `dampedTop50`).

| | es-ES | pt-BR | it-IT | fr-FR |
|---|---:|---:|---:|---:|
| keys dropped by step 6a | 682 | 860 | 675 | 128 |
| damped keys (of them class 0: count < 100) | 21 866 (12 955) | 27 130 (15 573) | 14 948 (8 516) | 8 813 (5 831) |
| S (before = after) | 15.466736 | 15.429650 | 16.294616 | 15.988646 |
| keys | 247 985 → 247 303 | 173 200 → 172 340 | 185 711 → 185 036 | 174 316 → 174 188 |
| forms | 250 743 → 250 033 | 174 752 → 173 892 | 188 355 → 187 642 | 179 237 → 179 108 |
| pair previous words | 38 819 → 38 606 | 21 100 → 21 077 | 37 566 → 37 329 | 41 447 → 41 423 |
| pairs | 385 432 → 383 339 | 279 747 → 278 624 | 405 570 → 403 156 | 490 266 → 489 933 |
| elided pairs (prefix as previous word) | – | – | 6 593 → 6 512 | 14 473 → 14 454 |
| words file, bytes | 817 685 → 807 883 | 593 768 → 581 994 | 618 669 → 610 575 | 610 829 → 607 736 |
| pairs file, bytes | 1 221 224 → 1 212 027 | 864 062 → 859 472 | 1 285 257 → 1 274 209 | 1 510 396 → 1 509 332 |

Dropped by step 6a, most frequent 30 (count):
- es-ES: amy 19799, ted 13688, tim 12307, roy 9331, ian 7992, lou 7704, liz 7187, vic 4958, ias 4613, sid 4547, meg 4422,
  eve 4386, jon 4338, hal 4054, raj 3770, dee 3672, wes 3540, hee 3086, jae 3061, joo 2645, wu 2464, stu 2291, bam 2263,
  yi 2003, kev 1987, umm 1794, yeo 1794, jed 1738, uld 1657, axl 1637 (also etc 984, xxi 867). Kept (≥ 20 000): joe,
  tom, jim (damped).
- pt-BR: huh 17350, ed 14455, ted 14177, mac 10695, pré 10582, ooh 10010, roy 9655, lou 9325, gus 7669, doc 7603, vic
  5651, ned 5378, sid 4829, bud 4701, aah 4688, jon 4459, eve 4455, jan 3868, woo 3846, wes 3645, ohh 3586, ahh 3545, deb
  3430, ivy 2915, oz 2906, kay 2875, bam 2647, hop 2610, hoo 2566, ahn 2550. Kept: sam, max, fbi, amy (damped).
- it-IT: jim 15377, amy 13630, ted 9437, sig 8992, huh 5998, ian 5311, gus 5255, ron 5122, bla 4740, liz 4444, lou 4427,
  uhm 3852, eun 3508, vic 3155, pat 3112, jen 3030, sid 2688, jan 2412, tae 2304, jae 2282, kay 2255, axl 2198, ivy
  2180, seo 1973, soo 1970, aah 1949, nsa 1897, hee 1816, oz 1813, stu 1698. Kept: sam, joe (damped).
- fr-FR: ooh 7428, ohh 2236, déj 2021, jae 1743, iui 1736, axl 1174, hwa 630, atf 584, ème 568, ctu 550, ryu 509, jeb
  465, uhh 443, unh 421, fda 419, tov 416, rox 376, aww 334, yui 319, irv 292, tzu 284, yul 275, qa 264, arr 252, zhi
  248, kui 242, hiv 241, qiu 234, miu 216, yeo 215. No short non-form keeps a count ≥ 20 000.

Damped keys, most frequent 50 (count):
- es-ES: joe 45636, michael 45435, tom 43486, george 42792, peter 36122, harry 30140, mary 28981, jimmy 28157, jim
  27653, danny 27110, steve 26343, sarah 25543, tommy 24312, billy 23456, johnny 23436, eddie 22208, robert 21895,
  chris 21747, ryan 21006, jake 20211, bobby 19822, eric 17896, jerry 17189, thomas 17038, andy 16833, rachel 16817,
  scott 16338, emily 15784, jeff 15706, kelly 15490, annie 14317, clark 14288, carl 14238, claire 14117, kyle 13939,
  roger 13425, luke 13424, alice 13182, lucy 13177, josh 13079, susan 13030, phil 12965, jenny 12409, taylor 12262,
  joey 12148, morgan 11909, hank 11844, arthur 11713, julie 11629, maggie 11590.
- pt-BR: srta 68973, sam 60584, michael 51930, charlie 46394, george 44807, mike 43894, james 37499, peter 37472, max
  36139, york 33641, mary 32818, steve 31411, sarah 29654, danny 29642, jimmy 29234, alex 29001, jane 28718, fbi 27285,
  billy 26207, richard 25220, johnny 24665, jake 24000, amy 23627, eddie 23265, chris 23202, robert 22523, bobby 21459,
  martin 20310, matt 20159, eric 19911, brian 19570, rachel 19215, scott 19137, kelly 19047, simon 18396, larry 18323,
  jeff 18115, kate 17781, thomas 17762, smith 17270, josh 16602, emily 16550, andy 16475, dave 16238, luke 15902, carter
  15619, emma 15502, claire 15452, kyle 15261, annie 15097.
- it-IT: sam 33675, charlie 26128, mike 25758, joe 24476, james 21763, jane 20009, danny 18788, sarah 18618, jake 17148,
  jimmy 16613, steve 15344, ryan 13986, billy 13615, richard 13478, kevin 12887, johnny 12726, kate 12295, chris 12245,
  brian 11829, charles 11784, angeles 11783, scott 11679, rachel 11674, bobby 11670, eddie 11213, andy 11212, emily
  11031, josh 10419, pete 10258, jason 10211, thomas 10156, annie 9818, luke 9544, simon 9468, lucy 9227, dave 8907,
  hank 8895, jeff 8809, carl 8802, sean 8654, william 8523, larry 8415, kyle 8289, phil 8210, jerry 8114, louis 8080,
  jenny 7893, clark 7880, alan 7836, gary 7743.
- fr-FR: angeles 8200, abby 8108, harvey 5546, jess 5222, casey 5063, gibbs 4946, randy 4892, simpson 3663, judy 3646,
  willie 3464, neal 3205, freddy 3161, mcgee 3115, nicky 3093, benny 3081, doyle 2902, grayson 2755, avery 2752, polly
  2725, beverly 2624, boyd 2619, scotty 2592, becky 2566, whoo 2518, flynn 2507, dixon 2461, buffy 2443, tess 2413,
  brennan 2373, brett 2364, dwight 2353, mikey 2345, artie 2336, darrin 2327, spock 2288, peyton 2233, starfleet 2164,
  shelby 2074, wally 2072, stevie 2046, carlo 2041, sherlock 1996, hyun 1953, bridget 1951, scully 1938, evelyn 1929,
  phillip 1908, susie 1872, fitz 1858, vicki 1856 (most French first names are forms of the French Wiktionary and are
  not damped).

The stock-name rules (§5 step 3) are unchanged: computed with the damped subtitle counts they give the same lists in
all four languages (`stockNamesChangedByDampedCounts` empty). French alias collisions (§11.5) still compare the
undamped counts; none changed.

| File (0.6.7 after step 6a and the damping; superseded by the abbreviation rule below) | Bytes | SHA-256 |
|---|---:|---|
| `es-ES.words.gz` | 807 883 | `770c687889c18d38a714b7cbe4ae04d4f41acd6fb9f1a2a60379d1270e1983ca` |
| `es-ES.pairs.gz` | 1 212 027 | `3de4037d32cb665a8cc1ec2d4c39c25a75134b8ad791fe80aa7bc976759b7dd0` |
| `pt-BR.words.gz` | 581 994 | `bc1663bcbb3d443d431f08146607f7e90efeef5f69b75afec0f0ac2fd1f27436` |
| `pt-BR.pairs.gz` | 859 472 | `430433b57e7e74ddebe893040e8d152f5403233c569ad3a83691fb9aab8b3323` |
| `it-IT.words.gz` | 610 575 | `82b4dc45a99f0e2ef361da782e996b3b48aa33070094eb9bc13532fc0ad56044` |
| `it-IT.pairs.gz` | 1 274 209 | `b9c791e6b66fac58f9f780dfe6537b56aa970ee321954a08e03cfa50b75e238e` |
| `fr-FR.words.gz` | 607 736 | `0791a33351d80bd2d1d8148292bd643b3e40cb24d3570ec86cc3d8f446654cb6` |
| `fr-FR.pairs.gz` | 1 509 332 | `c1de92d4f1596316ddef535f54ffe5ebd144aedad726c04a00d65be541789706` |

Word-list payload SHA-256 (superseded): es-ES `06685a4b…6a7aa8`, pt-BR `52bf87c4…268e96`, it-IT `a3f8218c…f9b0ce`, fr-FR
`a5de18a0…b181a7`. Together the four languages shrink by 60 KB; the release APK is 14.99 MB (0.6.6: 15.0 MB). All
worked examples of 05 §17.8–§17.11 and 06 keep their outcomes; Italian "perch" is no longer autocorrected to the name
"Percy" (class 124 → 49), so "perché" stands first again as 05 §17.10 example 7 originally expected (spec/QUESTIONS.md).

### Abbreviation titles and prefix compounds (0.6.7, all languages)

05b §3.6 "Abbreviation and affix titles", as decided for 0.6.7 (spec/QUESTIONS.md): a Wiktionary page of the language
whose title is letters followed by a **period** ("usw.", "etc.", "sig.") makes the key without the period a **form**,
with the title's case: kept from count ≥ 2, never dropped by step 6a, never damped; in German also a §3.7
dictionary-only lemma when the title's German section has a lemma part of speech ("Abkürzung" is one). A title ending
in a **hyphen** ("anti-", "sub-") is a prefix: it is **no form** in any language, but in German it may be the first part
of a compound (§3.5, count ≥ 20, not a function word). `tools/dictbuild/wiktionary_affix.py` reads each dump once more
and caches both kinds of titles per language section (`build-data/cache/<dump>wiktionary-20261001-affix.tsv.gz`; de
1 957, es 313, pt 338, it 262, fr 1 404, en 6 658 titles; the English dump's Spanish, Portuguese, Italian, French and
English sections). **English guard**: an abbreviation title makes no form when its key is among the 5000 most
frequent keys of the pinned English FrequencyWords list (`en_full.txt`, line order, lower-cased, each key once;
`ENGLISH_GUARD_RANK` in `build_dictionaries.py`); such keys stay under their build's count rule (step 6a, damping).
The forms count only for the dumps a build uses (es, pt, it: own dump and English dump; fr: French
dump; de: German dump). An abbreviation page is no inflection page. Report (`<tag>-extra`): `abbreviationTitles`,
`abbreviationKeys`, `abbreviationKeysInWordList`, `abbreviationKeysTop60`, `englishGuardKeys`, `englishGuardKeysList`;
de also `abbreviationKeysDictionaryOnly`,
`prefixParts`, `keysByPrefixCompounds`, `keysByPrefixCompoundsTop60`.

**English is not changed** (decided): the English build uses no Wiktionary (§4.1; a form of English is a SCOWL key, and
SCOWL's abbreviation lists already give "etc", "vs", "approx" … without the period). en-US stays byte-identical.

| | de-DE | es-ES | pt-BR | it-IT | fr-FR |
|---|---:|---:|---:|---:|---:|
| abbreviation forms of the used dumps (after the guard) | 1 285 | 161 | 87 | 61 | 164 |
| keys kept from being forms by the English guard | 18 | 5 | 5 | 7 | 1 |
| keys that are forms only by this rule | 895 | 100 | 46 | 40 | 76 |
| of them in the word list | 890 (657 dictionary-only lemmas) | 77 | 40 | 28 | 53 |
| keys kept by step 5 with a prefix as first part | 306 (of 559 prefix titles) | – | – | – | – |
| keys dropped by step 6a | 336 → 326 | 682 → 670 | 860 → 847 | 675 → 671 | 128 → 126 |
| damped keys | 9 228 → 9 176 | 21 866 → 21 863 | 27 130 → 27 124 | 14 948 → 14 947 | 8 813 → 8 812 |
| keys | 320 804 → 322 189 | 247 303 → 247 380 | 172 340 → 172 374 | 185 036 → 185 063 | 174 188 → 174 238 |
| forms | 326 931 → 328 429 | 250 033 → 250 115 | 173 892 → 173 935 | 187 642 → 187 677 | 179 108 → 179 163 |
| pair previous words | 54 840 → 54 866 | 38 606 → 38 618 | 21 077 → 21 078 | 37 329 → 37 330 | 41 423 → 41 429 |
| pairs | 565 578 → 565 691 | 383 339 → 383 400 | 278 624 → 278 635 | 403 156 → 403 163 | 489 933 → 489 958 |
| words file, bytes | 1 335 116 → 1 339 994 | 807 883 → 808 139 | 581 994 → 582 110 | 610 575 → 610 682 | 607 736 → 607 867 |
| pairs file, bytes | 1 889 786 → 1 890 080 | 1 212 027 → 1 212 411 | 859 472 → 859 184 | 1 274 209 → 1 274 931 | 1 509 332 → 1 508 320 |

(Left: German as in 0.6.6, the other languages after step 6a and the damping above.) S and maxCount are unchanged in
every language; no key is removed.

Keys that became forms by the abbreviation rule, most frequent 60 in the word list (subtitle count):
- de-DE: geb 5126, finn 1760, oz 1375, ling 1232, ver 765, jr 644, jem 561, mill 445, jt 405, jun 330, sek 317, tel
  257, wi 254, bret 228, jh 190, ev 185, ie 180, mme 180, frl 178, isl 165, qu 158, osman 156, aug 150, med 148, ew
  145, hor 135, dim 125, aram 122, engl 108, gt 101, hd 93, christl 87, merc 81, hl 72, fig 69, gef 68, ltd 65, nom
  64, bj 61, mech 56, argent 51, breton 51, etc 50, stud 50, psych 49, uvm 49, ung 48, rev 46, usw 46, aran 44, bez
  43, prot 42, anh 41, ehem 41, ged 40, verf 40, frz 39, gesch 38, vert 38, bem 37. The everyday abbreviations **usw**
  (count 46, class 63), **bzw** (38), **etc** (64), **evtl** (26) and **vgl** (11) are German keys; **ggf** (counted
  at most once) is a dictionary-only lemma with class 1.
- es-ES: jr 2428, der 1331, tte 1306, vd 1122, etc 984, ár 856, dra 703, cnel 469, sta 449, mts 384, br 327, dim 294,
  esp 264, sto 261, fr 214, cp 207, ant 201, prof 166, gto 144, hna 137, ing 125, lat 111, sup 102, mich 93, pte 93,
  rep 87, arg 79, conf 74, pl 72, frs 71, lda 71, sds 70, int 68, dud 67, cdo 58, tít 55, hno 52, inf 49, ec 48, cda
  44, atte 37, hnos 32, drs 31, mús 31, clás 27, sma 27, cfr 25, aum 24, pret 24, cdos 23, ext 19, obs 16, pers 14,
  admón 13, hch 12, malson 12, adj 11, adv 11, irl 11, sust 11. **etc** has class 107.
- pt-BR: srta 68973, jr 4204, jan 3871, dra 1225, jd 1225, jun 1225, vs 933, séc 461, ltda 279, prof 223, sm 199, sto
  133, lda 129, srtas 122, obs 114, eng 104, univ 82, bras 81, arq 77, nov 73, ref 72, vd 64, lg 63, jul 57, hb 51,
  pág 46, corr 42, fev 41, aprox 36, feat 34, enf 29, abr 28, págs 19, inf 15, adv 10, prov 10, enus 7, adj 3, subst
  3, accel 2. "pré" (a prefix title only) stays dropped by step 6a, "doc" too (no abbreviation page); **srta** is no
  longer damped.
- it-IT: sig 9129, oz 1813, dott 210, ecc 188, str 169, arch 164, tr 132, rag 110, cb 93, vc 75, cav 68, fl 64, vn 64,
  ing 53, vl 51, fam 37, ob 29, geom 28, cfr 27, avv 22, prov 19, arp 15, vla 13, agg 10, timp 8, cpv 6, vlc 5, spett
  2. **sig** has class 142.
- fr-FR: jr 1580, mar 512, acc 237, ant 229, litt 213, arbor 158, ven 114, dat 101, cic 98, virg 96, sc 95, colum 83,
  kr 66, voy 56, oct 51, rr 51, aus 47, tél 44, env 42, nov 41, rrr 39, mde 34, aug 33, rrrr 31, hor 30, resp 26,
  lucan 19, feat 16, pomp 16, varr 14, vv 14, arat 13, caes 12, bibl 10, éd 10, atil 8, acro 6, janv 6, ov 6, anth 5,
  ovid 5, abr 4, afran 4, auj 4, amph 3, andr 3, cuil 3, févr 3, abrév 2, avian 2, boet 2, juill 2, lun 0 ("etc" was a
  French key before).

Keys the English guard keeps from becoming forms (subtitle count; then the count rule decides): de ed 4703, bay 1700,
pat 1495, got 1429, get 1361, iron 675, part 488, rip 477, chin 463, mind 349, slow 95, mask 38, dial 33, erm 29, lit 26,
korean 6 (all dropped by step 6a or as too rare), indian 201 and math 56 (kept, damped: Indian class 11, Math class 0);
es it 15113, dr 4046, art 2363, port 835, part 403; pt st 11708, out 6722, part 829, ago 283, cast 107; it all 6281, on
6051, art 1756, dr 1404, sec 433, id 344, cost 65; fr african 40 (all dropped). German "Dr", "St" and "Art" stay keys:
they are German forms through ordinary pages. With them the German compound rule loses 33 keys whose first part was
such an abbreviation (bayfront, mastermind, flatiron, ripcord …). Prefix titles (ending in "-") made the earlier forms
of, al, phil, meg, in, em, re, pré, is, sex … (0.6.7 decision, spec/QUESTIONS.md); they are no forms.

German prefix compounds (§3.5 with a prefix as first part), most frequent 30: subcentral 1101, travers 244, monkeys 140, message 123, millicent 121, kinsella 116, retrovirus 105, halbach 103, nanosonden 97, psychospielchen 74, tralala 60, kintaro 59, subunternehmer 58, archangel 52, monsignor 52, andromache 50, trading 46, messire 43, antigone 42, phillippe 39, subatomare 39, triangle 39, hypospray 38, antivirus 36, mesenburg 36, subroutinen 36, antiserum 35, entleerer 32, subatomaren 32, halbachs 31 … Besides real compounds
("subunternehmer", "antivirus", "retrovirus", "nanosonden") the rule also keeps undamped names and English words whose
beginning is a prefix ("millicent", "halbach", "monsignor", "message", "triangle"). The real-word typo rule finds 126
keys instead of 115 (new: apl, dor, fem, hist, hom, iber, ide, ind, jur, unf, zz); the dictionary-only lemmas grow
from 84 581 to 85 243.

| File (0.6.7, final) | Bytes | SHA-256 |
|---|---:|---|
| `de-DE.words.gz` | 1 339 994 | `c11040f083d695160db6a6393aad349fc99303405e89172473f1463fdde247e9` |
| `de-DE.pairs.gz` | 1 890 080 | `1bea862b76e30f1e1c0355c77704e129f2ebf913b364a88810bcafe8cc3ed6f8` |
| `es-ES.words.gz` | 808 139 | `6139c4cf3ecedf82d3d3375e7c4e6e5932c9a43d6705985af45344558756c74d` |
| `es-ES.pairs.gz` | 1 212 411 | `bf82166a0415ae233c2fa4eeb928a813b4ffa258ae319ee6a37a05f9f4415381` |
| `pt-BR.words.gz` | 582 110 | `2d9aa4a012e526c2a3053fa860022b68313edaf2ef5682b7f7a5f70ad92c11e4` |
| `pt-BR.pairs.gz` | 859 184 | `8c35ad2bbec8eba24412dde6066159e8125e6bcd9673ebd3de4645d6b7ef7b4b` |
| `it-IT.words.gz` | 610 682 | `358611e5a3d321e8f92d99969932c77a2f2750adc35b87b45fc60a34699e725e` |
| `it-IT.pairs.gz` | 1 274 931 | `6788225dcd29ec4657394c6b54be9b613a852bfbede1e85b077ba3aefc215641` |
| `fr-FR.words.gz` | 607 867 | `367b9bd01a34dea5bf367c55186d07367ead3f3c1a61f850d681e7f1dc3cea64` |
| `fr-FR.pairs.gz` | 1 508 320 | `290c1b2f6b48bbdbc6ee8737c1a3a8bc9e8db0bd1b70c0e3ecf0c840604de567` |

Word-list payload SHA-256: de-DE `8e2af5f8…b70c20`, es-ES `d8712106…fc3970`, pt-BR `b6920706…4cde6c`, it-IT `4e3750e4…f34ff1`, fr-FR `08794237…db1f7e`; en-US unchanged (byte-identical). Release APK 14 995 690 bytes (0.6.6: 15.0 MB).
The JVM memory test retains the same within 0.4 MB, except fr+de+it 56.7 MB (+1.1 MB against the state before the
abbreviation rule, reproducible; not analysed further, within its 68 MB heap). All worked examples are unchanged
(German "Das" at the text start still 50, after "Vielen " anderen 52, Fällen 54); printed test values that changed:
"zj" lists "vj" (abbreviation "vj.") as fourth correction instead of "ZK", the Italian elided shape index has 6 506
entries (6 501).

### Umlaut merge (0.6.9, German) and unattested keys (0.6.9, all languages with a count rule)

**Umlaut merge** (05b §3.6, `build_de.GermanBuilder._merge_umlauts`, before steps 1–8): a subtitle key that is no
German form and is a German form with one or more of its umlauts written without the dots or ß written as "ss" (any
number of positions, nothing else changed; `lacks_umlauts`) is removed when that form is counted at least 10 times as
often (`UMLAUT_MERGE_RATIO`); its count is added to the form (the most frequent qualifying form; ties by text). German
forms are never merged ("schon"/"schön", "zahlen"/"zählen", "dass"/"daß" stay). As in the Spanish accent merge (§8.4) the
merged key's pairs are not carried over. 2 622 keys are merged (most of them below the count rule and never in the word
list; 116 were keys of the word list, 26 of them damped); S and maxCount are unchanged. Report (`de-DE-extra`):
`umlautMerged`, `umlautMergedTop100` (merged key and count → form and its count before the merge). The 100 most
frequent: fur 1319 → für 735252, uber 463 → über 196855, konnen 447 → können 240905, mussen 322 → müssen 191257, konnt 289 → könnt 20255, zuruck 228 → zurück 133326, gehort 160 → gehört 89459, madchen 160 → mädchen 72685, tur 148 → tür 41457, naturlich 145 → natürlich 82710, holle 128 → hölle 21656, dafur 124 → dafür 75302, spater 120 → später 44320, lowen 109 → löwen 2132, hande 93 → hände 22645, ruber 90 → rüber 11953, fraulein 84 → fräulein 6438, verruckt 72 → verrückt 38126, nachste 69 → nächste 22714, uberhaupt 69 → überhaupt 35566, erzahlen 64 → erzählen 29301, funf 59 → fünf 33424, vollig 56 → völlig 20317, daruber 55 → darüber 51711, durfen 52 → dürfen 23930, geschaft 52 → geschäft 15254, erzahlt 51 → erzählt 33821, konig 51 → könig 20785, moglich 50 → möglich 25388, brucke 49 → brücke 7012, walder 46 → wälder 927, bose 45 → böse 16940, nachsten 43 → nächsten 22247, backer 39 → bäcker 451, romer 39 → römer 1313, uberall 39 → überall 21161, erklaren 38 → erklären 18526, glucklich 38 → glücklich 25915, kapitan 38 → kapitän 5200, klart 38 → klärt 395, buro 37 → büro 22253, fruh 37 → früh 24189, horner 37 → hörner 576, standig 36 → ständig 13119, getotet 35 → getötet 31223, hore 35 → höre 16609, druben 33 → drüben 17704, buros 31 → büros 863, fruher 31 → früher 24599, offnen 31 → öffnen 10809, uberraschung 31 → überraschung 9952, haltst 29 → hältst 12043, kummern 29 → kümmern 17618, offne 29 → öffne 3561, tatsachlich 29 → tatsächlich 14483, gefahrlich 28 → gefährlich 14049, hubsch 28 → hübsch 11244, mude 28 → müde 11612, furs 27 → fürs 12485, hangen 27 → hängen 7677, aufhoren 26 → aufhören 19532, handen 26 → händen 8094, namlich 26 → nämlich 10147, notig 26 → nötig 14694, tschuss 26 → tschüss 11927, unmoglich 26 → unmöglich 14555, wahle 26 → wähle 1207, gebaude 25 → gebäude 9086, handler 25 → händler 1111, kampfen 25 → kämpfen 15639, personlich 25 → persönlich 10269, korper 24 → körper 17147, nachstes 24 → nächstes 10971, tater 24 → täter 3984, hundin 23 → hündin 342, morder 23 → mörder 15310, schwore 23 → schwöre 10398, erzahl 22 → erzähl 11759, offnet 22 → öffnet 3847, ungefahr 22 → ungefähr 8567, kuche 21 → küche 9052, kummere 21 → kümmere 9445, mogen 21 → mögen 14944, sanger 21 → sänger 934, schones 21 → schönes 8905, selbstverstandlich 21 → selbstverständlich 5421, stort 21 → stört 5745, bucher 20 → bücher 7147, durft 20 → dürft 2289, ubrigens 20 → übrigens 9753, verandern 20 → verändern 4711, zerstort 20 → zerstört 9755, zurich 20 → zürich 504, fahrst 19 → fährst 5379, blodsinn 18 → blödsinn 5227, foderation 18 → föderation 1223, fuhle 18 → fühle 19176, fuhlen 18 → fühlen 15884, gefuhl 18 → gefühl 18285, lacheln 18 → lächeln 6881.

**Unattested keys** (05 §10.1 step 5): the keys a build kept by its count rule without being a form of the language —
exactly the damped keys of §3.6 step 7 and of the "as German §3.6 step 7" steps of es, pt, it and fr — are flagged in
the word list: bit 7 of the key's form-count byte (`common.write_word_list(unattested=…)`; at most 127 forms per key).
Files written before never set the bit, so they stay readable (`Lexicon.read` masks it; no version change). English has
no count-rule keys of this kind and stays byte-identical. Rebuilt es-ES, pt-BR, it-IT and fr-FR word lists are
identical to 0.6.7 except for the flag bits, their pair tables except for the binding hash (checked by decoding both
versions). `<tag>.unattestedKeys` in `tools/dictionary-build.json`.

| | de-DE | es-ES | pt-BR | it-IT | fr-FR | en-US |
|---|---:|---:|---:|---:|---:|---:|
| unattested (flagged) keys | 9 150 | 21 863 | 27 124 | 14 947 | 8 812 | 0 |
| keys | 322 189 → 322 073 | 247 380 | 172 374 | 185 063 | 174 238 | 163 405 |
| forms | 328 429 → 328 313 | 250 115 | 173 935 | 187 677 | 179 163 | 164 040 |
| words file, bytes | 1 339 994 → 1 342 081 | 808 139 → 811 368 | 582 110 → 585 747 | 610 682 → 612 927 | 607 867 → 610 121 | 519 514 |
| pairs file, bytes | 1 890 080 → 1 890 114 | 1 212 411 | 859 184 → 859 185 | 1 274 931 → 1 274 932 | 1 508 320 → 1 508 324 | 1 927 135 |

German pairs: 54 866 → 54 865 previous words, 565 691 → 565 690 pairs. All files together grow by 13.5 KB.

| File (0.6.9) | Bytes | SHA-256 |
|---|---:|---|
| `de-DE.words.gz` | 1 342 081 | `6c4a9d5e8c89788473a5f07a19b02bf4e23cab74ce6904bfad98a8f753b7f7a3` |
| `de-DE.pairs.gz` | 1 890 114 | `28ac4ed6a42ba3344e9533c7a5f268b50951399b23b53558987d6f7edf50a238` |
| `es-ES.words.gz` | 811 368 | `f39e8681a5b6a5598a49295a0728e14ef94efd80842f89856490bb1191a0b22f` |
| `es-ES.pairs.gz` | 1 212 411 | `6c726f6ab2c9e88a6a04605fdf55e31b01d8cbe2147bdf31f6b0f4ecda2ea80f` |
| `pt-BR.words.gz` | 585 747 | `075e8b8e6789a2b38d638212918334e93181b0a1f277cd0d2838bd438d85ea6c` |
| `pt-BR.pairs.gz` | 859 185 | `d02beecb6fb43db094df4c63c20e30dad58c134df7e6581d09696b0146c6629a` |
| `it-IT.words.gz` | 612 927 | `5247a23bcb62248a51fc20e9351412f16da6d229685cd8542a5f0a7bc829762d` |
| `it-IT.pairs.gz` | 1 274 932 | `4f340a0edec198039bc55cdda9825ec7105affd787424ce635bd9593a73a4d76` |
| `fr-FR.words.gz` | 610 121 | `08355554e7ee0465071acea751384951ae7a9197edc0b2202371b5737a6c8bb8` |
| `fr-FR.pairs.gz` | 1 508 324 | `731144a88eefd06b8c6683a195883f56d697383593242243a29497ed2874ead4` |

en-US unchanged. The German rebuild without the merge and the flags reproduced the 0.6.7 files byte for byte.

### 0.7.0 build: spelling dictionaries, written-language frequencies, colloquial path (05b §12)

Implementation (`tools/dictbuild/`): `spellcheck.py` (spelling-dictionary membership of 05b §12.1 item 2 through the
reverse affix lookup of `tools/dicteval/hunspell.py`, never expanded, no compounding; the three casings per key; a
parallel prefetch and a cache `build-data/cache/spelling-<tag>-<input hash>.tsv.gz` that holds only lookup results),
`combined.py` (wordfreq, `pW` shares, the combined count of §12.2 with `WRITTEN_WEIGHT` 0.5, the colloquial path of
§12.4 with `COLLOQ_MIN_WRITTEN` 1e-6 and `COLLOQ_MAX_RATIO` 4, classes), `names.py` (§12.5, the rule of the evaluation's
generator computed from the same passes), `attested.py` (the rules shared by the de/es/pt/it/fr builders) and the
changes in every `build_*.py`. English keeps its selection and only gets the combined classes (§4.6). Every builder:

- **Forms** (§12.1): a Wiktionary form (with the abbreviation rule and the build's own derived forms: clitic,
  pre-clitic, apocopic and truncated forms) or a spelling-dictionary form. The junk rules (plausibility, one-letter keys,
  characters, OCR "l", missing-space artefacts, fused elisions, letters of elision prefixes, step 6a) exempt only
  Wiktionary forms; step 6a is therefore also applied to short spelling-only forms. German compound parts (§3.5), the
  derived forms and the coverage test that decides the English dump stay with Wiktionary.
- **Merges** (§12.1 item 5): a spelling the spelling dictionary accepts as written (or only capitalised while running
  text writes it capitalised) is itself a form and never merged; a key it knows only as a capitalised word while the
  subtitles' key stands for a lower-case spelling is merged as before (the Colombian place name "Dificil" does not keep
  "dificil" from "difícil"). Wiktionary's inflected-only forms still yield to a far more frequent accented form (pt
  "amanha" → "amanhã" although VERO knows "amanha" as a form of "amanhar"). Merged spellings never come back as written
  candidates.
- **Admission** (§12.3): forms with count ≥ 2, or count 0–1 and a share of the written frequency of their fold (a key
  without a subtitle count gets no share when another form of its fold has one: "gefaßter" next to "gefasster"). The
  candidates without a subtitle count are the Wiktionary forms whose fold is in the written list and the written list's
  words (with ß for ss in German) that the spelling dictionary knows.
- **Colloquial path** (§12.4) instead of the count rule and the damping: count ≥ 40, not a foreign title, `pW` ≥ 1e-6,
  `pS/pW` ≤ 4, no form of the language with the same fold, not a key of the en-US list (read from the built
  `en-US.words.gz`; English is built first), and the amendments 6–8: no misspelling of a near form (a lower-case,
  non-name form one substitution, insertion, deletion or adjacent swap away with `pW` ≥ 5 × the key's, unless a
  lower-case non-name form differs from the key only at its end), no lower-case form of another language's list (the
  lists in the output directory without their unattested keys, so the order of the builds does not matter; German is
  built before the Romance languages), no apostrophe fragment (the part before an apostrophe of a form of the
  language). Kept keys are flagged unattested (bit 7, 05 §10.1 step 5 stays off).
- **German umlaut merge** (§3.6, 0.7.0): also the transliterated umlauts ae, oe, ue ("fuer" → "für", "natuerlich" →
  "natürlich"); 4 090 keys merged, 1 438 of them only through the transliteration.
- **Classes** (§12.2): combined count = `T_S` · 10^(0.5 · log10 max(pS, ½/T_S) + 0.5 · log10 max(pW, ½·10⁻⁸)); names
  the lower of the two; apostrophe and hyphen keys, alias keys and elision prefixes the subtitles alone; `maxCount` the
  largest combined count, `corpusTokens` unchanged; attested keys at least class 1, the class-0 spellings stay 0.
- **German real-word typos**: a key without a subtitle occurrence is not tested (its dominance test against count 0
  says nothing; spec/QUESTIONS.md).
- **Pairs** (§12.6): the held-out sentences (Tatoeba id mod 20 = 7, Common Voice line number mod 20 = 7) are not
  counted; no follower is damped.
- **Report** (§12.7, `<tag>-extra` in dictionary-build.json): `keysWiktionaryOnly`, `keysSpellingDictionaryOnly`,
  `keysBoth` with the top 100, `coverageTop20000Subtitles`, `writtenAdmitted`, `colloquialKept`/`Dropped` with the top
  100 and the failed condition, `names` with the top 50, the 100 largest gaps between `pS` and `pW` in each direction,
  `classChangedBy20`, keys lost and new against 0.6.9, `S`, `maxCount`; per language `heldOutSentences` and `sizes`
  (with the growth against 0.6.9); `allDataFiles`.
- **Size limits** (§6): the build fails above 2.0 MB per word list, 2.5 MB per pair table, 17 MB in total, or a word list
  growth above 25 % against the previous release (`build-data/previous-<release>/`, the shipped files of that release)
  unless the owner's approval is recorded in [tools/dictionary-approvals.json](../tools/dictionary-approvals.json)
  (language, release, previous release, largest approved growth, where the decision is recorded; 0.7.0: de-DE up to
  30 %, approved on 2026-10-16). `MK_GROWTH_APPROVED=<tag>` allows any growth for a trial build only.

| | de-DE | en-US | es-ES | fr-FR | it-IT | pt-BR |
|---|---:|---:|---:|---:|---:|---:|
| keys 0.6.9 → 0.7.0 | 322 073 → 413 815 | 163 405 | 247 380 → 263 809 | 174 238 → 211 735 | 185 063 → 230 447 | 172 374 → 196 081 |
| forms | 328 313 → 421 482 | 164 040 | 250 115 → 266 875 | 179 163 → 219 086 | 187 677 → 233 726 | 173 935 → 197 931 |
| keys lost / new | 8 032 / 99 774 | 0 / 0 | 21 610 / 38 039 | 8 447 / 45 944 | 14 253 / 59 637 | 24 507 / 48 214 |
| keys only Wiktionary / only spelling dict. / both | 160 810 / 46 943 / 172 781 | – | 62 862 / 7 520 / 193 335 | 55 300 / 2 491 / 153 435 | 23 570 / 25 378 / 181 360 | 10 981 / 39 703 / 145 194 |
| top 20 000 subtitle keys: Wiktionary / spelling / union | 87.9 / 80.9 / 89.9 % | – | 89.9 / 86.6 / 90.4 % | 94.0 / 89.8 / 95.0 % | 85.3 / 84.6 / 86.3 % | 86.4 / 85.9 / 88.2 % |
| admitted by the written frequency (count 0–1) | 120 288 (+ 53 150 class-1 lemmas) | – | 33 180 | 44 571 | 40 255 | 19 047 |
| colloquial path kept / dropped | 132 / 7 993 | – | 92 / 21 557 | 23 / 8 434 | 130 / 14 236 | 203 / 24 080 |
| of the dropped: items 6 / 7 / 8 | 16 / 20 / 0 | – | 11 / 15 / 0 | 5 / 3 / 1 | 17 / 29 / 0 | 18 / 31 / 0 |
| unattested (flagged) keys | 132 | 0 | 92 | 23 | 130 | 203 |
| names | 27 352 | 4 732 | 12 168 | 34 712 | 12 868 | 5 054 |
| classes changed by more than 20 | 30 604 | 5 347 | 13 149 | 10 175 | 13 000 | 9 665 |
| S / maxCount | 16.947886 / 3 423 329 | 14.835626 / 29 161 342 | 15.173835 / 19 880 389 | 15.682406 / 11 527 661 | 16.106291 / 7 514 334 | 15.463676 / 14 508 678 |
| pair previous words / pairs | 55 366 / 553 032 | 41 276 / 574 921 | 36 826 / 369 515 | 41 037 / 478 291 | 35 797 / 390 318 | 20 339 / 270 118 |
| held-out sentences (Tatoeba / Common Voice) | 39 170 / 85 327 | 101 689 / 79 337 | 22 141 / 59 267 | 36 460 / 85 418 | 49 267 / 50 011 | 22 034 / 2 053 |
| words file, bytes | 1 342 081 → 1 696 370 (+26.4 %, approved) | 519 514 → 537 526 (+3.5 %) | 811 368 → 870 189 (+7.3 %) | 610 121 → 753 352 (+23.5 %) | 612 927 → 750 291 (+22.4 %) | 585 747 → 666 267 (+13.7 %) |
| pairs file, bytes | 1 890 114 → 1 867 310 | 1 927 135 → 1 876 202 | 1 212 411 → 1 172 828 | 1 508 324 → 1 479 369 | 1 274 932 → 1 240 825 | 859 185 → 836 263 |

The build stays deterministic (a second Portuguese run gave byte-identical files). All data files: 13 153 859 bytes
(0.6.9) → 13 746 792 bytes (limit 17 MB). The release APK grows from 15 009 182 bytes (0.6.9) to 15 633 930 bytes. The German growth (+26.4 %) comes from the forms admitted by their written
frequency (§12.3; spec estimate +40 000 keys, measured +91 742) and the spelling-only names; the owner approved it for
0.7.0 (tools/dictionary-approvals.json).

| File (0.7.0) | Bytes | SHA-256 |
|---|---:|---|
| `de-DE.words.gz` | 1 696 370 | `edafa980bab6ca902ddd7a77033b3c39b0dac5be4dc1d779b687666bcda9a4fa` |
| `de-DE.pairs.gz` | 1 867 310 | `3f1a14ca38fa57304f3337df24b1d82f7cdf8c04aa96bc7f5268f2bd0024b17a` |
| `en-US.words.gz` | 537 526 | `4ff727151da6837e934fd81e7b1360fc655d7d389cca5c5a39aff30015c78963` |
| `en-US.pairs.gz` | 1 876 202 | `30db574b7fde0e3a3f91ddeda9e4490289be743574652ff47960d0e0d086d5d0` |
| `es-ES.words.gz` | 870 189 | `95ec4093f230fb6f94d8872be918223bb618d78082316139a760178a4b2bfb73` |
| `es-ES.pairs.gz` | 1 172 828 | `29db69ea97840151bbd232ed120b692ec2f59a84d47139e9ac7760061a6f9504` |
| `fr-FR.words.gz` | 753 352 | `33a81d77e6964f68ed1bc61213706a78be26b69418d94b4aefdbf145e57abf07` |
| `fr-FR.pairs.gz` | 1 479 369 | `3091985164cf5c18184ca07eacce5ea310f4151a36ddf7c2d7eba0a775b34750` |
| `it-IT.words.gz` | 750 291 | `5a52a806a34c4d6ca385ade1522e66e5e5d049a759f153ad8daf6d53abb81d3d` |
| `it-IT.pairs.gz` | 1 240 825 | `eab6ee2d10beb0bfb3caa3a5b32c2e2aa8206f94ca29577b6844caca516ac6a6` |
| `pt-BR.words.gz` | 666 267 | `7789b8694bf8b5a6c40144f5868c6f07c70f44aa92fdf9823d4993338a54d921` |
| `pt-BR.pairs.gz` | 836 263 | `8d53d71037954e0cad256c9affd52d4ec232a8794043ba963f2afa7e952d5dd2` |

What changed, by kind (most frequent first; the full lists are in the build report): keys lost are almost all the
0.6.9 count-rule keys (names and English words of the dubbed films: de Dad, the, Captain; es Joe, Michael, Tom; it Sam,
Charlie, Mike; pt Sam, Charlie, Mary; fr Neal, McGee, Benny); new keys are forms admitted by their written frequency,
names that a spelling dictionary knows (es John, Alex, Frank; de Girl, Pierre, Santa; fr, it and pt also English words
the dictionaries list: fr "new", "shirt"; it "time", "love", "professor"; pt "here", "doctor") and accent-less spellings
the spelling dictionary knows as words of their own (es "futbol", "tenes"; pt "ha", "apos", "historia"), which are no
longer merged (§12.1 item 5). The amendments drop misspellings of near forms (de "hälst", "warscheinlich", "nciht"; fr
"mourrir", "apparement", "rapelle"; es "dijieron"), lower-case forms of other lists (pt "primera", it "comme", "todos")
and the fragment "aujourd"; colloquial keys kept include German "wär", "gibts", "würd", "hätt", "kanns", "siehste" (and
"gesehn", spec/QUESTIONS.md).

Memory (`MemoryBudgetTest.eachLanguageAlone`, retained JVM heap after loading one language with pairs, a query and a
swipe; 0.6.9 → 0.7.0): de-DE 28.3 → 32.8 MB, en-US 14.5 → 14.4, es-ES 16.6 → 17.3, fr-FR 16.7 → 18.6, it-IT 15.5 →
17.6, pt-BR 12.5 → 13.7 MB. All two- and three-language sets load within the test heaps of 56 and 68 MB (largest:
fr+de+it 64.1 MB; 0.6.9: 59.7 MB).

# App data sources

## Spelling dictionaries and written-language frequencies (0.7.0)

The spelling dictionaries (05b §2.14) and the written-language frequencies (05b §2.15) are in
[tools/sources.json](../tools/sources.json) since the 0.7.0 evaluation (all hashes equal the specification). The
dictionary evaluation of [EVALUATION.md](EVALUATION.md) uses them for its trusted words, and the 0.7.0 build for the
data (above). The spelling dictionaries' notices are pinned in [tools/notices.json](../tools/notices.json).

| Source | Version | Files | Licence | Used for |
|---|---|---|---|---|
| wordfreq (https://github.com/rspeer/wordfreq) | tag v3.2, commit `42233e6c36ce792031bcccfa17cdd0cec9af5fa7` | `wordfreq/data/large_{de,en,es,pt,it,fr}.msgpack.gz` (634 502, 321 180, 342 072, 267 979, 322 796, 311 419 words, as in 05b §2.15) | CC BY-SA 4.0 | written-language frequency `pW` (05b §12.2–§12.4) of every list; trusted words of the evaluation |
| LibreOffice dictionaries (https://github.com/LibreOffice/dictionaries) | commit `67032ee957631908098a72edf48dc81cac27e369` | `de/de_DE_frami.{aff,dic}`, the 42 files of `es/` (Spain and twenty American variants), `pt_BR/pt_BR.{aff,dic}`, `it_IT/it_IT.{aff,dic}` | GPL-2.0/GPL-3.0, GPL-3.0+, LGPL-3.0, GPL-3.0 | spelling-dictionary membership (05b §12.1) of the build and of the evaluation's trusted words and names |
| Dicollecte (https://grammalecte.net) | 7.7 | `hunspell-french-dictionaries-v7.7.zip`, read in place (`fr-toutesvariantes.{aff,dic}`) | MPL-2.0 | as above |

The real-error lists of 05c §2.3 (Wikipedia, CC BY-SA 4.0) are pinned in
[tools/dicteval/sources.json](../tools/dicteval/sources.json) with the SHA-256 of each revision's wikitext.

**Source-form export.** Every dictionary build also writes `build-data/export/<tag>.forms.tsv.gz` (05b §2 "Source
form", item 1: key, form, subtitle count, written-language frequency, combined count, class, flags, merged spellings;
not packaged), since 0.7.0 also `<tag>.pairs.tsv.gz` and `<tag>.prefixes.tsv`; flags of 0.7.0: spelling, wiktionary,
abbreviation, name, colloquial, merged, class0-typo, class0-dominated, lemma-only, alias. (The 0.6.9 export, still in
use up to 0.6.9, had the flag `countrule` for the keys kept by the count rule.)

## Selawik font

| Item | Value |
|---|---|
| Source | official repository https://github.com/microsoft/Selawik, release `1.01` (published 2015-12-08) |
| File | `Selawik_Release.zip` (https://github.com/microsoft/Selawik/releases/download/1.01/Selawik_Release.zip), SHA-256 `3f62c51e05e3b5a1e6241cf92a371f0be2ea1183aa87b30718bbd40832a8d423` |
| Licence | SIL Open Font License 1.1, Reserved Font Name "Selawik"; `LICENSE.txt` of the repository (branch `master`, commit `89362e84731d1f5777fa078fd4d5ebbd339e4378`), SHA-256 `77b7c2506d4efb22e09c8ccf10159f4956eab3ef7c007fef95de136bcf45300c`, shipped as `app/src/main/assets/licenses/Selawik-OFL.txt` |
| Use | the five TrueType files, unmodified, renamed only to Android resource names |

| Resource | Release file | SHA-256 |
|---|---|---|
| `res/font/selawik_light.ttf` | `selawkl.ttf` | `8e19d073091a1e869b5b0d48b925e605c0a4c6ece7df7b22a364fb065314c4c5` |
| `res/font/selawik_semilight.ttf` | `selawksl.ttf` | `8620960344f12093482cded984a1aafa5a57d24cbf7e9299da125ccf0e9d4102` |
| `res/font/selawik_regular.ttf` | `selawk.ttf` | `e9d98518d8ac2817782a9a382430463a2e0793ea68350b695bb727d9a830ee1c` |
| `res/font/selawik_semibold.ttf` | `selawksb.ttf` | `0a9e9d0549a10f24bef9b3a29e06fe6e0b5c21e7a784c503b048a307841a7783` |
| `res/font/selawik_bold.ttf` | `selawkb.ttf` | `f0db5e174a90e0956ad7d2844bdca1d5e6da92ec65b2c04e57ba9b180668c904` |

Characters Selawik does not contain (e.g. ⅓ ⅔ √ ≤ ≥ ≠, emoji) are drawn by Android's font fallback.

## Emoji page data

| Item | Value |
|---|---|
| Source | Unicode `emoji-test.txt`, Emoji 18.0, https://www.unicode.org/Public/18.0.0/emoji/emoji-test.txt (file date 2026-04-30) |
| SHA-256 | `8f3735cda1f92a779d78af67cf86066bb1f07143dc22f2ac29394d9bc57ab21a` |
| Licence | Unicode License v3 (https://www.unicode.org/license.txt, SHA-256 `e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96`), shipped as `app/src/main/assets/licenses/Unicode-LICENSE.txt` |
| Tool | `tools/build_emoji.py` (`download`, `build`); categories and group lists from `spec/data/emoji.json` |
| Output | `app/src/main/assets/emoji/emoji.txt`: fully-qualified emoji only, skin-tone variants left out, per category in the order of the emoji.json entries and within each entry in file order; the text smileys of the "emoticons" category are our own selection (70 items) |

Item counts: emoticons 70, people 309, activities 186, food-objects 400, travel 173, nature-weather 209, symbols 494.
