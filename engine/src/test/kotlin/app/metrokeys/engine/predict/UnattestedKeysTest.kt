package app.metrokeys.engine.predict

import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.lexicon.Dictionary
import app.metrokeys.engine.lexicon.Lexicon
import app.metrokeys.engine.lexicon.WordForm
import app.metrokeys.engine.query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 05 §10.1 step 5 "Unattested keys" (0.6.9): a word valid only through a key kept by the count rule without being a
 * form (the damped keys of 05b §3.6 step 7) no longer blocks a clearly better correction.
 */
class UnattestedKeysTest {
    @Test
    fun ruleIsOffIn069() {
        // 0.6.9: the flags are stored, the rule is off for every language (in effect from 0.7.0)
        assertFalse(Params.UNATTESTED_KEYS_RULE)
        for (p in listOf(LanguageParams.GERMAN, LanguageParams.ENGLISH, LanguageParams.SPANISH, LanguageParams.PORTUGUESE,
            LanguageParams.ITALIAN, LanguageParams.FRENCH)) assertFalse(p.unattestedKeysRule, p.languageTag)
        val off = LanguageEngine(LanguageParams.GERMAN, offLexicon())
        assertFalse(off.query("hallp").validity.unattested)
        assertNull(off.query("hallp").autoCorrection)
        // the bundled German engine: a valid unattested word stays uncorrected (0.7.0: the colloquial "gibts", "würd";
        // "hiervon" is a form of the spelling dictionary)
        val g = TestDictionaries.german()
        for (w in listOf("gibts", "würd", "hiervon")) {
            assertFalse(g.query(w).validity.unattested, w)
            assertNull(g.query(w).autoCorrection, w)
        }
    }

    private fun offLexicon(): Dictionary = Dictionary(
        Lexicon.fromEntries("de-DE", 16.357883f, 5890279, 156169536, mapOf(
            "hallo" to listOf(WordForm("hallo", 250)),
            "hallp" to listOf(WordForm("hallp", 140)),
        ), unattested = setOf("hallp")), null,
    )

    /** "hallp" valid with a moderate class (not rare), "hallo" far more frequent; [unattested] keys flagged. */
    private fun engine(vararg unattested: String): LanguageEngine {
        val entries = mapOf(
            "hallo" to listOf(WordForm("hallo", 250)),
            "hallp" to listOf(WordForm("hallp", 140)),
            "haus" to listOf(WordForm("Haus", 230)),
            // a name-like unattested key: most frequent form capitalised, "Hallo" far more frequent
            "halle" to listOf(WordForm("Halle", 140), WordForm("halle", 100)),
        )
        val lex = Lexicon.fromEntries("de-DE", 16.357883f, 5890279, 156169536, entries, unattested = unattested.toSet())
        return LanguageEngine(RULE_ON, Dictionary(lex, null))
    }

    @Test
    fun preconditions() {
        val e = engine()
        val v = e.query("hallp").validity
        assertTrue(v.valid)
        // valid and not rare: step 5 blocks the correction without the flag
        assertTrue(v.cost <= LanguageParams.GERMAN.acValidRare, "cost ${v.cost}")
        assertTrue(e.lm("hallo") < LanguageParams.GERMAN.acValidTarget, "lm ${e.lm("hallo")}")
        assertFalse(v.unattested)
        assertNull(e.query("hallp").autoCorrection)
    }

    @Test
    fun unattestedWordIsCorrected() {
        val e = engine("hallp")
        assertTrue(e.lexicon.isUnattestedWord("Hallp"))
        assertEquals(1, e.lexicon.unattestedCount)
        val r = e.query("hallp")
        assertTrue(r.validity.valid)
        assertTrue(r.validity.unattested)
        assertEquals("hallo", r.autoCorrection?.display)
        assertEquals("Hallo", e.query("Hallp").autoCorrection?.display)
    }

    @Test
    fun capitalisedUnattestedKeyKeepsBlocking() {
        // 0.6.9 owner decision: only keys whose most frequent form is all lower case
        val e = engine("halle")
        val v = e.query("Halle").validity
        assertTrue(v.valid)
        assertFalse(v.unattested)
        assertTrue(v.cost <= LanguageParams.GERMAN.acValidRare, "cost ${v.cost}")
        assertNull(e.query("Halle").autoCorrection)
        assertFalse(e.query("halle").validity.unattested)
        assertNull(e.query("halle").autoCorrection)
    }

    @Test
    fun noCorrectionToAnUnattestedWord() {
        val e = engine("hallp", "hallo")
        assertTrue(e.query("hallp").validity.unattested)
        assertNull(e.query("hallp").autoCorrection)
    }

    @Test
    fun learnedOrContactWordStaysUncorrected() {
        val e = engine("hallp")
        repeat(Params.LEARN_MIN_COUNT) { e.learn("hallp", emptyList()) }
        assertTrue(e.user.isKnownLearned("hallp"))
        assertFalse(e.query("hallp").validity.unattested)
        assertNull(e.query("hallp").autoCorrection)

        val c = engine("hallp")
        c.contacts = ContactNames.build(listOf("Hallp"))
        assertFalse(c.query("Hallp").validity.unattested)
        assertNull(c.query("Hallp").autoCorrection)
    }

    @Test
    fun rejectionsStillApply() {
        val e = engine("hallp")
        e.user.reject("hallp", "hallo", learn = false)
        assertNull(e.query("hallp").autoCorrection)
    }

    @Test
    fun bundledFlags() {
        // English has no unattested keys; from 0.7.0 the other languages flag the keys kept by the colloquial path
        // (05b §12.4), a few hundred
        assertEquals(0, TestDictionaries.en.lexicon.unattestedCount)
        for (d in listOf(TestDictionaries.de, TestDictionaries.es, TestDictionaries.pt, TestDictionaries.it, TestDictionaries.fr)) {
            assertTrue(d.lexicon.unattestedCount in 1..1000, d.languageTag)
        }
        val de = TestDictionaries.de.lexicon
        // the colloquial keys "wär" and "gibts" are flagged (05b §12.4); German forms are never flagged
        assertTrue(de.isUnattestedWord("wär"))
        assertTrue(de.isUnattestedWord("gibts"))
        // unattested names stay flagged but keep blocking corrections (main form capitalised)
        val g = LanguageEngine(RULE_ON, TestDictionaries.de)
        for (w in listOf("Fred", "Lucie", "Alain")) {
            assertTrue(de.isUnattestedWord(w), w)
            assertFalse(g.query(w).validity.unattested, w)
            assertNull(g.query(w).autoCorrection, w)
        }
        for (w in listOf("können", "schon", "schön", "Haus", "die", "usw")) assertFalse(de.isUnattestedWord(w), w)
    }
}

/** German with the unattested-key rule switched on (0.7.0); the tests of the rule enable it here. */
private val RULE_ON = LanguageParams.GERMAN.copy(unattestedKeysRule = true)

/**
 * Report (run with MK_UNATTESTED_REPORT=1, rule switched on): every unattested German key typed as its stored best form with no context,
 * German alone, and the autocorrection it gets now; "new" marks those the validity cost alone would have blocked.
 */
class UnattestedReport {
    @Test
    fun germanUnattestedCorrections() {
        if (System.getenv("MK_UNATTESTED_REPORT") == null) return
        val e = LanguageEngine(RULE_ON, TestDictionaries.de)
        val multi = TestDictionaries.multilingual("de-DE", LanguageEngine(RULE_ON, TestDictionaries.de), TestDictionaries.english())
        val lex = e.lexicon
        for (k in 0 until lex.keyCount) {
            if (!lex.isUnattested(k)) continue
            val typed = lex.formText(k, 0)
            val r = e.query(typed)
            val ac = r.autoCorrection ?: continue
            val blockedBefore = r.validity.valid && r.validity.cost <= LanguageParams.GERMAN.acValidRare
            val m = multi.query(app.metrokeys.engine.Query(typed)).autoCorrection ?: "-"
            println("UNATTESTED\t${lex.keyString(k)}\t$typed\t${ac.display}\t${lex.formClass(k, 0)}\t${r.validity.cost}\t${if (blockedBefore) "new" else "old"}\t$m")
        }
    }
}
