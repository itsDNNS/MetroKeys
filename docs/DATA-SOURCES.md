# Dictionary data sources

The word lists and word-pair tables in `engine/src/main/resources/app/metrokeys/engine/dict/` are built by
`tools/build_dictionaries.py` from the free sources named in the specification (`spec/05b-dictionaries.md`, not published)
§2. No other data is used. The machine-readable list of every source file with its URL and SHA-256 is
[tools/sources.json](../tools/sources.json); the build refuses to run if a hash does not match.

## Licence of the data files

The derived data files (`*.words.gz`, `*.pairs.gz`) are licensed under **CC BY-SA 4.0**
(https://creativecommons.org/licenses/by-sa/4.0/) because the frequency source is CC BY-SA. This licence applies to
the data files only, not to the MetroKeys code. A licence/attribution text accompanies each data file
(`LICENSE-de-DE.txt`, `LICENSE-en-US.txt`, `SCOWL-Copyright.txt`); the engine exposes it through
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

All hashes equal the ones listed in the specification. The Tatoeba exports downloaded on 2026-10-15 still carried
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

## Build result

Recorded by the build in [tools/dictionary-build.json](../tools/dictionary-build.json). Two runs on the same inputs
produced identical bytes.

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

The small differences to the reference counts come from details of the Wiktionary markup cleaning and the
tokenisation that the specification describes only functionally; all classes the worked examples of spec 05 §17
rely on are reproduced exactly (verified by `WorkedExamplesTest`).

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
