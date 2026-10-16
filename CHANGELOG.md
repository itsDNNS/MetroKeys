# Changelog

All notable changes to MetroKeys are listed here. Versions follow [semantic versioning](https://semver.org/);
before 1.0 a minor version may change behaviour.

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
