package app.metrokeys.engine.predict

import app.metrokeys.engine.EngineSettings
import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.Query
import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.barTexts
import app.metrokeys.engine.entry
import app.metrokeys.engine.lexicon.Dictionary
import app.metrokeys.engine.lexicon.Lexicon
import app.metrokeys.engine.lexicon.PairTable
import app.metrokeys.engine.lexicon.WordForm
import app.metrokeys.engine.query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules of 05 §8.3–§9 and §10.1 (0.7.1) that are not tied to one worked example: merging and order of the ranked
 * list, the cut of the bar, class-0 typed forms, the do-not-suggest flag and the steps of policy P3.
 */
class PipelineRulesTest {
    private val de = TestDictionaries.german()

    @Test
    fun mergeKeepsTheLowerScoreWithItsKind() {
        // 05 §8.4 example: for "dan" the completion "dann" (lm + 10) scores below the spelling source's correction "dann"
        // (lm + an inserted letter): it stays a completion and cannot become the autocorrection
        val r = de.query("dan")
        val dann = r.entry("dann")
        assertFalse(dann.isCorrection)
        assertEquals(dann.lm + Params.COMP_SURCHARGE, dann.score)
        assertTrue(r.autoCorrection?.display != "dann")
        // one entry per display text
        assertEquals(r.ranked.map { it.display }.distinct(), r.ranked.map { it.display })
    }

    @Test
    fun orderIsScoreThenDescendingText() {
        // 05 §8.4: equal scores in descending code-unit order of the display text
        var ties = 0
        for (q in listOf("Das ist das haus", "Hsus", "das ist scon", "Vielen Da", "ha", "ge", "sch", "Der Gotische")) {
            val r = de.query(q)
            for (i in 1 until r.ranked.size) {
                val a = r.ranked[i - 1]
                val b = r.ranked[i]
                if (r.prefixFirst && i == 1) continue
                assertTrue(a.score <= b.score, "$q: $a $b")
                if (a.score == b.score) {
                    ties++
                    assertTrue(a.display > b.display, "$q: $a before $b")
                }
            }
        }
        assertTrue(ties > 0)
    }

    @Test
    fun scoresAndFilters() {
        // 05 §8.3: score = lm + ed; for a completion ed = ED(W, prefix) + COMP_SURCHARGE; every entry passes the filters
        for (q in listOf("Hsus", "Vielen Da", "das mchte", "Uberpeufe")) {
            val r = de.query(q)
            val w = QueryContext.of(q).word
            val maxUnits = maxOf(Params.UNIT_FLOOR, (w.length * Params.UNIT_SLOPE).toInt())
            for (c in r.ranked) {
                assertEquals(c.lm + c.ed, c.score)
                assertTrue(c.units <= maxUnits, "$q: $c")
                assertTrue(c.lm + Params.COMP_EDIT_FACTOR * c.units / w.length <= Params.COMP_LIMIT, "$q: $c")
                if (!c.isCorrection) assertTrue(c.ed >= Params.COMP_SURCHARGE)
            }
        }
    }

    @Test
    fun cutOfTheBar() {
        // 05 §9: of the first BAR_MAX entries of (autocorrection, ranked list) the typed word and flagged entries are not
        // shown; entries after the first 11 never are, so a valid typed word among them costs a slot
        val r = de.query("Vielen Da")
        assertTrue(r.ranked.size >= Params.BAR_MAX)
        val typedIndex = r.ranked.indexOfFirst { it.isTyped }
        assertTrue(typedIndex in 0 until Params.BAR_MAX)
        assertEquals(Params.BAR_MAX - 1, r.bar().size)
        assertEquals(r.ranked.take(Params.BAR_MAX).filter { !it.isTyped }.map { it.display }, r.barTexts())
        val p = TestDictionaries.single(TestDictionaries.german()).query(Query("Vielen Da"))
        assertEquals(Params.BAR_MAX - 1, p.suggestions.size)
        // with an autocorrection: it is first and counts as one of the eleven
        val h = de.query("Das ist das haus")
        assertEquals("Haus", h.bar().first().display)
        assertTrue(h.bar().size <= Params.BAR_MAX)
    }

    @Test
    fun classZeroTypedForm() {
        // 05 §10.1 step 4: a class-0 form is valid (no spelling mark) but not valid for the decision; its own entry is the
        // typed word, never shown and no correction candidate
        val r = de.query("Ich komme heure")
        assertTrue(r.validity.valid && r.validity.classZero)
        assertTrue(de.isKnown("heure"))
        assertEquals("heute", r.autoCorrection?.display)
        assertTrue(r.ranked.none { it.display == "heure" && !it.isTyped })
        assertTrue("heure" !in r.barTexts())
        // a class-0 form is never offered by the key-slip search
        assertTrue(de.query("heur").barTexts().none { it == "heure" })
        // one undo keeps it for good (§10.4)
        val p = TestDictionaries.single(TestDictionaries.german())
        p.rejectAutoCorrection("heure", "heute", accepted = true)
        assertNull(p.query(Query("Ich komme heure")).autoCorrection)
    }

    @Test
    fun spellingSuggestionsThatAreNoOfferedForm() {
        // 05 §8.1b item 3: a spelling suggestion that is no offered form (class 0 or beyond SUGGEST_MAX_RANK) keeps its
        // spelling and gets lm SPELL_UNKNOWN_LM 300
        var seen = 0
        for (q in listOf("Cursos", "das nict", "Die App ist nicht performamt", "Ich habe hiervon")) {
            for (c in de.query(q).ranked) {
                if (c.source != CandidateSource.SPELLING) continue
                val offered = de.lexicon.isOfferedWord(c.word)
                if (offered) assertTrue(c.lm < Params.SPELL_UNKNOWN_LM, "$q: $c")
                else {
                    assertEquals(Params.SPELL_UNKNOWN_LM, c.lm, "$q: $c")
                    seen++
                }
            }
        }
        assertTrue(seen > 0)
    }

    // ------------------------------------------------------------------ do-not-suggest flag (05 §8, 05b §2.16)

    private fun flaggedEngine(): LanguageEngine {
        val entries = mapOf(
            "kacke" to listOf(WordForm("kacke", 240)),
            "backe" to listOf(WordForm("backe", 150)),
            "die" to listOf(WordForm("die", 250)),
            "ist" to listOf(WordForm("ist", 248)),
            "zzzz" to listOf(WordForm("zzzz", 1)),
        )
        val lex = Lexicon.fromEntries("de-DE", 16.357883f, 5890279, 156169536, entries, flagged = setOf("kacke"))
        val pairs = PairTable.fromTriples(lex, listOf(Triple("die", "kacke", 250), Triple("die", "ist", 200)))
        return LanguageEngine(LanguageParams.GERMAN, Dictionary(lex, pairs))
    }

    @Test
    fun flaggedFormIsNeverOffered() {
        val e = flaggedEngine()
        assertTrue(e.lexicon.isFlaggedWord("kacke"))
        // reachable by a slip (l typed for k): found and scored, but not shown, and it blocks the autocorrection
        val r = e.suggest(QueryContext.of("kacle"))
        val k = r.entry("kacke")
        assertTrue(k.isFlagged)
        assertTrue("kacke" !in r.barTexts())
        assertNull(r.autoCorrection)
        assertTrue(r.decisionStep == "4f" || r.decisionStep == "4t", r.decisionStep)
        // a completion
        assertTrue("kacke" !in e.suggest(QueryContext.of("kac")).barTexts())
        // never a next-word prediction, never a filler
        assertTrue(e.predictNext("die ").none { it.text == "kacke" })
        assertTrue(e.predictNext("").none { it.text == "kacke" })
        // the typed form itself stays valid (no spelling mark)
        assertTrue(e.isKnown("kacke"))
    }

    @Test
    fun flaggedFormTypedByTheUserIsOffered() {
        val e = flaggedEngine()
        repeat(Params.LEARN_MIN_COUNT) { e.learn("kacke", listOf("die")) }
        assertFalse(e.isFlagged("kacke"))
        val r = e.suggest(QueryContext.of("kacle"))
        assertTrue("kacke" in r.barTexts(), r.barTexts().toString())
        assertEquals("kacke", r.autoCorrection?.display)
        assertTrue(e.predictNext("die ").any { it.text == "kacke" })
    }

    @Test
    fun flaggedFormsInTheBundledLists() {
        // 05b §2.16: flags from the English Wiktionary's register labels; a word with a neutral sense is not flagged
        val lex = TestDictionaries.de.lexicon
        assertTrue(lex.flaggedCount in 100..5000, "${lex.flaggedCount}")
        for (w in listOf("Haus", "Schwanz", "Mist", "und")) assertFalse(lex.isFlaggedWord(w), w)
        // a flagged form never appears in a bar of the bundled German engine
        val e = TestDictionaries.single(TestDictionaries.german())
        for (k in 0 until lex.keyCount) {
            if (!lex.isFlagged(k, 0) || !lex.isOffered(k, 0)) continue
            val w = lex.formText(k, 0)
            val p = e.query(Query(w.dropLast(1)))
            assertTrue(p.suggestions.none { it.text == w }, w)
            break
        }
    }

    // ------------------------------------------------------------------ policy P3 (05 §10.1)

    @Test
    fun orthographicCandidatesFirst() {
        // 4o: an orthographic candidate (case, accents, apostrophes) is decided without AC_LIMIT
        assertEquals("4o", de.query("Die App ist nicht performanz").decisionStep)
        // 4m: two orthographic candidates within AC_MARGIN of each other that differ other than in case
        val hut = de.query("hut")
        assertNull(hut.autoCorrection)
        assertEquals("4m", hut.decisionStep)
        // 4t: the typed word itself ranks first among its orthographic candidates
        assertEquals("4t", de.query("das ist schon").decisionStep)
    }

    @Test
    fun omissionAndMissingSpaceGuards() {
        // 7a omission guard: another entry is the typed word with one letter inserted, about as likely as the best
        // correction ("diees": "diese")
        val o = de.query("diees")
        assertNull(o.autoCorrection)
        assertEquals("7o", o.decisionStep)
        assertTrue(o.ranked.any { c -> c.display.length == 6 && c.display.lowercase().let { d -> (0..5).any { d.removeRange(it, it + 1) == "diees" } } })
        // 7a missing-space guard (German, SPLIT_SPACE off): the typed letters are two frequent keys without the space
        val s = de.query("alsein")
        assertNull(s.autoCorrection)
        assertEquals("7s", s.decisionStep)
        assertTrue(de.lexicon.formClass(de.lexicon.keyIndex("als"), 0) >= de.lexicon.guardClass)
        assertTrue(de.lexicon.formClass(de.lexicon.keyIndex("ein"), 0) >= de.lexicon.guardClass)
    }

    /**
     * A synthetic English list for step 7: "walkin" is valid only through the -in rule (05 §7 rule 7: the rare form
     * "walking"); "qalkin" and "ealkin" are one neighbour slip away each (w → q, w → e) and equally frequent. Without
     * "walking" the typed word is unknown.
     */
    private fun runnerUpEngine(withForm: Boolean): LanguageEngine {
        val entries = mutableMapOf(
            "qalkin" to listOf(WordForm("qalkin", 200)),
            "ealkin" to listOf(WordForm("ealkin", 200)),
            "the" to listOf(WordForm("the", 250)),
        )
        if (withForm) entries["walking"] = listOf(WordForm("walking", 3))
        val lex = Lexicon.fromEntries("en-US", 14.8356f, 5890279, 156169536, entries)
        return LanguageEngine(LanguageParams.ENGLISH, Dictionary(lex, PairTable.fromTriples(lex, emptyList())))
    }

    @Test
    fun runnerUpMarginOnlyForValidTypedWords() {
        // 05 §10.1 step 7 (0.7.1 review): a valid (rare) typed word stays when the runner-up is within AC_MARGIN of best
        val ve = runnerUpEngine(withForm = true)
        val valid = ve.suggest(QueryContext.of("walkin"))
        assertTrue(valid.validity.valid && !valid.validity.classZero)
        assertEquals(7, valid.validity.rule)
        assertTrue(valid.validity.cost > LanguageParams.ENGLISH.acValidRare)
        val gap = ve.acScore(valid.entry("qalkin"), "walkin") - ve.acScore(valid.entry("ealkin"), "walkin")
        assertTrue(kotlin.math.abs(gap) <= LanguageParams.ENGLISH.acMargin, "$gap")
        assertNull(valid.autoCorrection)
        // the spelling source offers the valid typed word itself, so it is its own orthographic candidate and the
        // decision ends at step 4t before step 7 (spec/QUESTIONS.md, 0.7.1 review); either way the word stays
        assertTrue(valid.decisionStep == "4t" || valid.decisionStep == "7", valid.decisionStep)
        // the same letters as an unknown word: best is applied although the runner-up is as close
        val ue = runnerUpEngine(withForm = false)
        val unknown = ue.suggest(QueryContext.of("walkin"))
        assertFalse(unknown.validity.valid)
        val ugap = ue.acScore(unknown.entry("qalkin"), "walkin") - ue.acScore(unknown.entry("ealkin"), "walkin")
        assertTrue(kotlin.math.abs(ugap) <= LanguageParams.ENGLISH.acMargin, "$ugap")
        assertTrue(unknown.autoCorrection?.display in setOf("qalkin", "ealkin"), unknown.autoCorrection.toString())
        assertEquals("8", unknown.decisionStep)
        // German examples: an unknown typed word is corrected although the runner-up is within AC_MARGIN
        for ((q, expected) in listOf("Ich habe nir" to "mir", "das ist fue" to "für")) {
            val r = de.query(q)
            assertTrue(!r.validity.valid || r.validity.classZero, q)
            val w = QueryContext.of(q).word
            val best = r.autoCorrection!!
            assertEquals(expected, best.display, q)
            val closest = r.ranked.filter { it !== best && it.isCorrection && !it.isTyped }
                .minOf { kotlin.math.abs(de.acScore(it, w) - de.acScore(best, w)) }
            assertTrue(closest <= de.params.acMargin, "$q: $closest")
        }
        // a class-0 typed form counts as not valid for the decision: no runner-up margin either ("ind": und, ins)
        val ind = de.query("ind")
        assertTrue(ind.validity.classZero)
        assertEquals("und", ind.autoCorrection?.display)
        // no step of any query on unknown or class-0 words ends at 7
        for (q in listOf("Ich habe nir", "das ist fue", "ind", "das mchte", "das ist scon", "ich habe es gesen", "Hsus", "zj")) {
            val r = de.query(q)
            assertTrue(!r.validity.valid || r.validity.classZero, q)
            assertTrue(r.decisionStep != "7", q)
        }
    }

    @Test
    fun omissionGuardNotForTheOmissionReading() {
        // 05 §10.1 step 7a (0.7.1 review): W not valid and best is W with one letter inserted → the guard does not
        // apply ("das mchte" → "möchte", although "machte" is W with one letter inserted and close in lm)
        val m = de.query("das mchte")
        assertFalse(m.validity.valid)
        val best = m.autoCorrection!!
        assertEquals("möchte", best.display)
        assertTrue(LanguageEngine.isOneLetterInserted("mchte", "möchte"))
        val machte = m.entry("machte")
        assertTrue(LanguageEngine.isOneLetterInserted("mchte", "machte"))
        assertTrue(machte.lm <= best.lm + Params.AC_OMISSION_MARGIN)
        // with a best that is no omission reading the guard still applies ("diees": best "diese", "dieses" inserted);
        // "dieses" is a completion, so there is no omission reading to apply and the word stays
        val o = de.query("diees")
        assertEquals("7o", o.decisionStep)
        assertNull(o.autoCorrection)
    }

    @Test
    fun omissionGuardAppliesTheOmissionReadingForUnknownWords() {
        // 05 §10.1 step 7a (0.7.1 review): "Kanst" at the text start: "Knast" (swap) and "Kannst" (omission) have the
        // same AC score, "Knast" ranks first; the guard applies and gives the omission reading "Kannst"
        val k = de.query("Kanst")
        assertFalse(k.validity.valid)
        val w = "Kanst"
        val knast = k.entry("Knast")
        val kannst = k.entry("Kannst")
        assertTrue(knast.isCorrection && kannst.isCorrection)
        assertEquals(de.acScore(knast, w), de.acScore(kannst, w))
        assertTrue(k.ranked.indexOf(knast) < k.ranked.indexOf(kannst))
        assertFalse(LanguageEngine.isOneLetterInserted("kanst", "knast"))
        assertTrue(LanguageEngine.isOneLetterInserted("kanst", "kannst"))
        assertTrue(kannst.lm <= knast.lm + Params.AC_OMISSION_MARGIN)
        assertTrue(de.acScore(kannst, w) <= de.params.acLimit)
        assertEquals("Kannst", k.autoCorrection?.display)
        assertEquals("7r", k.decisionStep)
        // no qualifying correction: the entry with one letter inserted is only a completion ("diees": "dieses"),
        // so the guard still gives none
        val d = de.query("diees")
        assertFalse(d.validity.valid)
        val dieses = d.entry("dieses")
        assertFalse(dieses.isCorrection)
        assertTrue(d.ranked.none { it.isCorrection && LanguageEngine.isOneLetterInserted("diees", it.word.lowercase()) })
        assertNull(d.autoCorrection)
        assertEquals("7o", d.decisionStep)
        // "Aut" → "Auf" is not affected: "Auto" is a completion and is never applied
        val a = de.query("Aut")
        assertFalse(a.entry("Auto").isCorrection)
        assertEquals("Auf", a.autoCorrection?.display)
        // valid typed words are not affected: they stay, and no decision on a valid word applies an omission reading
        for (q in listOf("Knast", "Kunst", "Kamst", "Kants", "Mann", "kam", "mochte", "das ist schon")) {
            val r = de.query(q)
            assertTrue(r.validity.valid && !r.validity.classZero, q)
            assertNull(r.autoCorrection, q)
            assertTrue(r.decisionStep != "7r", q)
        }
    }

    @Test
    fun twoWordCandidatesMustKeepTheTypedLetters() {
        val en = TestDictionaries.english()
        // "ofthe": "of the" keeps every letter: eligible
        assertEquals("of the", en.query("ofthe").autoCorrection?.display)
        // a two-word suggestion that changes letters is an offer only
        val r = en.query("thankyuo")
        assertTrue(r.ranked.any { ' ' in it.display })
        assertFalse(r.autoCorrection?.display.orEmpty().contains(' '))
    }

    @Test
    fun elisionGuardAndUnpairedElision() {
        // 8.2.1 item 1a and the elision guard of step 7a: "détincelle" offers "d'étincelle" and is not changed to
        // "étincelle"
        val fr = TestDictionaries.french()
        val r = fr.query("détincelle")
        assertTrue(r.entry("d'étincelle").isUnpairedElision)
        assertTrue("d'étincelle" in r.barTexts())
        assertNull(r.autoCorrection)
        assertEquals("7e", r.decisionStep)
        // the unpaired elision is not generated when the unelided phrase is in the pair table ("un altro")
        assertTrue(TestDictionaries.italian().query("unaltro").ranked.none { it.display == "un'altro" })
    }

    @Test
    fun acEditWeightsReplaceTheRankingWeightsOnlyInTheDecision() {
        // "Das ist wirklch": the ranking ED of the missing i is ED_INSERT 210, the AC edit distance AC_INSERT 60
        val r = de.query("Das ist wirklch")
        val w = r.autoCorrection!!
        assertEquals(210, w.ed)
        assertEquals(60, w.acEd)
        assertEquals(w.lm + 5 * 60 / 7, w.acScore)
    }

    @Test
    fun multilingualBarDropsTypedAndFlaggedEntries() {
        val m = PredictionEngine(listOf(TestDictionaries.german(), TestDictionaries.english()), EngineSettings(multilingual = true))
        m.activeLanguage = "de-DE"
        val p = m.query(Query("Vielen Da"))
        assertTrue(p.suggestions.none { it.text == "Da" })
        assertEquals("Dank", p.suggestions.first().text)
    }
}
