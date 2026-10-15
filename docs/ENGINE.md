# MetroKeys engine

`engine/` is a pure Kotlin/JVM library (package root `app.metrokeys.engine`, no Android dependency). It contains the
dictionaries and everything of spec 05, 05b, 06 and the engine-side rules of 04: prediction, autocorrection,
next-word prediction, learning, contact names, multilingual typing, spelling marks and shape writing. The Android
app (next stage) only translates editor state into queries and applies the results.

## Packages

| Package | Content |
|---|---|
| `app.metrokeys.engine` | public facade: `PredictionEngine`, `Query`, `Prediction`, `Suggestion`, `EngineSettings`, `LearnedDataStore`, `Proofreader` |
| `…engine.lexicon` | runtime dictionaries: `Dictionary` (word list + pair table), `Lexicon` (prefix tree with per-subtree best class, `forms/exact/canonical/frequency/completions/fuzzy/words`), `PairTable` (`bigram/followers`, bound to its word list) |
| `…engine.predict` | per-language engine `LanguageEngine` (LM cost §3, case mode §6, validity §7, candidates §8, autocorrection decision §10.1, next word §11), `EditDistance` (§5), `LmCost`, `UserModel` (§12, persistence), `ContactNames` (§13), `LanguageParams`/`Params` (§1) |
| `…engine.text` | `TextModel` (word characters, current word, contexts §2.2), `TextRules` (04: auto-shift, double-space period, triggers, smart/trailing space, word delete, autocorrection gate), `FieldPolicy`/`FieldInfo` (§15 incl. Android input-type mapping), case helpers |
| `…engine.touch` | `KeyboardGeometry` (letter keys from `spec/data/layouts-*.json` or the fallback grid), `TouchModel` (§4), `Tap`/`TapSequence` |
| `…engine.swipe` | `ShapeWriter` (06 §3–§4: key sequence, angles, stage-A search, templates, DTW, context, unrecognised gestures), `ShapeTrace`, `ShapeResult` |

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
if (TextRules.mayApplyAutocorrection(/* keyboard-side conditions of 04 §3.2 */)) …
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
for the licences screen. Queries are thread-safe; learned data is synchronised internally.

## Data flow of a query

1. `QueryContext.of(before, taps)` extracts the current word, the character before it, the pair context
   (`PrevContext`: sentence start / word / none) and the preceding words of the sentence; taps are aligned from the
   end.
2. Each running `LanguageEngine` computes validity, the correction pool (fuzzy trie search, ß variant, canonical
   form, learned and contact words), the missing-space split, completions for the typed and the variant prefixes,
   the final filtering (CORR_KEEP) and the autocorrection decision.
3. `PredictionEngine` builds the bar: single language → autocorrection first, then the ranked list; multilingual →
   language weights from the preceding words, cost + 18 · (−log10 w) per language, merge, top-language-only
   autocorrection with the foreign-word block, emoji from the top language.

## Learning, acceptance and forgetting (05 §10.4, §12)

`UserModel` keeps per language: counts with last-use stamps and an *accepted* flag per stored form, n-gram followers,
the recent window, and the undo table with an undo count per typed word.

- **Known learned words** (`isKnownLearned`): a form whose key the dictionary knows in some casing needs
  `LEARN_MIN_COUNT` (2) uses; a word the dictionary does not know needs to be accepted, or `LEARN_MIN_UNKNOWN` (3) uses,
  or `REJECT_MIN_UNKNOWN` (2) undos. This one test is used for validity rule 3, the LM cost of learned-only words,
  the correction pool, completions, compound parts and learned next-word followers.
- **Acceptance**: `learn(…, accepted = true)` when the typed word itself is picked, `reject(…, accepted = true)` when an
  autocorrection is undone by picking the typed word, and every `addWord`.
- **Undo**: `isRejected` holds after one undo for a word the dictionary knows in some casing, after two for an unknown
  word. With learning on, a reject raises a dictionary word to two uses and adds one use to an unknown word.
- **Forget**: removes every form with the word's key from counts, contexts (as follower and as context word), recent
  window, accepted marks and undo table; refused for words a loaded dictionary knows. `PredictionEngine.forget` runs it
  in every language; `learnedWords()` is the list of 05 §12.5: every stored form unknown to every loaded dictionary,
  also forms used only once (no threshold), counts summed over the languages.

**Learned-data format** (`MKUM`, big-endian `DataOutputStream`): version 2 = stamp; words (form, count, last use, flags
byte with bit 0 = accepted); contexts (key, followers with counts); recent window; undo table (word, undo count).
Version 1 (without the flags byte and the undo counts) is still read: its undo entries get `REJECT_MIN_UNKNOWN` undos
(they were fully rejected before), its words are not accepted. Anything else yields an empty model.

**Three-letter words** (05 §8.1 extension): `LanguageEngine.twoNeighbourSubstitutions` tries, for each pair of positions,
every pair of neighbour letters (touch-model proximity at the key centres ≤ `NEAR_MAX`) and adds the best cased form of
every dictionary key it hits ("usz" → "ist"); scoring is unchanged.

## Dictionary format

Both files are gzip streams (written by `tools/dictbuild/common.py`, read by `Lexicon.read` / `PairTable.read`):

- **Word list** (`MKWL` v1): header with S (f32), maxCount, corpusTokens, key and form counts; then all keys in
  UTF-16 order, front-coded (shared prefix length + UTF-8 suffix), each with its forms (class byte + a kind byte:
  the key itself, first letter upper-cased, all upper-cased, or an explicit string — used for alias keys such as
  `dont` → `don't`).
- **Pair table** (`MKPT` v1): binding = key count, form count and the SHA-256 of the uncompressed word-list
  payload; then per previous word (sentence start first) up to 48 followers as (key index, pair class, form index),
  best class first. A table whose binding does not match is rejected (`PairTableMismatchException`);
  `Dictionary.load` then runs without pairs.

Both files are decoded as the gzip stream comes in (`ByteReader` over a buffered stream; the word list's SHA-256 is
computed on the way), never held as a whole. The front-coded keys are kept only as their new suffix characters (one
character per tree node) with their lengths and common-prefix lengths; the breadth-first prefix tree (children
contiguous and sorted, so lookups are binary searches and node order is (depth, text)) is then built level by level —
a key's node at depth d is new exactly when its common prefix with the previous key is shorter than d — into arrays of
exactly the node count: de-DE ≈ 717 000 nodes, en-US ≈ 385 000. Explicit forms are a sorted index array with their
texts; pair-table followers go straight into primitive arrays. Both languages with pair tables retain ≈ 33 MB of JVM
heap and load within a 44 MB maximum heap (`MemoryBudgetTest`; before: transient copies of the decompressed files, one
string per key and per-character build arrays). Loading both takes ≈ 0.2–0.3 s on a desktop JVM. Completions are a best-first search over the per-node maximum class; the fuzzy search walks the tree with
optimal-string-alignment rows and prunes with the row minimum (and the row before it, which keeps the pruning
admissible with transpositions). Shape writing reuses the same tree as its word index; contact words not in the
lexicon get a small second tree.

## Rebuilding the dictionaries

Requirements: Python 3.10+ (standard library only), ~2 GB free disk.

```sh
python3 tools/build_dictionaries.py download   # fetches tools/sources.json into build-data/src, verifies SHA-256
python3 tools/build_dictionaries.py build      # ≈ 3 minutes; writes engine/src/main/resources/app/metrokeys/engine/dict/
```

`build-data/` is git-ignored (downloads and caches; the Wiktionary pass is cached in
`build-data/cache/dewiktionary-20261001.tsv.gz`). Downloads are read in place (bz2/tar streams); from the SCOWL
archive only the plain lists and the `Copyright` file are extracted, no script is run. The build is deterministic
(sorted iteration, gzip with mtime 0); its counts and output hashes are written to `tools/dictionary-build.json`
and documented in [DATA-SOURCES.md](DATA-SOURCES.md). Pipeline modules (`tools/dictbuild/`):
`wiktionary_de.py` (§3.2), `build_de.py` (§3.3–3.7), `build_en.py` (§4), `pairs.py` (§5), `common.py` (classes and
binary formats), `licenses.py` (attribution texts).

## Tests

`JAVA_HOME=/path/to/jdk-21 ./gradlew :engine:test` runs every worked example of 05 §17 and 06 §8, the
conformance checklist of 05 §18 (`ConformanceTest`) and unit tests of all parts, against the bundled dictionaries
where the spec's numbers depend on them. The rules of 05 §12.5 are covered with the real dictionaries: "usz" → "ist",
"Usz" → "Ist", "pqt" does not reach "ist"; an unknown word typed twice is still corrected and known after the third
use; acceptance by picking or "add to dictionary"; one or two undos; forgetting; the version-1 data format. `MemoryBudgetTest`
loads both languages with pair tables, runs a query and a multilingual swipe in a separate JVM with `-Xmx44m`;
`LexiconStructureTest` checks the trees built from the front-coded keys and the streaming key lookup;
`LearnedWordListTest` the list of every stored unknown word (also from learned data without dictionaries).
`ShapeWriterTest` covers the multilingual example of 06 §4.1 (d-a-s → das · dass · daß · da · dad) and the language
penalty. `-PshowOutput` shows test output.

## Decisions where the specification leaves room

- **Search fields and the double-space period**: 05 §15 says yes, 04 §2/§8.2 says no; 04 is followed (off).
- **ED_CASE on substitutions** (05 §5): added to every substitution branch (diacritic, space, neighbour) when exactly
  one of the two letters is upper case.
- **Autocorrection runner-up** (05 §10.1 step 7): the second candidate in correction-cost order.
- **Compound validity cost** (05 §7.6): lm of head and tail without pair context.
- **Missing-space split** (05 §8.2): only for languages without compounds (`LanguageParams.missingSpaceSplit`: English
  yes, German no). `LanguageEngine.splitLm` costs a split from the static lists only: the pair cost when the pair table
  has (a, b′), else the floored mean of the static unigram costs plus `SPLIT_SURCHARGE`. One-letter heads only "a"/"A"
  and "i"/"I" (shown "I"). `MissingSpaceTest` covers the examples of §8.2.
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
- **Completion ties** (05 open point 2): equal classes are returned shorter first, then alphabetically.
- **Next-word display**: equal costs are ordered by text as the rule says (so "Das 49" precedes "Sie 49").
- **Stricter learning everywhere**: 05 §12.2's "typed twice" text and its worked example predate §12.5; §12.5 is
  applied to every use of learned words (the worked example is tested with an accepted word).
- **Learned words list**: every stored form the dictionaries do not know (also used once); each stored case form is its
  own entry; forgetting works on the word's key (all case forms).

Questions with assumptions that matter are recorded in the specification (`spec/QUESTIONS.md`, not published).
