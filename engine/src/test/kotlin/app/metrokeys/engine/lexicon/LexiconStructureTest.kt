package app.metrokeys.engine.lexicon

import app.metrokeys.engine.TestDictionaries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The prefix tree built level by level from the front-coded keys (05b §6) and the streaming key lookup. */
class LexiconStructureTest {
    private fun checkTree(l: Lexicon) {
        var nodes = 1
        var keys = if (l.keyAt(0) >= 0) 1 else 0
        for (n in 0 until l.nodeCount) {
            val first = l.firstChild(n)
            val count = l.childCount(n)
            for (c in first until first + count) {
                // children contiguous, sorted, breadth first (after their parent)
                assertTrue(c > n)
                if (c > first) assertTrue(l.charOf(c - 1) < l.charOf(c))
                assertTrue(l.maxClassOf(c) <= l.maxClassOf(n))
                nodes++
                if (l.keyAt(c) >= 0) keys++
            }
        }
        assertEquals(l.nodeCount, nodes)
        assertEquals(l.keyCount, keys)
        var prev = ""
        for (k in 0 until l.keyCount) {
            val key = l.keyString(k)
            assertTrue(k == 0 || key > prev, "keys sorted at $k")
            assertEquals(k, l.keyIndex(key))
            prev = key
        }
    }

    @Test
    fun bundledTreesAreConsistent() {
        checkTree(TestDictionaries.de.lexicon)
        checkTree(TestDictionaries.en.lexicon)
        println("de-DE keys ${TestDictionaries.de.lexicon.keyCount}, nodes ${TestDictionaries.de.lexicon.nodeCount}")
        // 0.6.5: 857 274 with the dictionary-only forms of 05b §3.7 (236 223 in 0.6.4, 236 559 before step 6a)
        assertEquals(857274, TestDictionaries.de.lexicon.keyCount)
        assertEquals(1_781_000.0, TestDictionaries.de.lexicon.nodeCount.toDouble(), 5_000.0)
    }

    @Test
    fun smallTreeFromEntries() {
        val l = Lexicon.fromEntries(
            "de-DE", 16f, 1000, 100000,
            mapOf(
                "a" to listOf(WordForm("a", 10)),
                "ab" to listOf(WordForm("ab", 20)),
                "abc" to listOf(WordForm("ABC", 30)),
                "b" to listOf(WordForm("b", 5)),
                "ba" to listOf(WordForm("Ba", 40)),
            ),
        )
        checkTree(l)
        assertEquals(6, l.nodeCount)
        assertEquals(listOf("ABC", "ab", "a"), l.completions("a", 5).map { it.text })
        assertEquals(40, l.maxClassOf(l.nodeOf("b")))
    }

    @Test
    fun containedKeysStreamsTheWordList() {
        val words = Dictionary::class.java.getResourceAsStream("/app/metrokeys/engine/dict/de-DE.words.gz")!!
        val found = words.use { Lexicon.containedKeys(it, setOf("haus", "leben", "qzxv", "zzzzzz")) }
        assertEquals(setOf("haus", "leben"), found)
    }
}
