# Dictionary evaluation

The dictionary evaluation of the specification (`spec/05c-dictionary-evaluation.md`, not published) measures how
often obvious typing errors are corrected and how often correct words are changed, with the word lists, pair tables
and the engine of the build under test. It finds problems by general rules only: test items are generated from pinned
free inputs, never edited by hand, and a failing case is never fixed by a word-specific remedy (05c §1).

Nothing of the evaluation is packaged: the test sets, lexicons, baselines and reports live in `evaluation/` and the
code in `tools/dicteval/` (generator) and `engine/src/test/kotlin/app/metrokeys/engine/eval/` (lint, engine runs,
report). The release APK's dictionary files are unchanged by it.

## Running it

| Task | Tier (05c §10) | Content | Time on the development machine (16 threads) |
|---|---|---|---|
| `./gradlew :engine:evalLint` | T0 | lint L1–L5 of all six languages | 3.2 s for all six languages (0.3–1.5 s each) |
| `./gradlew :engine:evalFast` | T1 | lint + dev half, bands B1+B2 (and unbanded sets), variant N, all sets except E9 and R, single profile (about 3 300–3 900 queries per language) | 6.9 s (21 342 queries), plus Gradle start-up |
| `./gradlew :engine:evalFull` | T2 | lint + both halves, N and S, the single profile of every language and the de+en profile, and the freshness run with salt B | 58 s (about 320 000 queries; 12–26 s per language and salt, 37–39 s for de+en) |

`JAVA_HOME` must point to a JDK 21. Reports go to `engine/build/evaluation/<tier>/report.{json,md}`. Options
(Gradle properties):

- `-PevalOut=evaluation/reports/0.6.9` writes the report there instead (committed reports);
- `-PevalWriteBaseline=evaluation/baseline/0.6.9.json -PevalBaselineName=0.6.9` also writes a baseline;
- `-PevalDict=path/to/dict` evaluates other data files (default: `engine/src/main/resources/app/metrokeys/engine/dict`);
- `-PevalLanguages=de-DE,en-US` restricts the languages (the de+en profile needs both).

`python3 tools/build_dictionaries.py build …` runs `evalLint` for the built languages at the end (tier T0) when
`JAVA_HOME` is set; `MK_EVAL_LINT=0` skips it. The CI workflow runs `evalFast` on every push and pull request and
`evalFull` on release tags; both upload `engine/build/evaluation/`.

The evaluation needs neither the large downloads nor network access. The provenance flags in the lint lists and the
worst cases come from the build's source-form export (`build-data/export/<tag>.forms.tsv.gz`, written by every
dictionary build); without it they show `-` and the lint falls back to the word list's attested flag (the report
header records the export's hash or "absent").

## Test sets

`python3 tools/build_evaluation.py download` fetches the five real-error lists (05c §2.3) through the MediaWiki API
into `build-data/src/realerrors/` and verifies the SHA-256 of each revision's wikitext
([tools/dicteval/sources.json](../tools/dicteval/sources.json)). `python3 tools/build_evaluation.py generate [TAG …]`
writes, per language,

- `evaluation/testsets/<tag>.A.tsv` (salt A, the gated run) and `<tag>.B.tsv` (salt B, the freshness run): one item
  per line, columns `id half set band lengthBand variant sentenceId before expected baseKey`, a header line with
  `EVAL_VERSION`, the salt and the SHA-256 of every input (the pinned sources of `tools/sources.json`, the layout file,
  the real-error revision and the generator sources);
- `evaluation/testsets/<tag>.<salt>.generation.json`: the counts per set, variant and half, the number of base words
  without a context occurrence, the trusted-word, name and abbreviation counts, the real-error parsing rule and the
  numbers of entries used and skipped;
- `evaluation/lexicon/<tag>.lexicon.tsv.gz`: the trusted words of ranks 1…100 000, the names and the abbreviations
  with their written-language frequency `pW`, rank and case forms (used by the lint, the severity and the name test of
  the worst cases).

The generator needs the pinned sources (`python3 tools/build_dictionaries.py download`) and the Wiktionary pass caches
of the dictionary build. It runs each language's build pipeline in dry mode (nothing is written) to use the build's
own Wiktionary pass, page-kind rules, case decision, stock names and Brazilian filter, and it never reads a built word
list, so a dictionary change cannot change what is tested (05c §1). Its own parts are in `tools/dicteval/`:

| Module | Content |
|---|---|
| `hunspell.py`, `spelling.py` | spelling-dictionary membership by reverse affix lookup (05b §12.1 item 2): at most one prefix and two suffix levels, conditions, cross product, continuation flags, NEEDAFFIX, ONLYINCOMPOUND, FORBIDDENWORD, CIRCUMFIX, flag formats and ICONV; no compounding, never expanded |
| `wordfreq.py` | reader of the wordfreq MessagePack files (05b §2.15) without the project's code |
| `wiktionary_pos.py` | part-of-speech headings for the name rule of 05b §12.5 (a pass of its own over the Spanish sections of both dumps; the Portuguese and Italian sections come from the build's caches) |
| `languages.py` | per-language inputs: Wiktionary forms, names, case forms, held-out sentences (05b §12.6), tokens (05b §5 step 2) |
| `realerrors.py` | the parsing rule of each real-error page |
| `generate.py` | trusted words (§2.1), context occurrences (§2.2), the error types E1–E9 and E2a, R, the false-positive sets F1–F7 (§3), halves and salts (§3.1, §3.2) |

The generated files are reviewed like code; they change only when an input pin, a set size, a band, a salt or the
generator changes, which requires a new `EVAL_VERSION` and new baselines (05c §9).

The held-out sentences of 05b §12.6 (Tatoeba ids and Common Voice line numbers with `n mod 20 = 7`) are the context
pool. In 0.6.9 they were still counted by the pair build (the S variants of the 0.6.9 baseline are slightly optimistic
for pairs); from 0.7.0 the pair build leaves them out.

**Collisions** (05c §3.5, 0.7.0 amendment): besides the items whose typed text is valid (05 §7 rules 1–2), an item of
E1–E4, E2a or E9 is a collision when its typed text, lower-cased and with diacritics removed as in E5, has the fold of
a key of the evaluated list other than its target — a case or accent variant of another word ("tim" → "Tim", "andre" →
"André"). `CaseAccentFolds` (EvalRunner.kt) builds the fold index from the evaluated list at run time; the test sets
and EVAL_VERSION do not change. Both baselines were recomputed with this rule.

**Test sets and the 0.7.0 build.** The generator uses the build's passes in dry mode. With the build code of 0.7.0 it
reproduces the test sets of German, English, French, Italian and Portuguese byte for byte (the German lexicon gains
case forms of a few names because the case decision now also uses the spelling dictionary's casings; the Portuguese
one has 3 fewer trusted words beyond rank 75 676). Spanish changes: the 0.7.0 rules no longer merge accent-less
spellings that the spelling dictionaries know (§12.1 item 5), so 31 more trusted words exist ("futbol"), its E5 item
becomes an F7 item and the later picks shift. The committed EVAL_VERSION 1 test sets and lexicons are kept unchanged,
so 0.6.9 and 0.7.0 are compared on the same items; a regeneration with the 0.7.0 build would be EVAL_VERSION 2 with new
baselines for both lists (spec/QUESTIONS.md).

## Reading the report

`report.md` (and the same content in `report.json`, deterministic: sorted keys, fixed number formats, items in id
order):

1. **Header**: `EVAL_VERSION`, salts, profiles, the SHA-256 of every evaluated data file, test set, lexicon and
   provenance export, the engine build id (SHA-256 of the compiled engine classes), a hash of the parameter tables
   (05 §1), the run date (not part of the hash) and the report hash.
2. **Gates** G1–G9 per profile and language on the test half, with value, n, limit, baseline value, difference and
   PASS/FAIL. All gates are **report-only** in this release (05c §7.1); G8/G9 (0.7.1: E2/E2a wrong corrections,
   accepted replacements) are report-only by definition.
3. **Ratchet**: every metric with n ≥ 200 of the test half compared with the baseline named in
   `evaluation/baseline/RATCHET` (tolerance 0.5 points for n ≥ 1000, 1.0 below), and new L1/L2 entries. Report-only.
4. **Results** per profile and language and half: one row per set and variant with n, fixed (AC recall), wrong,
   kept, precision, top-1, top-3 and offer (the expected word anywhere in the bar) for error sets, the false-correction
   rate for F sets (F7 also the offer rate of the accented word and, from 0.7.1, the accepted replacements: the
   accented word replaced by its more frequent spelling, 05c F7), the flagged bucket (expected word is a do-not-suggest
   form, 05b §2.16; not counted), the collision bucket (typed text valid in the evaluated list, not counted), the
   buckets F2z and F5z (F2 and F5 items whose key the list stores with class 0: corrected by design, report only, not
   in G6 or the ratchet; F5z from 0.7.1: abbreviations that are real-word typos, "zi", "fem") and the differences to
   every committed baseline. "All errors" is the mean recall over E1–E8 (equal weight per type), "all false positives"
   the mean false-correction rate over F1–F7. The JSON also has every metric by band and by length band.
5. **Lint** L1–L5 with counts, differences to the baselines and the first 50 entries of each list.
6. **Worst 50** per profile and language: severity = outcome weight × max(1, zipf of the base word); each line gives
   set, variant, typed and expected text, the autocorrection, the first five bar entries with kind, `lm`, `ed` and
   score, the typed word's validity (rule of 05 §7, unattested flag), the decision step of 05 §10.1 and the
   provenance flags of the typed key and of each entry; from 0.7.1 also each entry's source (key slip, spelling,
   completion, learned, split, elision) and the autocorrection score of 05 §10.1, and the decision step codes 1–8 of
   P3 (e.g. `4o`, `7o`, `7r` omission reading applied, `7s`). Flagged items and accepted replacements are not worst cases.
7. **Dev/test gap** (flagged above 2 points in favour of dev: possible overfitting) and the **freshness run** (salt B
   next to salt A, flagged above 2 points).

The decision step, validity rule, unattested flag and the bar entries' `lm`, `ed` and score come from
`PredictionEngine.queryDiagnosed`, an internal function visible only to the engine's own tests (05c §5); it returns
the same `Prediction` as `query` (`EvaluationDataTest`).

Changes of rules or parameters are judged on the dev half; the gates read the test half. An intended trade-off is
accepted by committing a new baseline with the report diff and a one-line reason in the change log; the owner
approves baselines.

## Baselines 0.6.9 and 0.7.0

Two baselines (05c §7.2), both computed with the amended collision rule on the same EVAL_VERSION 1 test sets: 0.6.9 from
the 0.6.9 lists (`evaluation/baseline/0.6.9.json`, report `evaluation/reports/0.6.9/`), 0.7.0 from the 0.7.0 lists of all
six languages (`evaluation/baseline/0.7.0.json`, report `evaluation/reports/0.7.0/` with the differences to 0.6.9);
`evaluation/baseline/RATCHET` names 0.7.0 for later releases. Test half, salt A, rates in percent; each cell 0.6.9 → 0.7.0.

| Profile | Language | All errors (recall) | All false positives | E1 N recall / precision | E2 N recall | E5 N recall | F2 N / F4 N / F5 N false corrections | L2 (2–3 letters) | L3 coverage 20 000 |
|---|---|---|---|---|---|---|---|---|---|
| single | de-DE | 59.5 → 60.9 | 6.2 → 3.4 | 78.9 / 99.3 → 79.9 / 99.6 | 4.2 → 5.1 | 82.1 → 84.8 | 3.4 / 5.8 / 10.6 → 0.3 / 9.7 / 2.8 | 24 → 2 | 99.95 → 100.00 |
| single | en-US | 84.9 → 85.2 | 3.4 → 3.7 | 89.5 / 97.7 → 89.7 / 97.9 | 47.6 → 49.1 | – | 0.0 / 0.0 / 17.1 → 0.0 / 0.0 / 18.4 | 0 → 0 | 99.78 → 99.78 |
| single | es-ES | 54.4 → 60.2 | 2.6 → 1.5 | 81.2 / 99.3 → 82.9 / 99.6 | 11.9 → 13.7 | 93.2 → 95.4 | 1.7 / 9.7 / 2.6 → 0.7 / 5.3 / 2.6 | 49 → 1 | 99.93 → 99.98 |
| single | fr-FR | 60.2 → 63.4 | 6.0 → 2.5 | 74.7 / 98.0 → 74.6 / 98.7 | 33.7 → 35.9 | 75.0 → 75.0 | 3.4 / 13.0 / 12.1 → 0.0 / 4.8 / 3.0 | 11 → 0 | 99.94 → 100.00 |
| single | it-IT | 60.7 → 65.7 | 13.1 → 11.5 | 83.8 / 99.4 → 84.8 / 99.4 | 20.8 → 20.1 | 100.0 → 100.0 | 2.7 / 13.7 / 5.9 → 0.7 / 5.9 / 5.9 | 44 → 0 | 99.94 → 100.00 |
| single | pt-BR | 60.0 → 64.1 | 2.4 → 0.4 | 88.1 / 99.6 → 89.0 / 99.4 | 29.2 → 29.4 | 92.6 → 96.6 | 1.3 / 13.3 / 0.0 → 0.0 / 2.6 / 0.0 | 60 → 1 | 99.76 → 100.00 |
| de+en | de-DE | 58.5 → 59.8 | 5.7 → 3.4 | 78.6 / 99.3 → 79.5 / 99.6 | 4.2 → 5.1 | 82.1 → 84.8 | 3.4 / 4.4 / 9.2 → 0.3 / 9.2 / 2.8 | | |
| de+en | en-US (typed with German selected) | 42.3 → 42.3 | 0.3 → 0.2 | 9.5 / 37.7 → 9.3 / 33.3 | 0.2 → 0.2 | – | | | |

The amended rule moved, over both halves and N and S of E1–E4, E2a and E9, on the 0.6.9 lists 209 (de), 110 (en),
212 (es), 293 (fr), 128 (it) and 178 (pt) items into the collision bucket, on the 0.7.0 lists 225, 110, 202, 327, 121
and 184. Gates stay report-only in 0.7.0; the ratchet of 0.7.0 against 0.6.9 lists 452 metrics worse by more than the
tolerance (mostly German E2 in long words, English short words, Spanish E2/E3/E8), no new L1/L2 entries.

The 0.7.0 baseline and report were computed again after the German umlaut rule of the engine (05 §5, `UMLAUT_TRANSLIT`;
lists unchanged, so no collision changed). It changes 63 of 148 685 German outcomes (both profiles, salts and halves);
every German headline value above is unchanged. Typed "ae"/"oe"/"ue" now also reaches umlaut words when it is a typo of
something else: "Waer" (for "Ware") is corrected to the colloquial key "Wär", "traen" (for "traten") to "trän", and
"oedr" and "hoehs" are no longer corrected to "oder" and "hohes" because "öde" and "Höhe" come close (§10.1 margin);
"Baer", "Vaer" and "Bluen" are no longer corrected to the wrong "Aber", "Vier" and "Blieb". German single, test half:
E4 N recall 82.8 → 82.6 (precision 99.8 → 99.6), E2 N wrong corrections 20.1 → 19.9. Against the 0.7.0 baseline before
the rule the ratchet passes; against 0.6.9 it lists 452 instead of 441 worse metrics (eleven German E2/E3/E4 and de+en
en-US E3/E4 cells that moved a few tenths across the tolerance; E2 "kept" rises because wrong corrections become kept
words).

## Baseline 0.7.1

The 0.7.1 candidate pipeline (05 §8: key-slip search and spelling source 05d, merge and cut of §8.4/§9, policy P3 of
§10.1) and the 0.7.1 lists (derived data, do-not-suggest flags; first with the German abbreviation exemption of the
0.7.1 draft, see the review fixes below) were evaluated with
`:engine:evalFull` on the unchanged EVAL_VERSION 1 test sets; report `evaluation/reports/0.7.1/` (differences to
0.6.9 and 0.7.0), baseline `evaluation/baseline/0.7.1.json` (05c §7.2 re-baseline, reason "0.7.1 pipeline"), and
`evaluation/baseline/RATCHET` now names 0.7.1. The ratchet of 0.7.1 against 0.7.0 lists 427 of 3 223 metrics worse
by more than the tolerance (report-only), 345 of them E1 and E4 recall, kept, top-1 and precision cells: P3 corrects
a substitution or swap only when it is clearly ahead (margin and guards of 05 §10.1), so more of these typos are kept
and offered instead; no new L1/L2 entries. Test half, salt A, N, rates in percent; each cell 0.7.0 → 0.7.1; E2 as
top-3 / wrong corrections (05c E2 by offer, top-3 and wrong from 0.7.1).

| Profile | Language | All errors (recall) | All false positives | E1 N recall / precision | E2 N top-3 / wrong | E5 N recall | F2 N / F4 N / F5 N false corrections | L2 (2–3 letters) | L3 coverage 20 000 |
|---|---|---|---|---|---|---|---|---|---|
| single | de-DE | 60.9 → 77.1 | 3.4 → 0.1 | 79.9 / 99.6 → 73.3 / 98.8 | 42.5 / 19.9 → 68.4 / 2.1 | 84.8 → 87.9 | 0.3 / 9.7 / 2.8 → 0.0 / 0.0 / 0.7 | 23 → 23 | 100.00 → 100.00 |
| single | en-US | 85.2 → 88.1 | 3.7 → 1.2 | 89.7 / 97.9 → 83.9 / 97.1 | 77.0 / 22.0 → 81.0 / 5.5 | – | 0.0 / 0.0 / 18.4 → 0.0 / 0.0 / 5.9 | 0 → 0 | 99.78 → 99.78 |
| single | es-ES | 60.2 → 78.7 | 1.5 → 0.0 | 82.9 / 99.6 → 77.3 / 98.6 | 63.2 / 20.4 → 76.1 / 4.4 | 95.4 → 97.7 | 0.7 / 5.3 / 2.6 → 0.0 / 0.0 / 0.0 | 5 → 5 | 99.98 → 99.98 |
| single | fr-FR | 63.4 → 79.5 | 2.5 → 0.3 | 74.6 / 98.7 → 72.9 / 98.2 | 81.7 / 10.7 → 83.3 / 2.2 | 75.0 → 90.2 | 0.0 / 4.8 / 3.0 → 0.0 / 0.0 / 0.0 | 2 → 2 | 100.00 → 100.00 |
| single | it-IT | 65.7 → 83.8 | 11.5 → 0.0 | 84.8 / 99.4 → 80.3 / 98.5 | 68.3 / 20.3 → 77.4 / 2.9 | 100.0 → 100.0 | 0.7 / 5.9 / 5.9 → 0.0 / 0.0 / 0.0 | 2 → 2 | 100.00 → 100.00 |
| single | pt-BR | 64.1 → 84.3 | 0.4 → 0.3 | 89.0 / 99.4 → 81.8 / 98.9 | 73.2 / 19.0 → 77.7 / 4.4 | 96.6 → 100.0 | 0.0 / 2.6 / 0.0 → 0.0 / 1.5 / 0.0 | 8 → 8 | 100.00 → 100.00 |
| de+en | de-DE | 59.8 → 75.7 | 3.4 → 0.1 | 79.5 / 99.6 → 73.1 / 98.8 | 43.1 / 19.7 → 66.5 / 1.9 | 84.8 → 87.9 | 0.3 / 9.2 / 2.8 → 0.0 / 0.0 / 0.7 | | |
| de+en | en-US (typed with German selected) | 42.3 → 43.2 | 0.2 → 0.2 | 9.3 / 33.3 → 7.8 / 44.0 | 69.7 / 14.5 → 71.6 / 10.7 | – | | | |

(The 0.7.0 values of this table come from the 0.7.0 report as recomputed by the 0.7.1 evaluation code, so E2 top-3 and
"all errors" differ slightly from the table above, which used AC recall for E2.) AC recall / wrong corrections over
E1–E4, E2a and E9 (N + S): de 47.5 / 6.2 → 67.7 / 1.2, en 81.3 / 6.3 → 81.3 / 2.8, es 51.3 / 6.9 → 72.3 / 1.4, fr
50.8 / 5.3 → 73.8 / 1.3, it 53.3 / 7.4 → 77.3 / 1.3, pt 56.7 / 7.0 → 78.7 / 1.5. F7 (accent-less spelling of a rare
accented word) counts the replacement by the more frequent accented spelling as accepted: de 16.0 %, fr 11.1 %, it
57.2 % of the F7 items, false corrections de 8.0 → 0.0, fr 8.0 → 1.6, it 57.2 → 0.0. Flagged bucket (test half): de
12, en 1, es 2, fr 24, it 5, pt 0 items. Gates failing (report-only): de G2 and G4, en G6 (F5 5.9) and G8, es G2 and
G8, pt G2, G6 (F4 1.5) and G8.

**0.7.1 review fixes.** The baseline and report were recomputed after the review of 0.7.1 (owner decision): the
abbreviation exemption of the real-word typo rule is withdrawn (32 German keys class 0 again, with the abbreviation
bit of 05b §1.1), the transliterated runs of 05 §8.1c hand over only completions longer than the typed word, and F5
items stored with class 0 are reported as F5z. Against the 0.7.1 baseline before the fixes the ratchet passes (3 271
metrics compared, none worse, no new lint entries). Changes (test half): German F5 N 141 → 136 items (false
corrections 0.7 % unchanged), F5z de 5 items of which 3 corrected (60 %; dev 9 items, 55.6 %), fr dev 1 item; German
E4 N wrong corrections 0.2 → 0.0 % (precision 99.8 → 100.0); German E1–E4/E2a/E9 AC recall and wrong corrections
unchanged (67.7 / 1.2), all other languages unchanged. The ratchet against 0.7.0 lists 429 worse metrics (427 before).

**Unknown words are always corrected (0.7.1 review, owner decision).** 05 §10.1 step 7 (runner-up within `AC_MARGIN`)
now applies only to typed words that are valid for the decision, and the omission guard of step 7a is skipped when
the best correction of such a word is itself the typed word with one letter inserted. Report and baseline 0.7.1 were
recomputed (reason "unknown words are always corrected"). AC recall / wrong corrections over E1–E4, E2a and E9 (test
half, N + S), before → after: de 67.7 / 1.2 → 80.5 / 4.9 (de+en 67.5 / 1.2 → 80.1 / 4.9), en 81.3 / 2.8 → 85.7 / 5.5,
es 72.3 / 1.4 → 82.7 / 5.0, fr 73.8 / 1.3 → 87.4 / 5.6, it 77.3 / 1.3 → 86.2 / 4.1, pt 78.7 / 1.5 → 88.2 / 5.1. False
corrections of the F sets are unchanged except en F5 N 5.9 → 7.2 % ("Af", "Calif": abbreviations the English list
does not know), de F5z 3 → 4 of 5 items ("iber") and, with German selected, the English F1 items 3 → 5 ("shown" →
"schon", "sad" → "das"). Against the previous 0.7.1 baseline the ratchet lists 808 of 3 271 metrics worse (report-only;
wrong and precision cells of E1–E4 and E8, and E1/E4 top-1), no new lint entries. Gates now failing in addition
(report-only, accepted by the owner): G5 (precision E1–E4) in every language, G8 (E2 wrong rate) also in de, fr and it,
G8 for E2a in de, es and it.

**Omission reading (0.7.1 review, owner decision).** For a typed word that is not valid for the decision, the
omission guard of 05 §10.1 step 7a now applies the omission reading (the first correction in AC-score order that is
the typed word with one letter inserted, within `AC_OMISSION_MARGIN` and `AC_LIMIT`; decision step `7r`) instead of
none ("Kanst" → "Kannst"). Report and baseline 0.7.1 were recomputed (reason "omission guard applies the omission
reading"). AC recall / wrong corrections over E1–E4, E2a and E9 (test half, N + S), before → after: de 80.5 / 4.9 →
81.9 / 5.4 (de+en 80.1 / 4.9 → 81.5 / 5.5), en 85.7 / 5.5 → 87.4 / 6.0, es 82.7 / 5.0 → 83.5 / 5.4, fr 87.4 / 5.6 →
88.2 / 6.0, it 86.2 / 4.1 → 87.7 / 4.9, pt 88.2 / 5.1 → 89.3 / 5.8. False corrections of every F set unchanged.
Against the previous 0.7.1 baseline the ratchet lists 166 of 3 271 metrics worse (report-only; wrong and
precision cells of E1–E4 and E8, and E4 top-1), no new lint entries. Gate newly failing (report-only): pt G8 for E2a (0.0 → 5.6 %).

## Baselines and reports

| File | Content |
|---|---|
| `evaluation/baseline/0.6.9.json` | first baseline: the 0.6.9 word lists, before any 0.7.0 data change (05c §7.2; recomputed with the amended collision rule) |
| `evaluation/baseline/0.7.0.json` | second baseline: the first 0.7.0 build (05c §7.2) |
| `evaluation/baseline/0.7.1.json` | third baseline: the 0.7.1 pipeline and lists (05c §7.2 re-baseline) |
| `evaluation/baseline/RATCHET` | the baseline the ratchet compares with (0.7.1 from this release) |
| `evaluation/reports/0.6.9/report.{json,md}` | the full-tier report of the 0.6.9 lists |
| `evaluation/reports/0.7.0/report.{json,md}` | the full-tier report of the 0.7.0 lists (differences to 0.6.9) |
| `evaluation/reports/0.7.1/report.{json,md}` | the full-tier report of 0.7.1 (differences to 0.6.9 and 0.7.0) |
| `evaluation/testsets/LICENSE.md` | sources and attribution of the test data (05c §2.4) |
