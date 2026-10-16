# Dictionary data sources

The word lists and word-pair tables in `engine/src/main/resources/app/metrokeys/engine/dict/` are built by
`tools/build_dictionaries.py` from the free sources named in the specification (`spec/05b-dictionaries.md`, not published)
§2. No other data is used. The machine-readable list of every source file with its URL and SHA-256 is
[tools/sources.json](../tools/sources.json); the build refuses to run if a hash does not match.

## Licence of the data files

The derived data files (`*.words.gz`, `*.pairs.gz`) are licensed under **CC BY-SA 4.0**
(https://creativecommons.org/licenses/by-sa/4.0/) because the frequency source is CC BY-SA. This licence applies to
the data files only, not to the MetroKeys code. A licence/attribution text accompanies each data file
(`LICENSE-de-DE.txt`, `LICENSE-en-US.txt`, `LICENSE-es-ES.txt`, `LICENSE-fr-FR.txt`, `LICENSE-it-IT.txt`,
`LICENSE-pt-BR.txt`, `SCOWL-Copyright.txt`); the engine exposes it through
`Dictionary.bundledLicenseText(tag)` for the app's licences screen.

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

- German word list: FrequencyWords © Hermit Dave (OpenSubtitles 2018), CC BY-SA 4.0; spellings from Wiktionary
  (de.wiktionary.org), authors see page histories, CC BY-SA 4.0. Modified (filtered, case, frequency classes).
- German word pairs: counted from sentences of Tatoeba (tatoeba.org, authors see sentence pages, CC BY 2.0 FR) and
  Mozilla Common Voice (CC0). Modified (pair counts, probability classes), CC BY-SA 4.0.
- English word list: FrequencyWords © Hermit Dave (OpenSubtitles 2018), CC BY-SA 4.0; SCOWL © Kevin Atkinson and
  others (wordlist.aspell.net), see the notice; sentence statistics from Tatoeba (CC BY 2.0 FR) and Mozilla Common
  Voice (CC0). Modified (filtered, spelling, contractions, frequency classes), CC BY-SA 4.0 — followed by the
  complete SCOWL `Copyright` file verbatim (`SCOWL-Copyright.txt`, also appended to `LICENSE-en-US.txt`).
- English word pairs: as German word pairs.
- Spanish word list: FrequencyWords © Hermit Dave (OpenSubtitles 2018), CC BY-SA 4.0; spellings from Wiktionary
  (es.wiktionary.org and the Spanish sections of en.wiktionary.org), authors see page histories, CC BY-SA 4.0;
  sentence statistics from Tatoeba (CC BY 2.0 FR) and Mozilla Common Voice (CC0). Modified (filtered, accents, case,
  frequency classes), CC BY-SA 4.0 (`LICENSE-es-ES.txt`).
- Spanish word pairs: as German word pairs.
- Portuguese (Brazil) word list: FrequencyWords © Hermit Dave (OpenSubtitles 2018, Brazilian Portuguese), CC BY-SA
  4.0; spellings from Wiktionary (pt.wiktionary.org and the Portuguese sections of en.wiktionary.org), authors see page
  histories, CC BY-SA 4.0; sentence statistics from Tatoeba (CC BY 2.0 FR) and Mozilla Common Voice (CC0). Modified
  (filtered, hyphen tokens split, accents, spelling reform, case, frequency classes), CC BY-SA 4.0
  (`LICENSE-pt-BR.txt`).
- Portuguese word pairs: as German word pairs.
- Italian word list: FrequencyWords © Hermit Dave (OpenSubtitles 2018), CC BY-SA 4.0; spellings from Wiktionary
  (it.wiktionary.org and en.wiktionary.org), authors see page histories, CC BY-SA 4.0; sentence statistics from Tatoeba
  (CC BY 2.0 FR) and Mozilla Common Voice (CC0). Modified (filtered, accents restored, elisions separated, case,
  frequency classes), CC BY-SA 4.0 (`LICENSE-it-IT.txt`).
- Italian word pairs: as German word pairs.
- French word list: FrequencyWords © Hermit Dave (OpenSubtitles 2018), CC BY-SA 4.0; spellings from Wiktionary
  (fr.wiktionary.org), authors see page histories, CC BY-SA 4.0; sentence statistics from Tatoeba (CC BY 2.0 FR) and
  Mozilla Common Voice (CC0). Modified (filtered, accents and ligatures restored, elisions separated, hyphenated forms
  split, compounds, case, frequency classes), CC BY-SA 4.0 (`LICENSE-fr-FR.txt`). The English Wiktionary is not
  named because it is not used for the French data.
- French word pairs: as German word pairs.

## Build result

Recorded by the build in [tools/dictionary-build.json](../tools/dictionary-build.json). Two runs on the same inputs
produced identical bytes (also after the 0.6 restructuring into per-language profiles: the German and English files are
byte-identical to 0.5.2, and two Spanish runs gave identical bytes).

| | de-DE | reference (05b) | en-US | reference (05b) |
|---|---:|---:|---:|---:|
| keys | 857 274 (0.6.5; 236 223 in 0.6.4, 236 559 before) | 236 823 | 163 405 | 163 419 |
| cased forms | 864 361 (241 563; 241 914) | 242 784 | 164 040 | 164 114 |
| S | 16.357883 | 16.357883 | 14.976348 | 14.976349 |
| maxCount | 5 890 279 | – | 24 812 233 | – |
| corpusTokens | 156 169 536 | 156 169 536 | 695 682 540 | 695 682 540 |
| alias keys (replacing a word) | – | – | 79 (11) | 79 (11) |
| possessive forms | – | – | 34 855 | 34 883 |
| pair previous words | 55 267 (52 820; 53 004) | 53 012 | 42 124 | 42 131 |
| pairs | 566 548 (558 993; 561 563) | 561 595 | 590 533 | 590 661 |

| File | Bytes | SHA-256 |
|---|---:|---|
| `de-DE.words.gz` (0.6.5) | 1 936 431 | `30b3ac90279991003d60651a0d5b7efa6c82937ff436aa8759f43388a4aa96e8` |
| `de-DE.pairs.gz` (0.6.5) | 1 942 877 | `457238c79acb756b43668a5d64f45e41f7e098867ef3173d7d42a6e2f89e6a83` |
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

# App data sources

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
