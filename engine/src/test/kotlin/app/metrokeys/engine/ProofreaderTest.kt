package app.metrokeys.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spelling marks, spec 05 §16. */
class ProofreaderTest {
    private val engine = TestDictionaries.single(TestDictionaries.german())
    private val proof = Proofreader(engine)

    @Test
    fun marksUnknownWords() {
        val text = "Das ist ein Hsus und ein Qxzvbw mit abc1 hier"
        val r = proof.run(text, text.length, text.length, emptyList())
        assertEquals(listOf("Hsus", "Qxzvbw"), r.add.map { it.word })
        val hsus = r.add.first()
        assertEquals(text.indexOf("Hsus"), hsus.start)
        assertEquals("Haus", hsus.suggestions.first())
        assertTrue(hsus.suggestions.size <= 5)
    }

    @Test
    fun caretWordAndSelectionAndAutocorrected() {
        val text = "ein Hsus"
        assertTrue(proof.run(text, text.length, text.length, emptyList()).add.isEmpty())
        assertTrue(proof.run(text, 4, 4, emptyList()).add.isEmpty())
        assertEquals(1, proof.run(text, 0, 0, emptyList()).add.size)
        assertEquals(ProofreadResult.NOTHING, proof.run(text, 0, 3, emptyList()))
        val range = TextRange(4, 8)
        assertTrue(proof.run(text, 0, 0, emptyList(), listOf(range)).add.isEmpty())
    }

    @Test
    fun knownMarkedWordsAreUnmarked() {
        val text = "ein Haus und Hsus"
        val r = proof.run(text, 0, 0, listOf(TextRange(4, 8), TextRange(13, 17)))
        assertEquals(listOf(TextRange(4, 8)), r.remove)
        assertTrue(r.add.isEmpty())
    }
}
