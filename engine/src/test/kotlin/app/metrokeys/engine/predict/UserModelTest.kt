package app.metrokeys.engine.predict

import app.metrokeys.engine.TestDictionaries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** User learning of spec 05 §12. */
class UserModelTest {
    private fun de() = TestDictionaries.german()

    @Test
    fun zwuckelWorkedExample() {
        // 05 §12.2 with the acceptance of §12.5 (an unknown word committed twice is known only when accepted)
        val e = de()
        e.user.learn("Zwuckel", emptyList(), accepted = true)
        e.user.learn("Zwuckel", emptyList())
        repeat(498) { e.user.learn("Füllwort", emptyList()) }
        assertEquals(500, e.user.totalCount)
        assertEquals(2, e.user.count("Zwuckel"))
        assertEquals(2, e.user.recentCount("Zwuckel"))
        assertEquals(88, e.lm("Zwuckel"))
    }

    @Test
    fun hausWorkedExample() {
        val e = de()
        assertEquals(75, e.lm("Haus"))
        repeat(5) { e.user.learn("Haus", emptyList()) }
        repeat(1000) { e.user.learn("Füllwort", emptyList()) }
        repeat(5) { e.user.learn("Haus", emptyList()) }
        repeat(990) { e.user.learn("Füllwort", emptyList()) }
        assertEquals(2000, e.user.totalCount)
        assertEquals(10, e.user.count("Haus"))
        assertEquals(5, e.user.recentCount("Haus"))
        assertEquals(73, e.lm("Haus"))
    }

    @Test
    fun learningNeverMakesADictionaryWordWorse() {
        val e = de()
        val before = e.lm("dann")
        e.user.learn("dann", emptyList())
        assertTrue(e.lm("dann") <= before)
    }

    @Test
    fun unknownWordsNeedThreeUses() {
        val e = de()
        e.user.learn("Zwuckel", emptyList())
        e.user.learn("Zwuckel", emptyList())
        assertEquals(255, e.lm("Zwuckel"))
        assertFalse(e.validity("Zwuckel", CaseMode.FIRST_UPPER).valid)
        e.user.learn("Zwuckel", emptyList())
        assertEquals(Params.LEARN_MIN_UNKNOWN, e.user.count("Zwuckel"))
        assertTrue(e.validity("Zwuckel", CaseMode.FIRST_UPPER).valid)
        assertTrue(e.lm("Zwuckel") < 255)
    }

    @Test
    fun dictionaryWordsInAnotherCasingNeedTwoUses() {
        // "Laufen"/"laufen" style: a learned form whose key the dictionary knows
        val e = de()
        assertFalse(e.user.isKnownLearned("ZWX"))
        e.user.learn("Hausx", emptyList())
        e.user.learn("HAUS", emptyList())
        assertTrue(e.user.dictionaryKnows("HAUS"))
        assertFalse(e.user.dictionaryKnows("Hausx"))
        e.user.learn("Ichx", emptyList(), accepted = true)
        assertTrue(e.user.isAccepted("Ichx"))
        assertTrue(e.user.isKnownLearned("Ichx"))
        assertFalse(e.user.isKnownLearned("Hausx"))
    }

    @Test
    fun undoCountsAndAcceptance() {
        val e = de()
        assertFalse(e.validity("Zwuckel", CaseMode.FIRST_UPPER).valid)
        // one undo is enough for a word the dictionary does not know (05 §10.4, 0.6.8)
        e.user.reject("Zwuckel", "Zucker", learn = true)
        assertEquals(1, e.user.undoCount("Zwuckel"))
        assertTrue(e.user.isRejected("Zwuckel"))
        assertTrue(e.user.isKnownLearned("Zwuckel"))
        assertTrue(e.validity("Zwuckel", CaseMode.FIRST_UPPER).valid)
        assertEquals(1, e.user.count("Zwuckel"))
        e.user.reject("Zwuckel", "Zucker", learn = true)
        assertEquals(2, e.user.undoCount("Zwuckel"))
        assertTrue(e.user.isRejected("Zwuckel"))
        assertEquals(2, e.user.count("Zwuckel"))
        // picking the typed word accepts it after one undo
        e.user.reject("Zwockel", "Zucker", learn = true, accepted = true)
        assertTrue(e.user.isAccepted("Zwockel"))
        assertTrue(e.validity("Zwockel", CaseMode.FIRST_UPPER).valid)
    }

    @Test
    fun storedFormCaseFolding() {
        val u = de().user
        assertEquals("Haus", u.storedForm("Haus"))
        assertEquals("Haus", u.storedForm("HAUS"))
        assertEquals("und", u.storedForm("Und"))
        assertEquals("haus", u.storedForm("haus"))
        assertEquals("Zwuckel", u.storedForm("Zwuckel"))
        assertEquals("ZWX", u.storedForm("ZWX"))
        u.learn("zwuckel", emptyList())
        assertEquals("zwuckel", u.storedForm("Zwuckel"))
    }

    @Test
    fun splitWordsAndContexts() {
        val u = de().user
        u.learn("das ist", listOf("Ich", "glaube"))
        assertEquals(1, u.count("das"))
        assertEquals(1, u.count("ist"))
        assertEquals(mapOf("das" to 1), u.followers("glaube"))
        assertEquals(mapOf("das" to 1), u.followers("ich glaube"))
        assertEquals(mapOf("ist" to 1), u.followers("glaube das"))
        assertEquals(mapOf("ist" to 1), u.followers("das"))
        // trimmed, length and character limits
        u.learn("(x)", emptyList())
        u.learn("\"Hallo!\"", emptyList())
        u.learn("abc1", emptyList())
        assertEquals(0, u.count("x"))
        // "Hallo" is itself a dictionary form, so it is stored as is
        assertEquals(1, u.count("Hallo"))
        assertEquals(1, u.count("abc1"))
        u.learn("1234", emptyList())
        assertEquals(0, u.count("1234"))
    }

    @Test
    fun learnedNextWord() {
        val e = de()
        repeat(2) { e.user.learn("Zwuckel", listOf("mein", "kleiner")) }
        // an unknown follower is predicted only once it is known (05 §12.5)
        assertTrue(e.predictNext("Das ist mein kleiner ").none { it.text == "Zwuckel" })
        e.user.learn("Zwuckel", emptyList(), accepted = true)
        val next = e.predictNext("Das ist mein kleiner ")
        assertEquals("Zwuckel", next.first().text)
        assertEquals(15, next.first().cost)
        // backoff: only the shorter context is known
        val e2 = de()
        repeat(2) { e2.user.learn("Zwuckel", listOf("kleiner"), accepted = true) }
        repeat(2) { e2.user.learn("Hund", listOf("ein", "kleiner")) }
        val n2 = e2.predictNext("ein kleiner ").associate { it.text to it.cost }
        assertEquals(15, n2["Hund"])
        assertEquals(LmCost.cost(0.4 * 2 / 4), n2["Zwuckel"])
    }

    @Test
    fun addWord() {
        val e = de()
        assertTrue(e.user.addWord(" Zwuckel, "))
        assertEquals(3, e.user.count("Zwuckel"))
        assertTrue(e.user.isAccepted("Zwuckel"))
        assertTrue(e.validity("Zwuckel", CaseMode.FIRST_UPPER).valid)
        assertFalse(e.user.addWord("x"))
        assertFalse(e.user.addWord("123"))
    }

    @Test
    fun rejectRaisesTypedAndLowersCorrection() {
        val e = de()
        e.user.learn("Haus", emptyList())
        e.user.reject("Hsus", "Haus", learn = true)
        // unknown word: one use per undo, rejected after one undo (0.6.8)
        assertTrue(e.user.isRejected("Hsus"))
        assertEquals(1, e.user.count("Hsus"))
        assertEquals(0, e.user.count("Haus"))
        assertEquals(1, e.user.totalCount)
        // a word the dictionary knows in some casing: raised to two uses, rejected after one undo
        e.user.reject("haus", "Haus", learn = true)
        assertTrue(e.user.isRejected("haus"))
        assertEquals(2, e.user.count("haus"))
        val noLearn = de()
        noLearn.user.reject("Hsus", "Haus", learn = false)
        assertTrue(noLearn.user.isRejected("Hsus"))
        assertEquals(0, noLearn.user.count("Hsus"))
    }

    @Test
    fun undoTableLimitAndOrder() {
        val u = de().user
        for (i in 0 until Params.UNDO_MAX + 5) u.reject("wort$i", "x", learn = false)
        assertEquals(Params.UNDO_MAX, u.rejectedWords().size)
        assertFalse(u.isRejected("wort0"))
        u.reject("wort10", "x", learn = false)
        assertEquals("wort10", u.rejectedWords().last())
        // NFKC ("fix" is a dictionary word in English only; here it is unknown, one undo suffices)
        u.reject("ﬁx", "fix", learn = false)
        assertTrue(u.isRejected("fix"))
    }

    @Test
    fun eviction() {
        val u = UserModel(null)
        for (i in 0 until Params.USER_MAX_WORDS) u.learn("w$i" + "x", emptyList())
        u.learn("w0x", emptyList())
        u.learn("neuwort", emptyList())
        assertEquals(Params.USER_MAX_WORDS, u.wordCount)
        assertEquals(0, u.count("w1x"))
        assertEquals(2, u.count("w0x"))
        assertEquals(1, u.count("neuwort"))
        for (i in 0 until Params.USER_MAX_NEXT + 3) u.learn("f$i" + "x", listOf("kontext"))
        assertEquals(Params.USER_MAX_NEXT, u.followers("kontext")!!.size)
    }

    @Test
    fun persistenceRoundTrip() {
        val e = de()
        e.user.learn("Zwuckel", listOf("mein"), accepted = true)
        e.user.learn("Zwuckel", listOf("mein"))
        repeat(2) { e.user.reject("Hsus", "Haus", learn = true) }
        e.user.reject("Hsis", "Haus", learn = false)
        val bytes = e.user.serialize()
        val restored = UserModel.read(bytes, e.lexicon)
        assertEquals(2, restored.count("Zwuckel"))
        assertTrue(restored.isAccepted("Zwuckel"))
        assertEquals(2, restored.recentCount("Zwuckel"))
        assertEquals(mapOf("Zwuckel" to 2), restored.followers("mein"))
        assertTrue(restored.isRejected("Hsus"))
        assertEquals(2, restored.undoCount("Hsus"))
        assertEquals(1, restored.undoCount("Hsis"))
        assertTrue(restored.isRejected("Hsis"))
        assertEquals(e.user.totalCount, restored.totalCount)
        assertTrue(bytes.contentEquals(restored.serialize()))
    }

    /** Version 1 of the format (before acceptance and undo counts) is still read. */
    @Test
    fun readsVersionOneData() {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { o ->
            o.writeInt(0x4D4B554D)
            o.writeInt(1)
            o.writeLong(7)
            o.writeInt(2)
            o.writeUTF("Zwuckel"); o.writeInt(2); o.writeLong(5)
            o.writeUTF("Haus"); o.writeInt(4); o.writeLong(7)
            o.writeInt(1)
            o.writeUTF("mein"); o.writeInt(1); o.writeUTF("Zwuckel"); o.writeInt(2)
            o.writeInt(2)
            o.writeUTF("Zwuckel"); o.writeUTF("Haus")
            o.writeInt(1)
            o.writeUTF("Hsus")
        }
        val e = de()
        assertTrue(e.user.restore(bytes.toByteArray()))
        assertEquals(2, e.user.count("Zwuckel"))
        assertEquals(4, e.user.count("Haus"))
        assertEquals(6, e.user.totalCount)
        assertFalse(e.user.isAccepted("Zwuckel"))
        assertEquals(mapOf("Zwuckel" to 2), e.user.followers("mein"))
        assertEquals(1, e.user.recentCount("Haus"))
        // version-1 undo entries stay fully rejected
        assertTrue(e.user.isRejected("Hsus"))
        // an unknown word with two uses now needs a third (or acceptance)
        assertFalse(e.user.isKnownLearned("Zwuckel"))
        // saved again in the current version
        val again = UserModel.read(e.user.serialize(), e.lexicon)
        assertTrue(again.isRejected("Hsus"))
        assertEquals(2, again.count("Zwuckel"))
    }

    @Test
    fun forget() {
        val e = de()
        repeat(3) { e.user.learn("Zwuckel", listOf("mein")) }
        e.user.learn("zwuckel", emptyList())
        e.user.learn("Hund", listOf("Zwuckel"))
        repeat(2) { e.user.reject("Zwuckel", "Zucker", learn = false) }
        // every stored unknown form, also one used once (05 §12.5)
        assertEquals(listOf("Zwuckel" to 3, "zwuckel" to 1), e.user.unknownLearnedWords())
        assertTrue(e.user.forget("Zwuckel"))
        assertEquals(0, e.user.count("Zwuckel"))
        assertEquals(0, e.user.count("zwuckel"))
        assertEquals(1, e.user.count("Hund"))
        assertEquals(1L, e.user.totalCount)
        assertNull(e.user.followers("mein"))
        assertNull(e.user.followers("zwuckel"))
        assertEquals(0, e.user.undoCount("Zwuckel"))
        assertEquals(0, e.user.recentCount("Zwuckel"))
        assertFalse(e.user.forget("Zwuckel"))
        assertFalse(e.user.forget("Hund"))
        assertEquals(1, e.user.count("Hund"))
    }

    @Test
    fun unreadableDataGivesAnEmptyModel() {
        val good = de().user.also { it.learn("Zwuckel", emptyList()) }.serialize()
        for (bad in listOf(ByteArray(0), byteArrayOf(1, 2, 3), good.copyOf(good.size - 3), good.copyOf().also { it[7] = 9 })) {
            val m = UserModel.read(bad, null)
            assertEquals(0, m.wordCount)
            assertEquals(0L, m.totalCount)
        }
        assertEquals(0, UserModel.read(null, null).wordCount)
    }

    @Test
    fun clearRemovesEverything() {
        val u = de().user
        u.learn("Zwuckel", listOf("mein"))
        u.reject("Hsus", "Haus", learn = true)
        u.clear()
        assertEquals(0, u.wordCount)
        assertNull(u.followers("mein"))
        assertFalse(u.isRejected("Hsus"))
    }
}
