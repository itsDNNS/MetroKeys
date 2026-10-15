# MetroKeys demo script

A script and checklist for showing MetroKeys 0.5.2 to an audience in 5–10 minutes. It assumes an English-speaking
audience; for a German audience use the German keyboard and app language instead.

## 1. Setup on the demo phone (the day before)

1. **Install** the signed release APK (`app/build/outputs/apk/release/app-release.apk`, or the release asset), e.g.
   `adb install -r app-release.apk`, or copy it to the phone and open it with the file manager (allow installing
   from that source when asked). If an older debug build is installed, uninstall it first: debug and release builds
   are signed with different keys and cannot replace each other.
2. **Enable the keyboard**: open MetroKeys, tap **turn on** in the first item, switch MetroKeys on in the Android
   keyboard settings and confirm Android's notice for third-party keyboards; back in MetroKeys tap **choose** and
   select MetroKeys. The first item then reads "MetroKeys is active – tap to switch".
3. **Languages** (settings → languages):
   - App language: **English**.
   - Keyboard languages: **English (United States)** on. Leave German off for a single-language demo, or turn it on
     together with "Type in several languages" if you want to show multilingual typing (the language key appears).
4. **Suggestions** (settings → suggestions): keep the defaults (suggestions, autocorrect, space after a suggestion,
   underline unknown words, password manager suggestions: on; contact names: off).
5. **Typing** (settings → typing): keep the defaults (shape writing, capitals, double-space period, back to letters
   after an emoji and clipboard history: all on).
6. **Layout** (settings → layout): keyboard height 100 %, below the keyboard "app bar"; under **app bar buttons** the
   default buttons (tap: text field action, emoji, voice input; hold: nothing).
7. **Theme**: start with background "same as system", accent **cobalt (default)** and system dark mode on, so the
   first impression is the classic dark keyboard.
8. **Sounds**: key sounds on, but keep the phone in silent/vibrate mode during the talk if sounds would disturb.
9. **Prepare content**:
   - A messaging or notes app with an empty conversation/note to type into (any app works; the **Try it** field on
     the MetroKeys main page is a fallback).
   - A web page or gallery picture you can copy as an image (in Chrome: long-press the picture → **Copy image**).
10. Do a full rehearsal of section 2 once, then reset the test data (section 3).

## 2. Demo flow (about 8 minutes)

| Time | Step | What to do | What to point out |
|---|---|---|---|
| 0:00 | Intro | Show the launcher icon, open the notes/messaging app, tap the text field. | Inspired by the Windows Phone 8.1 keyboard, independent clean-room implementation, no internet permission. |
| 0:45 | Typing and predictions | Type `thanks for the ` slowly. | Automatic capital, next-word predictions in the bar after each space, the scrolling suggestion bar. |
| 1:30 | Autocorrection | Type `invitaton ` (missing "i"). | The bold suggestion is applied on space and flashes in the bar. Put the cursor right after the corrected word: the bar offers what you typed first; tapping it undoes the correction (and teaches the keyboard the word). |
| 2:15 | Double-space period | Type `see you soon` and two spaces. | Period and capital for the next sentence. |
| 2:45 | Shape writing | Swipe `tonight`, then `great` (one stroke per word). | The trace follows the finger; the word and alternatives appear in the bar; spaces are added automatically. |
| 3:30 | Long press | Hold `e`, slide to `é`. | Alternates picker with sliding selection. |
| 3:50 | Emoji | Tap the smiley key, browse two categories, insert an emoji. | Categories, recently used, text smileys; the keyboard returns to letters after an emoji. |
| 4:30 | App bar | Tap **…** below the keys. | Labelled buttons and the menu: switch keyboard, clipboard history, keyboard settings. |
| 5:00 | Clipboard: text | Select a sentence in the app, copy it, go back to an empty line. | The chip in the suggestion bar shows the copied text for two minutes; tap it to paste. |
| 5:40 | Clipboard: image | Switch to the browser, long-press the prepared picture → **Copy image**, return to the field. | The chip shows a thumbnail. Open **…** → **clipboard history**: text and image entries, hold to pin, × to remove. Paste the image (in apps that accept images). |
| 6:30 | Settings tour | **…** → **keyboard settings**. Open **layout → app bar buttons**, tap the right button in the preview, switch to **hold** and pick **undo**; then **theme**: change the accent to **red**, then switch the phone to light mode (quick settings). | Flat settings pages with turnstile transitions and tilt on press; the app bar is chosen in a live preview; the keyboard follows the new accent and the light theme immediately (show it in the **Try it** field). |
| 7:40 | Learned words | In the **Try it** field type a made-up word such as `zorblax` and a space; hide the keyboard; open **data+info → learned words**. | Every learned word is listed and can be forgotten with a tap; nothing leaves the phone. |
| 8:30 | Wrap-up | Back to the main page, then **data+info** to the about section. | Privacy (no network, no analytics, data stays on device), clean-room process, open source under the GNU GPL v3 with open data sources (data+info → licenses, about). |

Optional extras if time allows: multilingual typing with German and English enabled; voice input via the
microphone button; landscape orientation; keyboard height slider (settings → layout); TalkBack; "ofthe " in English
becomes "of the".

### Fallbacks

- **Keyboard does not appear**: Android's keyboard switcher (navigation bar keyboard icon or notification), or
  the first item on the MetroKeys main page. After installing an update of the APK, or after "force stop" of
  MetroKeys in the Android app settings, Android switches back to its default keyboard: tap **choose** again.
- **First suggestions are slow** right after the phone starts: the dictionaries load once; type a word in the
  **Try it** field before the demo.
- **No image paste in the target app**: many fields accept only text; show the image in the clipboard history and
  the chip instead, or use a messaging app that accepts images.
- **Swipe produces a different word**: pick the right one from the suggestion bar and mention that the
  alternatives are always offered.

## 3. Pre-demo checklist

- [ ] Battery above 80 %, charger at hand; screen timeout at 5 minutes or more.
- [ ] Do not disturb on; notifications from other apps silenced; no personal notifications visible.
- [ ] Phone in silent/vibrate mode (or key sounds turned off) unless the room should hear the clicks.
- [ ] Screen mirroring/recording tested (cable or casting), font size and display size at default.
- [ ] MetroKeys is the active keyboard; app language English; keyboard language English (United States).
- [ ] Theme back to "same as system", accent cobalt, system dark mode on; the right button's hold back to "nothing"
      (if you changed them while rehearsing).
- [ ] **Clear test data**: settings → data+info → **clear learned words** (also clears the emoji history); in the
      clipboard history tap **clear** (unpin pinned entries first if you want them gone); delete rehearsal notes.
- [ ] The picture to copy is open in the browser; the target note/conversation is empty.
- [ ] Airplane mode is fine: MetroKeys works fully offline (voice input may need the network, depending on the
      phone's speech service).
- [ ] One quick test: type a word, swipe a word, open the emoji page.
