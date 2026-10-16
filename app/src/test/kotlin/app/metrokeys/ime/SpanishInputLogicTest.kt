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

/** Spanish text rules (spec 04 §1.6 inverted marks, 06 §5.6) through [InputLogic] with the bundled Spanish dictionary. */
class SpanishInputLogicTest {
    private lateinit var editor: FakeEditor
    private lateinit var sched: FakeScheduler
    private lateinit var logic: InputLogic
    private val corrected = ArrayList<String>()

    companion object {
        val engine: PredictionEngine by lazy {
            PredictionEngine.withBundledDictionaries(listOf("es-ES")).also {
                it.setGeometry(KeyboardGeometry.fromLayoutJson(File("../spec/data/layouts-es.json").readText(), "text.letters"))
            }
        }
    }

    @Before
    fun setUp() {
        editor = FakeEditor()
        sched = FakeScheduler()
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
        logic.settings = KeyboardSettings.DEFAULT.copy(languages = listOf(Prefs.SPANISH))
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
    fun questionWithInvertedMarks() {
        type("¿Dónde está la estación?")
        assertEquals("¿Dónde está la estación?|", editor.toString())
        assertTrue(corrected.isEmpty())
    }

    @Test
    fun autocorrectionBeforeTheClosingMark() {
        // "?" is a trigger (04 §1.6 item 3); the accent-less word is corrected
        type("¿Hasta manana?")
        assertEquals("¿Hasta mañana?|", editor.toString())
        assertEquals(listOf("mañana"), corrected)
    }

    @Test
    fun openingMarksAreNoTriggers() {
        // no autocorrection of the word before them, no space inserted (04 §1.6 item 4)
        type("Hloa¿")
        assertEquals("Hloa¿|", editor.toString())
        assertTrue(corrected.isEmpty())
        assertFalse(TextRules.isAutocorrectTrigger('¿'))
        assertFalse(TextRules.isAutocorrectTrigger('¡'))
        assertTrue(TextRules.isAutocorrectTrigger('?'))
        assertTrue(TextRules.isAutocorrectTrigger('!'))
    }

    @Test
    fun anAutoSpaceStaysBeforeAnOpeningMark() {
        type("Hloa ")
        assertEquals("Hola |", editor.toString())
        type("¿")
        assertEquals("Hola ¿|", editor.toString())
        // the smart space before a closing mark is removed
        setUp()
        type("Ho")
        val i = logic.strip.items.indexOfFirst { it.text == "Hola" }
        assertTrue("Hola offered: ${logic.strip.items}", i >= 0)
        logic.pick(logic.strip, i)
        sched.run(400)
        assertEquals("Hola |", editor.toString())
        type("!")
        assertEquals("Hola!|", editor.toString())
    }

    @Test
    fun openingMarkAfterASwipedWordIsAppendedDirectly() {
        // 04 §1.6 item 4, 06 §5.6 (0.6.8, as in the reference): "Hola" swiped + "¿" → "Hola¿"
        swipe("Hola", "Hora")
        assertEquals("Hola|", editor.toString())
        logic.onText("¿")
        sched.run(400)
        assertEquals("Hola¿|", editor.toString())
        // a swiped word directly after the mark gets no space (06 §5.1: "Hola¿" + swipe "que" → "Hola¿Que" with the
        // auto-shift after ¿)
        assertTrue(TextRules.isSentenceStart("Hola¿"))
        swipe("Qué", "Que")
        assertEquals("Hola¿Qué|", editor.toString())
        // other punctuation after a swiped word is appended directly (06 §5.6)
        logic.onText("?")
        sched.run(400)
        assertEquals("Hola¿Qué?|", editor.toString())
        logic.onText(" ")
        sched.run(400)
        swipe("Hola")
        logic.onText("¡")
        sched.run(400)
        assertEquals("Hola¿Qué? Hola¡|", editor.toString())
    }

    @Test
    fun autoShiftAfterTheOpeningMarks() {
        assertTrue(TextRules.isSentenceStart("¿"))
        assertTrue(TextRules.isSentenceStart("Hola. ¡"))
        assertTrue(TextRules.isSentenceStart("Oye, ¿"))
        assertTrue(TextRules.isSentenceStart("¿¡"))
        assertTrue(TextRules.isSentenceStart("¡¡"))
        assertFalse(TextRules.isSentenceStart("¿ "))
        assertTrue(TextRules.isSentenceStart("¿Qué tal? "))
        assertFalse(TextRules.isSentenceStart("¿Vienes?, "))
    }

    @Test
    fun doubleSpacePeriod() {
        type("¿Qué tal?")
        logic.onText(" ")
        sched.run(100)
        logic.onText(" ")
        sched.run()
        assertEquals("¿Qué tal?  |", editor.toString())
        setUp()
        type("¡Hola")
        logic.onText(" ")
        sched.run(100)
        logic.onText(" ")
        sched.run()
        assertEquals("¡Hola. |", editor.toString())
    }

    @Test
    fun backspaceDeletesTheMarkAndTheStripStaysEmptyAfterIt() {
        type("Oye, ¿")
        assertTrue(logic.strip.items.isEmpty())
        logic.backspace()
        sched.run(400)
        assertEquals("Oye, |", editor.toString())
    }

    @Test
    fun enyeAndAccentlessWords() {
        type("mañana ")
        assertEquals("mañana |", editor.toString())
        assertTrue(corrected.isEmpty())
        type("cancion ")
        assertEquals("mañana canción |", editor.toString())
    }
}
