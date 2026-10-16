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
release APK (10.6 MB since the Portuguese dictionary in 0.6.1, 9.1 MB in 0.6.0; debug about 22 MB) was checked on the emulator: typing, suggestions, autocorrection, shape
writing, emoji, clipboard text and images, app bar, all settings pages, learned words and licences. Signing uses
`keystore.properties` in the repository root when present (README, "Building"); the version is `appVersionName` /
`appVersionCode` in `gradle/libs.versions.toml`.

The layout, metrics and theme data of `spec/data/` are packaged unchanged as assets (the `main` source set adds
`spec/data` as an asset directory), so the specification stays the only source of the layouts.

## Architecture

```
MetroKeysService (InputMethodService)        Android lifecycle, insets, hardware keys, inline autofill
 └─ KeyboardController                       pages, shift, languages, panels, app bar, dictation, clipboard
     ├─ InputLogic ── Editor                 text rules of spec 04 over an editor abstraction
     │                └─ InputConnectionEditor
     ├─ PredictionEngine (engine module)     loaded on a worker thread
     ├─ ClipboardHistory + ClipImages        history model; image files, thumbnails (own thread)
     │   └─ ClipImageProvider                read-only content provider for commitContent
     └─ KeyboardView (custom View)           drawing and touch of everything below
         ├─ StripBar        candidate strip, paste header, recently-copied chip, clipboard button, flash,
         │                  dictation state, forget confirmation, messages
         ├─ key area        keys, popup, alternates/language picker (in KeyboardView itself)
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

Everything runs on the main thread except engine work: dictionary loading, queries, learning, shape recognition and
proofreading go through one worker thread (`Scheduler.background`).

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

The whole scenario (0.5) also runs with the per-app heap limit lowered to 64 MB (`dalvik.vm.heapgrowthlimit`, root on the
emulator) without an OutOfMemoryError; the default limit is 192 MB. Results come back with a generation number; an
outdated answer is dropped (03 §6.2). Autocorrection at a separator uses the last query result for the same text or
runs the query synchronously, so a fast trigger is never missed.

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
`ForgetRequestsTest` the preference that carries forgotten words to the keyboard. `InputLogicTest` covers the 05 §12.5
undo rules: picking the typed word accepts it, editing the corrected word needs two undos for an unknown word, and a
held backspace never rejects; and the selection of the word still being typed ("Kopiert" keeps "Kopierte …", an older
word gets the correction search). It also runs every own insertion and edit after a swiped word (paste, clip text
and image, emoji, text smiley, domain key, alternate, dictation, caret move, pick, autocorrection undo) on a fake
editor that, like Android, keeps the composing span through a paste and skips it in `deleteSurroundingText`, also
with an editor that leaves the composing region out of its selection updates. `LayoutDataTest` checks the fixed
text-smiley list of emoji.json.

## Spec sections → code

| Spec | Where |
|---|---|
| 01 layouts, pages, roles, input types | `LayoutSet.parse`, `KeyboardController` (`setPage`, `typeOnPage`, start page per `FieldState.inputKind`) |
| 01 §5.2 / §11 languages | `KeyboardController.switchLanguage`/`nextLanguage` (label order, then tag), `layouts-<lang>[-multi].json` by the number of enabled languages |
| 05 §14.5 active set | `KeyboardController.activeSet`/`loadEngine` (engines of staying languages are reused, new ones load in the background, leaving ones are saved and released), `Prefs.LANGUAGE_RECENCY`, `forgetInStoredData` for enabled languages that are not loaded |
| 01 §7 emoji page | `EmojiData` (generated `assets/emoji/emoji.txt`), `EmojiPanel`, `EmojiHistory` |
| 01 §11.6 domains | `KeyboardController.domains` (the list of the layout data whose region is the device region; other regions: own ccTLD first) |
| 04 §1.6 ¿ ¡ | `TextRules.isOpeningMark`; `InputLogic.otherText` (space first after a swiped word), `InputLogic.applySwipe` (no space after the mark); auto-shift via `TextRules.isSentenceStart` |
| 06 §5.1 item 1 space before a swiped word | `TextRules.spaceBeforeSwipe` (none after whitespace, `¿ ¡` or "letter + hyphen"), used by `InputLogic.applySwipe` |
| 04 §11.1 Portuguese words (hyphen, `d'`, º ª) | the hyphen is no trigger and empties the strip (`InputLogic.otherText`); `d'` and º ª are handled by the engine (`LanguageEngine.elisionPrefixLength`, ENGINE.md) |
| 02 §2, §3, 03 §17 sizes | `Metrics` (portrait s = w/1080, landscape s = min(w/1596, short/1080), height factor) |
| 02 §4 typography | `TextStyles`, `Fonts`, `KeyboardView.drawLabel` (cap-height centring, 96 % fit) |
| 02 §5.3–§5.7 keys, popup, picker | `KeyboardView.drawKey`, `drawPopup`, `openPicker`/`drawPicker` (mirroring, two rows, opening slide) |
| 02 §5.8, 03 §6 strip | `StripBar` |
| 03 §6.6 items 2, 8 paste button | `KeyboardController.canPaste` (false while the chip shows); `StripBar.draw` draws it only when pasting is possible |
| 03 §6.6 items 5, 6 paste, 04 §12.2 items 2, 4 | `InputLogic.paste`/`insertContent`/`insertClip`: the swiped word's composing text is finished before the editor's paste, an image or a clip is inserted |
| 03 §6.11 recently-copied chip | `KeyboardController.chip`/`chipPaste` (current clip, 2 min, pasted stamp), `StripBar.drawChip` and chip slot |
| 03 §6.3 item 4 hold to forget | `StripBar.onHold`/`drawForget`, `KeyboardController.isForgettable`/`forgetWord` → `PredictionEngine.forget` |
| 03 §10.1 item 3, §10.3 item 7, §10.4 images | `KeyboardController.recordCurrentClip`/`insertImage`, `ClipImages`, `ClipImageProvider`, `ClipboardPanel` thumbnails |
| 05 §10.4, §12.5 in the keyboard | `InputLogic.pick` (accepting picks), `InputLogic.backspace(repeated)`, `KeyboardController.keyRepeat` |
| 07 §9.13 item 5 learned words | `DataPages.LearnedWords` (learned files + `Dictionary.bundledContains`)/`LearnedWordsPage`, `Prefs.FORGET_WORDS`, `KeyboardController.applyForgetRequests` |
| 03 §6.4 item 5 selected word still typed | `InputLogic.onSelectionUpdate`/`typedWordSelection` |
| 02 §5.10, 06 §6 trace | `TraceOverlay` |
| 02 §5.11, 03 §11, §12 app bar | `AppBar`, `KeyboardController.appBarButtons`/`runAction` |
| 02 §5.12, 03 §10 clipboard | `ClipboardHistory`, `ClipboardPanel`, recording in `KeyboardController` |
| 02 §6 glyphs | `Glyphs` (own vector drawings; app-bar pictograms are own designs) |
| 02 §7, 03 §6.8 motion | `Curves`, `ListScroller` |
| 02 §8, §9 colours | `Theme` |
| 03 §1 touch, §1.1a key choice | `KeyboardView.keyDown`/`chooseKey`/`keyMove`/`keyUp` |
| 03 §3, §5 picker, repeat, word delete | `KeyboardView.onHold`, `repeatRunnable` |
| 03 §4 shift | `KeyboardController.shiftDown`/`updateAutoShift` |
| 03 §13 hardware keyboard | `MetroKeysService.onKeyDown`, `KeyboardController.toggleMiniKeyboard`, slide in `KeyboardView` |
| 03 §14 dictation | `Dictation` |
| 03 §16 inline autofill | `MetroKeysService.onCreateInlineSuggestionsRequest`/`onInlineSuggestionsResponse`, `InlineAutofill` |
| 03 §18 insets | `MetroKeysService.onComputeInsets`, navigation inset from window insets |
| 03 §19 accessibility | `KeyboardAccessibility` (ExploreByTouchHelper; keys are text-entry keys) |
| 04 text rules | `InputLogic` (with `TextRules`/`TextModel` of the engine) |
| 06 shape writing | touch side in `KeyboardView`, recognition in the engine, insertion in `InputLogic.applySwipe` |
| 01 §5.2 comma key (extension) | `LayoutSet.withOptionalComma`, chosen in `KeyboardController.loadLayout` (`show_comma_key`) |
| 05 §13 contacts | `ContactsWordSource` (harvest, observer with 1 s debounce, 5 retries 30 s apart) → `PredictionEngine.refreshContacts` |
| 07 §9.5 languages page | `TypingPages.LanguagesPage` (one check box per `Prefs.SUPPORTED_LANGUAGES`, fixed order by tag; note for more than three), `LanguageNames`, `SettingsStore.setLanguages` (also prunes `language_recency`) |
| 07 §9.1 preferences | `Prefs`, `KeyboardSettings`; the service listens for changes; `SettingsStore` observes them for Compose |
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
- **Opening marks before a swipe**: 06 §5.1 inserts a space before a swiped word after any non-space character; after
  `¿`/`¡` none is inserted, following 04 §1.6 item 4 (see QUESTIONS), and since 0.6.1 none after a hyphen that follows
  a letter ("deixe-" + "me", "E-" + "Mail"; ` - ` keeps the space), in every language.
- **Keyboard switched while shown**: `InputMethodService.onDestroy` finishes the input view after
  `KeyboardController.destroy` has shut the engine worker down; background tasks posted then are dropped (the learned
  data was saved in `destroy`). Before 0.6.1 this crashed the keyboard process when another keyboard was chosen.
- **Hold on a candidate**: after 450 ms the release never picks; only learned words unknown to every loaded
  dictionary open the confirmation.

## Not done yet / simplified

- 03 §6.3.2 extension (restoring a kept list when no follow-up list arrives within 250 ms) is not implemented.
- 03 §19.3.2 (hover-exit activation for screen readers that do not type text-entry keys) is not implemented;
  TalkBack types the keys itself.
- No "not recognised" swipe sound (no sound assets are shipped).
- Compact mode draws its own hide/switch pictograms; Android's own IME buttons in the gesture inset stay visible.
- Settings: no feathering of the outgoing page (whole-page turnstile), no message box animation, no original file
  name in the sound slot subtitles (MAY), the radio button is part of the control set but used on no page.
