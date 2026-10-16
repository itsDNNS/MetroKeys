package app.metrokeys.engine.swipe

import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.predict.LanguageParams
import app.metrokeys.engine.predict.LmCost
import app.metrokeys.engine.touch.KeyboardGeometry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * French shape writing, spec 06 §8 E15–E17, §7.1, the AZERTY neighbourhoods of §4.2 and the elided, hyphenated and
 * ligature entries of §4.1, on the French fallback grid (AZERTY) with the built dictionary. Search costs and the winner
 * are asserted; shape distances are ≈ values.
 */
class FrenchShapeTest {
    private val g = KeyboardGeometry.fallback("fr-FR")
    private val fr = TestDictionaries.french().also { e -> e.geometry = g }
    private val w = ShapeWriter(fr)

    private fun trace(letters: String) = ShapeWriterTest.traceOn(g, letters)

    private fun seq(letters: String) = ShapeWriter.keySequence(trace(letters), g)!!

    private fun letters(s: ShapeWriter.KeySequence) = s.keys.map { k -> g.keys[k].letter }.joinToString("")

    private fun show(label: String, r: ShapeResult) = println("$label: " + r.candidates.take(5).map { c ->
        "${c.text} search=${"%.2f".format(c.searchCost)} d=${"%.1f".format(c.shapeDistance)} bonus=${c.contextBonus} total=${"%.1f".format(c.total)}"
    })

    private fun elidedClass(prefix: String, follower: String): Int {
        val pre = fr.unigramLog10(fr.lexicon.prefixClass(prefix))
        val pair = fr.dictionary.pairs!!.bigram(prefix, follower)
        assertTrue(pair >= 0, "no pair ($prefix, $follower)")
        return LmCost.roundHalfAway((pre + LmCost.pairLog10(pair) - fr.a) / fr.bc).coerceIn(0, 255)
    }

    @Test
    fun constantsOfTheFrenchColumn() {
        val s = LanguageParams.FRENCH.shape
        assertEquals(14.0, s.unusedKeyCost)
        assertEquals(35.0, s.neighbourFactor * 250, 1e-9)
        assertEquals(18.0, s.doubleLetterCost)
        assertEquals(8.0, s.accentCost, 1e-9)
        assertEquals(45f, s.cornerAngle)
    }

    @Test
    fun azertyNeighbourhoods() {
        // 06 §4.2: no half-key offsets; directly below 1.75 key widths, one key aside in the adjacent row 2.02, two keys
        // apart 2.0 — all three neighbours (radius 2.05)
        fun d(a: Char, b: Char): Double {
            val ka = g.ownKey(a)!!
            val kb = g.ownKey(b)!!
            return kotlin.math.hypot((ka.centerX - kb.centerX).toDouble(), (ka.centerY - kb.centerY).toDouble()) / g.keyWidth
        }
        assertEquals(1.75, d('a', 'q'), 1e-6)
        assertEquals(1.75, d('w', 'd'), 1e-6)
        assertEquals(2.02, d('a', 's'), 0.01)
        assertEquals(2.0, d('a', 'e'), 1e-6)
        assertTrue(d('m', 'n') > 2.05)
        assertEquals(g.ownKey('o'), g.shapeKey('œ'))
        assertTrue(g.isViaBaseLetter('œ'))
    }

    @Test
    fun e15MoiOnAzerty() {
        val s = seq("moi")
        assertEquals("moi", letters(s))
        val t = trace("moi")
        val all = ShapeWriterTest.allCandidates(w, t)
        val moi = all.getValue("moi")
        assertEquals(0.0 + 255 - fr.lexicon.exactClass("moi"), moi.searchCost, 1e-6)
        all["mi"]?.let { mi -> assertEquals(20.4 + 255 - fr.lexicon.exactClass("mi"), mi.searchCost, 1.0) }
        assertTrue("mon" !in all)
        val r = w.recognize(t, "")
        show("E15", r)
        assertEquals("moi", r.candidates.first().text)
        // counter-check: m → o → n gives "mon"
        val mon = w.recognize(trace("mon"), "")
        show("E15 mon", mon)
        assertEquals("mon", mon.candidates.first().text)
    }

    @Test
    fun e16ElidedCestAgainstCet() {
        val s = seq("cest")
        assertEquals("cfesdrt", letters(s))
        val t = trace("cest")
        val all = ShapeWriterTest.allCandidates(w, t)
        val cest = all.getValue("c'est")
        val cls = elidedClass("c'", "est")
        println("E16 class(c'est)=$cls class(cet)=${fr.lexicon.exactClass("cet")} angles=${s.angles.toList()}")
        // c@0, e@2 (f skipped), s@3, t@6 (d, r skipped): the skipped angles / 5 + 3 unused keys × 14; spec 75° → 57,
        // the synthetic trace's corner at d is flatter
        val skipped = (s.angles[1] + s.angles[4] + s.angles[5]) / 5.0
        assertEquals(42.0 + skipped + 255 - cls, cest.searchCost, 1e-3)
        assertEquals(57.0, 42.0 + skipped, 8.0)
        all["cet"]?.let { c ->
            val sk = (s.angles[1] + s.angles[3] + s.angles[4] + s.angles[5]) / 5.0
            assertEquals(56.0 + sk + 255 - fr.lexicon.exactClass("cet"), c.searchCost, 1e-3)
        }
        assertTrue("cest" !in all)
        val r = w.recognize(t, "")
        show("E16", r)
        assertEquals("c'est", r.candidates.first().text)
        // after a picked "c'" the swipe of "est" is recognised with the context c'
        val after = w.recognize(trace("est"), "c'")
        show("E16 after c'", after)
        assertEquals("est", after.candidates.first().text)
    }

    @Test
    fun e17TresThroughItsBaseKey() {
        val s = seq("tres")
        assertEquals("tres", letters(s))
        val t = trace("tres")
        val all = ShapeWriterTest.allCandidates(w, t)
        val tres = all.getValue("très")
        assertEquals(8.0 + 255 - fr.lexicon.exactClass("très"), tres.searchCost, 1e-6)
        all["tes"]?.let { c -> assertEquals(14.0 + 255 - fr.lexicon.exactClass("tes"), c.searchCost, 1e-6) }
        // the class-0 spellings "tres" and "trés" are hidden (never suggested)
        assertTrue("tres" !in all && "trés" !in all)
        val r = w.recognize(t, "")
        show("E17", r)
        assertEquals("très", r.candidates.first().text)
        // 06 E17 expects "tes" as the first alternative; with the 0.7.0 classes the elided "t'es" comes before it
        // (spec/QUESTIONS.md, 0.7.0)
        println("E17 alternatives: ${r.candidates.map { it.text }}")
        assertTrue("tes" in r.candidates.take(3).map { it.text }, r.candidates.map { it.text }.toString())
    }

    @Test
    fun hyphenCompoundsAndLigaturesInOneGesture() {
        // 06 §4.1: hyphens and apostrophes are joiners, so "est-ce" is swiped e s t c e and "cœur" c o u r
        val estce = w.recognize(trace("estce"), "")
        show("est-ce", estce)
        assertEquals("est-ce", estce.candidates.first().text)
        val coeur = w.recognize(trace("cour"), "")
        show("cœur", coeur)
        assertTrue(coeur.candidates.take(3).any { c -> c.text == "cœur" }, coeur.candidates.toString())
        val auj = w.recognize(trace("aujourdhui"), "")
        assertEquals("aujourd'hui", auj.candidates.first().text)
        assertTrue(w.elidedEntryCount in 5000..60000, "${w.elidedEntryCount}")
        println("elided shape-index entries: ${w.elidedEntryCount}")
    }

    @Test
    fun acceptanceAccuracyFrench() {
        // 06 open point 16: common words with a z q w m and accents, and elided entries
        val words = ("moi mais aussi avec quoi très déjà été ça cœur c'est j'ai qu'il l'homme bonjour merci maison " +
            "quand comme mon pour nous vous faire avoir être aller voir savoir vouloir toujours jamais rien beaucoup " +
            "peut-être aujourd'hui demain matin soir femme enfant ami travail monde temps chose")
            .split(" ")
        val rnd = Random(42)
        var hits = 0
        val misses = ArrayList<String>()
        for (word in words) {
            val ox = (rnd.nextFloat() * 2 - 1) * 0.15f * g.keyWidth
            val oy = (rnd.nextFloat() * 2 - 1) * 0.15f * g.keyWidth
            val t = ShapeWriterTest.traceOn(g, word.replace("'", "").replace("-", "")) {
                (ox + (rnd.nextFloat() * 2 - 1) * 0.1f * g.keyWidth) to (oy + (rnd.nextFloat() * 2 - 1) * 0.1f * g.keyWidth)
            }
            val top = w.recognize(t, "").candidates.firstOrNull()?.text?.lowercase()
            if (top == word) hits++ else misses.add("$word→$top")
        }
        val rate = hits.toDouble() / words.size
        println("French shape-writing top-1 rate: $rate (${words.size} words), misses $misses")
        assertTrue(rate >= 0.85, "top-1 rate $rate, misses $misses")
    }
}
