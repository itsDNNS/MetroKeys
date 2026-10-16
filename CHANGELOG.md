# Changelog

All notable changes to MetroKeys are listed here. Versions follow [semantic versioning](https://semver.org/);
before 1.0 a minor version may change behaviour.

## 0.6.7 – 2026-10-16

### Spanish, Portuguese, Italian and French dictionaries
- Short words of up to three letters that are no word of the language (names and interjections of dubbed films such
  as "ted", "amy", "huh", "ooh") are left out unless they are very frequent: 670 Spanish, 847 Portuguese, 671 Italian
  and 126 French entries fewer. German has done this since 0.6.4.
- Names and foreign words that the dictionaries know only from film subtitles count a hundred times less for
  suggestions and next-word predictions, so they no longer push real words aside: 21 863 Spanish, 27 124 Portuguese,
  14 947 Italian and 8 812 French entries. Italian "perch" now offers "perché" first instead of autocorrecting to
  "Percy". Very rare names among them are, in Italian and French, still accepted when typed but no longer suggested.
- Abbreviations that Wiktionary lists with a period ("etc.", "sig.", "Srta.") count as words of the language without
  it, so "etc" in Spanish and "sig" in Italian are kept again and "Srta" is no longer treated like a name. Common
  English words that happen to be abbreviations too ("it", "on", "all", "out") are not affected.
- The four dictionaries are about 59 KB smaller than in 0.6.6; memory use stays the same. English is unchanged.

### German dictionary
- Abbreviations from Wiktionary are words: "usw", "bzw", "etc", "evtl", "vgl" and "ggf" are known and stay as typed
  (the period is typed as usual). 890 such words are in the dictionary (657 of them only from Wiktionary); common
  English words such as "get", "got" or "mind" are not among them.
- Compounds starting with a prefix that Wiktionary lists ("anti-", "sub-", "retro-") are recognised: 306 more words
  such as "Subunternehmer", "Antivirus" and "Retrovirus".

## 0.6.6 – 2026-10-16

### Keyboard
- Key popup: the popup and the pressed look of a released key stay for 75 ms exactly as they were, also when the typed
  letter turns the shift off ("Q" stays "Q" until it disappears). Shift, backspace, space and Enter never show a popup;
  before, an empty popup (or one with the space-bar word) flashed for a moment when they were released.
- Sliding: a key reached by sliding the finger gets no click, never opens its alternates and never repeats; space,
  Enter and backspace reached by sliding do nothing. On the letter page (where a touch can become a swiped word) the
  keys passed by a sliding finger show no popup; the first key's popup ends shortly after the finger leaves it, and a
  release without a swipe types the key under the finger. On the symbol pages the popup follows the finger.
- Moving the finger off the keyboard (above the top row or below it) cancels the key: nothing is typed.
- Hit testing as in the reference: the gaps between keys belong to the key whose cell they lie in, the free space left
  of "a" and right of "l" goes to the nearest key with vertical distance counted double (it can be q/p or shift/
  backspace near the row edges), and touches up to 40 px below the keyboard go to the nearest key. A backspace tapped
  very quickly after two other keys in normal text fields types the nearest letter instead, as the reference does.
- Alternates picker: a picker released without moving stays open until the next touch (before: one second). A touch
  outside it closes it without typing anything. Sliding selects only within the cells; a finger dragged past the last
  cell selects nothing, and releasing then types nothing. Each cell slides out with its own frame, and the alternates
  of keys at the right edge slide over the key's own cell as in the reference.
- Popup and picker edges are aligned to whole pixels.

### German dictionary
- The words added in 0.6.5 from Wiktionary are limited to base forms (lemmas), which now also include lemmas seen once in
  the subtitles: 84 581 words instead of 621 051. "performant" is still offered for "performanz". The keyboard needs
  about 19 MB less memory with German (68 MB with German and English instead of 87 MB) and the release APK shrinks from
  15.7 MB to 15.0 MB. The next-word suggestions after "Vielen " are back at their earlier values.

## 0.6.5 – 2026-10-16

### Performance
- Typing fast no longer makes the keyboard wait for the app: it keeps its own copy of the text around the cursor
  instead of asking the text field 5–7 times per key, so each key is handled in well under a millisecond on the main
  thread (before up to 20–40 ms at a space). Measured on the emulator with German and English at nine keys per second:
  the corrected word appears in the app 17 ms after the space (median, 63 ms before; slowest 34 ms instead of 158 ms),
  and the keyboard drew no slow frame during 326 keys (323 before). Details in docs/APP.md, "Performance".
- Suggestions and autocorrection are computed in the background as soon as a word changes; a space typed quickly uses
  that result instead of computing it on the spot. The suggestion bar still updates after the usual typing pause, and
  every autocorrection still applies.
- Swiped words are inserted without waiting for the app (main-thread time 0.4 ms instead of 14 ms).
- The clipboard, the ringer mode and the app bar are no longer queried at every key; the key popup and the
  autocorrection flash no longer redraw the keyboard continuously while they are shown.

### German dictionary
- Words of written German that never occur in the subtitle corpus are now known: every German Wiktionary form without a
  subtitle occurrence is added with the lowest offered frequency (621 051 words, e.g. "performant", "Rückenschwimmens",
  "kompromisslosesten"). "Die App ist nicht performanz" now offers "performant" first. The word list grows from
  0.96 MB to 1.94 MB (the release APK from 14.6 MB to 15.7 MB) and the keyboard needs about 24 MB more memory with
  German loaded (still within the limits). Two next-word suggestions moved by one point (after "Vielen ": "anderen",
  "Fällen"; "Das" at the text start); their order is unchanged.
- The other languages' dictionaries are unchanged.

## 0.6.4 – 2026-10-16

### Keyboard
- Lighter key labels: letters, `&123`/`abc`, the domain key, `@`, the space-bar word, the language key and its
  sub-labels, the key-press popup, the accent picker and the language popup are drawn in Selawik Regular instead of
  Selawik Semibold, which is noticeably heavier than the reference look. An imported semibold font is used for them
  instead when there is one. Punctuation keys, number and dial pads, the suggestion bar, emoji and the settings app are
  unchanged.
- Application bar: only "…" opens and closes the expanded bar. A touch on an empty part of the bar no longer toggles it
  (no click, no change); the keyboard still takes the touch.

### Typing
- Caret inside a word: putting the caret into a word (letters on both sides) now offers candidates for the whole word,
  as for a selected word (its stored list, else corrections of the word, plus "add to dictionary" for an unknown word).
  A pick replaces the whole word and leaves the caret after it, without adding a space. Typing, backspace or another
  caret move end this; a space typed there still never autocorrects.
- After the input restarts in a field (for example when the editor's own spelling popup closes) and whenever the
  keyboard is shown again, the suggestion bar follows the current caret or selection instead of staying empty, so the
  candidates of a word around the caret stay available and a pick replaces the word.
- A missing accent or umlaut no longer counts as an edit when the keyboard looks for corrections: "Uberpeufe" (u for ü
  twice and one more typo) is corrected to "Überprüfe" like "Überpeufe" was. The cost of the missing accent itself is
  unchanged.

### German dictionary
- Short non-German keys of up to three letters need 20 000 subtitle occurrences: 336 such keys are gone ("ähm", "joe",
  "you", "seh", "nen" …); "dad", "the" and "wär" stay.
- Names and foreign words of the dubbed films that are no German word count a hundredth of their subtitle frequency:
  they stay valid but no longer push real words aside ("Cursos" now offers "Cursor" first, before "Crusoe" and
  "Curtis"). 9 228 keys are damped; those that end at the lowest frequency class are no longer offered.
- Real-word typos: a rare German word (fewer than 100 subtitle occurrences, mostly written in lower case) that is one
  key aside, or one swapped pair, from a word at least 5000 times as frequent is still accepted when typed, but is never
  offered and is autocorrected to the frequent word when the context favours it ("Ich komme heure" → "heute", "zi" →
  "zu"); 115 words are affected (list in docs/DATA-SOURCES.md). Words mainly written with a capital (nouns, names,
  abbreviations) are never affected.
- The other languages' dictionaries are unchanged (byte-identical rebuild).

## 0.6.3 – 2026-10-16

### Languages
- New keyboard language **French (France)**: the AZERTY letter rows (a z q w m in their French places, six letters in
  the third row between wider shift and backspace keys) with à â æ, é è ê ë, î ï, ô œ, ù û ü, ÿ and ç on long press, the
  apostrophe on the first symbol page and the guillemets « » on the long press of `"`, "espace" on the space bar,
  "FRA"/"FR" on the language key and picker, `.com .fr` first among the domain endings in France, and a decimal comma on
  the number pad. The language key cycles DEU → ENG → ESP → FRA → ITA → POR.
- French dictionary built from free sources (subtitle frequencies including their apostrophe and hyphenated forms,
  French Wiktionary, Tatoeba and Common Voice; CC BY-SA 4.0, attribution on the licenses page): missing and wrong
  accents are corrected ("tres" → "très", "deja" → "déjà", "garcon" → "garçon", "trés" → "très"), ligatures are
  restored ("coeur" → "cœur", "oeuvre" → "œuvre"), words that exist in both spellings stay as typed with the accented
  one offered ("a"/"à", "ou"/"où", "la"/"là"), capitals without their accent are correct ("Etat", "ECOLE"), and a
  missing space is fixed ("quelquechose" → "quelque chose").
- Elision without an apostrophe key: "jai" → "j'ai", "cest" → "c'est", "quil" → "qu'il", "lhomme" → "l'homme",
  "jusqua" → "jusqu'à"; typed with the apostrophe, only the rest is corrected ("c'etait" → "c'était"); a valid word
  offers its elided twin ("quelle" → "qu'elle"); after "c'" or "jusqu'" the bar offers whole words ("c'est",
  "jusqu'à"). Words with an inner apostrophe stay whole and are corrected without it ("aujourdhui" →
  "aujourd'hui", "quelquun" → "quelqu'un").
- Hyphenated words: frequent compounds and inversions typed without the hyphen are corrected and completed ("estce"
  → "est-ce", "peutetre" → "peut-être", "dismoi" → "dis-moi"); typed with the hyphen, each part is a word of its own.
- Shape writing for French on AZERTY: accented letters and ligatures through their base keys ("très", "cœur"), elided
  words ("c'est", "j'ai") and hyphenated words ("est-ce") in one gesture.

### Typing
- The letters of an elision prefix ("qu", "jusqu"; Italian "dell", "nell", "quest") are never autocorrected: they stay
  as typed at a space, and the prefix ("qu'", "dell'") stands first in the suggestion bar. Before, Italian "dell" +
  space became "delk".
- Typed "oe"/"ae" for the ligatures œ/æ count as a small accent error in every language.
- A picked hyphenated word is learned part by part.

### Settings
- French on the languages page; any number of the six keyboard languages can be enabled.

## 0.6.2 – 2026-10-16

### Languages
- New keyboard language **Italian**: the English QWERTY rows with à, è/é, ì, ò/ó and ù on long press, the apostrophe
  on the first symbol page, "spazio" on the space bar, "ITA" with the keyboard pictogram on the language key and picker,
  `.com .it` first among the domain endings in Italy, and a decimal comma on the number pad. The language key cycles
  DEU → ENG → ESP → ITA → POR.
- Italian dictionary built from free sources (subtitle frequencies, Italian and English Wiktionary, Tatoeba and Common
  Voice; CC BY-SA 4.0, attribution on the licenses page): accents are restored on the last letter ("perche" →
  "perché", "piu" → "più", "citta" → "città"), a wrong accent is fixed ("perchè" → "perché"), words that exist in both
  spellings stay as typed with the accented one offered ("e"/"è", "si"/"sì", "la"/"là"), clitic forms are words
  ("dimmelo", "farlo"), and a missing space is fixed ("perfavore" → "per favore").
- Elision without an apostrophe key: "lacqua" → "l'acqua", "cè" → "c'è", "dellanno" → "dell'anno"; typed with the
  apostrophe, only the rest is corrected ("c'e" → "c'è"); a typed "l" or "dell" offers "l'" / "dell'", which is inserted
  without a space, and the bar then offers whole words ("dell'acqua", "dell'anno"). "un po" → "un po'".
- Shape writing for Italian: accented letters through their base keys ("più"), elided words in one gesture
  ("l'acqua", "c'è"), and no space before a word swiped after "l'".

### Typing
- All languages with an elided prefix (Portuguese "d'", Italian): a word learned after the prefix keeps it as context,
  so learned words can appear after a bare "d'".

### Settings
- Italian on the languages page.

## 0.6.1 – 2026-10-16

### Languages
- New keyboard language **Portuguese (Brazil)**: QWERTY with its own ç key, ã and õ first on the long press of a and o,
  "espaço" on the space bar, "POR"/"BR" on the language key and picker, `.com .br .com.br .gov.br` among the domain
  endings in Brazil, and a decimal comma on the number pad. The language key cycles DEU → ENG → ESP → POR.
- Brazilian Portuguese dictionary built from free sources (Brazilian subtitle frequencies including their hyphenated
  forms, Portuguese and English Wiktionary, Tatoeba and Common Voice filtered for Brazilian usage; CC BY-SA 4.0,
  attribution on the licenses page): missing accents, tildes and cedillas are corrected ("voce" → "você",
  "nao" → "não", "informacao" → "informação", "comecar" → "começar"); words that exist in both spellings stay as typed
  with the accented one offered ("esta"/"está", "e"/"é"); spellings of the 1990 orthographic agreement are preferred
  while the former Brazilian ones ("idéia", "vôo") stay valid; European-only forms ("facto", "bebé", "autocarro") are
  not suggested; a missing space is fixed ("porfavor" → "por favor").
- Hyphenated forms are typed as their parts: "deixe-me", "fazê-lo" and "bem-vindo" are two words each, the hyphen
  never triggers an autocorrection, and the verb forms used before a pronoun ("fazê", "ajudá") are words.
- "d'água": the elided "d'" is kept as typed and only the rest is predicted and corrected ("d'agua" → "d'água").
- Words with the ordinal indicators º and ª ("1º", "nº") are never corrected or marked.
- Shape writing for Portuguese: "faço" is swiped over the ç key, accented letters through their base keys.

### Typing
- All languages: a word swiped directly after a letter and a hyphen gets no space before it, so hyphenated words can
  be swiped part by part ("deixe-" + "me", "E-" + "Mail"); after " - " the space stays.

### Settings
- Portuguese (Brazil) on the languages page.

### Fixes
- Switching to another keyboard while MetroKeys was shown could crash the keyboard process while it closed.

## 0.6.0 – 2026-10-16

### Languages
- New keyboard language **Spanish (Spain)**: QWERTY with its own ñ key, accented letters on long press, ¿ and ¡ on the
  long press of the period key, "espacio" on the space bar, "ESP"/"ES" on the language key and picker, `.com .es`
  first among the domain endings in Spain, and a decimal comma on the number pad.
- Any number of keyboard languages can be enabled. The language key cycles through all of them in label order
  (DEU → ENG → ESP). Multilingual typing runs up to three languages at once; with more, the current language and the
  two used last (remembered across restarts).
- Spanish dictionary built from free sources (subtitle frequencies, Spanish and English Wiktionary, Tatoeba, Common
  Voice; CC BY-SA 4.0, attribution on the licenses page): accents and ñ are part of the spelling, accent-less
  spellings are corrected for free ("cancion" → "canción", "manana" → "mañana", "damelo" → "dámelo"), words that
  exist in both spellings stay as typed with the accented one offered first ("esta"/"está", "si"/"sí"),
  clitic forms are words ("dime", "hazlo", "decírselo"), and a missing space is fixed ("porfavor" → "por favor").
- Shape writing for Spanish: "año" is swiped over the ñ key.

### Typing
- Spanish punctuation: the keyboard capitalises after ¿ and ¡, never puts a space after them, and puts a space before
  them after a swiped word; ? and ! still trigger autocorrection, ¿ and ¡ do not.
- Every keyboard language has its own prediction and shape-writing parameters; English shape writing now uses its own
  values (accuracy on the acceptance test unchanged: 94.6 % instead of 94.8 % over 20 runs). A swiped double letter
  must be the same letter twice.
- Letters without a key of their own (í, ñ, ç …) count on their base letter's key for corrections on every layout.

### Settings
- The languages page lists every supported language; the main page summary names all enabled ones.

## 0.5.2 – 2026-10-15

### Licence
- MetroKeys is now free and open-source software under the GNU General Public License v3.0 only (GPL-3.0-only). The
  GPL text is in `LICENSE` and is the first entry of the app's licenses page. The third-party components keep their
  own licences (Selawik under the SIL OFL 1.1, the dictionary data under CC BY-SA 4.0, Unicode data, Apache 2.0
  libraries).

### Prediction
- Missing-space splits only for English: German and other compounding languages no longer get a candidate with an
  inserted space ("imhaus", "dasist", "nikolaus"). In English one split competes like every other correction, costed
  from the static word lists (the word pair if known, else the mean part cost plus a surcharge), with one-letter first
  parts only "a" and "I": "ofthe" → "of the", "inthe" → "in the", "thankyou" → "thank you"; "restau" no longer gets
  "res tau".

### Settings
- Regrouped pages with a comparable number of settings each: **suggestions** (suggestions, autocorrect, space after a
  suggestion, underline unknown words, contact names, password managers), **typing** (shape writing, capitals,
  double-space period, back to letters after an emoji, clipboard history) and **layout** (keyboard height, below the
  keyboard, app bar buttons). The theme page no longer holds the keyboard height. Stored settings are unchanged.
- New **app bar buttons** page: a live preview of the expanded app bar in the keyboard's colours; tap a button, choose
  tap or hold, and pick its action from a grid of pictograms. Choices apply at once; "open app…" and the microphone
  permission work as before. New pictogram for "open app".
- **data+info** lists learned words first and has an **about** section with the version, the copyright and licence,
  a trademark note and a link to the source repository (opened in the browser; the app still has no internet
  permission).
- Main page summaries follow the new pages, e.g. "100%, app bar" for layout.

## 0.5.1 – 2026-10-15

### Fixed
- Pasting with the paste button right after a swiped word left the word underlined as composing text. The caret was
  then out of step with the keyboard, and the next backspaces deleted the text before the swiped word instead of the
  end of the pasted text. The keyboard now finishes the swiped word before every insertion it does not type itself
  (04 §12.2). Pasted text still gets no space before it.
- The swiped word is also finished when a caret move is reported while it is still composing, before the
  application-bar field action, and when the editor does not report the composing region in its selection updates.

## 0.5.0 – 2026-10-15

First version prepared for demos and a later release. It summarises everything built so far.

### Keyboard
- Input method with a keyboard view drawn by the app: German (QWERTZ) and US English (QWERTY) letter pages, two
  symbol pages, number, phone and PIN pads, long-press alternates with sliding selection, key popups, shift and
  caps lock, key repeat and word delete.
- Suggestion bar with scrolling, word completions, next-word prediction, autocorrection with flash and undo,
  stored suggestion lists when returning to a word, spelling marks and an "add to dictionary" item.
- Prediction engine (pure Kotlin/JVM): frequency word lists and word-pair tables, touch model, case handling,
  missing-space split, learning with acceptance rules, forgetting words, contact names as an optional word source,
  multilingual typing with per-language weighting.
- Shape writing with a fading trace, alternatives in the bar and automatic spacing.
- Text rules: automatic capitalisation, double-space period, smart and trailing spaces, emoji spacing, grapheme-aware
  backspace.
- Emoji page (Unicode emoji 18.0) with categories, recently used emoji and text smileys; emoji the system font
  cannot draw are hidden.
- Application bar below the keys with three configurable buttons and an expanded "…" menu; a compact mode for
  gesture navigation.
- Clipboard history for text and images with pinning and expiry, a recently-copied chip in the suggestion bar,
  and image insertion into apps that accept it.
- Voice input through the system speech recognizer, inline password-manager suggestions (Android 11+), hardware
  keyboard support, landscape and adjustable keyboard height, TalkBack support.

### Settings app
- Settings in the same flat style (Jetpack Compose, own controls, turnstile page transitions, tilt on press,
  predictive back): setup steps and a try-it field, languages, typing, clipboard and autofill, sounds and
  vibration, theme (background, 20 accent colours, app colour, high contrast, keyboard height, font), app bar,
  data and info.
- Learned-words list with forgetting, clearing all learned data, licences page generated at build time.
- Import of your own key sounds (WAV) and fonts; English and German user interface with a per-app language.

### Privacy
- No internet permission, no analytics. Learned words, clipboard history and imported files stay in no-backup
  app storage.

### Build and release
- Version 0.5.0 (version code 5), defined once in the version catalog.
- Release builds are minified with R8 and resource-shrunk, and signed when a local `keystore.properties` is
  present (unsigned otherwise).
- GitHub Actions workflow: unit tests, lint and the debug APK on pushes and pull requests to `main`; tags `v*` also
  build the release APK and attach it to a draft release.
- README with screenshots, a demo script ([docs/DEMO.md](docs/DEMO.md)) and this changelog.

### Known limitations
- No sound assets are shipped (Android's click sounds are used unless you import your own).
- A few optional behaviours of the specification are not implemented yet; see "Not done yet" in
  [docs/APP.md](docs/APP.md).
