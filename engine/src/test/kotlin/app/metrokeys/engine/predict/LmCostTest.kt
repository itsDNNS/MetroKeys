package app.metrokeys.engine.predict

import app.metrokeys.engine.TestDictionaries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LmCostTest {
    @Test
    fun rounding() {
        assertEquals(3, LmCost.roundHalfAway(2.5))
        assertEquals(-3, LmCost.roundHalfAway(-2.5))
        assertEquals(2, LmCost.roundHalfAway(2.49))
    }

    @Test
    fun costFormula() {
        assertEquals(15, LmCost.cost(1.0))
        assertEquals(255, LmCost.cost(0.0))
        assertEquals(33, LmCost.cost(0.1))
        assertEquals(255, LmCost.cost(1e-30))
        assertEquals(87, LmCost.cost(1e-4))
    }

    @Test
    fun dictionaryConstants() {
        val de = TestDictionaries.german()
        val en = TestDictionaries.english()
        assertEquals(-8.19, de.a, 1e-9)
        assertEquals(0.02655, de.bc, 1e-9)
        assertEquals(-8.84, en.a, 1e-9)
        assertEquals(0.02900, en.bc, 1e-9)
        // German: round(162.42 − 0.4779 k): class 255 → 41, class 0 → 162
        assertEquals(41, LmCost.cost(Math.pow(10.0, de.unigramLog10(255))))
        assertEquals(162, LmCost.cost(Math.pow(10.0, de.unigramLog10(0))))
        // the rounded linear forms of §3.2 agree up to rounding at .5 edges
        for (k in 0..255) {
            val d = LmCost.cost(Math.pow(10.0, de.unigramLog10(k))) - (162.42 - 0.4779 * k)
            val e = LmCost.cost(Math.pow(10.0, en.unigramLog10(k))) - (174.12 - 0.522 * k)
            assertTrue(Math.abs(d) <= 0.51 && Math.abs(e) <= 0.51, "class $k")
        }
    }

    @Test
    fun pairProbability() {
        assertEquals(-2.059, LmCost.pairLog10(150), 0.0005)
        assertEquals(0.0, LmCost.pairLog10(255), 1e-12)
        assertEquals(-5.0, LmCost.pairLog10(0), 1e-12)
    }
}
