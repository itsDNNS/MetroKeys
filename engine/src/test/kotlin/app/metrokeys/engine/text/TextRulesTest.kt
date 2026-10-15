package app.metrokeys.engine.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Text-editing rules of spec 04 that live in the engine. */
class TextRulesTest {
    @Test
    fun sentenceStart() {
        assertTrue(TextRules.isSentenceStart(""))
        assertTrue(TextRules.isSentenceStart("Hallo. "))
        assertTrue(TextRules.isSentenceStart("Wirklich?\" "))
        assertTrue(TextRules.isSentenceStart("z.B. "))
        assertTrue(TextRules.isSentenceStart("Ende.  "))
        assertTrue(TextRules.isSentenceStart("Zeile\n"))
        assertTrue(TextRules.isSentenceStart("¿"))
        assertTrue(TextRules.isSentenceStart("Achtung: "))
        assertFalse(TextRules.isSentenceStart("Hallo, "))
        assertFalse(TextRules.isSentenceStart("Hallo."))
        assertFalse(TextRules.isSentenceStart("Hallo "))
    }

    @Test
    fun autoShiftPreconditions() {
        fun shift(before: String, policy: FieldPolicy = FieldPolicy.TEXT, setting: Boolean = true, sentence: Boolean = true,
                  word: Boolean = false, editor: Boolean = false, letters: Boolean = true) =
            TextRules.autoShift(before, setting, letters, policy, sentence, word, editor)
        assertTrue(shift("Hallo. "))
        assertFalse(shift("Hallo. ", setting = false))
        assertFalse(shift("Hallo. ", letters = false))
        assertFalse(shift("Hallo. ", policy = FieldPolicy.SEARCH))
        assertFalse(shift("Hallo. ", sentence = false))
        assertTrue(shift("Anna ", sentence = false, word = true, editor = true))
        assertFalse(shift("Anna ", sentence = true, word = false, editor = true))
    }

    @Test
    fun doubleSpacePeriod() {
        fun period(before: String, origin: SpaceOrigin, ms: Long, policy: FieldPolicy = FieldPolicy.TEXT) =
            TextRules.shouldInsertPeriod(before, origin, ms, true, policy)
        assertTrue(period("Hallo ", SpaceOrigin.KEY, 300))
        assertFalse(period("Hallo ", SpaceOrigin.KEY, 400))
        assertTrue(period("Hallo ", SpaceOrigin.AUTOCORRECTION_OR_PICK, 900))
        assertFalse(period("Hallo ", SpaceOrigin.EMOJI, 10))
        assertFalse(period("Hallo, ", SpaceOrigin.KEY, 100))
        assertFalse(period("Hallo. ", SpaceOrigin.KEY, 100))
        assertFalse(period("Hallo  ", SpaceOrigin.KEY, 100))
        assertFalse(period("- ", SpaceOrigin.KEY, 100))
        assertFalse(period("Hallo ", SpaceOrigin.KEY, 100, FieldPolicy.URL))
        assertFalse(period("Hallo ", SpaceOrigin.KEY, 100, FieldPolicy.SEARCH))
        assertTrue(period("Hallo ", SpaceOrigin.KEY, 100, FieldPolicy.DEFAULT))
        assertFalse(TextRules.shouldInsertPeriod("Hallo ", SpaceOrigin.KEY, 100, false, FieldPolicy.TEXT))
    }

    @Test
    fun triggers() {
        for (c in " .,!?;:)\"؟،؛।॥") assertTrue(TextRules.isAutocorrectTrigger(c), "$c")
        for (c in "-/(@#'&+*1") assertFalse(TextRules.isAutocorrectTrigger(c), "$c")
        assertEquals("usw", TextRules.correctionText("usw.", '.'))
        assertEquals("usw.", TextRules.correctionText("usw.", ' '))
        assertTrue(TextRules.blocksAutocorrect('@'))
        assertFalse(TextRules.blocksAutocorrect(' '))
        assertFalse(TextRules.blocksAutocorrect(null))
    }

    @Test
    fun keyboardSideAutocorrectConditions() {
        fun may(
            typedSinceMove: Boolean = true, inside: Boolean = false, before: Char? = ' ', word: String = "hsus",
            rejected: Boolean = false, swiped: Boolean = false, clip: Boolean = false, trigger: Char = ' ',
            multiLine: Boolean = false, policy: FieldPolicy = FieldPolicy.TEXT, setting: Boolean = true,
        ) = TextRules.mayApplyAutocorrection(setting, policy, typedSinceMove, inside, before, word, rejected, false, swiped, clip, trigger, multiLine)
        assertTrue(may())
        assertFalse(may(setting = false))
        assertFalse(may(policy = FieldPolicy.SEARCH))
        assertFalse(may(typedSinceMove = false))
        assertFalse(may(inside = true))
        assertFalse(may(before = '/'))
        assertFalse(may(word = "abc1"))
        assertFalse(may(rejected = true))
        assertFalse(may(swiped = true))
        assertFalse(may(clip = true))
        assertFalse(may(trigger = '-'))
        assertFalse(may(trigger = '\n'))
        assertTrue(may(trigger = '\n', multiLine = true))
    }

    @Test
    fun trailingSpaceAndSmartSpace() {
        assertEquals(TrailingSpaceAction.INSERT_SPACE, TextRules.trailingSpace(""))
        assertEquals(TrailingSpaceAction.NOTHING, TextRules.trailingSpace(", ok"))
        assertEquals(TrailingSpaceAction.SKIP_EXISTING_SPACE, TextRules.trailingSpace(" "))
        assertEquals(TrailingSpaceAction.SKIP_EXISTING_SPACE, TextRules.trailingSpace("  x"))
        assertEquals(TrailingSpaceAction.SKIP_EXISTING_SPACE, TextRules.trailingSpace(" \n"))
        assertEquals(TrailingSpaceAction.NOTHING, TextRules.trailingSpace(" ok"))
        assertEquals(TrailingSpaceAction.INSERT_SPACE, TextRules.trailingSpace("x"))
        assertEquals(TrailingSpaceAction.INSERT_SPACE, TextRules.trailingSpace("-"))
        assertTrue(TextRules.smartSpaceDeletesBefore('.'))
        assertTrue(TextRules.smartSpaceDeletesBefore(','))
        assertFalse(TextRules.smartSpaceDeletesBefore('-'))
        assertFalse(TextRules.smartSpaceDeletesBefore('('))
        assertFalse(TextRules.smartSpaceDeletesBefore(' '))
    }

    @Test
    fun wordDeleteAndSelection() {
        assertEquals(5, TextRules.wordDeleteLength("ist gut, "))
        assertEquals(3, TextRules.wordDeleteLength("gut"))
        assertEquals(0, TextRules.wordDeleteLength("x￼"))
        assertTrue(TextRules.isSingleWordSelection("ein Haus hier", 4, 8))
        assertFalse(TextRules.isSingleWordSelection("ein Haus hier", 4, 7))
        assertFalse(TextRules.isSingleWordSelection("ein Haus hier", 3, 8))
        assertTrue(TextRules.caretInsideWord("t"))
        assertFalse(TextRules.caretInsideWord(" t"))
        assertEquals(350, TextRules.queryDelayMs(200))
        assertEquals(0, TextRules.queryDelayMs(800))
    }
}
