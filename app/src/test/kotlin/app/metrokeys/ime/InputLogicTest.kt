package app.metrokeys.ime

import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.touch.KeyboardGeometry
import app.metrokeys.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The text rules of spec 04 through [InputLogic] on a fake editor with the bundled German dictionary. */
class InputLogicTest {
    private lateinit var editor: FakeEditor
    private lateinit var sched: FakeScheduler
    private lateinit var logic: InputLogic
    private val strips = ArrayList<StripContent>()
    private val corrected = ArrayList<String>()

    companion object {
        val engine: PredictionEngine by lazy {
            PredictionEngine.withBundledDictionaries(listOf("de-DE")).also {
                it.setGeometry(KeyboardGeometry.fromLayoutJson(File("../spec/data/layouts-de.json").readText(), "text.letters"))
            }
        }

        // TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_CAP_SENTENCES | TYPE_TEXT_FLAG_MULTI_LINE
        const val TEXT_MULTILINE = 0x1 or 0x4000 or 0x20000
    }

    @Before
    fun setUp() {
        editor = FakeEditor()
        sched = FakeScheduler()
        logic = InputLogic(CachingEditor(editor), sched, object : InputLogic.Listener {
            override fun onStrip(content: StripContent) {
                strips.add(content)
            }

            override fun onAutoCorrected(correction: String) {
                corrected.add(correction)
            }

            override fun onTextChanged() {}

            override fun onReleaseShift() {}
        })
        editor.onUpdate = { a, b, c, d -> sched.main(0) { logic.onSelectionUpdate(a, b, c, d) } }
        engine.deleteLearnedData()
        logic.engine = engine
        logic.settings = KeyboardSettings.DEFAULT
        logic.startInput(FieldState.from(TEXT_MULTILINE, 0), 0, 0)
    }

    private fun type(s: String, gap: Long = 800) {
        for (ch in s) {
            sched.run(gap)
            logic.onText(ch.toString())
            sched.run()
        }
        sched.run(400)
    }

    private fun backspace() {
        logic.backspace()
        sched.run(400)
    }

    private val strip get() = logic.strip

    @Test
    fun autocorrectionAndUndoWithBackspace() {
        // the sentence start is typed with the automatic shift
        type("Hsllo ")
        assertEquals("Hallo |", editor.toString())
        assertEquals(listOf("Hallo"), corrected)
        backspace()
        assertEquals("Hallo|", editor.toString())
        assertEquals(StripKind.RESUMED, strip.kind)
        assertEquals("Hsllo", strip.items.first().text)
        logic.pick(strip, 0)
        sched.run(400)
        assertEquals("Hsllo |", editor.toString())
        // rejected by picking the typed word, which accepts it (05 §12.5): typing it again later keeps it
        type("Hsllo ")
        assertEquals("Hsllo Hsllo |", editor.toString())
        assertTrue(engine.active.user.isAccepted("Hsllo"))
    }

    @Test
    fun undoByEditingNeedsOneUndoAlsoForAnUnknownWord() {
        // 05 §10.4, 04 §3.5 (0.6.8, as in the reference): one rejection is enough for every word
        type("Hsllo ")
        assertEquals("Hallo |", editor.toString())
        backspace()
        // deleting a character of the resumed correction rejects it (not accepted)
        backspace()
        assertEquals("Hall|", editor.toString())
        assertEquals(1, engine.active.user.undoCount("Hsllo"))
        assertTrue(!engine.active.user.isAccepted("Hsllo"))
        type(" Hsllo ")
        assertEquals("Hall Hsllo |", editor.toString())
    }

    @Test
    fun aHeldBackspaceRejectsWhenItDeletesIntoTheCorrectedWord() {
        // 05 §17 test list, 04 §6.2: the first repeat deletes the space and resumes "Hallo", the next deletes into it
        type("Hsllo ")
        assertEquals("Hallo |", editor.toString())
        logic.backspace()
        sched.run(83)
        assertEquals("Hallo|", editor.toString())
        assertEquals(StripKind.RESUMED, strip.kind)
        logic.backspace()
        sched.run(83)
        logic.backspace()
        sched.run(400)
        assertEquals("Hal|", editor.toString())
        assertEquals(1, engine.active.user.undoCount("Hsllo"))
        // "Hsllo" is never corrected again (a new input, so "Hal" is not autocorrected by the next space)
        logic.startInput(FieldState.from(TEXT_MULTILINE, 0), 3, 3)
        type(" Hsllo ")
        assertEquals("Hal Hsllo |", editor.toString())
    }

    @Test
    fun undoByTappingTheTypedWordNeedsOneUndo() {
        type("Hsllo ")
        backspace()
        assertEquals("Hsllo", strip.items.first().text)
        logic.pick(strip, 0)
        sched.run(400)
        assertEquals("Hsllo |", editor.toString())
        assertEquals(1, engine.active.user.undoCount("Hsllo"))
        type("Hsllo ")
        assertEquals("Hsllo Hsllo |", editor.toString())
    }

    @Test
    fun doubleSpacePeriod() {
        type("Hallo")
        logic.onText(" ")
        sched.run(100)
        logic.onText(" ")
        sched.run()
        assertEquals("Hallo. |", editor.toString())
    }

    @Test
    fun noDoubleSpacePeriodAfterAComma() {
        type("Hallo,")
        logic.onText(" ")
        sched.run(100)
        logic.onText(" ")
        sched.run()
        assertEquals("Hallo,  |", editor.toString())
    }

    @Test
    fun noDoubleSpacePeriodAfterTheWindow() {
        type("Hallo ")
        sched.run(500)
        logic.onText(" ")
        sched.run()
        assertEquals("Hallo  |", editor.toString())
    }

    @Test
    fun pickAddsATrailingSpaceAndSmartSpaceRemovesIt() {
        type("Hal")
        assertEquals(StripKind.TYPING, strip.kind)
        val i = strip.items.indexOfFirst { it.text == "Hallo" }
        assertTrue("Hallo offered: ${strip.items}", i >= 0)
        logic.pick(strip, i)
        sched.run(400)
        assertEquals("Hallo |", editor.toString())
        assertEquals(StripKind.PREDICTION, strip.kind)
        type(".")
        assertEquals("Hallo.|", editor.toString())
    }

    @Test
    fun smartSpaceKeepsTheSpaceBeforeAHyphen() {
        type("Hal")
        logic.pick(strip, strip.items.indexOfFirst { it.text == "Hallo" })
        sched.run(400)
        type("-")
        assertEquals("Hallo -|", editor.toString())
    }

    @Test
    fun emojiRules() {
        type("Hallo")
        logic.insertEmoji("😃", fromEmojiPage = true)
        sched.run()
        assertEquals("Hallo😃 |", editor.toString())
        logic.insertEmoji("😃", fromEmojiPage = true)
        sched.run()
        assertEquals("Hallo😃😃 |", editor.toString())
        type(".")
        assertEquals("Hallo😃😃.|", editor.toString())
    }

    @Test
    fun backspaceDeletesWholeGraphemeClusters() {
        logic.insertEmoji("👨‍👩‍👧", fromEmojiPage = true)
        sched.run()
        backspace() // the trailing space
        backspace() // the whole family
        assertEquals("|", editor.toString())
    }

    @Test
    fun wordDelete() {
        type("ist gut, ")
        logic.wordDelete()
        sched.run()
        assertEquals("ist |", editor.toString())
    }

    @Test
    fun caretMoveToTheEndOfAWordDoesNotAutocorrectIt() {
        type("hsus")
        editor.userMove(0)
        sched.run()
        editor.userMove(4)
        sched.run(400)
        type(" ")
        assertEquals("hsus |", editor.toString())
    }

    @Test
    fun caretInsideAWordNeverAutocorrects() {
        type("welt")
        editor.userMove(3)
        sched.run(400)
        // 0.6.4: candidates for the whole word around the caret (04 §10 item 5), but a trigger never corrects (§3.6)
        assertEquals(StripKind.CARET_WORD, strip.kind)
        assertEquals("welt", strip.word)
        type(" ")
        assertEquals("wel |t", editor.toString())
        assertTrue(corrected.isEmpty())
    }

    // ---------------------------------------------------------------- caret inside a word (04 §10 item 5, 0.6.4)

    /** Text that was in the field before; then the user puts the caret at [caret]. */
    private fun preset(text: String, caret: Int) {
        editor.text.append(text)
        editor.userMove(caret)
        sched.run(400)
    }

    @Test
    fun pickForTheWordAroundTheCaretReplacesTheWholeWord() {
        preset("Cursos", 3)
        assertEquals("Cur|sos", editor.toString())
        assertEquals(StripKind.CARET_WORD, strip.kind)
        assertEquals("Cursos", strip.word)
        assertEquals(0, strip.wordStart)
        val texts = strip.items.filter { !it.addWord }.map { it.text }
        // 05 §17.7a: "Cursor" is the first suggestion for "Cursos"
        assertEquals("$texts", "Cursor", texts.firstOrNull())
        assertTrue(strip.items.none { it.bold })
        logic.pick(strip, strip.items.indexOfFirst { it.text == "Cursor" })
        sched.run(400)
        // the whole word is replaced, the caret stands after it, no space is added
        assertEquals("Cursor|", editor.toString())
        assertTrue(corrected.isEmpty())
    }

    @Test
    fun pickInsideAWordInRunningText() {
        preset("Der Cursos ist da", 7)
        assertEquals("Der Cur|sos ist da", editor.toString())
        assertEquals(StripKind.CARET_WORD, strip.kind)
        assertEquals(4, strip.wordStart)
        logic.pick(strip, strip.items.indexOfFirst { it.text == "Cursor" })
        sched.run(400)
        assertEquals("Der Cursor| ist da", editor.toString())
        // the caret back inside the new word: candidates for it
        editor.userMove(6)
        sched.run(400)
        assertEquals(StripKind.CARET_WORD, strip.kind)
        assertEquals("Cursor", strip.word)
    }

    @Test
    fun typingInsideAWordEndsTheCaretWordState() {
        preset("Cursos", 3)
        assertEquals(StripKind.CARET_WORD, strip.kind)
        type("x")
        assertEquals("Curx|sos", editor.toString())
        assertTrue(strip.isEmpty)
        logic.refresh()
        sched.run(400)
        assertTrue(strip.isEmpty)
    }

    @Test
    fun backspaceInsideAWordEndsTheCaretWordState() {
        preset("Cursos", 3)
        assertEquals(StripKind.CARET_WORD, strip.kind)
        backspace()
        assertEquals("Cu|sos", editor.toString())
        assertTrue(strip.isEmpty)
        logic.refresh()
        sched.run(400)
        assertTrue(strip.isEmpty)
    }

    @Test
    fun inputRestartShowsTheCandidatesOfTheWordAroundTheCaret() {
        // 04 §10 "Input start, restart and re-shown keyboard" (0.6.4): a popup of the editor took the focus and gave it
        // back; the input restarts in the same field with the caret still inside "cursos"
        preset("Zu cursos ich komme. ", 5)
        assertEquals(StripKind.CARET_WORD, strip.kind)
        logic.startInput(FieldState.from(TEXT_MULTILINE, 0), 5, 5)
        sched.run(400)
        assertEquals(StripKind.CARET_WORD, strip.kind)
        assertEquals("cursos", strip.word)
        assertEquals(3, strip.wordStart)
        logic.pick(strip, strip.items.indexOfFirst { it.text == "Cursor" })
        sched.run(400)
        assertEquals("Zu Cursor| ich komme. ", editor.toString())
    }

    @Test
    fun reshownKeyboardShowsTheStripOfTheCurrentSelection() {
        preset("Zu cursos ich komme. ", 5)
        logic.finishInput()
        logic.reshown()
        sched.run(400)
        assertEquals(StripKind.CARET_WORD, strip.kind)
        assertEquals("cursos", strip.word)
        // a selected word: candidates for it
        editor.userMove(3, 9)
        sched.run(400)
        logic.finishInput()
        logic.reshown()
        sched.run(400)
        assertEquals(StripKind.SELECTION, strip.kind)
        assertEquals("cursos", strip.word)
        // caret after a separator: next-word predictions; at a word start: empty
        editor.userMove(21)
        sched.run(400)
        logic.startInput(FieldState.from(TEXT_MULTILINE, 0), 21, 21)
        sched.run(400)
        assertEquals(StripKind.PREDICTION, strip.kind)
        editor.userMove(3)
        sched.run(400)
        logic.startInput(FieldState.from(TEXT_MULTILINE, 0), 3, 3)
        sched.run(400)
        assertTrue(strip.isEmpty)
    }

    @Test
    fun caretMovesFromInsideAWord() {
        preset("Cursos ist", 3)
        assertEquals(StripKind.CARET_WORD, strip.kind)
        // caret at a word start: still an empty strip
        editor.userMove(0)
        sched.run(400)
        assertEquals("|Cursos ist", editor.toString())
        assertTrue(strip.isEmpty)
        editor.userMove(7)
        sched.run(400)
        assertTrue(strip.isEmpty)
        // caret at the end of a word: the word is resumed
        editor.userMove(6)
        sched.run(400)
        assertEquals("Cursos| ist", editor.toString())
        assertEquals(StripKind.RESUMED, strip.kind)
        assertEquals("Cursos", strip.word)
        // and back inside: candidates for the whole word again
        editor.userMove(2)
        sched.run(400)
        assertEquals(StripKind.CARET_WORD, strip.kind)
        assertEquals("Cursos", strip.word)
    }

    @Test
    fun enterInAMultiLineFieldAutocorrects() {
        type("Hsus")
        logic.enter()
        sched.run(400)
        assertEquals("Haus\n|", editor.toString())
        backspace()
        assertEquals(StripKind.RESUMED, strip.kind)
        assertEquals("Hsus", strip.items.first().text)
    }

    @Test
    fun enterWithAnActionDoesNotAutocorrect() {
        // TYPE_CLASS_TEXT, IME_ACTION_NEXT
        logic.startInput(FieldState.from(0x1, 5), 0, 0)
        type("Okon")
        logic.enter()
        sched.run()
        assertEquals("Okon|", editor.toString())
        assertEquals(listOf(5), editor.actions)
    }

    @Test
    fun swipedWordIsComposingAndBackspaceDeletesIt() {
        type("Das ")
        val r = app.metrokeys.engine.swipe.ShapeResult(emptyList(), true)
        logic.applySwipe(r, listOf("ist", "isst"))
        sched.run()
        assertEquals("Das ist|", editor.toString())
        assertEquals(StripKind.SWIPE, strip.kind)
        assertTrue(strip.items[0].bold)
        // a letter after a swiped word starts a new word
        logic.onText("g")
        sched.run(400)
        assertEquals("Das ist g|", editor.toString())
        backspace()
        logic.applySwipe(r, listOf("gut"))
        sched.run()
        assertEquals("Das ist gut|", editor.toString())
        backspace()
        assertEquals("Das ist |", editor.toString())
    }

    @Test
    fun swipedWordAfterAHyphenGetsNoSpace() {
        // 06 §5.1 item 1: "E-" + swipe "Mail" → "E-Mail"; since 0.6.8 also " -" + swipe → " -gut"
        type("Die E-")
        val r = app.metrokeys.engine.swipe.ShapeResult(emptyList(), true)
        logic.applySwipe(r, listOf("Mail"))
        sched.run()
        assertEquals("Die E-Mail|", editor.toString())
        type(" -")
        logic.applySwipe(r, listOf("gut"))
        sched.run()
        assertEquals("Die E-Mail -gut|", editor.toString())
    }

    private fun swipe(vararg words: String) {
        logic.applySwipe(app.metrokeys.engine.swipe.ShapeResult(emptyList(), true), words.toList())
        sched.run()
    }

    @Test
    fun noSpaceBeforeASwipedWordAfterOpeningCharacters() {
        // 06 §5.1 item 1, 04 §12.2 item 5 (0.6.8, as in the reference): none after ¿ ¡ ( " - # @ /
        type("(")
        swipe("Haus")
        assertEquals("(Haus|", editor.toString())
        type(") \"")
        swipe("gut")
        assertEquals("(Haus) \"gut|", editor.toString())
        type("\" #")
        swipe("tag")
        type(" @")
        swipe("anna")
        type(" /")
        swipe("weg")
        assertEquals("(Haus) \"gut\" #tag @anna /weg|", editor.toString())
        // every other character keeps the usual space: « [ . , a digit
        type(" «")
        swipe("wort")
        type(" [")
        swipe("wort")
        type("].")
        swipe("Wort")
        type(" 3")
        swipe("mal")
        assertEquals("(Haus) \"gut\" #tag @anna /weg « wort [ wort]. Wort 3 mal|", editor.toString())
    }

    @Test
    fun punctuationAfterASwipedWordIsAppendedDirectly() {
        // 06 §5.6, 04 §5.3: . , ? ! and - / ( @ # are appended without a space; a letter starts a new word
        for (p in listOf("?", ",", "!", ".", "-", "/", "(", "@", "#", ":", ";")) {
            swipe("gut")
            type(p)
            type(" ")
        }
        assertEquals("gut? gut, gut! gut. gut- gut/ gut( gut@ gut# gut: gut; |", editor.toString())
    }

    @Test
    fun aSwipedWordDirectlyAfterASentenceDelimiterIsCapitalised() {
        // 06 §5.2, 04 §1.5: "Hallo." + swipe "morgen" → "Hallo. Morgen" (the space comes from §5.1)
        val off = app.metrokeys.engine.swipe.ShiftState.OFF
        val one = app.metrokeys.engine.swipe.ShiftState.ONE_SHOT
        val caps = app.metrokeys.engine.swipe.ShiftState.CAPS_LOCK
        for (b in listOf("Hallo.", "Hallo!", "Hallo?", "Hallo:")) assertEquals(one, InputLogic.swipeShift(off, true, b))
        assertEquals(off, InputLogic.swipeShift(off, false, "Hallo."))
        assertEquals(off, InputLogic.swipeShift(off, true, "Hallo,"))
        assertEquals(caps, InputLogic.swipeShift(caps, true, "Hallo."))
        type("Hallo.")
        fun cand(t: String) = app.metrokeys.engine.swipe.ShapeCandidate(t, 0.0, 0.0, 0.0, 0, "de-DE")
        val r = app.metrokeys.engine.swipe.ShapeResult(listOf(cand("morgen"), cand("Morgen"), cand("morgens")), true)
        val texts = r.texts(InputLogic.swipeShift(off, true, editor.toString().removeSuffix("|")))
        assertEquals(listOf("Morgen", "Morgens"), texts)
        logic.applySwipe(r, texts)
        sched.run()
        assertEquals("Hallo. Morgen|", editor.toString())
    }

    @Test
    fun theLiveSwipeWordCannotBePicked() {
        type("Das ")
        val before = editor.toString()
        logic.pick(StripContent(StripKind.SWIPE_LIVE, listOf(StripItem("ist"))), 0)
        sched.run(400)
        assertEquals(before, editor.toString())
    }

    @Test
    fun aPickedUnrecognisedSwipeAlternativeFollowsTheLeadingSpaceRule() {
        // 06 §5.5: tapping an alternative inserts it with the leading space rule of §5.1
        type("(")
        logic.applySwipe(app.metrokeys.engine.swipe.ShapeResult(emptyList(), false), listOf("Haus", "Hans"))
        sched.run()
        assertEquals(StripKind.SWIPE_UNRECOGNISED, strip.kind)
        assertEquals("(|", editor.toString())
        logic.pick(strip, 0)
        sched.run(400)
        assertEquals("(Haus |", editor.toString())
    }

    @Test
    fun doubleSpacePeriodInASearchField() {
        // 04 §2, §8.2; 05 §15 (0.6.8): also in search fields (text field with the search action)
        logic.startInput(FieldState.from(0x1, 3), 0, 0)
        type("hallo")
        logic.onText(" ")
        sched.run(100)
        logic.onText(" ")
        sched.run()
        assertEquals("hallo. |", editor.toString())
    }

    @Test
    fun swipeAlternativeReplacesTheSwipedWord() {
        val r = app.metrokeys.engine.swipe.ShapeResult(emptyList(), true)
        logic.applySwipe(r, listOf("Hallo", "Halle"))
        sched.run()
        logic.pick(strip, 1)
        sched.run(400)
        assertEquals("Halle |", editor.toString())
    }

    @Test
    fun spellingMarksForUnknownWordsAwayFromTheCaret() {
        type("Das ist xqzzy ")
        sched.run(300)
        assertTrue("marked: ${editor.marks}", editor.marks.keys.any { editor.text.substring(it.first, it.last + 1) == "xqzzy" })
    }

    @Test
    fun selectingTheWordStillBeingTypedKeepsItsCompletions() {
        // 03 §6.4 item 5: "Kopiert" typed, then selected (select all) → the typing list, not the correction search
        type("Kopiert")
        val typing = strip.items.map { it.text }
        editor.userMove(0, 7)
        sched.run(400)
        assertEquals(StripKind.SELECTION, strip.kind)
        val texts = strip.items.map { it.text }
        assertTrue("$texts", texts.any { it.startsWith("Kopiert") && it != "Kopiert" })
        assertTrue("$texts", "Kapiert" !in texts || typing.contains("Kapiert"))
        assertTrue(strip.items.none { it.bold || it.addWord })
        // a pick replaces the selection
        val pick = strip.items.indexOfFirst { it.text.startsWith("Kopierte") }
        logic.pick(strip, pick)
        sched.run(400)
        assertTrue(editor.toString(), editor.toString().startsWith("Kopierte"))

        // an older word (after a caret move) gets the correction search
        setUp()
        type("Kopiert")
        editor.userMove(3)
        sched.run(400)
        editor.userMove(0, 7)
        sched.run(400)
        assertEquals(StripKind.SELECTION, strip.kind)
        assertTrue(strip.items.map { it.text }.none { it.startsWith("Kopierte") })
    }

    // ---------------------------------------------------------------- own edits after a swipe (04 §12.2 items 2, 4)

    /** Types "Das ", swipes "heute" (composing), runs [action] and checks that the composing text was finished. */
    private fun afterSwipe(action: () -> Unit) {
        type("Das ")
        logic.applySwipe(app.metrokeys.engine.swipe.ShapeResult(emptyList(), true), listOf("heute"))
        sched.run()
        assertEquals("Das heute|", editor.toString())
        assertEquals("heute", editor.composing)
        action()
        sched.run(400)
        assertEquals("composing after the edit: ${editor.toString()}", null, editor.composing)
    }

    @Test
    fun pasteAfterSwipeFinishesTheSwipedWord() {
        editor.clipboard = "Kopiert"
        afterSwipe { logic.paste { editor.performPaste() } }
        // no space is added by a paste (03 §6.6 item 6, 04 §4.4)
        assertEquals("Das heuteKopiert|", editor.toString())
        backspace()
        backspace()
        backspace()
        assertEquals("Das heuteKopi|", editor.toString())
    }

    @Test
    fun pasteAfterSwipeAlsoWhenTheEditorDoesNotReportTheComposingRegion() {
        editor.reportComposing = false
        editor.clipboard = "Kopiert"
        afterSwipe { logic.paste { editor.performPaste() } }
        assertEquals("Das heuteKopiert|", editor.toString())
        backspace()
        assertEquals("Das heuteKopier|", editor.toString())
    }

    @Test
    fun clipboardTextAfterSwipe() {
        // chip text and clipboard history entries
        afterSwipe { logic.insertClip("Kopiert") }
        assertEquals("Das heuteKopiert|", editor.toString())
        backspace()
        assertEquals("Das heuteKopier|", editor.toString())
        // also when the editor leaves the composing region out of its selection updates
        setUp()
        editor.reportComposing = false
        afterSwipe { logic.insertClip("Kopiert") }
        assertEquals("Das heuteKopiert|", editor.toString())
    }

    @Test
    fun autocorrectionUndoAfterSwipe() {
        type("Hsllo ")
        assertEquals("Hallo |", editor.toString())
        logic.applySwipe(app.metrokeys.engine.swipe.ShapeResult(emptyList(), true), listOf("heute"))
        sched.run()
        // the first backspace deletes the swiped word, the next ones go back to the corrected word
        backspace()
        assertEquals("Hallo |", editor.toString())
        assertEquals(null, editor.composing)
        backspace()
        assertEquals("Hallo|", editor.toString())
        assertEquals(StripKind.RESUMED, strip.kind)
        logic.pick(strip, strip.items.indexOfFirst { it.text == "Hsllo" })
        sched.run(400)
        assertEquals("Hsllo |", editor.toString())
    }

    @Test
    fun clipboardImageAfterSwipe() {
        var composingAtCommit: String? = "not called"
        afterSwipe {
            logic.insertContent {
                composingAtCommit = editor.composing
                true
            }
        }
        assertEquals(null, composingAtCommit)
        backspace()
        assertEquals("Das heut|", editor.toString())
    }

    @Test
    fun emojiAndTextSmileyAfterSwipe() {
        afterSwipe { logic.insertEmoji("😀", fromEmojiPage = true) }
        assertEquals("Das heute 😀 |", editor.toString())
        backspace()
        backspace()
        assertEquals("Das heute |", editor.toString())
        setUp()
        afterSwipe { logic.insertEmoji(":-)", fromEmojiPage = true) }
        assertEquals("Das heute :-) |", editor.toString())
        backspace()
        backspace()
        assertEquals("Das heute :-|", editor.toString())
    }

    @Test
    fun domainKeyAndAlternateAfterSwipe() {
        afterSwipe { logic.onText(".de") }
        assertEquals("Das heute.de|", editor.toString())
        backspace()
        assertEquals("Das heute.d|", editor.toString())
        setUp()
        afterSwipe { logic.onText("ä") }
        assertEquals("Das heute ä|", editor.toString())
        backspace()
        assertEquals("Das heute |", editor.toString())
    }

    @Test
    fun dictationAfterSwipe() {
        afterSwipe { logic.insertDictation("morgen", capitalize = false) }
        assertEquals("Das heute morgen|", editor.toString())
        backspace()
        assertEquals("Das heute morge|", editor.toString())
    }

    @Test
    fun cursorActionsAndCaretMovesAfterSwipe() {
        // app-bar arrows, undo, field actions and hardware keys first end the mark
        afterSwipe { logic.endSwipeMark() }
        assertEquals("Das heute|", editor.toString())
        setUp()
        afterSwipe { editor.userMove(2) }
        assertEquals("Da|s heute", editor.toString())
        backspace()
        assertEquals("D|s heute", editor.toString())
    }

    @Test
    fun stripPickAfterSwipeReplacesItAndBackspaceDeletesTheEnd() {
        afterSwipe { logic.pick(strip, 0) }
        assertEquals("Das heute |", editor.toString())
        backspace()
        backspace()
        assertEquals("Das heut|", editor.toString())
    }
}
