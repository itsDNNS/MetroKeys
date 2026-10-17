package app.metrokeys.ime

import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.swipe.ShapeResult
import app.metrokeys.engine.touch.KeyboardGeometry
import app.metrokeys.settings.KeyboardSettings
import app.metrokeys.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Italian text rules through [InputLogic] with the bundled dictionary (04 §11.2): accents and the elision split as
 * autocorrections, the apostrophe as a word character, a picked elision prefix without a trailing space (04 §4.3)
 * and no space before a word swiped after an elision prefix (06 §5.1).
 */
class ItalianInputLogicTest {
    private lateinit var editor: FakeEditor
    private lateinit var sched: FakeScheduler
    private lateinit var logic: InputLogic
    private val corrected = ArrayList<String>()

    companion object {
        val engine: PredictionEngine by lazy {
            PredictionEngine.withBundledDictionaries(listOf("it-IT")).also {
                it.setGeometry(KeyboardGeometry.fromLayoutJson(File("../spec/data/layouts-it.json").readText(), "text.letters"))
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
        logic.settings = KeyboardSettings.DEFAULT.copy(languages = listOf(Prefs.ITALIAN))
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
    fun accentsAndMissingApostrophes() {
        type("perche piu citta ")
        assertEquals("perché più città |", editor.toString())
        type("lacqua ")
        assertEquals("perché più città l'acqua |", editor.toString())
        type("cè ")
        assertEquals("perché più città l'acqua c'è |", editor.toString())
        assertEquals(listOf("perché", "più", "città", "l'acqua", "c'è"), corrected)
    }

    @Test
    fun typedApostropheAndOneLetterRemainder() {
        // the apostrophe is a word character and never a trigger; from 0.7.1 "c'e" + space stays (05 §17.10 example 3,
        // open point 21: the candidate "è" has one accent unit for one letter and is filtered, as in the reference)
        type("c'e ")
        assertEquals("c'e |", editor.toString())
        type("l'acqua e ")
        assertEquals("c'e l'acqua e |", editor.toString())
        assertEquals(emptyList<String>(), corrected)
        type("un po ")
        assertEquals("c'e l'acqua e un po' |", editor.toString())
    }

    @Test
    fun pickedPrefixGetsNoSpaceAndOffersElidedWords() {
        type("dell")
        val i = logic.strip.items.indexOfFirst { it.text == "dell'" }
        assertTrue(logic.strip.items.toString(), i >= 0)
        assertTrue(logic.strip.items[i].elisionPrefix)
        logic.pick(logic.strip, i)
        sched.run(400)
        assertEquals("dell'|", editor.toString())
        // the strip now shows whole elided words; picking one replaces the prefix and adds the trailing space
        val items = logic.strip.items.map { it.text }
        assertTrue(items.toString(), items.isNotEmpty() && items.all { it.startsWith("dell'") })
        val j = items.indexOf("dell'anno")
        assertTrue(items.toString(), j >= 0)
        logic.pick(logic.strip, j)
        sched.run(400)
        assertEquals("dell'anno |", editor.toString())
    }

    @Test
    fun swipeAfterAnElisionPrefix() {
        type("l")
        val i = logic.strip.items.indexOfFirst { it.text == "l'" }
        logic.pick(logic.strip, i)
        sched.run(400)
        swipe("acqua", "acque")
        assertEquals("l'acqua|", editor.toString())
        // an apostrophe that is no elision prefix keeps the usual space ("po'" + swipe)
        setUp()
        type("un po'")
        swipe("acqua")
        assertEquals("un po' acqua|", editor.toString())
    }
}
