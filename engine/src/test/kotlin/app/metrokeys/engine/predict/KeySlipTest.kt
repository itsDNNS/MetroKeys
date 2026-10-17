package app.metrokeys.engine.predict

import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.touch.KeyboardGeometry
import app.metrokeys.engine.touch.TouchModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The key-slip search of 05 §8.1: the best-first walk with per-node bounds gives exactly the lists that the rules define
 * (checked against a scan of every key), the slips of the rules and the examples of §8.1.
 */
class KeySlipTest {
    private val de = TestDictionaries.german()
    private val lex = de.lexicon
    private val slips = SlipTable(TouchModel(KeyboardGeometry.fallback("de-DE"), de.params), de.params)

    @Test
    fun slips() {
        // neighbour: floor(v · SLIP_NEAR / 1000) at the key centres (horizontal 386 · 34 / 1000 = 13)
        assertEquals(13, slips.slip('s', 'a'))
        // accent: SLIP_ACCENT 24, one direction only; ü lives on the u key, so it is no neighbour of u
        assertEquals(24, slips.slip('u', 'ü'))
        assertEquals(-1, slips.slip('ü', 'u'))
        assertEquals(24, slips.slip('e', 'é'))
        // far keys are not accepted
        assertEquals(-1, slips.slip('a', 'p'))
    }

    /** The lists of §8.1 by a scan over every key (no context: every lm is the static cost of the class). */
    private fun brute(low: String): Pair<List<Pair<String, Int>>, List<Pair<String, Int>>> {
        data class E(val text: String, val cls: Int, val total: Int)
        val same = HashMap<String, E>()
        val comp = HashMap<String, E>()
        fun put(m: HashMap<String, E>, e: E) {
            val o = m[e.text]
            if (o == null || e.total < o.total) m[e.text] = e
        }
        for (k in 0 until lex.keyCount) {
            val m = KeySlipSearch.matchKey(lex.keyString(k), low, slips, de.params.slipFiller) ?: continue
            for (f in 0 until lex.formCountOf(k)) {
                val text = lex.formText(k, f)
                val cls = lex.formClass(k, f)
                if (m.sameSlip == 0) put(same, E(text, cls, 1))
                else if (m.sameSlip > 0 && lex.isOffered(k, f)) put(same, E(text, cls, de.scale.classCost(cls) + m.sameSlip))
                if (m.compSlip >= 0 && lex.isOffered(k, f)) put(comp, E(text, cls, de.scale.classCost(cls) + m.compSlip))
            }
        }
        val order = compareBy<E>({ it.total }, { -it.cls }, { it.text })
        return same.values.sortedWith(order).take(Params.SLIP_SAME_KEEP).map { it.text to it.total } to
            comp.values.sortedWith(order).take(Params.SLIP_COMP_KEEP).map { it.text to it.total }
    }

    @Test
    fun bestFirstWalkEqualsTheScan() {
        val search = KeySlipSearch(lex, slips, de.params, de.scale::classCost)
        for (w in listOf("nict", "scon", "fur", "haus", "d", "zz", "uberpeufe", "kaffe", "strass")) {
            val r = search.search(w, emptyList(), null)
            val (same, comp) = brute(w)
            assertEquals(same, r.same.entries.map { it.text to it.total }, "same-length $w")
            assertEquals(comp, r.comp.entries.map { it.text to it.total }, "completions $w")
        }
    }

    @Test
    fun examplesOf81() {
        val search = KeySlipSearch(lex, slips, de.params, de.scale::classCost)
        fun texts(w: String) = search.search(w, emptyList(), null).handOver().map { it.text to it.completion }
        // "nict": no same-length word, but the completions "Microsoft" and "Nocturne"
        val nict = texts("nict")
        assertTrue(("Microsoft" to true) in nict && ("Nocturne" to true) in nict, nict.toString())
        // "scon": completions "Scones", "Scone", "Yvonne" and the same-length "Egon"
        val scon = texts("scon")
        assertTrue(listOf("Scones" to true, "Scone" to true, "Yvonne" to true, "Egon" to false).all { it in scon }, scon.toString())
        // "fur" gives "für" by an accent slip; missing letters are not reached ("mchte" → "möchte")
        assertTrue(("für" to false) in texts("fur"))
        assertTrue(texts("mchte").none { it.first == "möchte" })
        // a typed dictionary word always keeps a place (total 1), with its case variants ("haus" → "Haus")
        val haus = search.search("haus", emptyList(), null)
        assertEquals("Haus" to 1, haus.same.entries.first().let { it.text to it.total })
        // a not offered form of an all-exact key enters as well
        assertTrue(search.search("performanz", emptyList(), null).same.entries.any { it.text == "Performanz" && it.total == 1 })
        assertTrue(search.search("performan", emptyList(), null).comp.entries.none { it.text == "performant" })
    }

    @Test
    fun handOverOrder() {
        // same-length entries first (lowest total first), then completions, the first SLIP_HANDOVER
        val search = KeySlipSearch(lex, slips, de.params, de.scale::classCost)
        val r = search.search("ha", emptyList(), null)
        val h = r.handOver()
        assertTrue(h.size <= Params.SLIP_HANDOVER)
        val firstComp = h.indexOfFirst { it.completion }
        assertTrue(firstComp < 0 || h.drop(firstComp).all { it.completion })
        assertEquals(r.same.entries.size, firstComp.coerceAtLeast(0).let { if (firstComp < 0) h.size else it })
    }

    @Test
    fun pairFollowersAreCheckedSeparately() {
        // after "vielen" the pair follower "Dank" costs far below its static cost and is still found (05 §8.1 bound)
        val r = de.suggest(QueryContext.of("Vielen Da"))
        val dank = r.ranked.first()
        assertEquals("Dank", dank.display)
        assertTrue(dank.lm < de.scale.classCost(lex.exactClass("Dank")))
    }

    @Test
    fun formPrefixOfAnAliasForm() {
        assertEquals("don", KeySlipSearch.formPrefix("don't", "dont", 3))
        assertEquals("don't", KeySlipSearch.formPrefix("don't", "dont", 4))
        assertEquals("Mic", KeySlipSearch.formPrefix("Microsoft", "microsoft", 3))
        assertEquals("est-c", KeySlipSearch.formPrefix("est-ce", "estce", 4))
    }
}
