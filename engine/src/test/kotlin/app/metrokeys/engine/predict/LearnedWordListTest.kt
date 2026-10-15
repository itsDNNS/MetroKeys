package app.metrokeys.engine.predict

import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.lexicon.Dictionary
import kotlin.test.Test
import kotlin.test.assertEquals

/** The learned words list of 05 §12.5 (07 §9.13 item 5): every stored unknown word, no threshold. */
class LearnedWordListTest {
    @Test
    fun everyStoredUnknownWordWithItsCount() {
        val e = TestDictionaries.multilingual()
        e.learn("Zwuckel", listOf("mein"))                        // once: not known yet, but listed
        repeat(3) { e.learn("Blorpfisch", emptyList()) }
        e.learn("Haus", emptyList())                              // dictionary word: not listed
        e.language("en-US")!!.user.learn("Zwuckel", emptyList())  // stored in both languages: summed
        assertEquals(listOf("Blorpfisch" to 3, "Zwuckel" to 2), e.learnedWords())
    }

    @Test
    fun builtFromLearnedDataAndExactLookups() {
        // the settings' way: learned data without dictionaries, plus a streaming lookup of the stored keys
        val de = UserModel(null)
        val en = UserModel(null)
        de.learn("Zwuckel", emptyList())
        de.learn("Haus", emptyList())
        en.learn("hello", emptyList())
        en.learn("Zwuckel", emptyList())
        en.learn("qzxv", emptyList())
        val list = LearnedWordList.of(listOf(de, en)) { keys ->
            Dictionary.bundledContains("de-DE", keys) + Dictionary.bundledContains("en-US", keys)
        }
        assertEquals(listOf("qzxv" to 1, "Zwuckel" to 2), list)
    }
}
