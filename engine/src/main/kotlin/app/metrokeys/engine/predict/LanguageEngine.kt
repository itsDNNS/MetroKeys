package app.metrokeys.engine.predict

import app.metrokeys.engine.lexicon.Dictionary
import app.metrokeys.engine.lexicon.Lexicon
import app.metrokeys.engine.text.PrevContext
import app.metrokeys.engine.text.TextModel
import app.metrokeys.engine.text.TextRules
import app.metrokeys.engine.text.capitalizeFirst
import app.metrokeys.engine.text.decapitalize
import app.metrokeys.engine.text.isAllCapsWord
import app.metrokeys.engine.text.startsLower
import app.metrokeys.engine.text.startsUpper
import app.metrokeys.engine.text.toKey
import app.metrokeys.engine.text.upperDisplay
import app.metrokeys.engine.touch.KeyboardGeometry
import app.metrokeys.engine.touch.TapSequence
import app.metrokeys.engine.touch.TouchModel
import kotlin.math.floor
import kotlin.math.pow

/** Case mode of the typed word (spec 05 §6). */
public enum class CaseMode { AS_TYPED, FIRST_UPPER, ALL_CAPS }

/** Validity of a typed word (spec 05 §7) with its validity cost (255 if invalid). */
public data class Validity(val valid: Boolean, val cost: Int, val rule: Int = 0) {
    public companion object {
        @JvmField
        public val INVALID: Validity = Validity(false, 255, 0)
    }
}

/** One candidate of a per-language query (05 §8). Costs are [INF] when absent. */
public class Candidate internal constructor(
    word: String,
    /** Display form (shown in the bar). */
    public val display: String,
    lm: Int,
) {
    /** The dictionary/learned/contact word behind the display form. */
    public var word: String = word
        internal set
    public var lm: Int = lm
        internal set
    public var correctionCost: Int = INF
        internal set
    public var completionCost: Int = INF
        internal set

    /** Edit cost of the correction. */
    public var ed: Int = INF
        internal set

    public val cost: Int get() = minOf(correctionCost, completionCost)

    public val isCorrection: Boolean get() = correctionCost <= completionCost

    override fun toString(): String = "$display(lm=$lm, corr=${c(correctionCost)}, comp=${c(completionCost)}, ed=${c(ed)})"

    private fun c(v: Int) = if (v == INF) "-" else v.toString()

    public companion object {
        public const val INF: Int = Int.MAX_VALUE / 4
    }
}

/** Input of one per-language query, derived from the text before the caret. */
public class QueryContext internal constructor(
    public val before: String,
    public val word: String,
    public val wordStart: Int,
    public val charBefore: Char?,
    public val prev: PrevContext,
    /** Words before the current word in the current sentence, oldest first (up to 8). */
    public val preceding: List<String>,
    public val taps: TapSequence,
    public val nameField: Boolean,
) {
    public companion object {
        /** Builds the context for the current word at the end of [before]. */
        @JvmStatic
        @JvmOverloads
        public fun of(before: String, taps: List<app.metrokeys.engine.touch.Tap>? = null, nameField: Boolean = false): QueryContext {
            val start = TextModel.currentWordStart(before)
            val word = before.substring(start)
            return QueryContext(
                before, word, start, if (start > 0) before[start - 1] else null,
                TextModel.previousWordForPairs(before, start),
                TextModel.precedingWords(before, start, Params.LANG_WORDS),
                TapSequence.align(word, taps), nameField,
            )
        }

        /** A context for [word] alone: no text before it, no context, no taps. */
        @JvmStatic
        public fun standalone(word: String): QueryContext =
            QueryContext(word, word, 0, null, PrevContext.None, emptyList(), TapSequence.none(word.length), false)
    }
}

/** Result of a per-language query (before merging). */
public class LanguageSuggestions internal constructor(
    public val languageTag: String,
    public val typed: String,
    public val validity: Validity,
    /** All candidates with a finite cost, by cost then display text (05 §8.5). */
    public val ranked: List<Candidate>,
    /** Candidates that kept a correction cost, by correction cost then text. */
    public val corrections: List<Candidate>,
    /** Autocorrection decided by this language (05 §10.1), or null. */
    public val autoCorrection: Candidate?,
) {
    /** Candidate-bar entries of this language alone (05 §9), without emoji. */
    public fun bar(): List<Candidate> {
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        autoCorrection?.let {
            out.add(it)
            seen.add(it.display)
        }
        for (c in ranked) {
            if (out.size >= Params.BAR_MAX) break
            if (seen.add(c.display)) out.add(c)
        }
        return out
    }
}

/** A next-word prediction of one language. */
public data class NextWord(val text: String, val cost: Int)

/**
 * The prediction engine of one language: language-model costs, validity, candidates, autocorrection decision and
 * next-word prediction (spec 05). Thread-safe for concurrent queries; learning goes through [user].
 */
public class LanguageEngine @JvmOverloads constructor(
    public val params: LanguageParams,
    public val dictionary: Dictionary,
    public val user: UserModel = UserModel(dictionary.lexicon),
) {
    public val languageTag: String get() = params.languageTag
    public val lexicon: Lexicon get() = dictionary.lexicon

    /** A = round(−log10 corpusTokens, 2). */
    public val a: Double = LmCost.constantA(lexicon.corpusTokens)

    /** Bc = round(1 / (S ln 10), 5). */
    public val bc: Double = LmCost.constantBc(lexicon.scale)

    @Volatile
    private var touchModel: TouchModel = TouchModel(KeyboardGeometry.fallback(params.languageTag), params)

    @Volatile
    private var editDistance: EditDistance = EditDistance(params, touchModel)

    @Volatile
    public var contacts: ContactNames = ContactNames.EMPTY

    public var geometry: KeyboardGeometry
        get() = touchModel.geometry
        set(value) {
            val t = TouchModel(value, params)
            editDistance = EditDistance(params, t)
            touchModel = t
        }

    public val touch: TouchModel get() = touchModel

    // ------------------------------------------------------------------ LM cost (§3)

    /** log10 p_uni of frequency class [k]. */
    public fun unigramLog10(k: Int): Double = a + k * bc

    /** log10 p_uni of [word] for the language vote (05 §14.1): unknown → −9. */
    public fun languageLog10(word: String): Double {
        val k = lexicon.frequency(word)
        return if (k < 0) Params.LANG_UNKNOWN else unigramLog10(k)
    }

    /** Static probability of [word] (an exact form, else 0) with the pair context [prev] (§3.1, §3.3). */
    public fun staticProbability(word: String, prev: PrevContext): Double {
        val k = lexicon.exactClass(word)
        if (k < 0) return 0.0
        val pUni = 10.0.pow(unigramLog10(k))
        val key = prev.pairKey ?: return pUni
        val b = dictionary.pairs?.bigram(key, word) ?: -1
        if (b < 0) return pUni
        val pPair = LmCost.pairProbability(b)
        return maxOf(pUni, Params.PAIR_LAMBDA * pPair + (1 - Params.PAIR_LAMBDA) * pUni)
    }

    /** p after the user interpolation of §3.4. */
    public fun probability(word: String, prev: PrevContext): Double {
        val pStatic = staticProbability(word, prev)
        val n = user.count(word)
        val r = user.recentCount(word)
        val c = n + Params.RECENT_FACTOR * r
        if (c == 0) return pStatic
        val lambda = minOf(c * Params.USER_LAMBDA_STEP, Params.USER_LAMBDA_CAP)
        val pUser = c.toDouble() / (user.totalCount + Params.USER_TOTAL_OFFSET)
        return when {
            lexicon.exact(word) -> maxOf(pStatic, lambda * pUser + (1 - lambda) * pStatic)
            user.isKnownLearned(word) -> lambda * pUser
            else -> 0.0
        }
    }

    /** lm(w) of §3.5: min(cost(p), contactCost(w)). */
    @JvmOverloads
    public fun lm(word: String, prev: PrevContext = PrevContext.None, nameField: Boolean = false): Int {
        val c = LmCost.cost(probability(word, prev))
        val contact = contacts.cost(word, nameField)
        return minOf(c, contact)
    }

    // ------------------------------------------------------------------ case and validity (§6, §7)

    public fun caseMode(word: String, preceding: List<String>): CaseMode {
        val twoCaps = preceding.size >= 2 && isAllCapsWord(preceding[preceding.size - 1]) && isAllCapsWord(preceding[preceding.size - 2])
        if (word.length >= 2 && isAllCapsWord(word)) return CaseMode.ALL_CAPS
        if (word.length <= 1 && (word.isEmpty() || Character.isUpperCase(word[0])) && twoCaps) return CaseMode.ALL_CAPS
        if (startsUpper(word)) return CaseMode.FIRST_UPPER
        return CaseMode.AS_TYPED
    }

    public fun display(word: String, mode: CaseMode): String = when (mode) {
        CaseMode.AS_TYPED -> word
        CaseMode.FIRST_UPPER -> capitalizeFirst(word)
        CaseMode.ALL_CAPS -> upperDisplay(word)
    }

    /** Case error of §7: lower-case word whose canonical dictionary form differs and starts upper case. */
    public fun isCaseError(word: String, mode: CaseMode): Boolean {
        if (!startsLower(word) || mode == CaseMode.ALL_CAPS) return false
        val canon = lexicon.canonical(word) ?: return false
        return canon != word && startsUpper(canon)
    }

    @JvmOverloads
    public fun validity(word: String, mode: CaseMode, prev: PrevContext = PrevContext.None, nameField: Boolean = false): Validity {
        if (word.isEmpty()) return Validity.INVALID
        val decap = if (startsUpper(word) && mode != CaseMode.ALL_CAPS) decapitalize(word) else null
        // 1 exact form
        if (lexicon.exact(word)) return Validity(true, lm(word, prev, nameField), 1)
        if (decap != null && lexicon.exact(decap)) return Validity(true, lm(decap, prev, nameField), 1)
        // 2 caps form
        if (mode == CaseMode.ALL_CAPS) {
            val canon = lexicon.canonical(word)
            if (canon != null && upperDisplay(canon) == word) return Validity(true, lm(canon, prev, nameField), 2)
        }
        val caseError = isCaseError(word, mode)
        // 3 learned
        if (!caseError) {
            if (user.isKnownLearned(word)) return Validity(true, lm(word, prev, nameField), 3)
            if (decap != null && user.isKnownLearned(decap)) return Validity(true, lm(decap, prev, nameField), 3)
        }
        // 4 contact names (incl. possessives; a possessive gets the cost of its base name)
        val cn = contacts
        if (cn.contains(word)) return Validity(true, lm(word, prev, nameField), 4)
        if (decap != null && cn.contains(decap)) return Validity(true, lm(decap, prev, nameField), 4)
        val base = when {
            word.endsWith("'s") || word.endsWith("’s") -> word.dropLast(2)
            word.endsWith("'") || word.endsWith("’") -> word.dropLast(1)
            else -> null
        }
        if (base != null && base.isNotEmpty() && cn.contains(base)) return Validity(true, lm(base, prev, nameField), 4)
        // 5 rejected autocorrection
        if (user.isRejected(word)) return Validity(true, lm(word, prev, nameField), 5)
        // 6 compound
        if (!caseError && params.compoundLinks != null) compound(word)?.let { return Validity(true, it, 6) }
        // 7 …in for …ing
        if (params.ingRule && word.length >= 4 && toKey(word).endsWith("in")) {
            val g = if (Character.isUpperCase(word.last())) "G" else "g"
            val wg = word + g
            if (lexicon.exact(wg)) return Validity(true, lm(wg, prev, nameField), 7)
            val d = decapitalize(wg)
            if (lexicon.exact(d)) return Validity(true, lm(d, prev, nameField), 7)
        }
        return Validity.INVALID
    }

    /** Compound rule (05 §7 rule 6): the cost max(lm(head), lm(tail)), or null. */
    private fun compound(word: String): Int? {
        val links = params.compoundLinks ?: return null
        if (word.length < 7 || !word.all { Character.isLetter(it) }) return null
        val low = toKey(word)
        for (k in 3..low.length - 4) {
            val headForm = knownForm(low.substring(0, k)) ?: continue
            val rest = low.substring(k)
            for (link in links) {
                if (!rest.startsWith(link)) continue
                val tail = rest.substring(link.length)
                if (tail.length < 4) continue
                val tailForm = nounForm(tail) ?: continue
                return maxOf(lm(headForm), lm(tailForm))
            }
        }
        return null
    }

    private fun knownForm(low: String): String? {
        if (lexicon.exact(low)) return low
        lexicon.canonical(low)?.let { return it }
        if (user.isKnownLearned(low)) return low
        val cap = capitalizeFirst(low)
        if (user.isKnownLearned(cap)) return cap
        return null
    }

    private fun nounForm(low: String): String? {
        val forms = lexicon.forms(low)
        forms.firstOrNull { startsUpper(it.text) }?.let { return it.text }
        val cap = capitalizeFirst(low)
        if (user.isKnownLearned(cap)) return cap
        return null
    }

    // ------------------------------------------------------------------ candidates (§8)

    private class Builder(val engine: LanguageEngine, val ctx: QueryContext, val mode: CaseMode) {
        val lmCache = HashMap<String, Int>()
        val byDisplay = LinkedHashMap<String, Candidate>()

        fun lm(word: String): Int = lmCache.getOrPut(word) { engine.lm(word, ctx.prev, ctx.nameField) }

        fun candidate(word: String, display: String): Candidate {
            val l = lm(word)
            val c = byDisplay[display]
            if (c == null) {
                val n = Candidate(word, display, l)
                byDisplay[display] = n
                return n
            }
            if (l < c.lm) {
                c.word = word
                c.lm = l
            }
            return c
        }
    }

    // ------------------------------------------------------------------ elided prefix (§2.2, extension)

    /**
     * Length of the language's elided prefix (`ELISION_PREFIXES`, e.g. "d'") at the start of [word], or 0 when the rule
     * does not apply: the prefix matches case-insensitively with `’` counted as `'`, and either [word] is the prefix
     * alone (the length of [word] is returned) or it continues with a letter and is not itself an exact dictionary form.
     */
    public fun elisionPrefixLength(word: String): Int {
        for (p in params.elisionPrefixes) {
            if (word.length < p.length) continue
            var match = true
            for (i in p.indices) {
                var c = Character.toLowerCase(word[i])
                if (c == '’') c = '\''
                if (c != p[i]) {
                    match = false
                    break
                }
            }
            if (!match) continue
            if (word.length == p.length) return p.length
            if (!Character.isLetter(word[p.length])) return 0
            if (lexicon.exact(word) || (startsUpper(word) && lexicon.exact(decapitalize(word)))) return 0
            return p.length
        }
        return 0
    }

    /** [word] without its elided prefix (the remainder the engine works on, 05 §2.2), else [word] itself. */
    public fun stripElision(word: String): String {
        val n = elisionPrefixLength(word)
        return if (n in 1 until word.length) word.substring(n) else word
    }

    /** Validity of the current word of [ctx] (05 §7), of the remainder after an elided prefix (§2.2). */
    public fun typedValidity(ctx: QueryContext): Validity {
        val n = elisionPrefixLength(ctx.word)
        if (n > 0 && n == ctx.word.length) return Validity.INVALID
        val w = if (n > 0) ctx.word.substring(n) else ctx.word
        val prev = if (n > 0) PrevContext.None else ctx.prev
        return validity(w, caseMode(w, ctx.preceding), prev, ctx.nameField)
    }

    /**
     * Runs the candidate generation of §8 and the autocorrection decision of §10.1 for the current word. A current word
     * with an elided prefix (§2.2) is predicted as its remainder without pair context; every candidate is the prefix as
     * typed followed by the remainder's candidate. The prefix alone gives no candidates.
     */
    @JvmOverloads
    public fun suggest(ctx: QueryContext, correctionsOnly: Boolean = false): LanguageSuggestions {
        val n = elisionPrefixLength(ctx.word)
        if (n == 0) return suggestWord(ctx, correctionsOnly)
        if (n == ctx.word.length) return LanguageSuggestions(languageTag, ctx.word, Validity.INVALID, emptyList(), emptyList(), null)
        val prefix = ctx.word.substring(0, n)
        val sub = QueryContext(
            ctx.before, ctx.word.substring(n), ctx.wordStart + n, ctx.charBefore, PrevContext.None, ctx.preceding,
            ctx.taps.drop(n), ctx.nameField,
        )
        val r = suggestWord(sub, correctionsOnly)
        val mapped = HashMap<Candidate, Candidate>()
        fun map(c: Candidate): Candidate = mapped.getOrPut(c) {
            Candidate(c.word, prefix + c.display, c.lm).also {
                it.correctionCost = c.correctionCost
                it.completionCost = c.completionCost
                it.ed = c.ed
            }
        }
        return LanguageSuggestions(
            languageTag, ctx.word, r.validity, r.ranked.map(::map), r.corrections.map(::map), r.autoCorrection?.let(::map),
        )
    }

    private fun suggestWord(ctx: QueryContext, correctionsOnly: Boolean): LanguageSuggestions {
        val w = ctx.word
        val len = w.length
        val mode = caseMode(w, ctx.preceding)
        val b = Builder(this, ctx, mode)
        val validity = validity(w, mode, ctx.prev, ctx.nameField)
        if (len == 0 || len > Params.WORD_MAX_LEN) {
            return LanguageSuggestions(languageTag, w, validity, emptyList(), emptyList(), null)
        }
        val low = toKey(w)
        val maxUnits = maxOf(Params.UNIT_FLOOR, floor(len * Params.UNIT_SLOPE).toInt())
        val maxEdits = if (len <= 3) 1 else 2
        val ed = editDistance
        val taps = ctx.taps

        // 8.1 correction pool
        val pool = LinkedHashSet<String>()
        for (m in lexicon.fuzzy(low, maxEdits, Params.FUZZY_POOL)) pool.add(m.form.text)
        if (len == 3) pool.addAll(twoNeighbourSubstitutions(low))
        if ("ss" in low) for (m in lexicon.fuzzy(low.replace("ss", "ß"), 1, 8)) pool.add(m.form.text)
        lexicon.canonical(w)?.let { pool.add(it) }
        for ((lw, _) in user.knownLearnedWords()) {
            if (kotlin.math.abs(lw.length - len) <= maxEdits && EditDistance.units(low, toKey(lw)) <= maxEdits) pool.add(lw)
        }
        for (cw in contacts.words) {
            if (kotlin.math.abs(cw.length - len) <= maxEdits && EditDistance.units(low, toKey(cw)) <= maxEdits) pool.add(cw)
        }
        for (word in pool) {
            val d = display(word, mode)
            if (EditDistance.units(w, d) > maxUnits) continue
            val e = ed.weighted(w, d, taps)
            val c = b.candidate(word, d)
            val cost = b.lm(word) + e
            if (cost < c.correctionCost) {
                c.correctionCost = cost
                c.ed = e
            }
        }

        // 8.2 missing space: none for compounding languages, at most one split otherwise
        if (params.missingSpaceSplit && !validity.valid && len >= 4 && w.all { Character.isLetter(it) }) {
            var bestText: String? = null
            var bestLm = 0
            for (k in 1..len - 2) {
                val a = w.substring(0, k)
                val aForm = splitHead(a) ?: continue
                val bPart = w.substring(k)
                val bForm = if (lexicon.exact(bPart)) bPart else lexicon.canonical(bPart) ?: continue
                val l = splitLm(aForm, bForm)
                if (bestText == null || l < bestLm) {
                    bestText = "${if (k == 1) splitHeadText(a, aForm) else a} $bForm"
                    bestLm = l
                }
            }
            if (bestText != null) {
                val existing = b.byDisplay[bestText]
                val c = existing ?: Candidate(bestText, bestText, bestLm).also { b.byDisplay[bestText] = it }
                if (bestLm < c.lm) {
                    c.lm = bestLm
                    c.word = bestText
                }
                val cost = bestLm + params.edSpace
                if (cost < c.correctionCost) {
                    c.correctionCost = cost
                    c.ed = params.edSpace
                }
            }
        }

        // 8.3 completions
        if (!correctionsOnly) {
            val words = LinkedHashSet<String>()
            for ((i, prefix) in prefixList(w, taps).withIndex()) {
                val limit = if (i == 0) Params.COMPLETIONS_TYPED else Params.COMPLETIONS_VARIANT
                for (f in lexicon.completions(prefix, limit)) words.add(f.text)
            }
            for ((lw, _) in user.knownLearnedWords()) if (lw.startsWith(w, ignoreCase = true)) words.add(lw)
            for (cw in contacts.words) if (cw.startsWith(w, ignoreCase = true)) words.add(cw)
            for (word in words) {
                val d = display(word, mode)
                if (d.length < len) continue
                val prefix = d.substring(0, len)
                val u = EditDistance.units(w, prefix)
                if (u > maxUnits) continue
                val l = b.lm(word)
                if (l + Params.COMP_EDIT_FACTOR * u / len > Params.COMP_LIMIT) continue
                val cost = l + Params.COMP_SURCHARGE + ed.weighted(w, prefix, taps)
                val c = b.candidate(word, d)
                if (cost < c.completionCost) c.completionCost = cost
            }
        }

        // 8.5 final filtering
        b.byDisplay.remove(w)
        val all = b.byDisplay.values.toList()
        val byCorrection = all.filter { it.correctionCost < Candidate.INF }
            .sortedWith(compareBy<Candidate>({ it.correctionCost }, { it.display }))
        for ((i, c) in byCorrection.withIndex()) {
            if (i >= Params.CORR_KEEP) {
                c.correctionCost = Candidate.INF
                c.ed = Candidate.INF
            }
        }
        val corrections = byCorrection.take(Params.CORR_KEEP)
        val ranked = all.filter { it.cost < Candidate.INF }.sortedWith(compareBy<Candidate>({ it.cost }, { it.display }))
        val ac = if (correctionsOnly) null else decideAutocorrection(ctx, w, validity, corrections, b)
        return LanguageSuggestions(languageTag, w, validity, ranked, corrections, ac)
    }

    /**
     * Exact form of the first part of a split (05 §8.2); a one-letter part only if it is one of the language's
     * `ONE_LETTER_WORDS` (English "a"/"A" and "i"/"I", Spanish "a", "e", "o", "u", "y" in either case).
     */
    private fun splitHead(a: String): String? {
        if (a.length == 1 && toKey(a) !in params.oneLetterWords) return null
        return when {
            lexicon.exact(a) -> a
            lexicon.exact(decapitalize(a)) -> decapitalize(a)
            a.length == 1 -> lexicon.forms(a).firstOrNull()?.text
            else -> null
        }
    }

    /** Text of a one-letter first part: the typed letter, or the dictionary form if only that has a capital ("I"). */
    private fun splitHeadText(a: String, form: String): String =
        if (form != a && a.none { Character.isUpperCase(it) } && form.any { Character.isUpperCase(it) }) form else a

    /**
     * Language cost of the split "[a] [b]" from the static word lists only (05 §8.2): the pair cost if the pair table
     * has (a, b), else floor of the mean static unigram cost plus `SPLIT_SURCHARGE` (`SPLIT_UNKNOWN` for a part missing
     * from the word list).
     */
    public fun splitLm(a: String, b: String): Int {
        val ka = lexicon.exactClass(a)
        val kb = lexicon.exactClass(b)
        if (ka >= 0 && kb >= 0) {
            val pair = dictionary.pairs?.bigram(a, b) ?: -1
            if (pair >= 0) return LmCost.cost(10.0.pow(unigramLog10(ka) + LmCost.pairLog10(pair)))
        }
        val la = if (ka < 0) Params.SPLIT_UNKNOWN else LmCost.cost(10.0.pow(unigramLog10(ka)))
        val lb = if (kb < 0) Params.SPLIT_UNKNOWN else LmCost.cost(10.0.pow(unigramLog10(kb)))
        return Math.floorDiv(la + lb, 2) + Params.SPLIT_SURCHARGE
    }

    /**
     * Extension of 05 §8.1 for three-letter words: dictionary words of length 3 that differ from [low] by exactly two
     * substitutions whose letters are both direct neighbour keys of the typed letters (touch-model neighbours at the
     * key centres), each as its best cased form.
     */
    internal fun twoNeighbourSubstitutions(low: String): List<String> {
        if (low.length != 3) return emptyList()
        val t = touchModel
        val letters = t.geometry.letters
        val near = Array(3) { i ->
            val c = low[i]
            if (t.geometry.touchKey(c) == null) emptyList()
            else letters.filter { it != c && t.proximity(c, Float.NaN, Float.NaN, it) >= 0 }
        }
        val out = LinkedHashSet<String>()
        val chars = low.toCharArray()
        for (i in 0 until 3) for (j in i + 1 until 3) {
            for (a in near[i]) for (b in near[j]) {
                val k = chars.copyOf()
                k[i] = a
                k[j] = b
                val idx = lexicon.keyIndex(String(k))
                if (idx < 0) continue
                val form = lexicon.bestForm(idx).text
                if (form.length == 3) out.add(form)
            }
        }
        return out.toList()
    }

    /** The prefix list of §8.4. */
    internal fun prefixList(w: String, taps: TapSequence): List<String> {
        val out = ArrayList<String>()
        out.add(w)
        val len = w.length
        if (len in 2..Params.VARIANT_MAX_LEN) {
            class Variant(val text: String, val cost: Int, val accent: Boolean = false)
            val variants = ArrayList<Variant>()
            val letters = touchModel.geometry.letters
            for (i in 0 until len) {
                val ch = w[i]
                if (!Character.isLetter(ch)) continue
                val lowc = Character.toLowerCase(ch)
                val upper = Character.isUpperCase(ch)
                val near = ArrayList<Pair<Char, Int>>()
                for (l in letters) {
                    if (l == lowc) continue
                    val v = touchModel.proximity(lowc, taps.x(i), taps.y(i), l)
                    if (v in 0..Params.VARIANT_NEAR_MAX) near.add(l to v)
                }
                near.sortWith(compareBy({ it.second }, { it.first }))
                for ((l, v) in near.take(Params.VARIANT_NEIGHBOURS)) {
                    val r = if (upper) Character.toUpperCase(l) else l
                    variants.add(Variant(w.substring(0, i) + r + w.substring(i + 1), v))
                }
                params.prefixAccents[lowc]?.forEach { acc ->
                    val r = if (upper) Character.toUpperCase(acc) else acc
                    variants.add(Variant(w.substring(0, i) + r + w.substring(i + 1), 0, accent = true))
                }
            }
            for (i in 0 until len - 1) {
                if (w[i] != w[i + 1]) variants.add(Variant(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2), params.edSwap))
            }
            // stable; accent variants (cost 0) come first, so the limit never removes them (05 §1.1 notes on pt-BR)
            variants.sortWith(compareBy<Variant>({ it.cost }, { !it.accent }))
            for (v in variants.take(Params.PREFIX_VARIANTS_MAX)) out.add(v.text)
        }
        if (w.contains("ss", ignoreCase = true)) out.add(w.replace("ss", "ß").replace("SS", "ß").replace("Ss", "ß").replace("sS", "ß"))
        return out
    }

    // ------------------------------------------------------------------ autocorrection (§10.1)

    /** score(c) = lm(c) + AC_EDIT_FACTOR · ed(c) / len. */
    public fun acScore(c: Candidate, len: Int): Int = c.lm + params.acEditFactor * c.ed / len

    private fun decideAutocorrection(
        ctx: QueryContext,
        w: String,
        validity: Validity,
        corrections: List<Candidate>,
        b: Builder,
    ): Candidate? {
        if (TextRules.blocksAutocorrect(ctx.charBefore)) return null
        val len = w.length
        if (len == 1) {
            if (user.isRejected(w)) return null
            val fix = params.singleLetterFix[w] ?: return null
            val c = b.byDisplay[fix] ?: b.candidate(fix, fix)
            if (c.correctionCost == Candidate.INF) {
                c.ed = 0
            }
            return c
        }
        if (w.any { it.isDigit() || TextModel.isOrdinalIndicator(it) } || user.isRejected(w)) return null
        val best = corrections.firstOrNull() ?: return null
        if (validity.valid && !(validity.cost > params.acValidRare && best.lm < params.acValidTarget)) return null
        val sb = acScore(best, len)
        if (sb > params.acLimit) return null
        val second = corrections.getOrNull(1)
        if (second != null && kotlin.math.abs(sb - acScore(second, len)) <= params.acMargin &&
            !best.display.equals(second.display, ignoreCase = true)
        ) return null
        return best
    }

    // ------------------------------------------------------------------ next word (§11)

    /** Next-word predictions after the text [before] (current word empty), by cost then text, merged by display. */
    @JvmOverloads
    public fun predictNext(before: String, nameField: Boolean = false): List<NextWord> {
        val end = before.length
        val preceding = TextModel.precedingWords(before, end, Params.LANG_WORDS)
        val costs = LinkedHashMap<String, Int>()
        fun put(word: String, cost: Int) {
            val old = costs[word]
            if (old == null || cost < old) costs[word] = cost
        }
        // 1 learned n-grams
        val pw = preceding.takeLast(Params.LEARN_CONTEXT).map { toKey(it) }
        val longest = minOf(pw.size, Params.LEARN_CONTEXT)
        for (k in longest downTo 1) {
            val followers = user.followers(pw.takeLast(k).joinToString(" ")) ?: continue
            val sum = followers.values.sum().toDouble()
            val weight = if (k == longest) 1.0 else Params.NEXT_BACKOFF
            for ((f, c) in followers) {
                if (c >= Params.LEARN_MIN_COUNT && (user.dictionaryKnows(f) || user.isKnownLearned(f))) put(f, LmCost.cost(weight * c / sum))
            }
        }
        // 2 static pairs
        val prev = TextModel.previousWordForPairs(before, end)
        val key = prev.pairKey
        val pairs = dictionary.pairs
        if (key != null && pairs != null) {
            for (f in pairs.followers(key, Params.NEXT_PAIR_FETCH)) {
                put(f.text, LmCost.cost(LmCost.pairProbability(f.pairClass)) + Params.COMP_SURCHARGE)
            }
        }
        // 3 contact name runs
        for ((f, c) in contactRunFollowers(before, nameField)) put(f, c)
        // 4 filler
        if (costs.size < Params.BAR_MAX) {
            val floor = (costs.values.maxOrNull() ?: -1) + 1
            for (f in lexicon.completions("", Params.NEXT_FILLER)) {
                put(f.text, maxOf(floor, lm(f.text) + 2 * Params.COMP_SURCHARGE))
            }
        }
        // display
        val twoCaps = preceding.size >= 2 && isAllCapsWord(preceding[preceding.size - 1]) && isAllCapsWord(preceding[preceding.size - 2])
        val mode = when {
            twoCaps -> CaseMode.ALL_CAPS
            TextRules.isSentenceStart(before) -> CaseMode.FIRST_UPPER
            else -> CaseMode.AS_TYPED
        }
        val merged = LinkedHashMap<String, Int>()
        for ((wd, c) in costs) {
            val d = display(wd, mode)
            val old = merged[d]
            if (old == null || c < old) merged[d] = c
        }
        return merged.entries.sortedWith(compareBy({ it.value }, { it.key })).take(Params.BAR_MAX).map { NextWord(it.key, it.value) }
    }

    /** Next name word after the words directly before the caret (05 §13). */
    private fun contactRunFollowers(before: String, nameField: Boolean): Map<String, Int> {
        val cn = contacts
        if (cn.totalCount == 0 || !before.endsWith(" ")) return emptyMap()
        val words = ArrayList<String>()
        var i = before.length - 1
        while (words.size < Params.NAME_RUN_MAX - 1) {
            if (i < 0 || before[i] != ' ') break
            val e = i
            var s = e
            while (s > 0 && (TextModel.isWordChar(before[s - 1]) || before[s - 1] == '-')) s--
            if (s == e) break
            words.add(before.substring(s, e))
            i = s - 1
            if (s == 0) break
        }
        words.reverse()
        for (k in words.size downTo 1) {
            val ctx = words.takeLast(k).joinToString(" ") { toKey(it) }
            val f = cn.followers(ctx) ?: continue
            val sum = f.values.sum()
            val out = LinkedHashMap<String, Int>()
            for ((w, c) in f) {
                out[w] = ContactNames.surcharge(nameField) + Params.COMP_SURCHARGE + LmCost.cost(ContactNames.lambda(c) * c / sum)
            }
            if (out.isNotEmpty()) return out
        }
        return emptyMap()
    }

    /** Emoji for a word (Appendix A), or null. */
    public fun emojiFor(word: String): String? = params.emoji[toKey(word)]

    /**
     * True if [word] alone is valid (proofing, 05 §16): no context, case mode from the word itself; a word with an
     * elided prefix is known if its remainder is (§2.2).
     */
    public fun isKnown(word: String): Boolean {
        val w = stripElision(word)
        return validity(w, caseMode(w, emptyList())).valid
    }
}
