package app.metrokeys.engine.swipe

import app.metrokeys.engine.EngineSettings
import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.lexicon.Dictionary
import app.metrokeys.engine.lexicon.Lexicon
import app.metrokeys.engine.lexicon.PairTable
import app.metrokeys.engine.lexicon.WordForm
import app.metrokeys.engine.predict.LanguageEngine
import app.metrokeys.engine.predict.LanguageParams
import app.metrokeys.engine.text.FieldInfo
import app.metrokeys.engine.text.FieldPolicy
import app.metrokeys.engine.touch.KeyboardGeometry
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shape writing, spec 06 (worked examples §8 and acceptance test §4.6). */
class ShapeWriterTest {
    private val g = KeyboardGeometry.fallback("de-DE")
    private val sx = 480.0 / 1080
    private val sy = 336.0 / 756

    /** Straight lines between key centres, one sample every [step] px (06 §8). */
    private fun trace(word: String, step: Double = 19.4, jitter: (Int) -> Pair<Float, Float> = { 0f to 0f }): ShapeTrace {
        val centres = word.mapIndexed { i, c ->
            val k = g.shapeKey(c)!!
            val (jx, jy) = jitter(i)
            (k.centerX + jx).toDouble() to (k.centerY + jy).toDouble()
        }
        val pts = ArrayList<Pair<Float, Float>>()
        pts.add(centres[0].first.toFloat() to centres[0].second.toFloat())
        for (i in 1 until centres.size) {
            val (x0, y0) = centres[i - 1]
            val (x1, y1) = centres[i]
            val n = maxOf(1, ceil(hypot(x1 - x0, y1 - y0) / step).toInt())
            for (s in 1..n) pts.add((x0 + (x1 - x0) * s / n).toFloat() to (y0 + (y1 - y0) * s / n).toFloat())
        }
        return ShapeTrace.of(pts)
    }

    private fun synthetic(entries: Map<String, List<WordForm>>, pairs: List<Triple<String, String, Int>> = emptyList()): LanguageEngine {
        val lex = Lexicon.fromEntries("de-DE", 16.357883f, 5890279, 156169536, entries)
        return LanguageEngine(LanguageParams.GERMAN, Dictionary(lex, PairTable.fromTriples(lex, pairs)))
    }

    @Test
    fun e1TapVersusSwipe() {
        assertEquals(112.5, ShapeWriter.startThreshold(1080f, 756f), 1e-9)
        assertEquals(119.4, ShapeWriter.startThreshold(1596f, 576f), 0.05)
        assertFalse(ShapeWriter.isSwipeStart(518f - 410f, 300f - 280f, 1080f, 756f))
        assertTrue(ShapeWriter.isSwipeStart(525f - 410f, 290f - 280f, 1080f, 756f))
        assertFalse(ShapeWriter.isSwipeCandidate(600f, 756f, onLetterKey = true))
        assertTrue(ShapeWriter.isSwipeCandidate(280f, 756f, onLetterKey = true))
        assertFalse(ShapeWriter.isSwipeCandidate(280f, 756f, onLetterKey = false))
    }

    @Test
    fun rejectedTraces() {
        val e = TestDictionaries.german()
        val w = ShapeWriter(e)
        assertEquals(ShapeResult.NONE, w.recognize(ShapeTrace.of(listOf(410f to 280f))))
        // never exceeds the threshold
        assertEquals(ShapeResult.NONE, w.recognize(ShapeTrace.of(listOf(410f to 280f, 480f to 284f, 518f to 300f))))
        // starts in the bottom row
        assertEquals(ShapeResult.NONE, w.recognize(ShapeTrace.of(listOf(400f to 600f, 600f to 300f, 700f to 300f))))
    }

    @Test
    fun e2KeySequenceAnglesTemplateAndDistances() {
        val t = trace("gut")
        val seq = ShapeWriter.keySequence(t, g)!!
        assertEquals("guzt", seq.keys.map { g.keys[it].letter }.joinToString(""))
        val angles = seq.angles.map { Math.round(it) }
        assertEquals(listOf(180, 79, 52, 180), angles)
        val tpl = ShapeWriter.template("gut", g, sx, sy)!!
        assertEquals(25, tpl.size)
        assertEquals(240.0, tpl[0][0], 1e-9)
        assertEquals(126.0, tpl[0][1], 1e-9)
        assertEquals(312.0, tpl[11][0], 1e-9)
        assertEquals(42.0, tpl[11][1], 1e-9)
        assertEquals(312.0, tpl[12][0], 1e-9) // u repeated
        assertEquals(216.0, tpl[23][0], 1e-9)
        assertEquals(216.0, tpl[24][0], 1e-9) // padded with t
        val trimmed = ShapeWriter.trimmedTrace(t, sx, sy)
        assertEquals(3.5, ShapeWriter.dtw(trimmed, tpl), 0.1)
        assertEquals(13.9, ShapeWriter.dtw(trimmed, ShapeWriter.template("hut", g, sx, sy)!!), 0.1)
        val one = ShapeWriter.template("a", g, sx, sy)!!
        assertTrue(one.all { it[0] == one[0][0] && it[1] == one[0][1] })
    }

    @Test
    fun e2ScoresWithInventedClasses() {
        val e = synthetic(
            mapOf(
                "gut" to listOf(WordForm("gut", 200), WordForm("Gut", 170)),
                "hut" to listOf(WordForm("hut", 150)),
            ),
        )
        val r = ShapeWriter(e).recognize(trace("gut"), "")
        val gut = r.candidates.first { it.text == "gut" }
        val hut = r.candidates.first { it.text == "hut" }
        assertEquals(20.4 + 55, gut.searchCost, 0.2) // the angle at z is 51.6°
        assertEquals(44.4 + 105, hut.searchCost, 0.2)
        assertEquals(83.0, gut.total, 0.5)
        assertEquals(180.0, hut.total, 0.5)
        assertEquals("gut", r.candidates.first().text)
        assertTrue(r.recognized)
        assertTrue(r.candidates.any { it.text == "Gut" })
        // one-shot shift: gut and Gut collapse into one
        assertEquals(listOf("Gut", "Hut"), r.texts(ShiftState.ONE_SHOT))
        assertEquals(listOf("GUT", "HUT"), r.texts(ShiftState.CAPS_LOCK))
    }

    @Test
    fun e3DoubleLetter() {
        val e = synthetic(
            mapOf(
                "hallo" to listOf(WordForm("hallo", 200)),
                "halo" to listOf(WordForm("halo", 200)),
                "hall" to listOf(WordForm("hall", 200)),
            ),
        )
        val t = trace("halo")
        assertEquals("hgfdsasdfghjklo", ShapeWriter.keySequence(t, g)!!.keys.map { g.keys[it].letter }.joinToString(""))
        val r = ShapeWriter(e).recognize(t, "")
        val hallo = r.candidates.first { it.text == "hallo" }
        val halo = r.candidates.first { it.text == "halo" }
        val hall = r.candidates.first { it.text == "hall" }
        // the double letter costs 14 and saves one unused key (10)
        assertEquals(4.0, hallo.searchCost - halo.searchCost, 1e-6)
        assertEquals(14.8, hallo.shapeDistance, 0.2)
        assertEquals(11.2, halo.shapeDistance, 0.2)
        // hall: final l as neighbour of the last key o (0.096 · 455)
        assertEquals(0.096 * 250 * hypot(54.0, 189.0) / 108, 43.7, 0.1)
        assertTrue(hall.searchCost > halo.searchCost + 40)
    }

    @Test
    fun e4Context() {
        val e = synthetic(
            mapOf(
                "das" to listOf(WordForm("das", 230)),
                "dass" to listOf(WordForm("dass", 215)),
                "weiß" to listOf(WordForm("weiß", 200)),
                "ich" to listOf(WordForm("ich", 250)),
            ),
            listOf(Triple("weiß", "dass", 230)),
        )
        val t = trace("das")
        val seq = ShapeWriter.keySequence(t, g)!!
        assertEquals("dsas", seq.keys.map { g.keys[it].letter }.joinToString(""))
        assertEquals(listOf(180, 0, 180, 180), seq.angles.map { Math.round(it) })
        val w = ShapeWriter(e)
        val ctx = w.recognize(t, "Ich weiß ")
        val das = ctx.candidates.first { it.text == "das" }
        val dass = ctx.candidates.first { it.text == "dass" }
        assertEquals(10.0 + 25, das.searchCost, 1e-6)
        assertEquals(14.0 + 40, dass.searchCost, 1e-6)
        assertEquals(40, dass.contextBonus)
        assertEquals(3.6, das.shapeDistance, 0.3)
        assertEquals(7.4, dass.shapeDistance, 0.3)
        assertEquals("dass", ctx.candidates.first().text)
        val comma = w.recognize(t, "Ich weiß, ")
        assertEquals("das", comma.candidates.first().text)
        assertEquals(0, comma.candidates.first { it.text == "dass" }.contextBonus)
    }

    @Test
    fun realDictionary() {
        val e = TestDictionaries.german()
        val w = ShapeWriter(e)
        for (word in listOf("gut", "hallo", "das", "morgen", "wie", "geht", "ich", "nicht", "danke")) {
            assertEquals(word, w.recognize(trace(word), "").candidates.first().text.lowercase(), word)
        }
        val tsch = w.recognize(trace("tschüss"), "")
        assertEquals("tschüss", tsch.candidates.first().text)
        assertTrue(tsch.candidates.size <= 5)
    }

    @Test
    fun acceptanceAccuracy() {
        val e = TestDictionaries.german()
        val w = ShapeWriter(e)
        val words = ("ich du er sie wir ihr und oder aber nicht auch noch schon jetzt heute morgen gestern hallo danke bitte " +
            "gut sehr viel mehr immer wieder machen haben sein werden kommen gehen sagen wissen sehen geben finden " +
            "denken leben arbeit zeit jahr mann frau kind haus stadt welt land leute freund liebe wasser essen " +
            "schule spielen lernen fahren bald gerne vielleicht wirklich einfach").split(" ")
        val rnd = Random(42)
        var hits = 0
        for (word in words) {
            val ox = (rnd.nextFloat() * 2 - 1) * 0.15f * g.keyWidth
            val oy = (rnd.nextFloat() * 2 - 1) * 0.15f * g.keyWidth
            val t = trace(word) { (ox + (rnd.nextFloat() * 2 - 1) * 0.1f * g.keyWidth) to (oy + (rnd.nextFloat() * 2 - 1) * 0.1f * g.keyWidth) }
            val r = w.recognize(t, "")
            if (r.candidates.firstOrNull()?.text?.lowercase() == word) hits++
        }
        val rate = hits.toDouble() / words.size
        assertTrue(rate >= 0.9, "top-1 rate $rate")
    }

    @Test
    fun contactWordsGetAnIndex() {
        val e = TestDictionaries.german()
        val engine = PredictionEngine(listOf(e))
        engine.setContactNames(listOf("Xaverine Quast"))
        val r = engine.recognizeShape(trace("xaverine"), "Hallo ")
        assertTrue(r.candidates.any { it.text == "Xaverine" }, r.candidates.toString())
    }

    @Test
    fun policyAndSetting() {
        val engine = PredictionEngine(listOf(TestDictionaries.german()))
        assertEquals(ShapeResult.NONE, engine.recognizeShape(trace("gut"), "", FieldInfo(FieldPolicy.PASSWORD)))
        assertEquals(ShapeResult.NONE, engine.recognizeShape(trace("gut"), "", FieldInfo(FieldPolicy.URL)))
        engine.settings = EngineSettings(shapeWriting = false)
        assertEquals(ShapeResult.NONE, engine.recognizeShape(trace("gut"), ""))
    }

    @Test
    fun multilingualShapeMerge() {
        val engine = TestDictionaries.multilingual()
        engine.setGeometry(g)
        val r = engine.recognizeShape(trace("gut"), "Das ist ")
        assertEquals("gut", r.candidates.first().text)
        assertEquals(r.candidates.map { it.text }.distinct(), r.candidates.map { it.text })
    }

    @Test
    fun multilingualCasedFormsAndLanguagePenalty() {
        // 06 §4.1: German active, multilingual, swipe d-a-s, empty context (weights de 0.65 / en 0.35)
        val engine = TestDictionaries.multilingual()
        engine.setGeometry(g)
        val r = engine.recognizeShape(trace("das"), "")
        assertEquals(listOf("das", "dass", "daß", "da", "dad"), r.candidates.map { it.text })
        assertEquals("en-US", r.candidates[4].languageTag)
        // the penalty is the class drop of the weight: −log10(w) / Bc
        val en = engine.language("en-US")!!
        assertEquals(-kotlin.math.log10(0.35) / 0.029, engine.shapePenalty(0.35, en), 1e-6)
        assertEquals(4 / 0.02655, engine.shapePenalty(1e-6, engine.language("de-DE")!!), 1e-6)
    }

    @Test
    fun recognitionIsFast() {
        val w = ShapeWriter(TestDictionaries.german())
        val traces = listOf("vielleicht", "wirklich", "zusammen", "geburtstag", "entschuldigung").map { trace(it) }
        traces.forEach { w.recognize(it, "") } // warm-up
        val t0 = System.nanoTime()
        repeat(3) { traces.forEach { w.recognize(it, "") } }
        val mean = (System.nanoTime() - t0) / 1e6 / (3 * traces.size)
        assertTrue(mean < 100, "mean $mean ms")
    }
}
