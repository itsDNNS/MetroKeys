# MetroKeys engine

`engine/` is a pure Kotlin/JVM library (package root `app.metrokeys.engine`, no Android dependency). It contains the
dictionaries and everything of spec 05, 05b, 06 and the engine-side rules of 04: prediction, autocorrection,
next-word prediction, learning, contact names, multilingual typing, spelling marks and shape writing. The Android
app (next stage) only translates editor state into queries and applies the results.

## Packages

| Package | Content |
|---|---|
| `app.metrokeys.engine` | public facade: `PredictionEngine`, `Query`, `Prediction`, `Suggestion`, `EngineSettings`, `LearnedDataStore`, `Proofreader`, `ActiveLanguages` (05 §14.5) |
| `…engine.lexicon` | runtime dictionaries: `Dictionary` (word list + pair table), `Lexicon` (prefix tree with per-subtree best class and best offered class, the derived per-form data of 05b §1.1, `forms/exact/canonical/frequency/completions/words`), `CasedForms`/`CasedTrie` (the cased forms walked in code-unit order for the spelling source, 05d §7), `DerivedData` (cost anchors, offered bits and spelling classes for files without them), `PairTable` (`bigram/followers`, bound to its word list) |
| `…engine.predict` | per-language engine `LanguageEngine` (LM cost §3, case mode §6, validity §7, candidates §8, autocorrection decision §10.1, next word §11), `KeySlipSearch`/`SlipTable` (§8.1), `SpellingSource`/`SpellingParams` (05d), `EditDistance` (§5), `LmScale` (the anchored cost map of §3.2), `UserModel` (§12, persistence), `ContactNames` (§13), `LanguageParams`/`ShapeParams`/`Params` (§1, 06 §7.1) |
| `…engine.text` | `TextModel` (word characters, current word, contexts §2.2), `TextRules` (04: auto-shift, double-space period, triggers, smart/trailing space, word delete, autocorrection gate), `FieldPolicy`/`FieldInfo` (§15 incl. Android input-type mapping), case helpers |
| `…engine.touch` | `KeyboardGeometry` (letter keys from `spec/data/layouts-*.json` or the fallback grid), `TouchModel` (§4), `Tap`/`TapSequence` |
| `…engine.swipe` | `ShapeWriter` (06 §3–§4: key sequence, angles, stage-A search, templates, DTW, context, unrecognised gestures), `ShapeTrace`, `ShapeResult` |

## Languages

Keyboard languages: German (`de-DE`), US English (`en-US`), Spanish (`es-ES`), French (France, `fr-FR`, 0.6.3),
Portuguese (Brazil, `pt-BR`, 0.6.1) and Italian (`it-IT`, 0.6.2).
Everything that differs between them is one `LanguageParams` instance (`LanguageParams.ALL`, fixed language order =
alphabetical by tag): the weights and limits of 05 §1.1 (from 0.7.1 also the AC edit weights `acInsert`, `acDelete`,
`acSpace`, `acSwap` and the slips `slipNear`, `slipAccent`, `slipFiller`), the language rules (`slipAccents` with one or
more accented letters per base letter, `oneLetterWords`, `singleLetterFix`, `compoundLinks` = null for no compounds,
`missingSpaceSplit`, `ingRule`, `elisionPrefixes`, `elisionFullWords`, `accentSwap`, `hideClassZero` (shape writing
only), `elisionValidSlack`, `capsUnaccented`, `umlautTranslit`), one `SpellingParams` instance (the rows S, B, E, P, F,
letter pairs, letter-group rules, vowel classes, adjacent keys and digraphs of 05d §9), the fallback grid of 05 §4 (rows and the starts of rows 2 and 3: Spanish and Portuguese have ten keys
without indent in row 2, ñ / ç last; French AZERTY has ten keys in row 2 and starts row 3 at 0.2 · width), the
shape-writing constants
of 06 §7.1 (`ShapeParams`; English uses its own column since 0.6) and the emoji table. A further language adds one
instance, its dictionary files and a build profile (DATA-SOURCES.md).

**Active set** (05 §14.5): `ActiveLanguages.select(enabled, selected, recency)` gives the languages that run and stay
loaded: all enabled up to three, else the selected one and the two others selected most recently (never selected
ones in the fixed order). `ActiveLanguages.selected` updates the recency order; the app stores it as
`language_recency`. A `PredictionEngine` is built for the active set; its weights use N = its size.

## Using it from the app

```kotlin
val engine = PredictionEngine.withBundledDictionaries(listOf("de-DE", "en-US"),
    EngineSettings(multilingual = true))                      // load on a background thread
engine.activeLanguage = "de-DE"                               // language key
engine.setGeometry(KeyboardGeometry.fromLayoutJson(json, "text.letters", keyAreaWidthPx, keyAreaHeightPx))
engine.loadLearnedData(store)                                 // LearnedDataStore over private files

val field = FieldPolicy.fromAndroid(editorInfo.inputType, editorInfo.imeOptions)
val p = engine.query(Query(before = textBeforeCaret, after = textAfterCaret, taps = taps, field = field))
// p.suggestions: bar entries (autocorrection first and marked), p.autoCorrection: applied on a trigger
// engine.query(q) { outdated } stops with QueryCancelledException once the check reports the query outdated (05 §2.3)
if (TextRules.mayApplyAutocorrection(/* keyboard-side conditions of 04 §3.2 */) &&
    !TextRules.keepsAbbreviation(trigger, p.typedIsAbbreviation)) …   // 04 §3.2 item 9: "Zi." stays
engine.learn(committedWord, previousWords, field, accepted = pickedTypedWord)
engine.rejectAutoCorrection(typed, correction, field, accepted = undoneByPickingTheTypedWord)
engine.isForgettable(candidate); engine.forget(word); engine.learnedWords()   // 05 §12.5
engine.recognizeShape(ShapeTrace(xs, ys, times), textBeforeCaret, field).texts(ShiftState.ONE_SHOT)
Proofreader(engine).run(text, selStart, selEnd, markedRanges, autocorrectedRanges)
engine.saveLearnedData(store)                                 // when input finishes
```

`PredictionEngine.query` returns next-word predictions when the current word is empty, nothing when the caret is
inside a word, and honours the settings and the field policy (§9, §15). `resumeCorrections(before)` is the
correction-only search for a resumed word (03 §6.4), `spellingSuggestions(word)` the suggestions of a spelling mark,
`addWord` the "add to dictionary" item, `deleteLearnedData` the reset. `Dictionary.loadBundled(tag)` returns the
process-wide instance of a bundled dictionary while one is in use (05b §6: loaded at most once per process; a language
switch reuses the dictionary that stays). `Dictionary.bundledContains(tag, keys)` tells which keys a bundled word list
contains, from the loaded instance or by streaming through the compressed file without building it
(`Lexicon.containedKeys`); `LearnedWordList.of(models, knownKeys)` builds the learned words list of 05 §12.5 from learned
data alone with such a lookup (the settings use both). `Dictionary.bundledLicenseText(tag)` gives the attribution text
for the licences screen. Queries are thread-safe; learned data is synchronised internally. `queryObserver` (0.6.5) is
called at the start of every `query` on the calling thread, so the keyboard's tests (and debug checks) can verify that
no query runs on its UI thread.

## Data flow of a query (0.7.1)

1. `QueryContext.of(before, taps, nameField, cancel)` extracts the current word, the character before it, the pair
   context (`PrevContext`: sentence start / word / none) and the preceding words of the sentence; taps are aligned from
   the end.
2. Each running `LanguageEngine` computes validity (with `classZero` for a word valid only as a class-0 form) and then
   the candidates of 05 §8, each source on its own:
   - the **key-slip search** (§8.1, `KeySlipSearch`): a best-first walk over the key tree along the typed letters with
     exact, neighbour (proximity at the key centres), filler and accent slips; the priority is slip + the static cost
     of the highest *offered* class below a node, so subtrees that cannot improve a full list are not entered; the
     forms whose cost in the query is below their static cost (stored pair followers of the previous word, forms with
     learned counts, contact words) are matched directly. Same-length list (5), completion list (12), hand-over (12);
     the forms of an all-exact key enter with total 1;
   - the same walk over the **user words** (§8.1a: known learned words without case errors, contact words);
   - the walks of the **transliterated spellings** (§8.1c: ß for ss, œ/æ for oe/ae in French, umlauts for ae/oe/ue in
     German); such a walk hands over only completions longer than the typed word (0.7.1 review: "fue" → "fü" no
     longer makes "für" a completion, so "das ist fue" corrects to "für"; "Strass" completes to "Straßen", not
     "Straße");
   - the **spelling source** (§8.1b, 05d, `SpellingSource`): its first five words, looked up as stored forms (lm 300
     for a word that is no offered form, two-word suggestions only where `SPLIT_SPACE` is on);
   - the **missing-space split** (§8.2) and the **elision candidates** (§8.2.1 items 1, 1a, 2, 3).
   Every candidate is scored against its display form (score = lm + ed; completions ed of the matched prefix +
   `COMP_SURCHARGE`), filtered by the unit rules of §8.3, merged by display (lower score kept with its kind) and ordered
   (score, then descending code-unit order). The decision of §10.1 (policy P3) follows.
3. `PredictionEngine` builds the bar (05 §9): autocorrection first, then the ranked list; of the first 11 entries of that
   order the typed word and flagged entries are not shown. Multilingual: language weights from the preceding words,
   score + 23 · (−log10 w) per language, merge, top-language-only autocorrection with the foreign-word block, emoji from
   the top language.
4. Cancellation (05 §2.3, 05d §7): `query(q, cancel)` passes the check to the key-slip search and the spelling source,
   which test it every 1 000 heap steps / node visits and throw `QueryCancelledException`; the spelling source stops at
   `SPELL_VISIT_MAX` = 200 000 node visits. The keyboard cancels its typing queries when a newer word state replaces
   them.

## Learning, acceptance and forgetting (05 §10.4, §12)

`UserModel` keeps per language: counts with last-use stamps and an *accepted* flag per stored form, n-gram followers,
the recent window, and the undo table with an undo count per typed word.

- **Known learned words** (`isKnownLearned`): a form whose key the dictionary knows in some casing needs
  `LEARN_MIN_COUNT` (2) uses; a word the dictionary does not know needs to be accepted, or `LEARN_MIN_UNKNOWN` (3) uses,
  or one undone autocorrection (it is in the undo table; 0.6.8, before two). This one test is used for validity rule 3, the LM cost of learned-only words,
  the correction pool, completions, compound parts and learned next-word followers.
- **Acceptance**: `learn(…, accepted = true)` when the typed word itself is picked, `reject(…, accepted = true)` when an
  autocorrection is undone by picking the typed word, and every `addWord`.
- **Undo**: `isRejected` holds after one undo for every word, whether the dictionary knows it or not (05 §10.4, 0.6.8 as
  in the reference; `REJECT_MIN_UNKNOWN` = 2 for unknown words before). With learning on, a reject raises a dictionary word to two uses and adds one use to an unknown word.
- **Forget**: removes every form with the word's key from counts, contexts (as follower and as context word), recent
  window, accepted marks and undo table; refused for words a loaded dictionary knows. `PredictionEngine.forget` runs it
  in every language; `learnedWords()` is the list of 05 §12.5: every stored form unknown to every loaded dictionary,
  also forms used only once (no threshold), counts summed over the languages.

**Learned-data format** (`MKUM`, big-endian `DataOutputStream`): version 2 = stamp; words (form, count, last use, flags
byte with bit 0 = accepted); contexts (key, followers with counts); recent window; undo table (word, undo count).
Version 1 (without the flags byte and the undo counts) is still read: its undo entries get one undo (they were rejected
before), its words are not accepted. Anything else yields an empty model.

**Three-letter words**: since 0.7.1 the key-slip search lets any number of positions slip, so "usz" reaches "ist" (two
neighbour slips) without the extension of 0.6.x.

## Dictionary format

Both files are gzip streams (written by `tools/dictbuild/common.py`, read by `Lexicon.read` / `PairTable.read`):

- **Word list** (`MKWL` v1): header with S (f32), maxCount, corpusTokens, key and form counts; then all keys in
  UTF-16 order, front-coded (shared prefix length + UTF-8 suffix), each with its forms (class byte + a kind byte:
  the key itself, first letter upper-cased, all upper-cased, or an explicit string — used for alias keys such as
  `dont` → `don't`).
- **Version 3** (0.7.1, every language; 05b §1.1 derived data): after the form count the header holds the cost anchors
  (count, then class and cost bytes) and the guard class; the kind byte of every form carries bit 2 offered (the
  `SUGGEST_MAX_RANK` most frequent forms by form count), bits 3–4 the spelling class of 05d §6 and bit 5 the
  do-not-suggest flag of 05b §2.16; bit 6 of a key's form-count byte is the **abbreviation bit** of 05b §1.1 (0.7.1
  review; so a version-3 key has at most 63 forms; `Lexicon.isAbbreviation` / `isAbbreviationWord`,
  `abbreviationCount`, a `BitSet` over the key indices); the prefix table always follows the keys (count 0 where a
  language has none). Older files and lexicons built from entries derive these data from their classes
  (`DerivedData`) and have no abbreviation bits.
- **Unattested flag** (0.6.9; meaning from 0.7.0): bit 7 of a key's form-count byte marks a key that is no form of the
  language: since 0.7.0 the keys kept by the colloquial path of 05b §12.4 (23–203 per language), in 0.6.9 the keys kept
  by the count rule (the damped keys of 05b §3.6 step 7). `Lexicon.isUnattested` /
  `isUnattestedWord`, `unattestedCount`; stored as a `BitSet` over the key indices (≈ 40 KB for German). Files without
  flags (English, and every file before 0.6.9) read unchanged.
- **Pair table** (`MKPT` v1): binding = key count, form count and the SHA-256 of the uncompressed word-list
  payload; then per previous word (sentence start first) up to 48 followers as (key index, pair class, form index),
  best class first. A table whose binding does not match is rejected (`PairTableMismatchException`);
  `Dictionary.load` then runs without pairs.
- **Version 2** (0.6.2, languages with an elision prefix table: it-IT and, since 0.6.3, fr-FR; the other languages stay
  at version 1 and their files are unchanged): the word list ends with the prefix table (count, then per prefix its text
  and class; `Lexicon.prefixClass`); in the pair table the previous-word slots keyCount + 1 + p and the follower indices
  keyCount + p are elision prefix p, and follower counts are varints (a prefix keeps up to `PREFIX_FOLLOWERS_MAX` =
  4000 followers). `PairTable` keeps one start offset per slot (`start[s] … start[s + 1]`), which is also smaller than
  the former start + count arrays.

Both files are decoded as the gzip stream comes in (`ByteReader` over a buffered stream; the word list's SHA-256 is
computed on the way), never held as a whole. The front-coded keys are kept only as their new suffix characters (one
character per tree node) with their lengths and common-prefix lengths; the breadth-first prefix tree (children
contiguous and sorted, so lookups are binary searches and node order is (depth, text)) is then built level by level —
a key's node at depth d is new exactly when its common prefix with the previous key is shorter than d — into arrays of
exactly the node count (0.7.1: the children ranges as one array, `start[i] … start[i + 1]`, because the breadth-first
layout puts the children of node i + 1 right after those of node i, and the key of a terminal node through a bit set
with a rank directory, which saves 6 bytes per node against separate child counts and a key per node; two further bytes
per node hold the best offered class and the case kinds of the forms below): de-DE ≈ 1 091 400 nodes (322 189 keys since 0.6.7 with the abbreviation titles and prefix compounds; 1 089 000 nodes and 320 804 keys in 0.6.6 with the dictionary-only lemmas; 1 781 000 nodes and 857 274 keys in 0.6.5 with all dictionary-only forms; 717 000 nodes and 236 223 keys in 0.6.4), en-US ≈ 385 000, es-ES ≈ 516 000, pt-BR ≈ 369 000, it-IT (185 063 keys since 0.6.7, 185 711 before),
fr-FR (174 238 keys since 0.6.7, 174 316 before). Explicit forms are a sorted index array with their
texts; pair-table followers go straight into primitive arrays. Two languages with pair tables retain 24–35 MB of JVM
heap without German and 38–42 MB with German since 0.6.6 (56–60 MB in 0.6.5, 31–35 MB in 0.6.4) and load within a 56 MB
maximum heap (0.6.5: 80 MB, 0.6.4: 44 MB), three 39–43 MB without and 50–57 MB with German within 68 MB (0.6.5: 88 MB,
0.6.4: 60 MB) (`MemoryBudgetTest`; before: transient copies of the decompressed files, one
string per key and per-character build arrays). Loading both takes ≈ 0.2–0.3 s on a desktop JVM. Completions (next-word filler) are a best-first search over the per-node maximum class; the key-slip search is a
best-first search over the per-node maximum offered class; the spelling source walks the cased forms in code-unit order
as (key node, case modes, explicit-form node) without a second tree (`CasedForms`): a key node's children are produced
once per case mode (L: as the key, F: first letter upper, A: every letter upper) where the case kinds below the child
have forms of that mode, merged with the children of the small tree of the explicitly stored forms. Shape writing reuses
the key tree as its word index; contact words not in the lexicon get a small second tree.

## Rebuilding the dictionaries

Requirements: Python 3.10+ (standard library only), ~4 GB free disk.

```sh
python3 tools/build_dictionaries.py download   # fetches tools/sources.json into build-data/src, verifies SHA-256
python3 tools/build_dictionaries.py build      # all languages; writes engine/src/main/resources/app/metrokeys/engine/dict/
python3 tools/build_dictionaries.py build es-ES   # one language (≈ 1.5 minutes once the Wiktionary passes are cached)
python3 tools/build_dictionaries.py build pt-BR   # ≈ 1.5 minutes once the Portuguese sections are cached
python3 tools/build_dictionaries.py build it-IT   # ≈ 8 minutes once the Italian sections are cached (three sentence passes)
python3 tools/build_dictionaries.py build fr-FR   # ≈ 10 minutes once the French sections are cached
python3 tools/build_dictionaries.py source-form 0.7.0   # the source form of the data files for the release (05b §2)
```

Since 0.7.0 every build also asks the spelling dictionaries for about a million keys per language (parallel over all
CPU cores, a few minutes the first time; the results are cached in `build-data/cache/spelling-<tag>-<hash>.tsv.gz`) and
reads the en-US word list for the colloquial path (all-language builds build English first). The build fails when a
size limit of 05b §6 is exceeded; a word list growing more than 25 % against the previous release (the shipped files
of that release copied to `build-data/previous-<release>/`) needs the owner's approval recorded in `tools/dictionary-approvals.json`. `MK_STATS_PATH` writes the
build report to another file (languages built in parallel processes, merged afterwards).

`build-data/` is git-ignored (downloads and caches; the Wiktionary passes are cached in
`build-data/cache/dewiktionary-20261001.tsv.gz`, `eswiktionary-20261001.tsv.gz`,
`enwiktionary-20261001-spanish.tsv.gz`, and for Portuguese the raw Portuguese sections `ptwiktionary-20261001-pt.tsv.gz`
and `enwiktionary-20261001-pt.tsv.gz`, for Italian `itwiktionary-20261001-it.tsv.gz` and
`enwiktionary-20261001-it.tsv.gz`, for French `frwiktionary-20261001-fr.tsv.gz` (titles with their language codes and
the reduced French sections, ≈ 5 minutes) and, only for the coverage report, `enwiktionary-20261001-fr.tsv.gz`, which
are classified on every build; the English dump takes ≈ 15 minutes per pass). Downloads are read in place (bz2/tar streams); from the SCOWL
archive only the plain lists and the `Copyright` file are extracted, no script is run. The build is deterministic
(sorted iteration, gzip with mtime 0); its counts and output hashes are written to `tools/dictionary-build.json`
and documented in [DATA-SOURCES.md](DATA-SOURCES.md). Pipeline modules (`tools/dictbuild/`):
`wiktionary_de.py` (§3.2; since 0.6.5 also the parts of speech of the German sections for the lemmas of §3.7), `build_de.py` (§3.3–3.7; since 0.6.6 the dictionary-only lemmas of §3.7; since 0.6.4 step 6a, the damping of non-German keys, also
in the pair table via `PairCounter(damped=…)`, and the real-word typos with the neighbour keys of
`spec/data/layouts-de.json`), `build_en.py` (§4), `wiktionary_es.py` (§8.2), `build_es.py`
(§8), `wiktionary_pt.py` (§9.2: section extraction and page kinds of both dumps), `build_pt.py` (§9.1, §9.3–§9.7),
`wiktionary_it.py` (§10.2), `build_it.py` (§10.1, §10.3–§10.6; also the §5 tokens of Italian),
`wiktionary_fr.py` (§11.2), `build_fr.py` (§11.1, §11.3–§11.7; also the §5 tokens of French),
`wiktionary_affix.py` (0.6.7: abbreviation and prefix titles of every dump, 05b §3.6; cached as
`build-data/cache/<dump>wiktionary-20261001-affix.tsv.gz`), `attested.py`, `spellcheck.py`, `combined.py`, `names.py` (0.7.0: spelling dictionaries, written-language frequencies, colloquial path, names, combined classes, 05b §12; DATA-SOURCES.md), `casing.py` (case decision §3.4, shared by de, es, pt, it and fr), `pairs.py` (§5, per-language pair profiles; Italian
prefix tokens and the elided-pair filter in `ItalianPairCounter`, the two French elided-pair filters in
`FrenchPairCounter`), `common.py`
(classes and binary formats, and their decoders for the build report), `licenses.py` (attribution texts, GPL-3.0 from 0.7.0), `provenance.py` (source-form exports of 05b §2). `build_dictionaries.py` holds one build profile per
language (`PROFILES`, 05b §7).

## Tests

`JAVA_HOME=/path/to/jdk-21 ./gradlew :engine:test` runs every worked example of 05 §17 and 06 §8, the
conformance checklist of 05 §18 (`ConformanceTest`) and unit tests of all parts, against the bundled dictionaries
where the spec's numbers depend on them. The rules of 05 §12.5 are covered with the real dictionaries: "usz" → "ist",
"Usz" → "Ist", "pqt" does not reach "ist"; an unknown word typed twice is still corrected and known after the third
use; acceptance by picking or "add to dictionary"; one undo for every word; forgetting; the version-1 data format. `MemoryBudgetTest`
loads every pair of languages with pair tables in a separate JVM with `-Xmx56m` (0.6.5: 80, 0.6.4: 44) and the sets of
three with `-Xmx68m` (0.6.5: 88, 0.6.4: 60), runs queries and a multilingual swipe (retained heap in 0.6.6 with the
dictionary-only German lemmas, KB / 1000: de+en 40.3 MB, de+es 42.4 MB, de+pt 38.3 MB, it+de 41.3 MB, fr+de 42.4 MB;
de+en+es 54.5 MB, de+en+pt 50.5 MB, de+es+pt 52.6 MB, it+de+en 53.5 MB, it+de+es 55.5 MB, it+de+pt 51.5 MB, fr+de+en
54.6 MB, fr+de+es 56.6 MB, fr+de+it 55.5 MB, fr+de+pt 52.7 MB; retained heap in 0.6.5 with all dictionary-only
German forms: de+en 57.8 MB, de+es 59.8 MB, de+pt 55.9 MB, it+de 58.7 MB, fr+de 59.9 MB; de+en+es 71.7 MB, de+en+pt
67.7 MB, de+es+pt 69.7 MB, it+de+en 70.7 MB, it+de+es 72.6 MB, it+de+pt 68.7 MB, fr+de+en 71.7 MB, fr+de+es 73.7 MB,
fr+de+it 72.7 MB, fr+de+pt 69.9 MB; sets without German as below; the peak while loading needs up to 76 MB for it+de;
retained heap in 0.6.2: de+en 32.8 MB, de+es 34.9 MB, en+es 28.6 MB, de+pt 30.8 MB,
en+pt 24.5 MB, es+pt 26.6 MB, it+de 34.5 MB, it+en 27.5 MB, it+es 29.6 MB, it+pt 25.6 MB; de+en+es 47.1 MB,
de+en+pt 43.2 MB, de+es+pt 45.4 MB, en+es+pt 38.8 MB, it+de+en 46.0 MB, it+de+es 48.0 MB, it+de+pt 44.1 MB,
it+en+es 41.8 MB, it+en+pt 37.8 MB, it+es+pt 39.9 MB; 0.6.3 with French: fr+de 34.9 MB, fr+en 28.7 MB, fr+es 30.8 MB, fr+it 29.9 MB, fr+pt 26.7 MB;
fr+de+en 47.2 MB, fr+de+es 49.2 MB, fr+de+it 48.1 MB, fr+de+pt 45.2 MB, fr+en+es 43.0 MB, fr+en+it 41.9 MB,
fr+en+pt 39.0 MB, fr+es+it 44.1 MB, fr+es+pt 41.0 MB, fr+it+pt 40.0 MB);
`SpanishExamplesTest`, `SpanishShapeTest` (06 E8/E9), `ActiveLanguagesTest` (the five-language example of 05 §14.5) and
`MultilingualTest.threeLanguages` cover 0.6; `PortugueseExamplesTest` (05 §17.9 and the pt rows of §18),
`PortugueseShapeTest` (06 E10/E11, acceptance test) and `TouchModelTest.portugueseGeometry` cover 0.6.1;
`ItalianExamplesTest` (05 §17.10, the it rows of §18, accent swap, learning and proofing of elided words),
`ItalianShapeTest` (06 E12–E14, elided index, acceptance test) and `ActiveLanguagesTest.theFiveSupportedLanguages` (since 0.6.3 `theSixSupportedLanguages`)
cover 0.6.2;
`LexiconStructureTest` checks the trees built from the front-coded keys and the streaming key lookup;
`LearnedWordListTest` the list of every stored unknown word (also from learned data without dictionaries).
`ShapeWriterTest` covers the multilingual example of 06 §4.1 (d-a-s → das · dass · daß · da · dad) and the language
penalty. `-PshowOutput` shows test output.

0.7.1 tests: `WorkedExamplesTest` (05 §17.1–17.7a with the anchored costs) and the language example tests (§17.8–17.10);
`SpellingSourceTest` (05d: every edit kind and group rule with hand-computed costs, rounds and confidence, case
penalty, two-word suggestions, transliterated spellings, cancellation and the introsort order of equal costs); `KeySlipTest` (the
best-first search returns the same lists as a brute-force scan of the trie, tie order, pair followers, learned and
contact words); `PipelineRulesTest` (merge and tie order of §8.4, the filters, the bar cut of §9, the class-0 rule,
do-not-suggest forms, the P3 steps 4o/4m/4t, the omission guard 7o, the missing-space guard 7s, the apostrophe guard
7e, two-word candidates, the AC edit weights, unoffered spelling candidates); `LmCostTest` (anchors, slopes, inverse);
`LexiconTest` (format version 3, derived data of the bundled lists, the fallback derivation, the cased forms in
code-unit order); `MemoryBudgetTest` with the compacted trie arrays (German alone 29.8 MB retained, 0.7.0 32.8 MB; the
largest set of three, fr + de + it, 59.3 MB, 0.7.0 64.1 MB; every pair and set of three passes the limits above);
`tools/dictbuild/test_build_071.py` (the build's derived data, register labels, the real-word typo rule without
the withdrawn abbreviation exemption and the abbreviation bit). 0.7.1 review: `ShortKeysAndNamesTest.zi` /
`abbreviationBit` ("zi", "fem", "ind", "dor" are class 0 with the abbreviation bit; the bit in all six lists),
`WorkedExamplesTest` ("zi" + space → "zu", "ind" → "und", "wie geht es dor" → "dir", "das ist fue" → "für"),
`UmlautTranslitTest.completions` (§8.1c length rule, "Strass" → "Straßen"), `TextRulesTest.periodKeepsAnAbbreviation`
and in the app `InputLogicTest.anAbbreviationTypedWithItsPeriodStays` ("Zi." and "fem." stay, "zi " → "zu ").
Unknown words always corrected (0.7.1 review): `PipelineRulesTest.runnerUpMarginOnlyForValidTypedWords` (a synthetic
list: the same close corrections keep a valid rare word and correct the unknown one; "nir", "fue", "ind") and
`omissionGuardNotForTheOmissionReading` ("das mchte" → "möchte", "diees" still 7o) and
`omissionGuardAppliesTheOmissionReadingForUnknownWords` ("Kanst" at the text start → "Kannst" in step 7r; "diees"
stays because "dieses" is only a completion; "Aut" → "Auf"; valid typed words stay); `WorkedExamplesTest` ("das mchte"
→ "möchte", "das ist scon" → "schon", "ich habe es gesen" → "gegen", "Ich habe nir" → "mir") and
`SpanishExamplesTest.example7MissingSpace` ("aveces" → "veces", "a veces" among the next three).

## Latency (0.7.1)

A query runs off the input thread and is cancelled by the next keystroke (`QueryContext.cancel`, checked every 1 000
visits of the spelling walk and between sources; `InputLogic` passes a generation check). The spelling walk also stops
at `SPELL_VISIT_MAX` 200 000 visits (05d §7). Measured per query over the salt-A test sets (every item, typed word
with its taps, no learning, no swipe):

- JVM (`MK_LATENCY=1 ./gradlew :engine:test --tests '*LatencyBenchmark*'`, JDK 21, after warm-up): mean / p95 / max
  in ms — de 1.75 / 3.96 / 8.07, en 1.07 / 2.31 / 9.17, es 1.14 / 2.53 / 5.49, fr 1.78 / 3.48 / 7.72, it 0.96 / 2.00 /
  4.69, pt 1.13 / 2.41 / 5.12. German spelling visits: mean 20 198, p95 45 071, max 80 439 (the budget is never hit).
- Emulator (`SERIAL=emulator-5580 tools/device-benchmark.sh`: the engine shrunk with R8 in release mode as in the
  release APK, run with `app_process`, 2 000 items per language): de 3.02 / 6.58 / 13.84, en 1.83 / 4.03 / 6.94, es
  2.04 / 4.48 / 10.06, fr 3.19 / 6.09 / 9.42, it 1.72 / 3.54 / 5.63, pt 2.04 / 4.30 / 7.83 — p95 well below the 50 ms
  of the task, so no further optimisation was needed.

## Decisions where the specification leaves room

- **Search fields and the double-space period** (0.6.8): on, as in the reference (04 §2, §8.2 and 05 §15 agree since
  0.6.8; before, 04 was followed and it was off).
- **ED_CASE on substitutions** (05 §5): added to every substitution branch (diacritic, space, neighbour) when exactly
  one of the two letters is upper case.
- **Autocorrection runner-up** (05 §10.1 steps 4o and 7, 0.7.1): among the orthographic candidates the second one in
  ranked-list order; otherwise the second correction in AC-score order (equal AC scores in ranked-list order). From the
  0.7.1 review (owner decision) step 7 applies only to a typed word that is valid for the decision: an unknown word or
  a class-0 form never stays because two corrections are within `AC_MARGIN` ("Ich habe nir" → "mir", "das ist fue" →
  "für", "ind" → "und"). The omission guard of step 7a is not applied when such a word's best correction is itself the
  typed word with one letter inserted ("das mchte" → "möchte" although "machte" is close). With any other best it
  still applies, but for such a word it applies the omission reading instead of none: the first correction in
  AC-score order (not best, not the typed word, not flagged) that is the typed word with one letter inserted, with an
  lm within `AC_OMISSION_MARGIN` of best's and an AC score within `AC_LIMIT` ("Kanst" at the text start → "Kannst",
  although "Knast" has the same AC score and ranks first; diagnostic step `7r`). Only without such a correction is
  the result none ("diees" stays: "dieses" is only a completion, and completions are never applied). Valid typed
  words keep the guard's plain "none". A valid typed word is offered by the spelling source and is its own orthographic
  candidate, so its decision normally ends in step 4o (4t) before step 7.
- **Unattested keys** (05 §10.1 step 5; off — owner decision for 0.7.0, the flag marks the colloquial-path keys for the
  evaluation of 05c only): the rule is behind
  `LanguageParams.unattestedKeysRule`, whose default for every language is the one switch `Params.UNATTESTED_KEYS_RULE`
  (false); the flags are stored and read, but with the switch off `Validity.unattested` is never set and step 5 is as in
  0.6.8. The rule's tests enable it with `LanguageParams.GERMAN.copy(unattestedKeysRule = true)`. With the switch on,
  `Validity.unattested` is set when the word is valid through rule 1 or 2
  of §7 (also the unaccented capitals of French) with an unattested key whose most frequent form (the first stored
  form) has no upper-case letter (names keep blocking, owner decision) and is neither a known learned word nor a
  contact word (also its decapitalised form); step 5 then counts the word as rare unless `best.word`'s key is
  unattested too. Rules 3–7 (learned, contacts, rejected, compounds, -in) never set it; rejections are checked before
  step 5 as before. `UnattestedKeysTest` (synthetic lexicon: corrected, not to an unattested word, not for a capitalised key, not when learned or a
  contact, not after a rejection; flags of the bundled files); `UnattestedReport` (`MK_UNATTESTED_REPORT=1`) lists every
  unattested German key that a space now corrects (spec/QUESTIONS.md: 1 087, of them 111 blocked before; 95 of
  those also with German + English).
- **Compound validity cost** (05 §7.6): lm of head and tail without pair context.
- **Missing-space split** (05 §8.2): only for languages without compounds (`LanguageParams.missingSpaceSplit`: English
  and Spanish yes, German no). `LanguageEngine.splitLm` costs a split from the static lists only: the pair cost when the
  pair table has (a, b′), else the floored mean of the static unigram costs plus `SPLIT_SURCHARGE`. One-letter heads
  only the language's `ONE_LETTER_WORDS` in either case (English "i" is shown as its dictionary form "I").
  `MissingSpaceTest` covers the examples of §8.2.
- **Letters without a key** (05 §4): `KeyboardGeometry.touchKey` maps any letter without a key of its own to the key of
  the letter with its diacritic removed (and ß, § to s), so ñ lives on n on the German and English pages and is its
  own key on the Spanish page; every language's engine uses the geometry of the current page (05 §14.4).
- **Double letter** (06 §4.3 A5, open point 10): only the same letter twice (base letters compared, so "eé" counts).
- **Contact possessives** (05 §7.4): "Anna's" gets the cost of "Anna" (the possessive itself has no LM cost).
- **Language weights**: words with digits are skipped and do not consume a decay step.
- **Learning target in multilingual mode** (05 §14.4): the only language that knows every part; otherwise the
  highest weight among the languages that know it, or among all.
- **Proofing suggestions**: correction candidates of the word alone (no context, no taps); words beyond the first
  20 newly marked distinct words are marked without suggestions.
- **Shape writing**: tail trimming uses a strict "< 24 ru" test (this reproduces the shape distances 3.5/13.9 of
  06 E2); the double-letter rule (A5) needs the letter's key at that position; the corner test of A4 uses ≥ 45° as
  written; in multilingual mode every language's index is searched on the current layout and its totals are raised
  by `swLmPerClass × −log10(max(w, 10⁻⁴)) / Bc` (06 §4.1); learned words are not in the shape index (open point 3).
  The stage-A dominance table is an open-addressing map over primitive arrays (≤ 16 bytes per slot).
- **Touch keys**: the touch model uses the alternates listed in 05 §4 (ä → a …), shape writing removes any
  diacritic (06 §4.2). Nominal key size = the most common letter-key width/height (05 open point 3).
- **Completion ties** (05 open point 2): in the next-word filler equal classes are returned shorter first, then
  alphabetically; in the key-slip lists equal totals by higher class, then the form in code-unit order (05 §8.1).
- **Next-word display**: equal costs are ordered by text as the rule says (in 0.6.4 "Das 49" preceded "Sie 49"; since
  0.6.5 "Das" costs 50, see the German dictionary-only forms below).
- **Stricter learning everywhere**: 05 §12.2's "typed twice" text and its worked example predate §12.5; §12.5 is
  applied to every use of learned words (the worked example is tested with an accepted word).
- **Learned words list**: every stored form the dictionaries do not know (also used once); each stored case form is its
  own entry; forgetting works on the word's key (all case forms).

- **Shape writing per language**: `ShapeWriter(engine)` takes the constants of `engine.params.shape`; in multilingual
  mode every index is searched with its own language's constants. English acceptance test (06 §4.6, 76 common
  words, fallback grid, ±10 % jitter and up to 15 % offset): German constants (before 0.6) 0.947 at seed 42, mean
  0.948 over 20 seeds; English constants 0.947 at seed 42, mean 0.946 (min 0.934). Spanish: 0.983 (58 words).
- **Spanish worked examples** (05 §17.8): outcomes are tested in `SpanishExamplesTest` together with the computed values
  of this build (05 open point 10): lm(canción) 87, lm(mañana | hasta) 56, lm(dámelo) 101, score(por favor) 177,
  score(España) = lm + 29, score(Hola) = lm + 65. Measured deviations from the expected outcomes are in
  spec/QUESTIONS.md ("Muchas " → "gracias" is fifth, "aveces" scores 218 > 217).
- **Slip accents** (05 §8.1, `SLIP_ACCENTS`, 0.7.1; up to 0.7.0 the prefix variants of `PREFIX_ACCENTS`): a typed base
  letter may stand for each listed accented letter at any number of positions (Portuguese "acao" reaches "ação" with two
  accent slips, `PortugueseExamplesTest`); a typed letter without an entry uses the entry of its base letter.
- **Elided prefix** (05 §2.2, `ELISION_PREFIXES`): `LanguageEngine.suggest` strips the language's prefix ("d'", also
  typed with `’`) when the word continues with a letter and is not itself an exact form, runs the query for the
  remainder without pair context (the character before the prefix still decides `AC_BLOCK_PREV`) and puts the prefix
  as typed in front of every candidate; the prefix alone gives an empty result. `typedValidity`, `isKnown`
  (proofing), `learn`, `addWord` and `rejectAutoCorrection` use the remainder (`stripElision`). Other languages see
  the whole word.
- **Ordinal indicators** º ª: word characters (letters), never autocorrected (§10.1 step 3, like digits) and never
  checked by proofing (`TextModel.isCheckable`).
- **Space before a swiped word** (06 §5.1 item 1, all languages, 0.6.8 as in the reference): `TextRules.spaceBeforeSwipe`
  inserts no space after whitespace (every Unicode space), a line break, an object marker (U+FFFC) or one of
  `¿ ¡ ( " - # @ /` ("(" + "word" → "(word", "deixe-" + "me", " -" + "word" → " -word"; before 0.6.8 only after `¿ ¡`
  and a hyphen that followed a letter). The elision-prefix exception is applied by the keyboard
  (`PredictionEngine.isElisionPrefix`). `TextRules.capitalizesSwipe` is true directly after `. ! ? :` or the danda
  (06 §5.2: a swiped word there is capitalised like after a one-shot shift).
- **Swipe candidate** (06 §2.1, 0.6.8): `ShapeWriter.isSwipeCandidate(y, height)` no longer asks for a letter key; a
  touch in the upper 75 % may start a swipe on whatever key it lands (on the letter pages also Shift and Backspace).
  The recogniser already ignores samples outside the letter keys (§3.2), so the first letter key reached starts the
  sequence. `KeySequenceTracker` builds the key sequence sample by sample (the live word of 06 §5.3);
  `ShapeWriter.keySequence` uses it, so both agree. `PredictionEngine.geometry` exposes the current letter geometry.
- **Portuguese worked examples** (05 §17.9, 06 E10/E11): `PortugueseExamplesTest` and `PortugueseShapeTest` with the
  computed values of this build: lm(você) 41, score(não) 91, lm(amanhã | até) 51, lm(ação) 89, lm(por favor) 68,
  score(d'água) = lm(água) + 37; E10 searchCost(faço) = 120 + LM; E11 searchCost(você) = 121.5 + LM (spec 120.4 with
  rounded angles). Shape-writing acceptance test for Portuguese (51 words with ç, ã, õ): see the test output.

- **Elision, general form** (05 §2.2, §8.2.1; 0.6.2): the prefix is the part up to the word's first apostrophe and
  must be one of the language's `ELISION_PREFIXES` (pt: `d'`; it: 41). The remainder is predicted with the prefix
  (lower case, `'`) as previous word, so the Italian pair table predicts it from the prefix and the Portuguese one gives
  no context, as before. A one-letter remainder is valid only if it follows the prefix (static or learned), and §10.1
  step 2 does not apply to it (`c'e` → `c'è`). A bare prefix (`dell'`) yields the §11 items 1–2 followers as
  completions "prefix as typed + follower" (all caps after an all-caps prefix). In languages with a prefix table
  (`Lexicon.hasPrefixTable`) the elision split, elided completions and prefix candidates of §8.2.1 are generated in
  `LanguageEngine.elisionCandidates` from `prefixFollowers` (static followers, cached per prefix, plus learned followers
  with count ≥ 2, only those starting with a vowel or h), costed as lm(P′F) = cost(p_pre(P′ | w₋₁) · P(F | P′)). A prefix
  candidate is flagged (`Suggestion.isElisionPrefix`) so the keyboard picks it without a space.
  `PredictionEngine.isElisionPrefix` tells the keyboard that a swipe follows a prefix (06 §5.1).
- **Elided previous words** (own decision, prefix-table languages only): a previous word with an elided prefix counts
  as its remainder for pairs (`dell'anno` + `scorso` uses (anno, scorso), as the pair build counts it) and as prefix +
  remainder in learned contexts; Portuguese keeps the whole word (see spec/QUESTIONS.md).
- **Learning elided words** (§12.1): `UserModel.learn` takes the language's prefix test; "dell'anno" learns "anno" with
  "dell'" as last context word, a bare prefix is never learned.
- **ACCENT_SWAP** (§5): `EditDistance.isAccentSwap` (both letters carry a diacritic, same base letter) costs
  `ED_DIACRITIC`; a typed accented letter for a plain one stays a substitution of the same key (`ED_SUBSTITUTE`).
- **Class-0 spellings** ("perche", "citta", "cè", German real-word typos, Portuguese pre-reform spellings): valid, never
  offered (0.7.1: no class-0 form is an offered form in any language, 05 §8), and not valid for the autocorrection
  decision (05 §10.1 step 4); `hideClassZero` (de, it, fr) now only keeps them out of shape writing.
- **Elided words in shape writing** (06 §4.1): a second index (`Lexicon.fromEntries`, 6 506 entries since 0.6.7, 6 581 before) of prefix +
  static follower with the class of p_pre · P(F | P′); its context bonus mixes P(prefix | previous word) into p_pre. After
  a prefix ("l'" + swipe) the previous word is the prefix and the elided index is not searched.
- **Italian worked examples** (05 §17.10, 06 E12–E14): computed values of this build: lm(perché) 53 (score 54),
  lm(più) 60, lm(città) 78, lm(però) 79, lm(è | c') 22 (score 32), lm(l'acqua) 81, "Un " → po' first, "dell'" →
  dell'ultimo, dell'acqua, dell'anno …; E12 searchCost(più) = 16 + LM, E13 class(c'è) 215 against class(ce) 195
  (c'è first), E14 searchCost(l'acqua) = 194.4 + LM (the synthetic trace's skipped angles sum to 92° instead of 78°).
  Deviations from the spec's expected outcomes are recorded in spec/QUESTIONS.md ("sé" not in the bar after "se";
  "perch" + space → "Percy" until 0.6.6, since the damping of 0.6.7 "perché" stands first and nothing is autocorrected). Shape-writing acceptance for Italian: 0.936 (47 words incl. elided words).

- **French (0.6.3)**: `LanguageParams.FRENCH` with the fr-FR column of 05 §1.1 and 06 §7.1, the 14 elision prefixes,
  `ACCENT_SWAP` (also á → à, the picker slip), `hideClassZero` (the dominated spellings of 05b §11.4 step 2 are valid
  but never offered), `ELISION_VALID_SLACK` 30 and `CAPS_UNACCENTED`; AZERTY fallback grid (05 §4, row 3 from 0.2 ·
  width). `FrenchExamplesTest` (05 §17.11, the fr rows of §18), `FrenchShapeTest` (06 E15–E17, AZERTY neighbourhoods,
  compounds, ligatures, acceptance test) and `FrenchInputLogicTest` (app) cover it.
- **Ligatures** (05 §5, every language): `EditDistance` treats œ/æ as o/a with a diacritic (typed o for œ costs
  `ED_DIACRITIC`) and has the ligature rule (typed "oe"/"ae" for œ/æ: `ED_DIACRITIC`, plus `ED_CASE` when the case of the
  first typed letter differs; UNITS 1); the prefix list of §8.4 adds the variant with "oe"/"ae" replaced by œ/æ when the
  language's `PREFIX_ACCENTS` list a ligature; follower matching of §8.2.1 expands œ → oe; the touch model and shape
  writing put œ/æ on the o/a keys (`KeyboardGeometry.baseLetter`).
- **Umlaut transliteration** (`UMLAUT_TRANSLIT`, de only; 0.7.0, 05 §5, §8.4 item 5): `EditDistance` has the umlaut
  rule (typed "ae"/"oe"/"ue" for ä/ö/ü: `ED_DIACRITIC`, plus `ED_CASE` when the case of the first typed letter differs;
  UNITS 1, which equals the diacritic-plus-deletion path, so the unit filters are unchanged) and the prefix list adds W
  with every "ae"/"oe"/"ue" replaced by the umlaut (case of the first letter kept, after the other prefixes; the plain W
  stays first). "fuer" → "für", "ueber" → "über", "Ueber" → "Über", "koennen" → "können" (ed 4 each, autocorrected
  without context); "fue" completes to "für", "Feue" still to "Feuer" (`UmlautTranslitTest`). The completion prefix of
  §8.3 still counts an umlaut as one character ("fue" against "für", ed 34; spec/QUESTIONS.md). No worked example of
  05 §17 or 06 changed.
- **Unaccented capitals** (`CAPS_UNACCENTED`, fr): validity rules 1 and 2 accept a stored form whose display differs only
  by accents on capitals the user typed without them (`Lexicon.looseKeys` walks the tree with every unaccented capital
  matching its accented letters); the edit cost of such a pair is 0 and UNITS counts 0.
- **Completion prefix** (05 §8.3, every language): the shortest beginning of the candidate that holds len characters
  when `'` `.` `-` are not counted and a ligature counts as two (`LanguageEngine.completionPrefix`).
- **Hyphen compounds and apostrophe words** (05 §2.2, 05b §11.5): the dictionary stores them under alias keys
  ("estce" → est-ce, "aujourdhui" → aujourd'hui; an apostrophe word also under its own key). `Lexicon.keyIndex` looks a
  word with a hyphen up under its alias key, so `exact`, `exactClass` and the LM cost of a compound work; typed without
  the hyphen it is reached through `canonical` (filler 0), completions and the shape index. A picked or autocorrected
  compound is learned piece by piece (`UserModel.learn` splits at hyphens too), and the learning language is chosen
  from its pieces.
- **Letters of an elision prefix** (05 §8.5, §10.1 step 2a, every language with prefixes): a typed word whose lower-cased
  letters are those of a prefix and that is not valid is never autocorrected; with two or more letters (and a prefix
  table) the prefix candidate stands first in the ranked list, and in multilingual typing first after the
  autocorrection when the top language has it (`LanguageSuggestions.prefixFirst`). Italian "dell" + space stays "dell"
  (before 0.6.3: "delk").
- **Elision followers** (05 §8.2.1): "vowel" includes y and the ligatures (all languages with prefix tables, so Italian
  followers starting with y now count as well).
- **French worked examples**: computed values of this build are printed by the tests (lm(très) 66, lm(déjà) 72 → score
  107, lm(j'ai) 50, lm(jusqu'à) 78 → score 89, lm(qu'elle) 69 against "quelle" 76, lm(est-ce) 63, lm(peut-être) 70).
  Deviations from the spec's expected outcomes are recorded in spec/QUESTIONS.md ("Il ouvre la fenetr": "fenêtre" first
  but not autocorrected, AC_MARGIN; "etje" → "être"). Shape writing: E15 searchCost(moi) = 0 + LM, E16 c'est = 42 +
  skipped angles / 5 + LM (the synthetic trace's corner at d is flatter than the spec's 75°), E17 très = 8 + LM; the
  French acceptance test (46 words with a z q w m, accents, ligatures, elided and hyphenated entries): see the test
  output.

- **Diacritic units (0.6.4, 05 §5, §8.1)**: `EditDistance.units` counts a diacritic difference (typed base letter for an
  accented letter or a ligature of the candidate, and an `ACCENT_SWAP` pair in languages with that rule) as 0 units;
  the ß and ligature rules keep 1, a typed accented letter for a plain one stays 1. ED is unchanged. `Lexicon.fuzzy`
  walks the tree on accent-folded letters (`EditDistance.fold`: diacritic removed, œ → o, æ → a) within `maxEdits`
  and ranks the hits by their unit distance to the typed word, then class, then key; the correction pool, the learned
  and contact words and the UNITS filters of §8.1/§8.3 use the same units. No worked example of any language changed
  ("Uberpeufe" → "Überprüfe", `DiacriticUnitsTest`).
- **German class 0 (0.6.4)**: `LanguageParams.GERMAN.hideClassZero` is on, so the real-word typos of the German build
  (05b §3.6, class 0) are valid but never offered and are autocorrected through §10.1 step 5 ("Ich komme heure" →
  "heute", "zi" → "zu"). The damped keys (names, foreign words) counted below 100 times also end at class 0 and are
  hidden the same way (spec/QUESTIONS.md). `ShortKeysAndNamesTest` covers 05 §17.7a, step 6a, the damping and the
  real-word typos; "Das war really" now scores 242 (best candidate "reales"; 05 §17.3 gives 236 with "Reilly").

- **Dictionary-only German lemmas (0.6.6, 05b §3.7)**: every German Wiktionary lemma (a title whose German section has a
  part of speech other than an inflected-form, participle or comparison entry) whose key occurs at most once in the
  subtitles is in the word list with class 1 (84 581 keys: 69 796 unseen, 14 785 seen once; DATA-SOURCES.md). 0.6.5 added
  every form without a subtitle occurrence (621 051 keys). They are valid, offered and correction targets like any rare
  word: "Die App ist nicht performanz" offers "performant" first (no autocorrection; "Performanz" second), also in
  de+en (`ShortKeysAndNamesTest.performanzOffersPerformant`). All German worked examples are unchanged except "Das" at
  the text start, 50 (05 §17.7: 49), because the new keys are also counted in the pair sources; after "Vielen " anderen
  52 and Fällen 54 are again the values of 05 §17.7 (0.6.5: 53 and 55); spec/QUESTIONS.md.

- **Short keys and names in es, pt, it, fr (0.6.7, 05b §3.6 "Other languages")**: the Spanish, Portuguese, Italian and
  French dictionaries drop non-form keys of up to 3 letters counted below 20 000 times and count the keys kept only by
  the count rule (names and foreign words of the dubbed films) with count / 100 (DATA-SOURCES.md). No engine change:
  damped keys counted below 100 times get class 0, which Italian and French hide (`hideClassZero`; valid, never
  offered, autocorrected by §10.1 step 5 like the German ones), while Spanish and Portuguese keep offering class-0
  words with the lowest rank (spec/QUESTIONS.md). Changes in the printed test values: Italian "perch" → no
  autocorrection, "perché" first ("Percy" class 124 → 49, `ItalianExamplesTest.example7Completions`); E12's fifth
  candidate "Pu" (dropped by step 6a) is replaced by "Po", E17's fifth "Tess" by "Ted"; elided shape-index entries it
  6 581 → 6 501 (6 506 with the abbreviation titles), fr 14 457 → 14 438. `ShortKeysAndNamesTest.step6aAndDampingInOtherLanguages` checks dropped, kept and
  damped keys of the four languages. `MemoryBudgetTest` retained heaps are at most 0.15 MB lower (de+es 42.4 MB); shape-writing acceptance values are unchanged.

- **Abbreviation titles (0.6.7, 05b §3.6)**: a Wiktionary title ending in "." makes the key without it a form in
  German, Spanish, Portuguese, Italian and French; a title ending in "-" (a prefix) is no form, only the first part of
  a German compound, and a key among the 5000 most frequent English keys never becomes a form this way (English guard;
  DATA-SOURCES.md; English unchanged, its build uses no Wiktionary). No engine change: "usw", "bzw",
  "etc", "vgl" are German words and stay as typed ("Äpfel, Birnen usw" is not autocorrected), Spanish "etc" too
  (`ShortKeysAndNamesTest.abbreviationTitlesAreForms`); German "of" (prefix "of-") stays dropped by step 6a. The German
  key count of `LexiconStructureTest` is 322 189.

- **Evaluation diagnostics (0.7.0, 05c §5)**: `PredictionEngine.queryDiagnosed` (internal, used only by the engine's
  tests) returns the result of `query` together with `QueryDiagnostics`: the step of 05 §10.1 that ended the
  decision (`LanguageSuggestions.decisionStep`, internal: "1" … "8", or "off", "14.2 language weight", "14.3 foreign
  word" in multilingual typing), the typed word's validity with its rule of 05 §7, whether its key is unattested, and
  for every bar entry `lm`, `ed` and the score of §10.1 step 6. Nothing else changes; `EvaluationDataTest` checks that
  both return the same prediction. The dictionary evaluation itself (lint, engine runs, report) is described in
  [EVALUATION.md](EVALUATION.md).

- **Dictionaries of 0.7.0 (05b §12)**: no engine change. With the combined classes the header constants follow the
  built lists (05 §3.1): en-US Bc 0.02927 (S 14.8356), es-ES 0.02862 (S 15.1738; "de" now the most frequent key),
  pt-BR 0.02808 ("de"), it-IT ("di"), fr-FR (S 15.6824). Printed test values that changed (outcomes unchanged): English
  lm(receive) 94 (97), class(don't) 226 (228), unigram(class 131) −5.006 (−5.041); Spanish lm(canción) 86 (87),
  lm(mañana) 76 (72), lm(ano) 110 (112), lm(dámelo) 113 (101), class(etc) 137 (107); Portuguese lm(você) 42 (41),
  lm(ação) 87 (89), score(não) 92 (91). 06 E17: "très" is still inserted, but the elided "t'es" now precedes "tes" as
  the first alternative (spec/QUESTIONS.md). The names and foreign keys of the 0.6.9 count rule in es, pt, it and fr are
  dropped by the colloquial path or kept as names (`ShortKeysAndNamesTest.step6aAndColloquialPathInOtherLanguages`).
  German (rebuilt with the approved growth): Bc 0.02563 (S 16.9479), 413 815 keys and about 1 295 000 tree nodes;
  printed values that changed include class(Haus) 184 (183), lm(Haus) 58 (57), class(dann) 218 (212), class(für) 233
  (221), lm(Kaffeetasse) 126 (127), class(performant) 12 (1, now admitted by its written frequency), and "Das" at the
  text start 49 (50; again the value of 05 §17.7). Outcomes: "Cursos" offers "Crusoe", "Cursor", … ("Cursor" among the
  first three, "Curtis" no key: 05 §17.7a as amended), "dad", "the", "Curtis" and "Captain" are no keys any more, "wär"
  and "gibts" are flagged colloquial keys; the other German outcomes are unchanged.

- **Candidate pipeline of 0.7.1** (05 §8–§10, 05d): see "Data flow of a query". Decisions where the text leaves room
  (spec/QUESTIONS.md, 0.7.1):
  - the typed word itself is an orthographic candidate of step 4o (it keeps every typed letter); a valid typed word is
    therefore replaced only by an orthographic candidate that ranks more than `AC_MARGIN` above it ("Stuck" → "Stück");
  - a learned word that is a case error ("haus" learned for "Haus") is no user word of §8.1a (it never counts as known,
    §7 rule 3), so "Das ist das haus" is still corrected after "haus" was learned;
  - the unpaired elision candidate of §8.2.1 item 1a of a valid typed word is kept, like an elision split, only within
    `ELISION_VALID_SLACK` (French "lui" offers no "l'ui");
  - a two-word spelling suggestion is costed with the parts as stored forms, a capitalised part looked up decapitalised;
  - spelling source (05d): a space mark is checked once at the next letter and then cleared (§4.2); a finished group rule
    aligns p with the newest candidate letter (§4.6 step 5); the accent marks of a rule letter compared in folded form
    follow the letters compared; a form stored twice (an alias key and its own key) is walked once with the better
    spelling class;
  - the key-slip lists are computed best first with the bound of the highest offered class below a node; the forms of
    an all-exact key enter with total 1 before the walk; `KeySlipTest` checks the lists against a scan of every key.
- **Abbreviations and the period** (0.7.1 review, owner decision; 05 §2.1, 04 §3.2 item 9): German real-word typos
  that are also abbreviations ("zi", "fem", "ind", "dor") are class 0 like every real-word typo. `Prediction` reports
  `typedIsAbbreviation` (the typed word's key, of an elided word its remainder, carries the abbreviation bit in the
  deciding language: the only one, in multilingual typing the top language); the keyboard does not apply the
  autocorrection when the trigger is "." and the flag is set. The decision of §10.1 does not depend on the trigger.
- **Typing queries off the input thread** (05 §2.3, 05d §7): `PredictionEngine.query(q, cancel)`; the keyboard's ahead
  query is cancelled when a newer word state replaces it, its delayed strip query when the input generation changes.

Questions with assumptions that matter are recorded in the specification (`spec/QUESTIONS.md`, not published).
