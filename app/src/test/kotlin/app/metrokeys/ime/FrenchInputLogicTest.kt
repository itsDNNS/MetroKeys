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
 * French text rules through [InputLogic] with the bundled dictionary (04 §11.3): accents, ligatures, the elision split
 * and hyphen compounds as autocorrections, the letters of a prefix left as typed with the prefix first in the strip,
 * a picked prefix without a trailing space (04 §4.3), no space after "letter + hyphen" for a swiped word (06 §5.1) and
 * the auto space before `!` removed as in every language (04 §11.3 item 4).
 */
class FrenchInputLogicTest {
    private lateinit var editor: FakeEditor
    private lateinit var sched: FakeScheduler
    private lateinit var logic: InputLogic
    private val corrected = ArrayList<String>()

    companion object {
        val engine: PredictionEngine by lazy {
            PredictionEngine.withBundledDictionaries(listOf("fr-FR")).also {
                it.setGeometry(KeyboardGeometry.fromLayoutJson(File("../spec/data/layouts-fr.json").readText(), "text.letters"))
            }
        }
    }

    @Before
    fun setUp() {
        editor = FakeEditor()
        sched = FakeScheduler()
        corrected.clear()
        logic = InputLogic(editor, sched, object : InputLogic.Listener {
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
        logic.settings = KeyboardSettings.DEFAULT.copy(languages = listOf(Prefs.FRENCH))
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
    fun accentsLigaturesAndMissingApostrophes() {
        type("tres deja coeur ")
        assertEquals("très déjà cœur |", editor.toString())
        type("jai cest ")
        assertEquals("très déjà cœur j'ai c'est |", editor.toString())
        type("jusqua ")
        assertEquals("très déjà cœur j'ai c'est jusqu'à |", editor.toString())
        assertEquals(listOf("très", "déjà", "cœur", "j'ai", "c'est", "jusqu'à"), corrected)
    }

    @Test
    fun bothSpellingsAndCapitalsStayAsTyped() {
        type("Il a ")
        type("Etat ")
        assertEquals("Il a Etat |", editor.toString())
        assertTrue(corrected.toString(), corrected.isEmpty())
    }

    @Test
    fun hyphenCompoundsAndTypedHyphens() {
        type("estce ")
        assertEquals("est-ce |", editor.toString())
        // typed with the hyphen: the hyphen is no trigger, the part after it is corrected on its own
        setUp()
        type("peut-etre ")
        assertEquals("peut-être |", editor.toString())
    }

    @Test
    fun lettersOfAPrefixStayAndThePrefixComesFirst() {
        type("qu")
        assertEquals("qu'", logic.strip.items.first().text)
        assertTrue(logic.strip.items.first().elisionPrefix)
        type(" ")
        assertEquals("qu |", editor.toString())
        assertTrue(corrected.isEmpty())
        setUp()
        type("qu")
        logic.pick(logic.strip, 0)
        sched.run(400)
        assertEquals("qu'|", editor.toString())
        val items = logic.strip.items.map { it.text }
        assertTrue(items.toString(), "qu'il" in items && items.all { it.startsWith("qu'") })
    }

    @Test
    fun swipeAfterAPickedPrefixAndAfterAHyphen() {
        type("c")
        val i = logic.strip.items.indexOfFirst { it.text == "c'" }
        assertTrue(logic.strip.items.toString(), i >= 0)
        logic.pick(logic.strip, i)
        sched.run(400)
        swipe("est", "et")
        assertEquals("c'est|", editor.toString())
        setUp()
        type("peut-")
        swipe("être")
        assertEquals("peut-être|", editor.toString())
    }

    @Test
    fun autoSpaceBeforeExclamationMarkIsRemoved() {
        type("Merc")
        val i = logic.strip.items.indexOfFirst { it.text == "Merci" }
        assertTrue(logic.strip.items.toString(), i >= 0)
        logic.pick(logic.strip, i)
        sched.run(400)
        assertEquals("Merci |", editor.toString())
        type("!")
        assertEquals("Merci!|", editor.toString())
    }
}
