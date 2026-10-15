package app.metrokeys.engine.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Words, contexts and sentence starts of spec 05 §2.2. */
class TextModelTest {
    @Test
    fun wordCharacters() {
        assertEquals("Mail", TextModel.currentWord("E-Mail"))
        assertEquals("don't", TextModel.currentWord("I don't"))
        assertEquals("don’t", TextModel.currentWord("I don’t"))
        assertEquals("abc1", TextModel.currentWord("x abc1"))
        assertEquals("", TextModel.currentWord("Hallo "))
        assertEquals("", TextModel.currentWord("Hallo😀"))
        assertEquals("", TextModel.currentWord("x©"))
        assertEquals("é", TextModel.currentWord("caf é"))
        assertFalse(TextModel.isWordChar('-'))
        assertFalse(TextModel.isWordChar('⌀'))
        assertTrue(TextModel.isWordChar('ـ'))
        assertTrue(TextModel.isWordChar('ß'))
    }

    @Test
    fun previousWordForPairs() {
        fun prev(s: String) = TextModel.previousWordForPairs(s, TextModel.currentWordStart(s))
        assertEquals(PrevContext.SentenceStart, prev("Haus"))
        assertEquals(PrevContext.SentenceStart, prev("Ende. Haus"))
        assertEquals(PrevContext.SentenceStart, prev("Ende!  Haus"))
        assertEquals(PrevContext.SentenceStart, prev("Ende\nHaus"))
        assertEquals(PrevContext.SentenceStart, prev("„Haus"))
        assertEquals(PrevContext.SentenceStart, prev("Ende. „(Haus"))
        assertEquals(PrevContext.None, prev("das „Haus"))
        assertEquals(PrevContext.Word("das"), prev("das Haus"))
        assertEquals(PrevContext.Word("das"), prev("das   Haus"))
        assertEquals(PrevContext.None, prev("das, Haus"))
        assertEquals(PrevContext.None, prev("das-Haus"))
        assertEquals(PrevContext.None, prev("abc1 Haus"))
        assertEquals(PrevContext.Word("Vielen"), TextModel.previousWordForPairs("Vielen ", 7))
        assertEquals("", PrevContext.SentenceStart.pairKey)
        assertEquals(null, PrevContext.None.pairKey)
    }

    @Test
    fun precedingWords() {
        val s = "Hallo. Ich habe, heute leider keine Zeit"
        val start = TextModel.currentWordStart(s)
        assertEquals(listOf("Ich", "habe", "heute", "leider", "keine"), TextModel.precedingWords(s, start, 8))
        assertEquals(listOf("leider", "keine"), TextModel.precedingWords(s, start, 2))
        assertEquals(emptyList(), TextModel.precedingWords("Ende. ", 6, 2))
    }

    @Test
    fun checkableAndLearnable() {
        assertTrue(TextModel.isCheckable("Haus"))
        assertTrue(TextModel.isCheckable("don't"))
        assertFalse(TextModel.isCheckable("a"))
        assertFalse(TextModel.isCheckable("abc1"))
        assertTrue(TextModel.isLearnable("abc1"))
        assertFalse(TextModel.isLearnable("1234"))
        assertEquals("Hallo", TextModel.trimNonWord("\"Hallo!\""))
    }
}
