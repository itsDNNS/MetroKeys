# Dictionary data sources

The word lists and word-pair tables in `engine/src/main/resources/app/metrokeys/engine/dict/` are built by
`tools/build_dictionaries.py` from the free sources named in the specification (`spec/05b-dictionaries.md`, not published)
§2. No other data is used. The machine-readable list of every source file with its URL and SHA-256 is
[tools/sources.json](../tools/sources.json); the build refuses to run if a hash does not match.

## Licence of the data files

The derived data files (`*.words.gz`, `*.pairs.gz`) are licensed under **CC BY-SA 4.0**
(https://creativecommons.org/licenses/by-sa/4.0/) because the frequency source is CC BY-SA. This licence applies to
the data files only, not to the MetroKeys code. A licence/attribution text accompanies each data file
(`LICENSE-de-DE.txt`, `LICENSE-en-US.txt`, `LICENSE-es-ES.txt`, `LICENSE-pt-BR.txt`, `SCOWL-Copyright.txt`); the engine exposes it through
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

All hashes equal the ones listed in the specification (for the Wiktionary dumps the specification gives the sha1 of
the dump status; the sha256 values above were recorded at download). The directory `server/data/es/` of Common Voice
holds exactly the three files listed, `server/data/pt/` exactly the two named above (checked with the GitHub contents
API at that commit). The Tatoeba exports downloaded on 2026-10-15 still carried
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

## Build result

Recorded by the build in [tools/dictionary-build.json](../tools/dictionary-build.json). Two runs on the same inputs
produced identical bytes (also after the 0.6 restructuring into per-language profiles: the German and English files are
byte-identical to 0.5.2, and two Spanish runs gave identical bytes).

| | de-DE | reference (05b) | en-US | reference (05b) |
|---|---:|---:|---:|---:|
| keys | 236 559 | 236 823 | 163 405 | 163 419 |
| cased forms | 241 914 | 242 784 | 164 040 | 164 114 |
| S | 16.357883 | 16.357883 | 14.976348 | 14.976349 |
| maxCount | 5 890 279 | – | 24 812 233 | – |
| corpusTokens | 156 169 536 | 156 169 536 | 695 682 540 | 695 682 540 |
| alias keys (replacing a word) | – | – | 79 (11) | 79 (11) |
| possessive forms | – | – | 34 855 | 34 883 |
| pair previous words | 53 004 | 53 012 | 42 124 | 42 131 |
| pairs | 561 563 | 561 595 | 590 533 | 590 661 |

| File | Bytes | SHA-256 |
|---|---:|---|
| `de-DE.words.gz` | 967 409 | `5905c250567ccb2720536ce77debb1dc19786df26f097728389f55f908db7c0c` |
| `de-DE.pairs.gz` | 1 863 133 | `01baac8fd1b798ca0d353148193ce3255866aa61dc988e9bd91ece3130889713` |
| `en-US.words.gz` | 519 514 | `0d0595b450b30387511006ce39691e8c248e5d7175041f50d65899bb2e5b1f9b` |
| `en-US.pairs.gz` | 1 927 135 | `2dde9255fc17e317a7bec8ad1aa7f24ceb6c0b66379299c1ae25472518457e91` |

The SHA-256 of the uncompressed word-list payloads (stored in the pair tables as binding) are
`c51a843f…c8637ad` (de-DE) and `ceac596e…ec3fb43f` (en-US), see dictionary-build.json.

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
