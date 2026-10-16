package app.metrokeys.engine

import app.metrokeys.engine.text.FieldInfo
import app.metrokeys.engine.text.FieldPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Conformance checklist of spec 05 §18. */
class ConformanceTest {
    private fun de() = TestDictionaries.single(TestDictionaries.german())
    private fun en() = TestDictionaries.single(TestDictionaries.english())
    private fun multi() = TestDictionaries.multilingual()

    private fun PredictionEngine.ac(before: String) = query(Query(before)).autoCorrection

    @Test
    fun dasIstDasHaus() = assertEquals("Haus", de().ac("Das ist das haus"))

    @Test
    fun hausLearnedThreeTimesIsStillCorrected() {
        val e = de()
        repeat(3) { e.learn("haus", listOf("ist", "das")) }
        assertEquals(3, e.active.user.count("haus"))
        assertEquals("Haus", e.ac("Das ist das haus"))
    }

    @Test
    fun nikolaus() = assertEquals("Nikolaus", de().ac("Das ist das Haus vom nikolaus"))

    @Test
    fun dnan() {
        assertEquals("Dann", de().ac("Dnan"))
        assertEquals("dann", de().ac("Und dnan"))
    }

    @Test
    fun vielenDankFur() {
        assertEquals("für", de().ac("Vielen Dank fur"))
        assertEquals("für", multi().ac("Vielen Dank fur"))
    }

    @Test
    fun singleLetterIInGerman() = assertNull(de().ac("i"))

    @Test
    fun english() {
        val e = en()
        assertEquals("I", e.ac("i"))
        assertEquals("receive", e.ac("I did not recieve"))
        assertEquals("don't", e.ac("I dont"))
        assertNull(e.ac("I have"))
    }

    @Test
    fun multilingualGreat() = assertEquals("great", multi().ac("I think this is a grwat"))

    @Test
    fun multilingualMeeting() {
        assertEquals("Meeting", multi().ac("Ich habe heute ein meeting"))
        assertNull(multi().ac("I have a meeting"))
    }

    @Test
    fun multilingualNone() {
        assertNull(multi().ac("Das war really"))
        assertNull(multi().ac("I have"))
    }

    @Test
    fun multilingualDnan() = assertEquals("Dann", multi().ac("Dnan"))

    @Test
    fun nextWord() {
        val d = de().query(Query("Vielen ")).suggestions
        assertEquals("Dank", d.first().text)
        assertTrue("Dank" in d.take(3).map { it.text })
        assertTrue("you" in en().query(Query("thank ")).suggestions.take(3).map { it.text })
    }

    @Test
    fun neverAfterBlockingCharacters() {
        val e = de()
        for (before in listOf("www.hsus", "a/hsus", "x@hsus", "C:\\hsus")) assertNull(e.ac(before), before)
        // the same word elsewhere is corrected
        assertEquals("Haus", e.ac("Hsus"))
    }

    @Test
    fun neverWithDigit() {
        assertNull(de().ac("abc1"))
        assertNull(en().ac("teh2"))
    }

    @Test
    fun rejectedCorrection() {
        // "Hsus" is unknown to the dictionary: one undo by editing the word is enough (05 §10.4, 0.6.8)
        val e = de()
        assertEquals("Haus", e.ac("Hsus"))
        e.learn("Haus", emptyList())
        e.rejectAutoCorrection("Hsus", "Haus")
        assertEquals(0, e.active.user.count("Haus"))
        val p = e.query(Query("Hsus"))
        assertTrue(p.typedIsValid)
        assertNull(p.autoCorrection)
        assertNull(e.ac("Das ist das Hsus"))
        assertTrue(e.isKnown("Hsus"))
    }

    @Test
    fun rejectedDictionaryWordNeedsOneUndo() {
        // "haus" is a case error of a dictionary word: one undo makes it valid and it is never corrected again
        val e = de()
        assertEquals("Haus", e.ac("Das ist das haus"))
        e.rejectAutoCorrection("haus", "Haus")
        assertNull(e.ac("Das ist das haus"))
        assertTrue(e.query(Query("Das ist das haus")).typedIsValid)
    }

    @Test
    fun undoByPickingTheTypedWordAccepts() {
        val e = de()
        e.rejectAutoCorrection("Hsus", "Haus", accepted = true)
        assertNull(e.ac("Hsus"))
        assertTrue(e.isKnown("Hsus"))
        // without learning nothing is accepted or learned, but the undo table still makes the word valid
        val off = de()
        off.settings = EngineSettings(learning = false)
        off.rejectAutoCorrection("Hsus", "Haus", accepted = true)
        assertFalse(off.active.user.isAccepted("Hsus"))
        assertEquals(0, off.active.user.count("Hsus"))
        assertNull(off.ac("Hsus"))
    }

    @Test
    fun unknownWordCommittedTwice() {
        // 05 §12.5: an unknown word typed twice is still corrected, typed three times it is known
        val e = de()
        assertEquals("Dann", e.ac("Dnan"))
        e.learn("Dnan", emptyList())
        e.learn("Dnan", emptyList())
        assertEquals("Dann", e.ac("Dnan"))
        assertFalse(e.isKnown("Dnan"))
        e.learn("Dnan", emptyList())
        assertNull(e.ac("Dnan"))
        assertTrue(e.isKnown("Dnan"))
        assertFalse(e.isKnown("Zwuckel"))
        repeat(2) { e.learn("Zwuckel", emptyList()) }
        assertFalse(e.isKnown("Zwuckel"))
        assertTrue(e.query(Query("Das ist Zwuck")).suggestions.none { it.text == "Zwuckel" })
        e.learn("Zwuckel", emptyList())
        assertTrue(e.isKnown("Zwuckel"))
        val p = e.query(Query("Das ist Zwuck"))
        assertTrue("Zwuckel" in p.suggestions.map { it.text })
        val proof = Proofreader(e).run("Ein Zwuckel hier", 0, 0, emptyList())
        assertTrue(proof.add.none { it.word == "Zwuckel" })
    }

    @Test
    fun acceptedUnknownWordIsKnownAtOnce() {
        val e = de()
        e.learn("Zwuckel", listOf("mein"), accepted = true)
        assertTrue(e.isKnown("Zwuckel"))
        assertTrue(e.query(Query("Das ist Zwuck")).suggestions.any { it.text == "Zwuckel" })
        // a dictionary word in another casing still needs two uses
        e.learn("haus", emptyList(), accepted = true)
        assertTrue(e.query(Query("Das ist das haus")).autoCorrection == "Haus")
    }

    @Test
    fun forgetLearnedWords() {
        val e = de()
        repeat(3) { e.learn("Zwuckel", listOf("mein", "kleiner")) }
        e.learn("Hund", listOf("Zwuckel"))
        assertTrue(e.isKnown("Zwuckel"))
        assertTrue(e.isForgettable("Zwuckel"))
        assertTrue(e.isForgettable("ZWUCKEL"))
        assertEquals(listOf("Zwuckel" to 3), e.learnedWords())
        assertTrue(e.forget("zwuckel"))
        assertFalse(e.isKnown("Zwuckel"))
        assertEquals(0, e.active.user.count("Zwuckel"))
        assertNull(e.active.user.followers("kleiner"))
        assertNull(e.active.user.followers("zwuckel"))
        assertEquals(0, e.active.user.recentCount("Zwuckel"))
        assertTrue(e.learnedWords().isEmpty())
        assertTrue(e.query(Query("Das ist mein kleiner ")).suggestions.none { it.text == "Zwuckel" })
        // dictionary words cannot be forgotten; their learned frequency stays
        e.learn("Haus", emptyList())
        assertFalse(e.isForgettable("Haus"))
        assertFalse(e.forget("Haus"))
        assertEquals(1, e.active.user.count("Haus"))
        assertTrue(e.learnedWords().isEmpty())
        // the undo table entry goes too
        e.rejectAutoCorrection("Hsus", "Haus")
        assertNull(e.ac("Hsus"))
        assertTrue(e.forget("Hsus"))
        assertEquals("Haus", e.ac("Hsus"))
    }

    @Test
    fun threeLetterWordsWithTwoNeighbourSubstitutions() {
        // 05 §8.1 extension: u/i and z/t are neighbour keys
        val e = de()
        assertEquals("ist", e.ac("Das usz"))
        assertEquals("Ist", e.ac("Usz"))
        assertTrue(e.query(Query("Das usz")).suggestions.any { it.text == "ist" })
        // p and q are not neighbours of i and s
        assertTrue(e.query(Query("Das pqt")).suggestions.none { it.text == "ist" })
        assertTrue("ist" !in e.active.twoNeighbourSubstitutions("pqt"))
        assertTrue("ist" in e.active.twoNeighbourSubstitutions("usz"))
    }

    @Test
    fun contactAnnaSchmidt() {
        val e = de()
        e.setContactNames(listOf("Anna Schmidt"))
        assertTrue(e.query(Query("Das ist Anna's")).typedIsValid)
        assertTrue(e.isKnown("Anna's"))
        val corrections = e.resumeCorrections("Herr Schmitd").map { it.text }
        assertTrue("Schmidt" in corrections, corrections.toString())
        val bar = e.query(Query("Herr Schmitd")).suggestions.map { it.text }
        assertTrue("Schmidt" in bar, bar.toString())
    }

    @Test
    fun contactHansMueller() {
        val e = de()
        e.setContactNames(listOf("Hans Müller"))
        // follower cost = surcharge + 10 + cost(λ(1) · 1 / 1) = surcharge + 10 + 37: 116 in a text field (behind
        // the static pairs of "Hans"), 47 in a name field
        val m = e.active.predictNext("Hallo Hans ")
        assertTrue(m.none { it.text == "Müller" && it.cost != 116 })
        val nameField = e.query(Query("Hans ", field = FieldInfo(FieldPolicy.DEFAULT, nameField = true))).suggestions
        assertEquals("Müller", nameField.first().text)
        assertEquals(-47, nameField.first().score)
    }
}
