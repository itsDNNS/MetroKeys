# MetroKeys app module

`app/` is the Android application `app.metrokeys` (min SDK 26, compile/target SDK 37, AGP 9.4.1 with built-in
Kotlin). It contains the input method service with a keyboard view drawn by the app, and the settings app of
spec 07 (Jetpack Compose, Navigation 3).
Prediction, autocorrection, shape writing and the dictionaries come from the [engine](ENGINE.md).

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew :engine:test testDebugUnitTest lintDebug assembleDebug assembleRelease
```

**Release build.** `assembleRelease` minifies with R8 (`proguard-android-optimize.txt`) and shrinks resources. No own
keep rules are needed (reasons in `app/proguard-rules.pro`): manifest components are kept by AAPT's rules, the engine
loads its dictionaries by absolute Java resource paths, and the only reflective call targets a framework method. The
release APK (15.0 MB in 0.6.9, 0.6.8 and 0.6.6 with the dictionary-only German lemmas, 15.7 MB in 0.6.5 with all dictionary-only German forms, 14.6 MB in 0.6.4, 14.7 MB since the French dictionary in 0.6.3, 12.5 MB in 0.6.2, 10.6 MB in 0.6.1, 9.1 MB in 0.6.0; debug about 25.7 MB) was checked on the emulator: typing, suggestions, autocorrection, shape
writing, emoji, clipboard text and images, app bar, all settings pages, learned words and licences; for 0.6.8 (`input motionevent`, screenshots): a vertical move inside
`s` beyond the threshold shows the trace and, held, opens the picker (the trace fades, the drag selects); a swipe
from Shift through a-l-t gives "alt" in lower case (shift set back); "(" + swipe → "(gut"; swipe + "?" → "gut?";
"?" + swipe and "." + swipe → "? Gut", ". Gut"; "hsllo " → "hallo", undone by tapping "hsllo" and by holding backspace
into "habe" (both stay uncorrected when typed again); "hallo" + quick double space in the system settings search →
"hallo. "; the live word during a slow swipe ("ja", "hab", then "hallo" inserted); the paste ring at 50 % (143 instead
of 255 on the dark strip) with an empty clipboard, a tap on it does nothing, normal after a copy; for 0.6.6 the key
popup and picker checks listed under "Touch handling (0.6.6)" and a swipe of "vielleicht" with German and English; for 0.6.5 fast typing in Google Messages with German and English (ten keys per second,
"ich habe dke katze gesehen und dke maus …": every "dke" → "die"), "Die App ist nicht performanz" → "performant" first,
a swipe of "vielleicht" and the settings app; for 0.6.4 the
lighter key labels, a pick for a word around the caret, "zi " / "zj " → "zu", "Cursos" → "Cursor" first, "Ich komme heure " →
"heute", "Uberpeufe " → "Überprüfe" and the app bar (an empty part does nothing, "…" toggles). Signing uses
`keystore.properties` in the repository root when present (README, "Building"); the version is `appVersionName` /
`appVersionCode` in `gradle/libs.versions.toml`.

The layout, metrics and theme data of `spec/data/` are packaged unchanged as assets (the `main` source set adds
`spec/data` as an asset directory), so the specification stays the only source of the layouts.

## Architecture

```
MetroKeysService (InputMethodService)        Android lifecycle, insets, hardware keys, inline autofill
 └─ KeyboardController                       pages, shift, languages, panels, app bar, dictation, clipboard
     ├─ InputLogic ── Editor                 text rules of spec 04 over an editor abstraction
     │                └─ CachingEditor        copy of the text around the caret (0.6.5)
     │                    └─ InputConnectionEditor
     ├─ PredictionEngine (engine module)     loaded on a worker thread; current-word queries on a typing thread
     ├─ ClipboardHistory + ClipImages        history model; image files, thumbnails (own thread)
     │   └─ ClipImageProvider                read-only content provider for commitContent
     └─ KeyboardView (custom View)           drawing and touch of everything below
         ├─ StripBar        candidate strip, paste header, recently-copied chip, clipboard button, flash,
         │                  dictation state, forget confirmation, messages
         ├─ key area        keys, popup, alternates/language picker (drawn by KeyboardView)
         │   └─ KeyTouch    touch state machine of the keys (pure Kotlin, 0.6.6): HitTester, PickerModel
         ├─ EmojiPanel      emoji grid
         ├─ ClipboardPanel  clipboard history panel
         ├─ AppBar          application bar and "…" expansion
         ├─ TraceOverlay    shape-writing trace
         └─ KeyboardAccessibility  virtual nodes for screen readers
```

Packages: `app.metrokeys.ime` (service, controller, text logic, models of strip, clipboard, emoji, dictation,
autofill, contacts word source), `app.metrokeys.keyboard` (view and its components, metrics, theme, glyphs, fonts,
feedback, motion), `app.metrokeys.keyboard.layout` (layout data model and hit testing), `app.metrokeys.settings`
(preferences, settings activity, pages, import rules) and `app.metrokeys.settings.ui` (the own control set).

### Threads

Everything runs on the main thread except engine work: dictionary loading, learning, saving, shape recognition,
next-word predictions and proofreading go through one worker thread (`Scheduler.background`); the queries of the
current word run on an own typing thread (`Scheduler.query`), so they never wait behind a proofreading run or a save.
The typing path asks the engine nothing on the main thread (0.6.5, see "Performance").

### Memory (00 "Quality requirements", 05b §6)

The keyboard service and the settings activity share one process. The dictionaries are loaded only by the keyboard's
engine (`Dictionary.loadBundled` returns the process-wide instance, also across a language change); the settings never
load word lists: the learned-words page reads the learned files and asks `Dictionary.bundledContains`, which uses the
keyboard's loaded dictionary or streams through the compressed word list. Measured on the emulator
(`dumpsys meminfo app.metrokeys`, Dalvik heap, both languages, multilingual, keyboard shown in the try-it field and then
data+info and the learned-words page opened):

| | before | after |
|---|---|---|
| keyboard loading (peak sample) | 110 MB heap size / 86 MB allocated | 61 MB / 36 MB |
| keyboard shown, after a swipe | 64.6 MB / 40.0 MB | 64.6 MB / 40.0 MB |
| data+info opened (peak sample) | 145 MB / 120 MB | 64.8 MB / 40.2 MB |
| learned-words page open | 87 MB / 62 MB | 64.6 MB / 40.0 MB |

Since 0.6 the keyboard loads the active set of 05 §14.5 (up to three languages). Measured on the emulator with 0.6.0
(Dalvik heap size / allocated, keyboard shown in the try-it field after a restart, highest sample while loading and
after a swipe; identical values): de+en 63.0 / 39.0 MB, de+es 65.1 / 41.1 MB, en+es 58.9 / 34.9 MB, de+en+es
77.2 / 53.2 MB. Two languages stay far below 96 MB and three below 128 MB (even below 96 MB, 05 open point 9).
With Portuguese (0.6.1, same measurement, Portuguese selected, after a swipe of "faço"): de+pt 62.6 / 38.1 MB, en+pt
56.2 / 31.6 MB, es+pt 58.4 / 33.8 MB, de+en+pt 75.0 / 50.4 MB, de+es+pt 77.1 / 52.6 MB, en+es+pt 70.7 / 46.1 MB, all
four enabled (active set pt, de, en) 75.0 / 50.4 MB.
With Italian (0.6.2, same measurement, Italian selected, after a swipe of "più"; highest sample while loading / after
the swipe): it alone 41.3 / 20.7 → 43.8 / 21.9 MB; de+it 63.8 / 39.2 → 65.0 / 40.4 MB, en+it 58.6 / 34.0, es+it
60.7 / 36.1, it+pt 56.6 / 32.1 MB; de+en+it 77.1 / 52.5, de+es+it 79.2 / 54.6, en+es+it 72.9 / 48.3, es+it+pt
70.9 / 46.3 MB; all five enabled (active set it plus two) 77.1 / 52.5 MB.
With French (0.6.3, same measurement, French selected, after a swipe of "très"; highest sample while loading / after
the swipe): fr alone 40.8 / 20.4 → 45.5 / 22.7 MB; de+fr 63.4 / 38.8 → 65.8 / 41.2 MB, en+fr 57.1 / 32.5 → 59.5 / 34.9,
fr+it 56.7 / 32.1 → 60.2 / 35.6 MB; de+en+fr 75.6 / 51.1 → 78.0 / 53.4, de+es+fr 77.7 / 53.1 → 80.1 / 55.5, es+fr+it
70.9 / 46.3 → 74.5 / 49.9, en+fr+pt 67.3 / 42.7 → 69.7 / 45.1 MB; all six enabled (active set fr plus two)
75.7 / 51.1 → 78.0 / 53.4 MB. Three languages with French stay below 96 MB.

The whole scenario (0.5) also runs with the per-app heap limit lowered to 64 MB (`dalvik.vm.heapgrowthlimit`, root on the
emulator) without an OutOfMemoryError; the default limit is 192 MB. Results come back with a generation number; an
outdated answer is dropped (03 §6.2). Autocorrection at a separator uses the background query of the current word
started at its last change (waiting for it if it is still running), so a fast trigger is never missed (0.6.5).
With the dictionary-only German forms (0.6.5, 857 274 German keys instead of 236 223; same measurement, sampled
every ≈ 0.3 s while loading, then after a swipe of "vielleicht"): de+en 87.3 / 62.7 MB (0.6.4: 63.0 / 39.0),
de+en+fr 98.7 / 74.2 → 101.2 / 76.6 MB (0.6.4: 75.6 / 51.1 → 78.0 / 53.4). Both stay below the 96 MB and 128 MB of
00 "Quality requirements", with a smaller margin for two languages (spec/QUESTIONS.md).
With the dictionary-only German lemmas of 0.6.6 (320 804 German keys; same measurement, release build, multilingual,
highest of 15 samples while loading, then after a swipe of "vielleicht"; MiB of `dumpsys meminfo`): de+en 68.1 / 44.1 →
68.0 / 44.0 MB, de+en+fr 79.2 / 55.2 → 81.5 / 57.5 MB.
With the short-key guard and damping of 0.6.7 in the es, pt, it, fr dictionaries (release build, multilingual, third
language selected with the language key after a restart; highest of 15 samples while loading, then after typing a
sentence in the third language and a swipe; MiB of `dumpsys meminfo`, heap size / allocated): de+en+es 83.9 / 59.4 →
84.0 / 59.4 MB, de+en+pt 79.9 / 55.3 → 80.2 / 55.7 MB, de+en+it 81.3 / 56.8 → 82.9 / 58.3 MB, de+en+fr 81.1 / 56.5 →
83.8 / 59.3 MB. The dictionaries are 60 KB smaller; the differences to 0.6.6 (de+en+fr) are within the run-to-run spread
of the garbage collector's heap sizing (the JVM test retains at most 0.15 MB less). Measured before the abbreviation titles and prefix compounds of 05b §3.6 were added (0.6.7, German files +5.2 KB);
with them the JVM memory test retains the same within 0.4 MB except fr+de+it (+1.1 MB, 56.7 MB), so the emulator
values were not repeated.

### Performance (0.6.5)

Owner report: "the keyboard sometimes lags behind when I type fast" (German + English, multilingual). Measured on the
emulator (x86_64 on a desktop CPU, software GPU) with the release build typing into Google Messages: 326 taps at
≈ 9 per second for 36 s (German and English sentences with typos, two backspaces), replayed with exact timing on the
touchscreen device, traced with `atrace` (the keyboard's own trace sections `mk:*`, `Perf`), plus 19 swipes. Per key:
main-thread time of the touch-down and release events, time from the event to the next frame of the keyboard
(popup) and of the app (the text), synchronous binder calls of the main thread, frames over 16 ms, garbage
collections and bytes allocated (process-wide counter `mk:allocKiB`).

What cost main-thread time in 0.6.4: every key read the field through the InputConnection 5–7 times (text before
and after the caret for the word, the auto-shift, the app bar's send state, the strip; the clipboard and the ringer
mode through the system). Each read is a synchronous call that the app answers on its UI thread after it has applied
the keyboard's earlier edits, so the keyboard waited for the app's layout of every character; at a space it also
computed the autocorrection query itself (the delayed typing query had never run while typing fast). The key popup
and the autocorrection flash requested animation frames for their whole duration. GC pauses and allocations on the
main thread were not significant.

Changes:
- `CachingEditor` keeps a copy of the text around the caret that the keyboard's own commits, deletions and composing
  text (a swiped word) update; it is dropped at input start, when the keyboard is shown, at caret moves and unexpected
  selection updates, for selections set by the keyboard, keys sent to the app, the paste action and editor actions,
  and the first key after a pause of 1 s reads the field again. Spelling marks are applied only after the field's own
  text was read again.
- Every change of the current word starts its query at once on the typing thread (`InputLogic.AheadQuery`, only the
  newest one runs); the strip still shows it after the delay of 03 §6.2, and the autocorrection trigger takes its
  result (waiting for it when it is still running, computing it itself only if it has not started yet).
- The clipboard state is read when the clip changes, at input start and when the keyboard is shown; the app bar's
  buttons are built once per input start, show and settings change; the ringer mode follows its broadcast; the
  recently-copied chip is measured when its content changes; "add to dictionary" runs on the worker.
- The key popup and the autocorrection flash are static: they end by a posted redraw, not by animation frames.

Results (0.6.4 → 0.6.5):

| ms, median / p95 (0.6.4 → 0.6.5) | 1 language (de) | 2 languages (de, en) | 3 languages (de, en, fr) |
|---|---|---|---|
| letter: main thread at the release (commit) | 3.6 / 9.5 → 0.3 / 0.9 | 5.3 / 13.6 → 0.3 / 0.8 | 4.5 / 10.5 → 0.3 / 0.8 |
| letter: main thread at touch-down (popup, sound, key choice) | 1.0 / 15.5 → 0.3 / 0.5 | 1.2 / 18.8 → 0.3 / 0.6 | 1.3 / 13.7 → 0.3 / 0.6 |
| space: main thread at the release (autocorrection, learning) | 18.3 / 34.5 → 0.3 / 1.0 | 21.1 / 31.4 → 0.4 / 0.9 | 19.1 / 33.7 → 0.4 / 0.9 |
| backspace: main thread at the release | 12.3 / 18.2 → 0.4 / 0.7 | 12.4 / 26.2 → 0.5 / 0.9 | 12.9 / 19.1 → 0.4 / 0.5 |
| letter: release → frame of the app with the letter | 22.3 / 53.2 → 16.6 / 25.4 | 29.1 / 58.1 → 17.2 / 29.2 | 23.1 / 51.0 → 16.0 / 25.9 |
| space: release → frame of the app with the corrected word | 59.3 / 113.3 → 16.6 / 25.7 | 62.6 / 128.7 → 16.9 / 29.1 | 62.0 / 120.8 → 16.1 / 27.7 |
| letter: touch-down → keyboard frame with the popup | 30.1 / 53.5 → 14.6 / 23.5 | 37.8 / 61.2 → 15.6 / 21.7 | 30.1 / 47.9 → 14.3 / 21.7 |
| synchronous calls to the app or system per letter / space (median) | 5 / 7 → 0 / 0 | 5 / 7 → 0 / 0 | 5 / 7 → 0 / 0 |
| engine query on the main thread at a space | 1.0 / 2.3 → none | 2.0 / 5.1 → none | 3.3 / 7.6 → none |
| keyboard frames over 16 ms (326 keys) | 139 → 1 | 323 → 0 | 112 → 1 |
| main-thread CPU time of the run (s) | 1.33 → 0.65 | 1.71 → 0.64 | 1.83 → 0.66 |
| GCs during the run (total ms, concurrent) | 4 (21) → 8 (19) | 4 (18) → 12 (21) | 8 (35) → 21 (53) |
| allocated per key, whole process (KiB, median) | 68 → 160 | 64 → 256 | 36 → 416 |
| swipe: main thread applying the result | 9.3 / 19.9 → 0.3 / 1.5 | 13.8 / 22.1 → 0.4 / 2.7 | 15.0 / 24.3 → 0.3 / 1.1 |
| swipe: release → keyboard frame with the word | 48.8 / 69.8 → 49.0 / 65.8 | 46.8 / 73.9 → 49.1 / 69.6 | 63.2 / 79.4 → 43.8 / 68.5 |

The keyboard's frames on this emulator are dominated by the software GPU (≈ 14 ms per buffer swap); the popup latency
is therefore not representative of a phone, the relative change is. The background query of the current word took
0.9 / 1.5 / 2.2 ms (median, 1 / 2 / 3 languages) on the typing thread; it raises the allocations per key, collected
by the concurrent GC off the main thread. `TypingPathTest` checks that fast typing asks the engine nothing on the
main thread (`PredictionEngine.queryObserver`), that the strip keeps the typing pause, the fallback when the query has
not started, and that typing reads the field only after pauses; `CachingEditorTest` compares the copy with the field
over 6000 random edits. All `InputLogic` tests run through `CachingEditor`.

### Touch handling (0.6.6)

The key area's touch handling is `KeyTouch` (pure Kotlin, no Android types): `KeyboardView` passes touch-down, move and
lift in key-area pixels with the event times, implements `KeyTouch.Host` (controller calls, trace overlay,
accessibility) and draws what `KeyTouch` exposes: `pressedKey`, `popup` (key, its cell and the frozen content) and
`picker`. Timers (hold 450/200 ms, repeat 83 ms, word delete after 3 s every 200 ms, the 75 ms release delay) go through
`TouchClock` (the view's handler; `FakeClock` in the tests).

- **Hit testing** (`layout/HitTest.kt`, 03 §1.1): `KeyCells` truncates every layout rectangle × key-area size to whole
  pixels (a product within 1/1000 px below a whole number counts as that number, because 0.35f × 1080 is 377.99999);
  `HitTester.hit` takes `max(dx, 2·dy)` inside the key area and the truncated straight-line distance in the touch-down
  tolerance (40 px left, right and below), returns the first key of the smallest distance in layout order, leaves
  backspace out while the last three evaluations lie within 375 ms (fields of input type text without the
  short-message variation, `FieldState.fastTypingExclusion`) and clears that history after a function key or a hit with
  d > 0. A moving finger outside the key area hits nothing. Disabled keys (PIN blank, emoji grid) are found but inert.
  `LetterCorrection` (§1.1a) works on the same cells, Chebyshev distance not doubled; `Hysteresis` gives the areas of
  §1.2 (cell ± 10 px, 20 px beyond a selecting point outside the cell) and of a corrected key.
- **Press, slide, release** (03 §1.2, §1.2a, §1.5, §2; 02 §5.3, §5.5.6): a key that was touched down on gets click, hold
  timer and its touch-down action; a key reached by leaving the hysteresis area gets none of them and commits on
  release only when it is a character key. On a trace candidate keys reached by sliding show no pressed look or popup
  for the rest of the touch; elsewhere look and popup move in the same frame. On a lift, when a trace candidate leaves
  its key and when the finger leaves the key area, a popup key keeps its pressed look and popup for 75 ms with the
  content of that moment (`Host.popupSnapshot`: the shifted label, the language of the language key) and over the cell
  it had (also when the release switched the page); keys without a popup end at once and never show one. A new
  touch-down ends the delay at once. Becoming a trace changes no feedback by itself. After a page key the first key
  under the finger beyond half the page key's width becomes active without click and is committed like a normal key
  (03 §7.1 item 2, see QUESTIONS).
- **Picker** (`PickerModel`, 03 §3.4, 02 §5.6, §7.5): placement as before; hold zone, row choice, slots (the cell with its
  left frame) and hit band as specified; hold phase until the finger leaves the zone, then the drag phase (the leaving
  position changes nothing, no item outside the slots, no vertical limit). A release in the hold phase leaves it open
  without time limit; the next touch-down on a slot inside the band highlights with a click and continues in the drag
  phase, any other touch-down closes it and is swallowed until its lift. Cells are drawn with their own 9 px frame, per
  row from the rightmost final position to the leftmost; popup and picker edges are floored to whole device pixels.
- **Checked on the emulator** (release build, `input motionevent` with screen recordings, frame by frame): no popup in
  any frame on the release of shift, backspace and space; a one-shot shifted "Q" keeps its pressed look and the popup
  "Q" for one more frame (≈ 84 ms) while the keys are already lower case, then both disappear in one frame; g → h on
  the letter page: g's popup ends ≈ 80 ms after leaving g's area, h shows nothing, "h" is typed; 5 → 6 on the symbol page:
  the popup moves to 6 in the next frame and 6 is typed; leaving upwards from t and from 5 types nothing; a picker
  released without a move is still open after 3.5 s, a tap on ß types it, a tap on a key below the band closes it and
  types nothing; the opening of `o`'s picker shows ö with its frame bar sliding out over o. The popup of a top-row key
  covers rows −209 … −7 and of q columns 9 … 139.

`KeyTouchTest` drives `KeyTouch` with the German layout data and a fake clock (release delay and frozen label, no popup
for keys without one, sliding on letter and symbol pages, keys reached by sliding without click, hold timer and output
for space/Enter/backspace, leaving upwards and coming back, a 170 px vertical move inside the start key, the picker
examples of 03 §3.4, the sticky picker, key repeat, fast typing, the page-switch hand-over); `HitTestTest` has every
worked example of 03 §1.1 and §1.1a and the indent rule of rule 9 for every pixel; `PickerModelTest` the zones, slots,
rows and the opening animation.

### Swipe start, hold timer and live word (0.6.8)

- **Swipe candidate on any key** (03 §1.5 item 1, 06 §2.1): `KeyTouch.down` makes every touch in the upper 75 % of a
  letter page with shape writing a trace candidate; for a start key that is not a letter (Shift, Backspace) it keeps the
  key's cell and starts the trace only when the threshold is exceeded **and** the finger is outside that cell (a long
  move inside it stays a key press). `Host.traceStart` passes all samples since touch-down (recognised on release) and
  the index of the first drawn sample (the swipe start for Shift/Backspace, 06 §2.3). The controller then sets the
  shift state back for a trace that started on Shift (unless caps lock is on now).
- **Hold timer** (03 §1.5 item 2, §3.2; 06 §2.3, §2.4): the swipe start no longer stops it; it stops when the finger
  leaves the start key's hysteresis area or lifts. When it fires on a trace that has not left the key and opens the
  picker or starts a key repeat, the trace ends (`traceEnd`, the drawn trace fades; `traceAborted`, nothing is
  recognised) and the touch continues as a picker selection or a repeat. A key without a picker or repeat leaves the
  trace running.
- **Live word** (06 §5.3): `LiveSwipe` follows the key sequence with `KeySequenceTracker`; each gained key starts a
  recognition of the samples so far unless one is still running; its best word (case of 06 §5.2) replaces the strip.
  Release: the result list replaces it; no result, a cancel or a long press that won: the strip of the text state comes
  back. Results that arrive after the lift are dropped.

`KeyTouchTest` (0.6.8): a vertical 120 px move inside `s` becomes a trace and the long press then opens the picker,
fades the trace and commits "ß" by dragging; the hold timer of a trace that left its key never fires; a key without
alternates keeps the trace; the Shift example of 06 §2.1 ((80, 470) → (80, 360) no trace, (90, 350) trace drawn from
sample 2, no feedback for keys reached); a 189 px move inside the Shift cell is no trace; Backspace: a move inside the
cell types a backspace, leaving it beyond the threshold swipes, a held Backspace repeats and never traces; a cancelled
trace is aborted; the sticky picker tap clicks once and commits silently. `LiveSwipeTest`: a word per gained key, none
while a recognition runs, none after the lift, the strip restored without a result and after an abort, the samples
before a Shift start, and the German engine's word for a partial trace of "hallo".

### Default keyboard language (0.6.9)

`languages` has no fixed default (07 §9.1). `Prefs.languages(prefs, systemLocales)` is the one place that reads it: while
the key has never been stored it takes the first locale of the device's locale list whose language is supported, by
language only (`de-AT` → `de-DE`, `en-GB` → `en-US`, `pt-PT` → `pt-BR`, `es-MX` → `es-ES`, `fr-CA` → `fr-FR`; none →
`en-US`), stores it and returns it, so whichever of keyboard (`KeyboardSettings.read` at the controller's creation,
before the first engine is loaded) and settings app (`SettingsStore` at the activity's creation, before any page reads
it) comes first writes it. The device list is `LocaleManager.getSystemLocales()` on Android 13+ (not the per-app
language of the settings app), else `Resources.getSystem()`'s locales. A stored value is never rewritten, also not a
set without supported tags (it is read as the device default, 07 §9.1 "unknown tags are ignored"); installations that
stored `languages` before keep it, and a restored backup of the settings file brings its stored value along. The
keyboard's initial language is the first of the enabled languages (before: German). `KeyboardSettings.DEFAULT` (tests
only) has the fallback `{en-US}`. `DefaultLanguageTest` covers the mapping and the write-once rule. Checked on the
emulator with the release build and cleared app data (`pm clear`): system language French, the keyboard opened first
in a plain text field shows AZERTY with "espace" and stores `{fr-FR}`; system language switched to English with the
data kept: still French; data cleared again and the settings app opened first: `{en-US}` stored, the languages
summary reads "English (United States)" and the try-it field opens the English layout ("space").

### Inline autofill (0.6.9)

Owner report (0.6.8, Bitwarden): the strip stayed empty in login fields. Reproduced on the emulator (Android 15) with
a test-only autofill service that offers two inline suggestions for every username/password field: the platform
rendered both chips and handed them over, but the chip views were added to the row with a wrap-content width, which
replaced the size the platform gives each chip view (the renderer's measured size, e.g. 245 × 105 px). The chip view
does not measure its rendered content, so every chip was 0 px wide; the keyboard's own strip content was hidden for the
chips (`autofillActive`), so the strip was empty. Second cause: the request is made at input start, which on the first
input after the process start and after a rotation comes before the input view exists or is laid out; the request then
returned null (no inline suggestions for that field).

- Chips keep the platform's size (`InlineAutofill.chipLayoutSize`; 03 open point "accept the platform size").
- The request needs no view: sizes and style come from `KeyboardView.metricsFor`/`themeFor` with the settings
  (the laid-out view's metrics when there is one). It is skipped only while the keyboard window has never been shown:
  the platform renders chips for the window's input token, and without one it sends an empty response after the
  chips (seen on the emulator); without a request the autofill service shows the system's drop-down for that field.
- `ChipSlots` shows each chip as soon as it is inflated: after a rotation the platform sends the same suggestions twice
  and calls back once per chip for either response, so waiting for all chips of the newest response showed nothing
  in about one of three rotations. A clear (keyboard hidden, input view started, empty response) drops every earlier
  response.

Checked on the emulator, debug and release builds, with the test service: password field with the keyboard process
running (chips "alice", "bob"; a tap fills both fields), hidden and shown again, username after password, username
after typing in a plain field (no chips there), back from another app, six rotations in a row, a tap after rotations
(fills); first input after the keyboard process started: the system drop-down. `InlineAutofillTest` covers the request
rule, the chip spec from the settings, the platform size and the slot rules (repeated response, restart, clear).

### Own edits versus caret moves

`InputLogic` tracks the selection itself. After each own edit it records the selection it expects; an
`onUpdateSelection` that matches an expected entry is an own edit, anything else is a user move (04 §10).
Composing-only changes are not moves. Stored candidate lists, autocorrected ranges and spelling marks are shifted
with the keyboard's own edits and discarded when an edit overlaps them (03 §6.4.2).

The only composing text the keyboard keeps is a swiped word (04 §12.1). Every own edit finishes it first, also
insertions the editor performs itself (the paste action, commitContent): Android's `deleteSurroundingText` skips a
composing region, so a leftover one would make backspace delete the text before it. The swiped word is finished even
if the editor's selection updates do not report a composing region.

### Testability

`InputLogic`, `ListScroller`, `Curves`, the layout model, `FieldState`, `EmojiData`, `EmojiHistory`,
`ClipboardHistory` (incl. image entries) and `ImportRules` are plain Kotlin. The unit tests in `app/src/test` drive `InputLogic` with a fake editor and the
bundled German dictionary (autocorrection and undo, double-space period, smart space, emoji rules, grapheme
backspace, word delete, caret moves, Enter, swiped words, spelling marks) and check the scroller against the
reference numbers of 02 §7.4 (3000 px/s → 1.71 s / 1745 px, xMax ≈ 776 px). `ImportRulesTest` covers the sound
file naming rules, the WAV check and the font parsing of 07 §9.9/§9.11 (synthetic fonts and the bundled Selawik
files, and the style name deciding before the weight class); `LayoutDataTest` the optional comma key and the
clipboard history with image entries (dedup, pinning, reported files of removed entries, version-1 data);
`ForgetRequestsTest` the preference that carries forgotten words to the keyboard; `AppBarTouchTest` the bar's touch
regions and that an empty part of the bar gives no feedback and does not toggle (0.6.4). `InputLogicTest` covers the 05 §12.5
undo rules: picking the typed word accepts it, editing the corrected word needs one undo also for an unknown word, and a
held backspace rejects when its second step deletes into the resumed word (0.6.8; before, two undos and a held backspace
never rejected); the swipe spacing of 06 §5.1 (`( " # @ / -` without, `« [ . 3` with a space), punctuation appended
after a swiped word, the capital after "Hallo.", the double-space period in a search field, the live word that cannot be
picked (0.6.8); and the selection of the word still being typed ("Kopiert" keeps "Kopierte …", an older
word gets the correction search), and the caret inside a word (0.6.4): candidates for the whole word ("Cur|sos" →
"Cursor" first), a pick replaces the whole word without a space (also inside running text), typing or backspace there
empties the strip (also after a refresh), a caret at a word start still gives an empty strip, at the word end the word is
resumed; after an input restart in the same field or a re-shown keyboard the strip follows the current selection
(caret inside a word: its candidates, and a pick replaces the word; a selected word; predictions after a separator; empty
at a word start). It also runs every own insertion and edit after a swiped word (paste, clip text
and image, emoji, text smiley, domain key, alternate, dictation, caret move, pick, autocorrection undo) on a fake
editor that, like Android, keeps the composing span through a paste and skips it in `deleteSurroundingText`, also
with an editor that leaves the composing region out of its selection updates. `LayoutDataTest` checks the fixed
text-smiley list of emoji.json. `ItalianInputLogicTest` covers the Italian rules with the bundled dictionary (accents, missing
apostrophes, "c'e", "un po", a picked "dell'" without space followed by an elided word, a swipe after "l'");
`LayoutDataTest.italianPages` the Italian layout data (English rows, no apostrophe on the letter pages, `'` on
symbols-1, "spazio", ITA, domains, decimal comma). `FrenchInputLogicTest` covers the French rules (accents, ligatures,
missing apostrophes, "jusqua", capitals without accent and "a"/"à" left as typed, "estce" and a typed "peut-etre",
"qu" left as typed with "qu'" first and picked without space, a swipe after "c'" and after "peut-", the auto space
removed before "!"); `LayoutDataTest.frenchPages` the AZERTY data (rows, 0.2-wide shift and backspace, the alternates,
no apostrophe on the letter pages, `'` and « » on symbols-1, the common side keys on the symbol pages, "espace",
FRA/FR, domains, decimal comma) and `languageKeyOrderByLabel` the order DEU < ENG < ESP < FRA < ITA < POR.

## Spec sections → code

| Spec | Where |
|---|---|
| 01 layouts, pages, roles, input types | `LayoutSet.parse`, `KeyboardController` (`setPage`, `typeOnPage`, start page per `FieldState.inputKind`) |
| 01 §5.2 / §11 languages | `KeyboardController.switchLanguage`/`nextLanguage` (label order, then tag), `layouts-<lang>[-multi].json` by the number of enabled languages |
| 05 §14.5 active set | `KeyboardController.activeSet`/`loadEngine` (engines of staying languages are reused, new ones load in the background, leaving ones are saved and released), `Prefs.LANGUAGE_RECENCY`, `forgetInStoredData` for enabled languages that are not loaded |
| 01 §7 emoji page | `EmojiData` (generated `assets/emoji/emoji.txt`), `EmojiPanel`, `EmojiHistory` |
| 01 §11.6 domains | `KeyboardController.domains` (the list of the layout data whose region is the device region; other regions: own ccTLD first) |
| 04 §1.6 ¿ ¡ | `TextRules.isOpeningMark`; `InputLogic.otherText` (appended without a space after a swiped word, 0.6.8), `InputLogic.applySwipe` (no space after the mark); auto-shift via `TextRules.isSentenceStart` |
| 06 §5.1 item 1, 04 §12.2 item 5 space before a swiped word | `TextRules.spaceBeforeSwipe` (none after whitespace, an object marker or `¿ ¡ ( " - # @ /`, 0.6.8), used by `InputLogic.applySwipe` and for a picked alternative of an unrecognised swipe (06 §5.5) |
| 06 §5.2, 04 §1.5 capital after `. ! ? :` | `InputLogic.swipeShift` (with `TextRules.capitalizesSwipe`), used by `KeyboardController.swipeShiftState` for the result and the live word |
| 06 §5.3 live best word | `LiveSwipe` (key sequence by `KeySequenceTracker`, one recognition at a time on the worker, strip kind `SWIPE_LIVE`), fed by `KeyboardController.swipeStarted`/`swipeSample`/`swipeAborted`/`swipe` |
| 06 §2.1, §2.3, §2.4; 03 §1.5 swipe start, hold timer during a trace | `KeyTouch.down`/`move`/`onHold`/`abortTrace`; `Host.traceStart` (start key, first drawn sample), `Host.traceAborted` |
| 03 §4 item 8 shift set back by a trace | `KeyboardController.shiftDown` (snapshot) and `swipeStarted` |
| 04 §11.3 French apostrophes, hyphens, typography | as Italian; hyphen compounds and apostrophe words are dictionary forms (ENGINE.md), a picked or autocorrected compound is inserted whole; no-break spaces are not produced, the auto space before `; : ! ?` is removed as in every language |
| 04 §11.2 Italian apostrophes | elision, truncation and the missing apostrophe in the engine (ENGINE.md); `InputLogic.pick`: a strip item with `elisionPrefix` is inserted without trailing space or auto space and runs the typing query for the bare prefix; `InputLogic.applySwipe`: no space after an elision prefix of a loaded language (`PredictionEngine.isElisionPrefix`) |
| 04 §11.1 Portuguese words (hyphen, `d'`, º ª) | the hyphen is no trigger and empties the strip (`InputLogic.otherText`); `d'` and º ª are handled by the engine (`LanguageEngine.elisionPrefixLength`, ENGINE.md) |
| 02 §2, §3, 03 §17 sizes | `Metrics` (portrait s = w/1080, landscape s = min(w/1596, short/1080), height factor) |
| 02 §4 typography | `TextStyles`, `Fonts`, `KeyboardView.drawLabel` (cap-height centring, 96 % fit) |
| 02 §4.2 key-label weight (0.6.4) | every style the table gives as semibold uses `Weight.LABEL`; `Fonts.get(LABEL)` = the imported semibold font, else Selawik Regular |
| 02 §5.3–§5.7 keys, popup, picker | `KeyboardView.drawKey`, `drawPopup`, `drawPicker` (per-cell frames, drawing order, pixel snapping); `PickerModel.place` (mirroring, two rows), `PickerModel.cellAt` (opening slide) |
| 02 §5.8, 03 §6 strip | `StripBar` |
| 03 §6.6 items 2, 8 paste button, 02 §5.8.5 | `KeyboardController.canPaste` (false while the chip shows) and `pasteDimmed` (only the clip missing); `StripBar.draw` draws the header normally, at 50 % opacity (`saveLayerAlpha`, not tappable, target home only before the first candidates) or not at all; `KeyboardAccessibility` marks the dimmed header disabled (03 §19.2) |
| 03 §6.6 items 5, 6 paste, 04 §12.2 items 2, 4 | `InputLogic.paste`/`insertContent`/`insertClip`: the swiped word's composing text is finished before the editor's paste, an image or a clip is inserted |
| 03 §6.11 recently-copied chip | `KeyboardController.chip`/`chipPaste` (current clip, 2 min, pasted stamp), `StripBar.drawChip` and chip slot |
| 03 §6.3 item 4 hold to forget | `StripBar.onHold`/`drawForget`, `KeyboardController.isForgettable`/`forgetWord` → `PredictionEngine.forget` |
| 03 §10.1 item 3, §10.3 item 7, §10.4 images | `KeyboardController.recordCurrentClip`/`insertImage`, `ClipImages`, `ClipImageProvider`, `ClipboardPanel` thumbnails |
| 05 §10.4, §12.5 in the keyboard | `InputLogic.pick` (accepting picks), `InputLogic.backspace` (single and held: both reject when they delete into a resumed correction, 0.6.8), `KeyboardController.keyRepeat` |
| 07 §9.13 item 5 learned words | `DataPages.LearnedWords` (learned files + `Dictionary.bundledContains`)/`LearnedWordsPage`, `Prefs.FORGET_WORDS`, `KeyboardController.applyForgetRequests` |
| 03 §6.4 item 5 selected word still typed | `InputLogic.onSelectionUpdate`/`typedWordSelection` |
| 04 §10 input start, restart and re-shown keyboard (0.6.4) | `InputLogic.startInput` and `InputLogic.reshown` (called from `KeyboardController.onShow`) set the strip from the current selection (`stripFromSelection`, shared with user moves) |
| 04 §10 item 5, §4.1 caret inside a word (0.6.4) | `InputLogic.caretWordCandidates` (strip kind `CARET_WORD`), `InputLogic.pick` (selects the word and replaces it, no trailing space) |
| 02 §5.10, 06 §6 trace | `TraceOverlay` |
| 02 §5.11, 03 §11, §12 app bar | `AppBar`, `KeyboardController.appBarButtons`/`runAction`; touch regions and the rule that only "…" toggles (03 §12.2 item 5, 0.6.4) in `AppBarTouch` |
| 02 §5.12, 03 §10 clipboard | `ClipboardHistory`, `ClipboardPanel`, recording in `KeyboardController` |
| 02 §6 glyphs | `Glyphs` (own vector drawings; app-bar pictograms are own designs) |
| 02 §7, 03 §6.8 motion | `Curves`, `ListScroller` |
| 02 §8, §9 colours | `Theme` |
| 03 §1.1 hit testing, §1.1a key choice, §1.2 hysteresis | `layout/HitTest.kt`: `KeyCells`, `HitTester`, `LetterCorrection`, `Hysteresis` |
| 03 §1.2–§1.5, §2 touch, sliding, popup | `KeyTouch.down`/`move`/`up` (pressed look, popup and release delay: `pressedKey`, `popup`) |
| 03 §3, §5 picker, repeat, word delete | `KeyTouch.onHold`, `repeatTask`; `PickerModel` (hold zone, drag phase, slots, sticky picker) |
| 03 §4 shift | `KeyboardController.shiftDown`/`updateAutoShift` |
| 03 §13 hardware keyboard | `MetroKeysService.onKeyDown`, `KeyboardController.toggleMiniKeyboard`, slide in `KeyboardView` |
| 03 §14 dictation | `Dictation` |
| 03 §16 inline autofill | `MetroKeysService.onCreateInlineSuggestionsRequest`/`onInlineSuggestionsResponse`, `InlineAutofill` (request, chip sizes, `ChipSlots`), see "Inline autofill (0.6.9)" |
| 03 §18 insets | `MetroKeysService.onComputeInsets`, navigation inset from window insets |
| 03 §19 accessibility | `KeyboardAccessibility` (ExploreByTouchHelper; keys are text-entry keys) |
| 04 text rules | `InputLogic` (with `TextRules`/`TextModel` of the engine) |
| 06 shape writing | touch side in `KeyboardView`, recognition in the engine, insertion in `InputLogic.applySwipe` |
| 01 §5.2 comma key (extension) | `LayoutSet.withOptionalComma`, chosen in `KeyboardController.loadLayout` (`show_comma_key`) |
| 05 §13 contacts | `ContactsWordSource` (harvest, observer with 1 s debounce, 5 retries 30 s apart) → `PredictionEngine.refreshContacts` |
| 07 §9.5 languages page | `TypingPages.LanguagesPage` (one check box per `Prefs.SUPPORTED_LANGUAGES`, fixed order by tag: de, en, es, fr, it, pt; any number may be checked, only the last one cannot be unchecked; note for more than three), `LanguageNames`, `SettingsStore.setLanguages` (also prunes `language_recency`) |
| 07 §9.1 preferences | `Prefs`, `KeyboardSettings`; the service listens for changes; `SettingsStore` observes them for Compose |
| 07 §9.1 default of `languages` (0.6.9) | `Prefs.languages` (the only place: stores `Prefs.defaultLanguage(Prefs.systemLocales)` when nothing is stored), used by `KeyboardSettings.read`, `SettingsStore` (also at its creation) and the learned-words page |
| 07 §1, §8 activity, back stack | `SettingsActivity` (Navigation 3 `NavDisplay`, saved string back stack, edge-to-edge, `Theme.MetroKeys.Settings` window background) |
| 07 §3, §4 colours, type | `settings.ui.MetroTheme` (`MetroColors`, `MetroFonts`, `MetroType`, `Accents`, `MetroEasing`) |
| 07 §5 page structure | `settings.ui.PageLayout` (`MetroPage`, `baselines` modifier, `PageMargins`, title shrinking) |
| 07 §5.3 spacing between elements | `settings.ui.Blocks` (`BlockColumn`, `BlockGaps`, `InkTop`/`InkBottom` lines), `settings.ui.Ink` (glyph extents) |
| 07 §6 controls | `settings.ui.Controls` (list item, toggle, check box, radio, button, slider), `ListPicker`, `Overlays` (full-screen picker, accent grid, message box), `TextBox` |
| 07 §7.2 tilt | `settings.ui.Tilt` (`Modifier.tilt`, a `Modifier.Node` driven by press interactions) |
| 07 §7.3 turnstile | `settings.ui.Turnstile` (per-entry animation on Navigation 3's transition, also seeked by predictive back) |
| 07 §9.2–§9.14 pages | `MainPage`, `TypingPages` (languages, suggestions, typing), `LayoutPages` (layout, app bar buttons), `SoundPages`, `ThemePages` (theme, font), `DataPages` (data+info with the about section, learned words, licenses) |
| 07 §9.12.1 app bar buttons | `LayoutPages.AppBarButtonsPage`: `AppBarPreview` (keyboard colours from `Theme.create`, pictograms from `Glyphs.appBarIcon`), `ModeSwitch`, `ActionGrid`; the open-app picker and the microphone request |
| 07 §9.13 item 6 about | `DataPage` (version from `PackageManager`), `StandaloneLink` (07 §6.2), `openLink` (ACTION_VIEW, toast when nothing handles it) |
| 07 §9.9, §9.11 imports | `ImportRules` (pure), `AssetImporter`, `SoundPreview` |
| 07 §9.14 licences | Gradle task `LicensesTask` → generated asset `licenses.txt` (MetroKeys and the GPL v3 first) |
| 08 launcher icon | `res/drawable/ic_launcher_*.xml`, `res/mipmap-anydpi/` (adaptive with monochrome layer) |

## Storage

| Data | Location |
|---|---|
| Settings | SharedPreferences file `settings` (keys of 07 §9.1); `active_language` and `emoji_history` are written by the keyboard |
| Learned data | `noBackupFilesDir/learned/<language>.bin` (engine format, `LearnedFiles`), saved when input finishes and after a forget |
| Clipboard history | `noBackupFilesDir/clipboard.bin` (format version 2 with image file and MIME type per entry) |
| Clipboard images | `noBackupFilesDir/clips/<sha-256>.<ext>`, served as `content://app.metrokeys.clips/<file>`; deleted with their entry, stale files swept at start |
| Forgotten words for the keyboard | preference `forget_words`: lines "timestamp TAB word", last 20 |
| Imported fonts | `noBackupFilesDir/fonts/<slot>`, slot = light, semilight, regular, semibold, bold, emoji |
| Imported sounds | `noBackupFilesDir/sounds/<slot>.wav`, slot = letter, space, function, picker, candidate, dictionary |
| Contact names | memory only (keyboard process) |
| Keyboard language selection order | preference `language_recency` (written by the keyboard) |
| Keyboard languages | preference `languages`, stored on first use from the device's locale list (0.6.9) |
| App language | Android's per-app locale (`LocaleManager`, Android 13+); `generateLocaleConfig` declares `en` and `de` |

No-backup storage is never backed up or transferred; the backup rules include only the settings file. After an
import the settings app changes `custom_assets_stamp`; the keyboard reloads fonts and sounds on every preference
change.

## The settings app

`SettingsActivity` is the launcher activity and the input method's settings activity. It hosts a Navigation 3
`NavDisplay` over a back stack of page keys (`Pages`), saved with `rememberSaveable` so it survives configuration
changes and process death; per-page state (scroll position, open pickers, message boxes, the try-it text) is saved by
the entry's saveable state holder. Pages read and write the preferences through `SettingsStore`, whose reads subscribe
to a change counter fed by the preference listener, so summaries follow changes made anywhere (also by the keyboard).

- **Spacing** (07 §5.3): the content of a sub-page is a `BlockColumn`. Every element is a block of a kind (body text,
  field header, note, group title, picker, toggle, slider, check box, list item, button) and reports its visual edges
  as alignment lines: the glyph top of its first and the glyph bottom of its last text line (`InkMetrics`, measured with
  the resolved typeface on the laid-out lines) or a control's outline. `BlockGaps` gives the gap per pair of kinds in
  reference px. Inside controls: picker header glyph top → box 48 px; toggle header glyph top → row bottom 123 px
  (two toggles 199 px apart), the state label cap-centred in the row; a control's note 36 px below its bottom edge;
  check box rows exactly 48 dp (+ 44 px per further line); message box buttons 76 px below the text. The main page keeps
  its own paddings (`MetroPage(blockSpacing = false)`).
- **Controls** are drawn with Compose foundation only (no Material component is visible; Material 3 is used only for
  `dynamicLightColorScheme`, the "system colors" app accent). Texts are positioned by baseline distances
  (`Modifier.baselines`) as the spec gives them.
- **Overlays**: full-screen pickers, the accent grid and message boxes register with an `OverlayHost` while open and
  are drawn above the `NavDisplay`, so they cover the status bar, take no part in the page transition and their Back
  handlers win. Inline pickers collapse on Back or on a touch outside (`DismissRegistry`, the touch is swallowed).
- **Turnstile**: `NavDisplay` gets "no animation" transforms; each entry rotates itself about a hinge at its left edge
  by animating on the entry's `AnimatedContentScope.transition`. The direction is decided once per transition from the
  back stack and whether the last change was a pop, so a predictive back that commits keeps its direction. The
  activity opts into `enableOnBackInvokedCallback` so Android 13–15 deliver the gesture progress.
- **Setup status** is re-read on resume, on window focus and by a content observer on the enabled/default input
  method settings. Permissions are re-read on resume and when a toggle asks for them; a contacts setting whose
  permission was revoked is turned off on resume.
- **Imports** run on `Dispatchers.IO`; files are written to a temporary file and renamed (fonts only after
  `Typeface.Builder` loaded them). After an import or removal `custom_assets_stamp` changes and the keyboard reloads.
- **Licenses page**: `LicensesTask` (in `app/build.gradle.kts`) writes `licenses.txt` per variant: first MetroKeys'
  copyright and GPL notice with the GNU GPL v3 text (`app/licenses/GPL-3.0.txt`, the verbatim text of the repository's
  `LICENSE`), then the third-party texts from docs/DATA-SOURCES.md (Selawik and emoji rows), the bundled licence files, the dictionaries' `LICENSE-*.txt` and the
  resolved runtime libraries (all under the Apache License 2.0, text in `app/licenses/Apache-2.0.txt`); the build
  fails for a library group without a known licence. Hard-wrapped lines are re-flowed.

## Decisions where the specification leaves room

- **Resources**: English in `values`, German in `values-de` (see QUESTIONS); the keyboard creates a
  configuration context with the per-app locale (Android 13+) each time it is shown.
- **Hardware keyboard**: strip-only mode exactly when the platform would hide the on-screen keyboard.
- **Emoji**: Unicode emoji 18.0; emoji the system font cannot draw (`Paint.hasGlyph`) are hidden at run time, so
  older devices do not show empty boxes.
- **Swipe start**: the per-axis threshold of 06 §2.2 (see QUESTIONS).
- **Keys reached by sliding** (03 §1.2a, 0.6.6): shift, page and function keys reached by sliding show the pressed look
  like any other key (item 4) but do nothing; re-hits during a trace count for the fast-typing history (see QUESTIONS).
- **Language popup/picker**: label in the upper half, sub-label or keyboard pictogram below (02 open point 1).
- **Strip compression**: items are moved by the compression mapping; their text is not squeezed.
- **Picker rows**: the upper row is placed with the same right/mirror/push rule as the lower one.
- **App-bar pictograms**: own line drawings (send, search, arrows, check mark, emoji, microphone, hide, switch,
  start, web search, clipboard, language, cursor, undo, settings, open app: a box open at its top-right corner with an
  arrow leaving it); smiley, speech bubble and microphone follow the
  measured ink boxes of 02 §5.11 (measured on the emulator: 36 × 36, 40 × 28, 28 × 40 px with antialiasing). The
  expanded bar's labels share one baseline: the ascender top of "dl" 3 px below the bar.
- **Web search fallback**: a search URL in the browser when no app handles `ACTION_WEB_SEARCH`.
- **Selection candidates**: the correction search for the selected word plus the add-to-dictionary item; if the selection
  is the first move after typing and covers exactly the typed word, the typing list stays (shown list, else a typing
  query), without bold and without the add item (03 §6.4 item 5).
- **Learning at separators**: only words typed since the last caret move.
- **Tilt and turnstile perspective**: Compose `cameraDistance = 16 × density` (≈ 3000 px); measured on the emulator:
  a press near the left edge of a list item shifts its text 16 px right (spec: ~17 px).
- **Turnstile**: whole page, no feathering (07 §7.3 MAY); backward durations equal forward (07 §13 point 2).
- **Message box**: appears without animation (07 §6.10 open point).
- **Buttons**: 32 dp outline in a 48 dp touch box; spec spacings are applied to the outline.
- **Tilt angles**: the practical values of 07 §7.2 (list item 6°, check box 6°, picker 4°, button 10°, grid cell 12°),
  push-back scale 0.985, 50 ms in, held until 300 ms, 200 ms return with D(10).
- **Settings regrouping (0.5.2)**: the stored keys are unchanged; a saved back stack with a page key that no longer
  exists ("clipboard", "appbar") falls back to the main page.
- **Layout summary**: "<height>, <mode>" with the percent format of 07 §11.1 ("100%" in English, "100 %" in German);
  the spec's English example "100 %" is read as a typo (see QUESTIONS).
- **App bar buttons page**: the preview is drawn with the keyboard's own `Theme` and `Glyphs` at s = page width / 1080;
  the text field action is shown as "send" (speech bubble), the keyboard shows the field's own action. A selected app
  button keeps a 6/41 accent ring around the app icon; unselected app icons fill the circle. Disabled colours as on the
  keyboard (switch keyboard language with one language, a removed app). Grid tiles: 41 dp circle, ring 6/41 and stroke
  5/41 of the radius, labels with two lines reserved so rows line up and automatic hyphenation for long German compounds; tilt 12° like accent cells; preview buttons
  tilt 10°. Own gaps (07 §5.3 has none for these elements): body text → preview 60 px, preview → mode switch glyph top
  48 px, mode switch → field header 67 px (group-title rule), field header → grid 20 px from the header's layout bottom
  (so the grid does not move when the header text changes).
- **Data+info**: list item → field header 60 px, field header → body text 20 px (own values); the three about lines
  are one body text with line breaks; the stand-alone link tilts like a button and has a 48 dp touch height.
- **Clipboard history off**: the settings app also deletes `clipboard.bin` and the image directory, so a keyboard that
  is not running cannot bring old entries back.
- **Chip copy time**: the time the clipboard listener saw the change, else the clip's own timestamp if plausible; a
  timer re-evaluates the strip when the two minutes are over. The chip follows 02 §5.8.9: square key-coloured box
  0.64 × the strip height, outline clipboard in a 54 px slot, preview at 0.8 × the candidate size (≤ 45 % of the strip),
  24 px right padding, 12 px slot margins; an image thumbnail follows the pictogram slot after the same 10 px gap as text.
- **Images**: the clip's MIME type comes from the clip description, else the content resolver; bytes are read with the
  clipboard's grant on a separate thread, hashed for the file name (the same image again is one entry) and checked with
  a sampled decode. Thumbnails are decoded on that thread into an LRU cache of 32.
- **Messages instead of toasts**: when the app's notifications are off (Android 13+ drops toasts of the keyboard then),
  the text is shown in the strip for the toast's duration (see QUESTIONS).
- **Learned words in the settings**: stored words of the enabled languages' learned files, checked against the enabled
  languages' dictionaries (see QUESTIONS); lookups are cached per key.
- **Forget from the settings**: the settings app forgets the word in the learned files it read and appends
  "timestamp TAB word" to `forget_words`; the running keyboard applies
  every entry newer than the newest one it saw at start, also when its engine finishes loading later.
- **Font slot by style name** (07 §9.11): name ID 17, else 2, matched case-insensitively with spaces, hyphens and
  underscores removed, "semilight"/"semibold" before "light"/"bold"; italic fonts stay rejected and an "emoji" family
  still wins. The bundled Selawik Semilight now lands in the Semilight slot.
- **Loaded languages** (05 §14.5): the active set is loaded also with multilingual typing off, so the language key
  switches between up to three languages without loading; with more than three enabled languages, selecting one
  outside the set loads it in the background while the staying languages answer.
- **Live swipe word** (06 §5.3, 0.6.8): drawn like a normal candidate (not bold; the bold "applied" style is kept for the
  inserted word after the release); a tap on it does nothing (the release result follows at once). While it is shown,
  other strip lists (a late typing query) are held back and the text state's list is shown when the gesture ends
  without a result. The recognition of the partial trace uses the same engine call as the release, on the worker; with
  one key in the sequence nothing is recognised (06 §3.1 needs two keys).
- **Shift set back by a trace** (03 §4 item 8): shift state, manual mark, "just unlocked" flag and the time of the
  previous shift touch-down are restored, so a following tap is no double tap.
- **Keyboard switched while shown**: `InputMethodService.onDestroy` finishes the input view after
  `KeyboardController.destroy` has shut the engine worker down; background tasks posted then are dropped (the learned
  data was saved in `destroy`). Before 0.6.1 this crashed the keyboard process when another keyboard was chosen.
- **Hold on a candidate**: after 450 ms the release never picks; only learned words unknown to every loaded
  dictionary open the confirmation.
- **Key-label weight** (02 §4.2, 0.6.4): the popup sizes the table gives as semibold for punctuation, glyph and dial-pad
  keys (`punctuation-large` 105, `icon` 102, `dialpad-punctuation` 105) use the label weight as well, like every other
  semibold entry of the table; the keys' own semilight/regular weights are unchanged.
- **Caret inside a word** (04 §10 item 5, 0.6.4): the word is the run of word characters on both sides of the caret
  (≤ 27 characters); a correction-search result is not stored on the word (as for a selected word), a stored list of the
  word is shown and moves to the picked word. The pick selects the word in the editor and commits the candidate over it.
  A refresh (engine loaded, settings changed) shows the list again only while no key was used since the move.

## Not done yet / simplified

- 03 §6.3.2 extension (restoring a kept list when no follow-up list arrives within 250 ms) is not implemented.
- 03 §19.3.2 (hover-exit activation for screen readers that do not type text-entry keys) is not implemented;
  TalkBack types the keys itself.
- No "not recognised" swipe sound (no sound assets are shipped).
- Compact mode draws its own hide/switch pictograms; Android's own IME buttons in the gesture inset stay visible.
- Settings: no feathering of the outgoing page (whole-page turnstile), no message box animation, no original file
  name in the sound slot subtitles (MAY), the radio button is part of the control set but used on no page.
