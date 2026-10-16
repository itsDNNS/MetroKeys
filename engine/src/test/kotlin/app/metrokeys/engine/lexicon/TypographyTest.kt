package app.metrokeys.engine.lexicon

import app.metrokeys.engine.predict.LanguageParams
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 04 §11.2 item 4, §11.3 items 4 and 8 (0.6.8, as in the reference): the keyboard never inserts `’` or a (narrow)
 * no-break space by itself. Everything it inserts besides typed characters comes from the word lists (corrections,
 * completions, swiped words, elision prefixes), so no form of any bundled dictionary may contain them.
 */
class TypographyTest {
    @Test
    fun noBundledFormContainsATypographicApostropheOrANoBreakSpace() {
        for (p in LanguageParams.ALL) {
            val d = Dictionary.loadBundled(p.languageTag, withPairs = false)
            val bad = d.lexicon.words().filter { w -> w.text.any { it == '’' || it == ' ' || it == ' ' } }.take(5).toList()
            assertTrue(bad.isEmpty(), "${p.languageTag}: $bad")
            for (i in 0 until d.lexicon.prefixCount) {
                val t = d.lexicon.prefixText(i)
                assertTrue(t.none { it == '’' || it == ' ' || it == ' ' }, "${p.languageTag}: prefix $t")
            }
        }
    }
}
