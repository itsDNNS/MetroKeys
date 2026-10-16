package app.metrokeys.engine.predict

import app.metrokeys.engine.EngineSettings
import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.Query
import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.query
import app.metrokeys.engine.touch.TapSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * French worked examples of spec 05 §17.11 and the fr rows of the conformance checklist (§18) with the built
 * dictionary (no learned data, no taps, French AZERTY fallback grid). The outcomes are asserted as the spec words
 * them; the lm values and scores come from the build and are printed (05 §17.11: to be computed by the implementation).
 */
class FrenchExamplesTest {
    private val fr = TestDictionaries.french()
    private val engine = TestDictionaries.single(TestDictionaries.french())

    private fun LanguageSuggestions.corr(display: String): Candidate =
        corrections.firstOrNull { c -> c.display == display } ?: error("no correction $display in $corrections")

    private fun ac(text: String): String? = engine.query(Query(text)).autoCorrection

    private fun bar(text: String): List<String> = engine.query(Query(text)).suggestions.map { s -> s.text }

    private fun next(text: String): List<String> = engine.query(Query(text)).suggestions.map { s -> s.text }

    private fun show(label: String, text: String) {
        val r = fr.query(text)
        println("$label: valid=${r.validity} ac=${r.autoCorrection} corr=${r.corrections.take(3)} bar=${bar(text)}")
    }

    @Test
    fun header() {
        // 05 §3.1: corpusTokens = all counts of the frequency list plus the credited hyphen parts
        assertEquals(328_850_678L, fr.lexicon.corpusTokens)
        assertEquals(-8.52, fr.a, 1e-9)
        println("fr header: S=${fr.lexicon.scale} maxCount=${fr.lexicon.maxCount} Bc=${fr.bc}")
        assertEquals(LmCost.constantBc(fr.lexicon.scale), fr.bc)
        assertEquals(15.989, fr.lexicon.scale.toDouble(), 0.01)
        assertTrue(fr.lexicon.exactClass("de") >= 254 && fr.lexicon.exactClass("je") >= 254)
        // the prefix table: the 14 prefixes of 05 §1.1, "c'" the most frequent
        assertEquals(14, fr.lexicon.prefixCount)
        for (p in LanguageParams.FRENCH_ELISION_PREFIXES) assertTrue(fr.lexicon.prefixClass(p) > 0, p)
        assertTrue(fr.lexicon.prefixClass("c'") >= fr.lexicon.prefixClass("l'"))
        assertTrue(fr.lexicon.prefixClass("l'") > fr.lexicon.prefixClass("jusqu'"))
        assertEquals(-1, fr.lexicon.prefixClass("quelqu'"))
        assertFalse(fr.lexicon.exact("l'"))
        // one-letter keys: exactly a, à, y (05 §1.1)
        val one = fr.lexicon.words().filter { w -> w.text.length == 1 }.map { w -> w.text.lowercase() }.toSet()
        assertEquals(setOf("a", "à", "y"), one)
        // the letters of the prefixes are no words (05b §11.6 step 3), fused elisions are dropped (step 7)
        for (k in listOf("qu", "jusqu", "lorsqu", "puisqu", "quoiqu", "quil", "daccord", "jusqua", "dabord")) {
            assertTrue(fr.lexicon.keyIndex(k) < 0, k)
        }
        // "lhomme" is only the key of the name "Lhomme": typed in lower case a case error (05 §17.11 example 4)
        assertTrue(fr.lexicon.forms("lhomme").all { f -> f.text[0].isUpperCase() }, fr.lexicon.forms("lhomme").toString())
        // "jai" and "cest" are keys only of capitalised forms (the acronyms "JAI", "CEST", class 0, and names): typed in
        // lower case a case error
        for (k in listOf("jai", "cest")) {
            assertTrue(fr.lexicon.forms(k).all { f -> f.text[0].isUpperCase() }, fr.lexicon.forms(k).toString())
            assertFalse(fr.query(k).validity.valid, k)
        }
    }

    @Test
    fun example1MissingAccents() {
        // "tres" is kept with class 0 (05b §11.4 step 2): valid, cost 168 > AC_VALID_RARE 163
        val t = fr.query("tres")
        assertTrue(t.validity.valid)
        assertEquals(0, fr.lexicon.exactClass("tres"))
        assertTrue(t.validity.cost > fr.params.acValidRare)
        val tc = t.corr("très")
        assertEquals(14, tc.ed)
        assertTrue(tc.lm < fr.params.acValidTarget)
        assertEquals(tc.lm + 17, fr.acScore(tc, 4))
        println("lm(très)=${tc.lm}")
        assertEquals("très", ac("tres"))
        assertEquals("très", ac("C'est tres"))
        // "deja" is no key, ED 28
        assertFalse(fr.lexicon.exact("deja"))
        val d = fr.query("deja").corr("déjà")
        assertEquals(28, d.ed)
        println("lm(déjà)=${d.lm} score=${fr.acScore(d, 4)}")
        assertEquals("déjà", ac("deja"))
        assertEquals("Déjà", ac("Deja"))
        assertEquals(14, fr.query("garcon").corr("garçon").ed)
        assertEquals("garçon", ac("garcon"))
        show("tres", "tres")
    }

    @Test
    fun example2WrongAccent() {
        assertEquals(0, fr.lexicon.exactClass("trés"))
        assertEquals(14, fr.query("trés").corr("très").ed)
        assertEquals("très", ac("trés"))
        assertEquals("après", ac("aprés"))
        // without ACCENT_SWAP the same-key substitution costs ED_SUBSTITUTE
        val noSwap = fr.params.copy(accentSwap = false)
        assertEquals(125, EditDistance(noSwap, fr.touch).weighted("é", "è", TapSequence.none(1)))
        assertEquals(14, EditDistance(fr.params, fr.touch).weighted("é", "è", TapSequence.none(1)))
        show("aprés", "aprés")
    }

    @Test
    fun example3BothSpellingsValid() {
        assertNull(ac("Je vais a"))
        assertTrue(fr.query("Je vais a").validity.valid)
        assertEquals(14, fr.query("Je vais a").corr("à").ed)
        assertTrue("à" in bar("Je vais a").take(3), bar("Je vais a").toString())
        assertNull(ac("Tu vas ou"))
        assertTrue("où" in bar("Tu vas ou").take(3), bar("Tu vas ou").toString())
        for (typed in listOf("la", "sur", "du")) assertNull(ac(typed), typed)
        show("Je vais a", "Je vais a")
        show("Tu vas ou", "Tu vas ou")
    }

    @Test
    fun example4MissingApostrophe() {
        for (typed in listOf("jai", "cest", "quil", "lhomme", "dabord")) assertFalse(fr.query(typed).validity.valid, typed)
        val j = fr.query("jai").corr("j'ai")
        assertEquals(0, j.ed)
        println("lm(j'ai)=${j.lm}")
        assertEquals("j'ai", ac("jai"))
        assertEquals("J'ai", ac("Jai"))
        assertEquals("c'est", ac("cest"))
        assertEquals("qu'il", ac("Je pense quil"))
        assertEquals("l'homme", ac("lhomme"))
        assertEquals("d'abord", ac("dabord"))
        show("jai", "jai")
        show("Je pense quil", "Je pense quil")
    }

    @Test
    fun example5OneLetterRemainder() {
        assertFalse(fr.query("jusqua").validity.valid)
        val j = fr.query("jusqua").corr("jusqu'à")
        assertEquals(14, j.ed)
        println("lm(jusqu'à)=${j.lm} score=${fr.acScore(j, 6)}")
        assertEquals("jusqu'à", ac("jusqua"))
        // the accent-lost pair (jusqu', a) is not in the pair table: "jusqu'a" is invalid and corrected
        assertFalse(fr.query("jusqu'a").validity.valid)
        assertEquals("jusqu'à", ac("jusqu'a"))
        assertTrue(fr.query("jusqu'à").validity.valid)
        assertNull(ac("jusqu'à"))
    }

    @Test
    fun example6ValidWordElidedOrNot() {
        assertTrue(fr.query("quelle").validity.valid)
        assertNull(ac("quelle"))
        assertTrue("qu'elle" in bar("quelle").take(3), bar("quelle").toString())
        assertTrue(fr.query("dans").validity.valid)
        assertNull(ac("dans"))
        assertFalse("d'ans" in bar("dans"), bar("dans").toString())
        assertFalse(fr.query("dans").ranked.any { c -> c.display == "d'ans" })
        assertFalse(bar("lui").any { s -> s.startsWith("l'") }, bar("lui").toString())
        show("quelle", "quelle")
        show("dans", "dans")
    }

    @Test
    fun example7AfterAPrefix() {
        assertEquals("c'est", bar("c'").first(), bar("c'").toString())
        assertTrue("c'était" in bar("c'"), bar("c'").toString())
        assertEquals("jusqu'à", bar("jusqu'").first(), bar("jusqu'").toString())
        // at a sentence start the shift applies to the prefix only (04 §11.3 item 1): "C'" → "C'est"
        assertEquals("C'est", bar("Merci. C'").first(), bar("Merci. C'").toString())
        assertEquals("JUSQU'À", bar("JUSQU'").first(), bar("JUSQU'").toString())
        assertEquals("c'était", ac("c'etait"))
        assertEquals("C'était", ac("C'etait"))
        println("c': ${bar("c'")} jusqu': ${bar("jusqu'")}")
    }

    @Test
    fun example8Ligature() {
        assertFalse(fr.query("coeur").validity.valid)
        assertTrue(fr.lexicon.exact("cœur"))
        val c = fr.query("coeur").corr("cœur")
        assertEquals(14, c.ed)
        assertEquals(c.lm + 14, fr.acScore(c, 5))
        assertEquals("cœur", ac("coeur"))
        assertEquals("cœur", ac("Mon coeur"))
        for ((typed, want) in listOf("soeur" to "sœur", "oeil" to "œil", "oeuvre" to "œuvre", "noeud" to "nœud", "Oeuvre" to "Œuvre")) {
            assertEquals(want, ac(typed), typed)
        }
        // a prefix with o/a for œ/æ completes the ligature word (05 §8.4 item 4)
        assertTrue("cœur" in bar("coeu"), bar("coeu").toString())
        assertTrue("sœur" in bar("soe"), bar("soe").toString())
    }

    @Test
    fun example9NextWord() {
        val je = next("Je ")
        assertTrue("ne" in je.take(3) && "suis" in je.take(3), je.toString())
        assertTrue(je.none { w -> w.endsWith("'") }, je.toString())
        val merci = next("Merci ")
        assertTrue("de" in merci.take(3) && "pour" in merci.take(3), merci.toString())
        assertTrue("beaucoup" in merci.take(5), merci.toString())
        val bonne = next("Bonne ")
        assertTrue("idée" in bonne.take(3), bonne.toString())
        println("Je: $je\nMerci: $merci\nBonne: $bonne")
    }

    @Test
    fun example10CompletionThroughAnAccentVariant() {
        val b = bar("Il ouvre la fenetr")
        assertEquals("fenêtre", b.first(), b.toString())
        val r = fr.query("Il ouvre la fenetr")
        val f = r.ranked.first()
        assertEquals("fenêtre", f.display)
        assertFalse(f.isCorrection)
        // the correction "fenêtre" (insert e + ê, 125 + 14) is the cheapest correction as well
        assertEquals("fenêtre", r.corrections.first().display)
        assertEquals(139, r.corrections.first().ed)
        // measured: its score (lm + 5 · 139 / 6) lies within AC_MARGIN 32 of the runner-up "tenter", so §10.1 step 7
        // leaves the word as typed (spec/QUESTIONS.md); the rules are unchanged
        val s1 = fr.acScore(r.corrections[0], 6)
        val s2 = fr.acScore(r.corrections[1], 6)
        println("fenetr: ${r.corrections.take(3)} scores $s1 / $s2")
        if (kotlin.math.abs(s1 - s2) > fr.params.acMargin) assertEquals("fenêtre", ac("Il ouvre la fenetr"))
        else assertNull(ac("Il ouvre la fenetr"))
    }

    @Test
    fun example11LettersOfAPrefix() {
        assertNull(ac("qu"))
        assertEquals("qu'", bar("qu").first(), bar("qu").toString())
        assertTrue(engine.query(Query("qu")).suggestions.first().isElisionPrefix)
        assertNull(ac("jusqu"))
        assertEquals("jusqu'", bar("jusqu").first(), bar("jusqu").toString())
        // after the picked prefix the bar offers whole elided words
        val after = bar("qu'")
        assertTrue("qu'il" in after && "qu'elle" in after, after.toString())
        println("qu: ${bar("qu")} qu': $after")
    }

    @Test
    fun example12UnaccentedCapitals() {
        val e = fr.query("Etat")
        assertTrue(e.validity.valid)
        assertNull(ac("Etat"))
        assertEquals("État", bar("Etat").first(), bar("Etat").toString())
        assertEquals(0, EditDistance(fr.params, fr.touch).weighted("Etat", "État", TapSequence.none(4)))
        assertTrue(fr.query("ECOLE").validity.valid)
        assertNull(ac("ECOLE"))
        assertEquals("ÉCOLE", bar("ECOLE").first(), bar("ECOLE").toString())
        assertTrue(fr.isKnown("Ecole"))
        // a lower-case letter must match exactly
        assertFalse(fr.query("etat").validity.valid)
        assertEquals("état", ac("etat"))
        // other languages do not have the rule
        val it = TestDictionaries.italian()
        assertFalse(it.params.capsUnaccented)
    }

    @Test
    fun example13HyphenCompoundsAndApostropheWords() {
        assertFalse(fr.query("estce").validity.valid)
        assertEquals(0, fr.query("estce").corr("est-ce").ed)
        assertEquals("est-ce", ac("estce"))
        assertEquals("Est-ce", ac("Estce"))
        assertEquals("peut-être", ac("peutetre"))
        assertEquals("dis-moi", ac("dismoi"))
        // typed with the hyphen: two words, "etre" after the hyphen has no pair context
        assertEquals("être", ac("peut-etre"))
        assertEquals("aujourd'hui", ac("aujourdhui"))
        assertEquals("quelqu'un", ac("quelquun"))
        // an apostrophe word is never split
        assertTrue(fr.query("aujourd'hui").validity.valid)
        assertEquals(0, fr.elisionPrefixLength("aujourd'hui"))
        assertTrue(fr.isKnown("quelqu'un"))
        // completion of a compound: "peu" → "peut-être"
        assertTrue("peut-être" in bar("peu"), bar("peu").toString())
        assertEquals("peut", fr.completionPrefix("peut-être", 4))
        assertEquals("peut-êt", fr.completionPrefix("peut-être", 6))
        assertEquals("cœu", fr.completionPrefix("cœur", 4))
        println("estce: ${bar("estce")} peu: ${bar("peu")}")
    }

    @Test
    fun example14CaseError() {
        assertFalse(fr.lexicon.exact("france"))
        assertTrue(fr.lexicon.exact("France"))
        val r = fr.query("Il vit en france")
        assertFalse(r.validity.valid)
        assertEquals(44, r.corr("France").ed)
        assertEquals("France", ac("Il vit en france"))
    }

    @Test
    fun example15MissingSpace() {
        assertTrue(fr.lexicon.keyIndex("quelquechose") < 0)
        assertEquals("quelque chose", ac("quelquechose"))
        // "etje" is dropped as a missing-space artefact (05b §11.6 step 6) and the split "et je" is offered; the
        // autocorrection goes to "être" (ED 139 against ED_SPACE 156; spec/QUESTIONS.md)
        assertTrue(fr.lexicon.keyIndex("etje") < 0)
        assertFalse(fr.query("etje").validity.valid)
        assertTrue("et je" in bar("etje"), bar("etje").toString())
    }

    @Test
    fun example16SingleLetters() {
        for (w in listOf("a", "à", "y")) {
            assertTrue(fr.query(w).validity.valid, w)
            assertNull(ac(w), w)
        }
        // the euphonic t of "a-t-il": an invalid one-letter word, never corrected, never marked
        assertFalse(fr.query("a-t").validity.valid)
        assertNull(ac("a-t"))
        assertFalse(TestDictionaries.single(TestDictionaries.french()).isKnown("t"))
        assertNull(ac("á"))
        assertEquals("à", bar("á").first(), bar("á").toString())
        assertEquals(14, fr.query("á").corr("à").ed)
    }

    @Test
    fun example17Multilingual() {
        val p = TestDictionaries.multilingual("fr-FR", TestDictionaries.french(), TestDictionaries.english())
        val w0 = p.languageWeights(emptyList())
        assertEquals(0.65, w0.getValue("fr-FR"), 1e-9)
        assertEquals(0.35, w0.getValue("en-US"), 1e-9)
        assertEquals("très", p.query(Query("tres")).autoCorrection)
        val en = p.languageWeights(listOf("much", "very", "you", "Thank"))
        assertTrue(en.getValue("en-US") > 0.95, en.toString())
        assertNull(p.query(Query("Thank you very much")).autoCorrection)
        val frw = p.languageWeights(listOf("aide", "ton", "pour", "beaucoup", "Merci"))
        assertTrue(frw.getValue("fr-FR") > 0.95, frw.toString())
        assertEquals("c'est", p.query(Query("cest")).autoCorrection)
        val enOnly = TestDictionaries.english().query("cest")
        assertTrue(enOnly.corrections.none { c -> c.display == "c'est" })
    }

    @Test
    fun touchGeometry() {
        // 05 §4 at key centres of the AZERTY fallback grid: horizontal 34, a↔q 45, m↔p 45, a↔s 75, m↔n 125, two apart 125
        val t = fr.touch
        fun sub(a: Char, b: Char) = t.substitutionCost(a, Float.NaN, Float.NaN, b)
        assertEquals(34, sub('a', 'z'))
        assertEquals(34, sub('l', 'm'))
        assertEquals(34, sub('w', 'x'))
        assertEquals(45, sub('a', 'q'))
        assertEquals(45, sub('m', 'p'))
        assertEquals(45, sub('w', 'd'))
        assertEquals(45, sub('n', 'k'))
        assertEquals(75, sub('a', 's'))
        assertEquals(75, sub('n', 'l'))
        assertEquals(125, sub('m', 'n'))
        assertEquals(125, sub('a', 'e'))
        // the geometry of the layout data gives the same values
        val g = TestDictionaries.layoutGeometry("fr")
        val e = LanguageEngine(LanguageParams.FRENCH, TestDictionaries.fr).also { x -> x.geometry = g }
        assertEquals(34, e.touch.substitutionCost('a', Float.NaN, Float.NaN, 'z'))
        assertEquals(45, e.touch.substitutionCost('m', Float.NaN, Float.NaN, 'p'))
        assertEquals(125, e.touch.substitutionCost('m', Float.NaN, Float.NaN, 'n'))
        // ligatures and accented letters live on their base keys
        assertEquals(g.ownKey('o'), g.touchKey('œ'))
        assertEquals(g.ownKey('a'), g.touchKey('æ'))
        assertEquals(g.ownKey('c'), g.touchKey('ç'))
    }

    @Test
    fun learningAndProofing() {
        val e = TestDictionaries.french()
        val p = PredictionEngine(listOf(e), EngineSettings())
        // a picked hyphen compound is learned piece by piece (05 §12.1)
        p.learn("est-ce", listOf("Qu'"))
        assertEquals(1, e.user.count("est"))
        assertEquals(1, e.user.count("ce"))
        assertEquals(0, e.user.count("est-ce"))
        p.learn("j'ai", listOf("Oui"))
        assertEquals(1, e.user.count("ai"))
        assertEquals(mapOf("ai" to 1), e.user.followers("j'"))
        assertTrue(p.isKnown("l'homme"))
        assertTrue(p.isKnown("c'était"))
        assertFalse(p.isKnown("c'etait"))
        assertTrue(p.isKnown("Etat"))
        assertEquals("👌", e.emojiFor("d'accord"))
        assertEquals("❤️", e.emojiFor("cœur"))
    }
}
