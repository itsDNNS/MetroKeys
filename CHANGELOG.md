# Changelog

All notable changes to MetroKeys are listed here. Versions follow [semantic versioning](https://semver.org/);
before 1.0 a minor version may change behaviour.

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
