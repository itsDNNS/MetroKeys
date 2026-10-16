package app.metrokeys.ime

import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.swipe.ShapeResult
import app.metrokeys.engine.text.TextRules
import app.metrokeys.engine.touch.KeyboardGeometry
import app.metrokeys.settings.KeyboardSettings
import app.metrokeys.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Portuguese (Brazil) text rules through [InputLogic] with the bundled dictionary: accents and cedilla (05 §17.9), the
 * hyphen as a word boundary that is no trigger and the elided "d'" (04 §11.1), and the 06 §5.1 spacing after
 * "letter + hyphen" (all languages).
 */
class PortugueseInputLogicTest {
    private lateinit var editor: FakeEditor
    private lateinit var sched: FakeScheduler
    private lateinit var logic: InputLogic
    private val corrected = ArrayList<String>()

    companion object {
        val engine: PredictionEngine by lazy {
            PredictionEngine.withBundledDictionaries(listOf("pt-BR")).also {
                it.setGeometry(KeyboardGeometry.fromLayoutJson(File("../spec/data/layouts-pt.json").readText(), "text.letters"))
            }
        }
    }

    @Before
    fun setUp() {
        editor = FakeEditor()
        sched = FakeScheduler()
        corrected.clear()
        logic = InputLogic(CachingEditor(editor), sched, object : InputLogic.Listener {
            override fun onStrip(content: StripContent) {}

            override fun onAutoCorrected(correction: String) {
                corrected.add(correction)
            }

            override fun onTextChanged() {}

            override fun onReleaseShift() {}
        })
        editor.onUpdate = { a, b, c, d -> sched.main(0) { logic.onSelectionUpdate(a, b, c, d) } }
        engine.deleteLearnedData()
        logic.engine = engine
        logic.settings = KeyboardSettings.DEFAULT.copy(languages = listOf(Prefs.PORTUGUESE))
        logic.startInput(FieldState.from(InputLogicTest.TEXT_MULTILINE, 0), 0, 0)
    }

    private fun type(s: String, gap: Long = 800) {
        for (ch in s) {
            sched.run(gap)
            logic.onText(ch.toString())
            sched.run()
        }
        sched.run(400)
    }

    private fun swipe(vararg words: String) {
        logic.applySwipe(ShapeResult(emptyList(), true), words.toList())
        sched.run()
    }

    @Test
    fun accentsTildeAndCedilla() {
        type("Você não sabe ")
        assertEquals("Você não sabe |", editor.toString())
        assertTrue(corrected.isEmpty())
        type("informacao ")
        assertEquals("Você não sabe informação |", editor.toString())
        type("acao ")
        assertEquals("Você não sabe informação ação |", editor.toString())
        assertEquals(listOf("informação", "ação"), corrected)
    }

    @Test
    fun theHyphenIsNoTrigger() {
        // the word before "-" is never autocorrected and the strip is emptied (04 §11.1 item 1)
        type("faze-")
        assertEquals("faze-|", editor.toString())
        assertTrue(logic.strip.items.isEmpty())
        type("lo ")
        assertEquals("faze-lo |", editor.toString())
        assertTrue(corrected.isEmpty())
        assertFalse(TextRules.isAutocorrectTrigger('-'))
        setUp()
        type("Deixe-me ver ")
        assertEquals("Deixe-me ver |", editor.toString())
        assertTrue(corrected.isEmpty())
    }

    @Test
    fun elidedPrefix() {
        type("um copo d'agua ")
        assertEquals("um copo d'água |", editor.toString())
        assertEquals(listOf("d'água"), corrected)
    }

    @Test
    fun ordinalIndicatorsAreNeverCorrected() {
        logic.insertClip("nº")
        type(" ")
        assertEquals("nº |", editor.toString())
        assertTrue(corrected.isEmpty())
    }

    @Test
    fun swipedWordsAfterLetterAndHyphen() {
        // 06 §5.1 item 1 (0.6, all languages): no space directly after "<letter>-"
        swipe("deixe", "deixa")
        logic.onText("-")
        sched.run(400)
        assertEquals("deixe-|", editor.toString())
        swipe("me", "mas")
        assertEquals("deixe-me|", editor.toString())
        // a hyphen after a space (a dash) keeps the space before the next swiped word
        logic.onText(" ")
        sched.run(400)
        logic.onText("-")
        sched.run(400)
        swipe("ver")
        assertEquals("deixe-me - ver|", editor.toString())
    }

    @Test
    fun spaceBeforeSwipeRule() {
        assertFalse(TextRules.spaceBeforeSwipe(""))
        assertFalse(TextRules.spaceBeforeSwipe("deixe-"))
        assertFalse(TextRules.spaceBeforeSwipe("E-"))
        assertTrue(TextRules.spaceBeforeSwipe(" -"))
        assertTrue(TextRules.spaceBeforeSwipe("-"))
        assertTrue(TextRules.spaceBeforeSwipe("1-"))
        assertFalse(TextRules.spaceBeforeSwipe("¿"))
        assertFalse(TextRules.spaceBeforeSwipe("a "))
        assertTrue(TextRules.spaceBeforeSwipe("a"))
        assertTrue(TextRules.spaceBeforeSwipe("a,"))
    }
}
