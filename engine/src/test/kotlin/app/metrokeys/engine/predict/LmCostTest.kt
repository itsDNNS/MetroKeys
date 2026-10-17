package app.metrokeys.engine.predict

import app.metrokeys.engine.TestDictionaries
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The anchored cost scale of 05 §3.2 (0.7.1) and the constants of §3.1. */
class LmCostTest {
    @Test
    fun rounding() {
        assertEquals(3, LmCost.roundHalfAway(2.5))
        assertEquals(-3, LmCost.roundHalfAway(-2.5))
        assertEquals(2, LmCost.roundHalfAway(2.49))
    }

    @Test
    fun dictionaryConstants() {
        val de = TestDictionaries.german()
        val en = TestDictionaries.english()
        assertEquals(-8.19, de.a, 1e-9)
        assertEquals(0.02563, de.bc, 1e-9)
        assertEquals(-8.84, en.a, 1e-9)
        assertEquals(0.02927, en.bc, 1e-9)
    }

    @Test
    fun germanAnchorsOfTheExample() {
        // 05b §1.1 example (German 0.7.0 list): classes 255, 253, 244, 227, 205, 181, 158, 137, 111, 83, 68 with the de
        // costs of 05 §1.1, then (1, 254)
        val de = TestDictionaries.german()
        assertEquals(
            listOf(255 to 112, 253 to 118, 244 to 122, 227 to 129, 205 to 140, 181 to 153, 158 to 166, 137 to 178,
                111 to 192, 83 to 210, 68 to 222, 1 to 254),
            de.lexicon.costAnchors,
        )
        // the anchors as log10 p (05 §3.2): (−1.654, 112), (−1.706, 118), (−1.936, 122) …, (−8.164, 254)
        assertEquals(-1.654, de.unigramLog10(255), 0.0005)
        assertEquals(-8.164, de.unigramLog10(1), 0.0005)
        assertEquals(126, de.lexicon.guardClass) // the class of rank 5 000 (05 §3.2: "a form of rank 5 000 (class 126) 184")
    }

    @Test
    fun costFormula() {
        val de = TestDictionaries.german()
        val s = de.scale
        assertEquals(255, s.cost(0.0))
        // above the first anchor: c1 − 23 · (x − x1)
        assertEquals(LmCost.roundHalfAway(112 - 23 * (0 - de.unigramLog10(255))), s.cost(1.0))
        // 05 §3.2: "und" (class 253) 118, "nicht" 121, "Haus" (184) 151, rank 5 000 (class 126) 184, rank 90 000 (class 49)
        // 231, class 1 and class 0 254
        assertEquals(118, s.classCost(253))
        assertEquals(121, s.classCost(de.lexicon.exactClass("nicht")))
        assertEquals(151, s.classCost(184))
        assertEquals(184, s.classCost(126))
        assertEquals(231, s.classCost(49))
        assertEquals(254, s.classCost(1))
        assertEquals(254, s.classCost(0))
        // between anchors the map is linear in log10 p and increasing in cost
        for (k in 1..254) assertTrue(s.classCost(k) >= s.classCost(k + 1), "class $k")
        // 05 §17.7: cost(0.137) = 94
        assertEquals(94, s.cost(0.137))
    }

    @Test
    fun inverseMap() {
        // 06 §4.1 (0.7.1): the inverse of the anchored map
        val s = TestDictionaries.german().scale
        for (c in listOf(80.0, 112.0, 130.0, 151.0, 200.0, 253.0)) {
            val x = s.inverseLog10(c)!!
            assertEquals(c, s.raw(x), 1e-6)
        }
        assertNull(s.inverseLog10(254.0))
        assertNull(s.inverseLog10(300.0))
        assertTrue(s.inverseLog10(100.0)!! > log10(0.01))
    }

    @Test
    fun pairProbability() {
        assertEquals(-2.059, LmCost.pairLog10(150), 0.0005)
        assertEquals(0.0, LmCost.pairLog10(255), 1e-12)
        assertEquals(-5.0, LmCost.pairLog10(0), 1e-12)
    }
}
