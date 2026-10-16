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

/**
 * Validity of a typed word (spec 05 §7) with its validity cost (255 if invalid). [unattested]: valid only through an
 * unattested dictionary key and no learned or contact word (05 §10.1 step 5, 0.6.9).
 */
public data class Validity(val valid: Boolean, val cost: Int, val rule: Int = 0, val unattested: Boolean = false) {
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

    /**
     * True for a prefix candidate (05 §8.2.1 item 3): the display is an elision prefix ("l'"), picked without a trailing
     * space (04 §4.3).
     */
    public var isElisionPrefix: Boolean = false
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
    /** True if the typed word is the letters of an elision prefix and [ranked] starts with that prefix (05 §8.5). */
    public val prefixFirst: Boolean = false,
    /**
     * The step of 05 §10.1 that ended the autocorrection decision: "1", "2", "2a", "3" … "8" ("8" = applied), "-" when
     * no decision was made (corrections only, empty or too long word). Diagnostics of the dictionary evaluation (05c §5);
     * not part of the public API.
     */
    internal val decisionStep: String = "-",
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
        val k = lexicon.frequency(stripElision(word))
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
        fun dictionary(form: String, rule: Int) =
            Validity(true, lm(form, prev, nameField), rule, params.unattestedKeysRule && unattestedLowerCase(form) && !learnedOrContact(word, decap))
        // 1 exact form
        if (lexicon.exact(word)) return dictionary(word, 1)
        if (decap != null && lexicon.exact(decap)) return dictionary(decap, 1)
        // 2 caps form
        if (mode == CaseMode.ALL_CAPS) {
            val canon = lexicon.canonical(word)
            if (canon != null && upperDisplay(canon) == word) return dictionary(canon, 2)
        }
        // 1, 2 with unaccented capitals (CAPS_UNACCENTED, fr): "Etat" for "État", "ECOLE" for "école"
        if (params.capsUnaccented) unaccentedCapitalForm(word, mode, decap != null)?.let {
            return dictionary(it, 1)
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

    /**
     * True if the key of [form] is unattested and its most frequent form (the first stored form) is all lower case
     * (05 §10.1 step 5, 0.6.9): unattested keys written mainly with a capital (names) keep blocking corrections.
     */
    private fun unattestedLowerCase(form: String): Boolean {
        val k = lexicon.keyIndex(form)
        if (!lexicon.isUnattested(k)) return false
        return lexicon.formText(k, 0).none { Character.isUpperCase(it) }
    }

    /** True if [word] (or [decap]) is also a known learned word or a contact name (05 §10.1 step 5, 0.6.9). */
    private fun learnedOrContact(word: String, decap: String?): Boolean =
        user.isKnownLearned(word) || (decap != null && user.isKnownLearned(decap)) ||
            contacts.contains(word) || (decap != null && contacts.contains(decap))

    /**
     * 05 §7 rules 1 and 2 with `CAPS_UNACCENTED`: the stored form whose display (as stored, first letter upper-cased if
     * [firstUpper], all caps in ALL CAPS mode) equals [word] when an upper-case letter of [word] without diacritic may
     * stand for the same capital with a diacritic; null if there is none.
     */
    private fun unaccentedCapitalForm(word: String, mode: CaseMode, firstUpper: Boolean): String? {
        val loose = BooleanArray(word.length) { i ->
            val c = word[i]
            Character.isUpperCase(c) && EditDistance.stripDiacritic(Character.toLowerCase(c)) == Character.toLowerCase(c)
        }
        if (loose.none { it }) return null
        for (k in lexicon.looseKeys(word, loose)) {
            for (f in lexicon.formsOf(k)) {
                if (unaccentedMatch(word, f.text) || (firstUpper && unaccentedMatch(word, capitalizeFirst(f.text))) ||
                    (mode == CaseMode.ALL_CAPS && unaccentedMatch(word, upperDisplay(f.text)))
                ) return f.text
            }
        }
        return null
    }

    private fun unaccentedMatch(word: String, display: String): Boolean {
        if (word.length != display.length || word == display) return false
        for (i in word.indices) {
            val a = word[i]
            val b = display[i]
            if (a == b) continue
            if (!(Character.isUpperCase(a) && Character.isUpperCase(b) &&
                    EditDistance.isUnaccentedCapital(Character.toLowerCase(a), Character.toLowerCase(b)))
            ) return false
        }
        return true
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
     * Length of the language's elision prefix (`ELISION_PREFIXES`, e.g. "d'", "l'", "dell'") at the start of [word], or
     * 0 when the rule does not apply. The prefix is the part up to and including the word's first apostrophe (letters
     * compared case-insensitively, `’` counted as `'`); either [word] is the prefix alone (the length of [word] is
     * returned) or it continues with a letter and is not itself an exact dictionary form.
     */
    public fun elisionPrefixLength(word: String): Int {
        if (params.elisionPrefixes.isEmpty()) return 0
        var a = -1
        for (i in word.indices) if (word[i] == '\'' || word[i] == '’') {
            a = i
            break
        }
        if (a < 1) return 0
        val n = a + 1
        if (Lexicon.normalizePrefix(word.substring(0, n)) !in elisionSet) return 0
        if (word.length == n) return n
        if (!Character.isLetter(word[n])) return 0
        if (lexicon.exact(word) || (startsUpper(word) && lexicon.exact(decapitalize(word)))) return 0
        return n
    }

    private val elisionSet: Set<String> = params.elisionPrefixes.toHashSet()

    /** True if [word] is exactly one of the language's elision prefixes ("l'", "DELL'", "d’"). */
    public fun isElisionPrefix(word: String): Boolean = word.isNotEmpty() && elisionPrefixLength(word) == word.length

    /** [word] without its elided prefix (the remainder the engine works on, 05 §2.2), else [word] itself. */
    public fun stripElision(word: String): String {
        val n = elisionPrefixLength(word)
        return if (n in 1 until word.length) word.substring(n) else word
    }

    /**
     * Previous word for pairs of a word that follows an elided word ("dell'anno" → "anno"), as the pair build counts
     * it (05b §5 step 2, Italian); only in languages whose dictionary stores elision prefixes. Other contexts unchanged.
     */
    internal fun pairPrev(prev: PrevContext): PrevContext {
        if (prev !is PrevContext.Word || !lexicon.hasPrefixTable) return prev
        val n = elisionPrefixLength(prev.word)
        return if (n in 1 until prev.word.length) PrevContext.Word(prev.word.substring(n)) else prev
    }

    /** Context words for learned n-grams: an elided word counts as its prefix and its remainder (prefix-table languages). */
    internal fun contextWords(words: List<String>): List<String> {
        if (!lexicon.hasPrefixTable) return words
        val out = ArrayList<String>(words.size + 2)
        for (w in words) {
            val n = elisionPrefixLength(w)
            if (n in 1 until w.length) {
                out.add(Lexicon.normalizePrefix(w.substring(0, n)))
                out.add(w.substring(n))
            } else {
                out.add(w)
            }
        }
        return out
    }

    /**
     * Followers of an elision prefix (05 §8.2.1): the static pair followers (all stored, at most `PREFIX_FOLLOWERS_MAX`)
     * and the learned followers of the context [prefix] with count ≥ `LEARN_MIN_COUNT`, only those that start with a
     * vowel or "h". Each with its conditional log10 probability P(F | prefix).
     */
    internal class PrefixFollower(val text: String, val log10p: Double)

    private val staticPrefixFollowers = java.util.concurrent.ConcurrentHashMap<String, List<PrefixFollower>>()

    internal fun staticFollowers(prefix: String): List<PrefixFollower> = staticPrefixFollowers.getOrPut(prefix) {
        val pairs = dictionary.pairs ?: return@getOrPut emptyList()
        pairs.followers(prefix, Params.PREFIX_FOLLOWERS_MAX).filter { startsVowelOrH(it.text) }
            .map { PrefixFollower(it.text, LmCost.pairLog10(it.pairClass)) }
    }

    internal fun prefixFollowers(prefix: String): List<PrefixFollower> {
        val stat = staticFollowers(prefix)
        val learned = user.followers(prefix) ?: return stat
        val sum = learned.values.sum().toDouble()
        val seen = stat.mapTo(HashSet()) { toKey(it.text) }
        val out = ArrayList(stat)
        for ((f, c) in learned) {
            if (c < Params.LEARN_MIN_COUNT || !startsVowelOrH(f) || !seen.add(toKey(f))) continue
            out.add(PrefixFollower(f, kotlin.math.log10(c / sum)))
        }
        return out
    }

    /** True if [prefix] has followers at all (static or learned, 05 §2.2 one-letter remainder). */
    private fun prefixHasFollowers(prefix: String): Boolean {
        if ((dictionary.pairs?.followerCount(prefix) ?: 0) > 0) return true
        return user.followers(prefix)?.values?.any { it >= Params.LEARN_MIN_COUNT } == true
    }

    /** log10 p_pre(P′): the probability of the prefix's class with the pair context [prev] (05 §8.2.1, §3.3). */
    internal fun prefixLog10(prefix: String, prev: PrevContext): Double {
        val k = lexicon.prefixClass(prefix)
        if (k < 0) return Double.NEGATIVE_INFINITY
        val pUni = 10.0.pow(unigramLog10(k))
        val key = prev.pairKey ?: return kotlin.math.log10(pUni)
        val b = dictionary.pairs?.bigram(key, prefix) ?: -1
        if (b < 0) return kotlin.math.log10(pUni)
        return kotlin.math.log10(maxOf(pUni, Params.PAIR_LAMBDA * LmCost.pairProbability(b) + (1 - Params.PAIR_LAMBDA) * pUni))
    }

    /**
     * Learns a committed word (05 §12.1): a part with an elided prefix is learned as its remainder with the prefix as
     * context (§2.2); in prefix-table languages elided previous words count as prefix and remainder.
     */
    @JvmOverloads
    public fun learn(word: String, previousWords: List<String>, accepted: Boolean = false) {
        user.learn(word, previousWords, accepted, if (params.elisionPrefixes.isEmpty()) null else ::elisionPrefixLength,
            lexicon.hasPrefixTable)
    }

    /** Validity of the current word of [ctx] (05 §7), of the remainder after an elided prefix (§2.2). */
    public fun typedValidity(ctx: QueryContext): Validity {
        val n = elisionPrefixLength(ctx.word)
        if (n > 0 && n == ctx.word.length) return Validity.INVALID
        val w = if (n > 0) ctx.word.substring(n) else ctx.word
        val prev = if (n > 0) PrevContext.Word(Lexicon.normalizePrefix(ctx.word.substring(0, n))) else pairPrev(ctx.prev)
        val v = validity(w, caseMode(w, ctx.preceding), prev, ctx.nameField)
        return if (n > 0) remainderValidity(ctx.word.substring(0, n), w, v) else v
    }

    /**
     * The one-letter remainder rule of 05 §2.2: after a prefix that has followers at all, a one-letter remainder is valid
     * only if it is one of them ("c'è" valid, "c'e" invalid).
     */
    private fun remainderValidity(prefix: String, remainder: String, v: Validity): Validity {
        if (!v.valid || remainder.length != 1) return v
        val p = Lexicon.normalizePrefix(prefix)
        if (!prefixHasFollowers(p)) return v
        val key = toKey(remainder)
        val known = (dictionary.pairs?.bigram(p, remainder) ?: -1) >= 0 ||
            (user.followers(p)?.any { (f, c) -> c >= Params.LEARN_MIN_COUNT && toKey(f) == key } == true)
        return if (known) v else Validity.INVALID
    }

    /**
     * Runs the candidate generation of §8 and the autocorrection decision of §10.1 for the current word. A current word
     * with an elided prefix (§2.2) is predicted as its remainder with the prefix as previous word; every candidate is the
     * prefix as typed followed by the remainder's candidate. The prefix alone gives the elided words of §2.2 "bare
     * prefix" as completions.
     */
    @JvmOverloads
    public fun suggest(ctx: QueryContext, correctionsOnly: Boolean = false): LanguageSuggestions {
        val n = elisionPrefixLength(ctx.word)
        if (n == 0) {
            val prev = pairPrev(ctx.prev)
            val c = if (prev === ctx.prev) ctx else QueryContext(ctx.before, ctx.word, ctx.wordStart, ctx.charBefore, prev,
                ctx.preceding, ctx.taps, ctx.nameField)
            return suggestWord(c, correctionsOnly, null)
        }
        if (n == ctx.word.length) {
            val ranked = if (correctionsOnly) emptyList() else barePrefixCompletions(ctx)
            return LanguageSuggestions(languageTag, ctx.word, Validity.INVALID, ranked, emptyList(), null)
        }
        val prefix = ctx.word.substring(0, n)
        val sub = QueryContext(
            ctx.before, ctx.word.substring(n), ctx.wordStart + n, ctx.charBefore,
            PrevContext.Word(Lexicon.normalizePrefix(prefix)), ctx.preceding, ctx.taps.drop(n), ctx.nameField,
        )
        val r = suggestWord(sub, correctionsOnly, prefix)
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
            decisionStep = r.decisionStep,
        )
    }

    /**
     * 05 §2.2 "bare prefix": the current word is exactly an elision prefix ("dell'"); the next-word predictions of §11
     * items 1 and 2 with the prefix as previous word and context (no contact names, no filler) as completions
     * "prefix as typed + follower", the follower upper-cased if the prefix is all caps.
     */
    private fun barePrefixCompletions(ctx: QueryContext): List<Candidate> {
        val typed = ctx.word
        val prefix = Lexicon.normalizePrefix(typed)
        val costs = LinkedHashMap<String, Int>()
        fun put(word: String, cost: Int) {
            if (!startsVowelOrH(word)) return
            val old = costs[word]
            if (old == null || cost < old) costs[word] = cost
        }
        val pw = contextWords(ctx.preceding).takeLast(Params.LEARN_CONTEXT - 1).map { toKey(it) } + prefix
        val longest = pw.size
        for (k in longest downTo 1) {
            val followers = user.followers(pw.takeLast(k).joinToString(" ")) ?: continue
            val sum = followers.values.sum().toDouble()
            val weight = if (k == longest) 1.0 else Params.NEXT_BACKOFF
            for ((f, c) in followers) {
                if (c >= Params.LEARN_MIN_COUNT && (user.dictionaryKnows(f) || user.isKnownLearned(f))) put(f, LmCost.cost(weight * c / sum))
            }
        }
        dictionary.pairs?.let { pairs ->
            for (f in pairs.followers(prefix, Params.NEXT_PAIR_FETCH)) {
                put(f.text, LmCost.cost(LmCost.pairProbability(f.pairClass)) + Params.COMP_SURCHARGE)
            }
        }
        // all caps only for a prefix of two or more capitals ("DELL'"; a single capital is a sentence start, "C'" →
        // "C'est", as the case mode of §6 needs two letters for ALL CAPS)
        val letters = typed.filter { Character.isLetter(it) }
        val allCaps = letters.length >= 2 && letters.all { Character.isUpperCase(it) }
        val merged = LinkedHashMap<String, Candidate>()
        for ((w, c) in costs) {
            val d = typed + if (allCaps) upperDisplay(w) else w
            val old = merged[d]
            if (old == null || c < old.completionCost) {
                merged[d] = Candidate(typed + w, d, c - Params.COMP_SURCHARGE).also { it.completionCost = c }
            }
        }
        return merged.values.sortedWith(compareBy<Candidate>({ it.cost }, { it.display })).take(Params.BAR_MAX)
    }

    /** [elidedPrefix]: the prefix as typed when [ctx] is the remainder of an elided word (05 §2.2), else null. */
    private fun suggestWord(ctx: QueryContext, correctionsOnly: Boolean, elidedPrefix: String?): LanguageSuggestions {
        val w = ctx.word
        val len = w.length
        val mode = caseMode(w, ctx.preceding)
        val b = Builder(this, ctx, mode)
        val validity = validity(w, mode, ctx.prev, ctx.nameField).let { v ->
            if (elidedPrefix != null) remainderValidity(elidedPrefix, w, v) else v
        }
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
        val unitsOf = { key: String ->
            EditDistance.units(low, key, accentSwap = params.accentSwap, umlautTranslit = params.umlautTranslit)
        }
        for (m in lexicon.fuzzy(low, maxEdits, Params.FUZZY_POOL, unitsOf)) pool.add(m.form.text)
        if (len == 3) pool.addAll(twoNeighbourSubstitutions(low))
        if ("ss" in low) for (m in lexicon.fuzzy(low.replace("ss", "ß"), 1, 8)) pool.add(m.form.text)
        lexicon.canonical(w)?.let { pool.add(it) }
        for ((lw, _) in user.knownLearnedWords()) {
            if (kotlin.math.abs(lw.length - len) <= maxEdits && unitsOf(toKey(lw)) <= maxEdits) pool.add(lw)
        }
        for (cw in contacts.words) {
            if (kotlin.math.abs(cw.length - len) <= maxEdits && unitsOf(toKey(cw)) <= maxEdits) pool.add(cw)
        }
        for (word in pool) {
            if (hidden(word)) continue
            val d = display(word, mode)
            if (EditDistance.units(w, d, params.capsUnaccented, params.accentSwap, params.umlautTranslit) > maxUnits) continue
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

        // 8.2.1 elision: apostrophe split, elided completions, prefix candidates
        if (elidedPrefix == null && lexicon.hasPrefixTable && w.all { Character.isLetter(it) }) {
            elisionCandidates(w, mode, validity, ctx, b, correctionsOnly)
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
                if (hidden(word)) continue
                val d = display(word, mode)
                val prefix = completionPrefix(d, len) ?: continue
                val u = EditDistance.units(w, prefix, params.capsUnaccented, params.accentSwap, params.umlautTranslit)
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
        var ranked = all.filter { it.cost < Candidate.INF }.sortedWith(compareBy<Candidate>({ it.cost }, { it.display }))
        // letters of an elision prefix with at least two letters ("qu", "jusqu", "dell"): the prefix candidate first (§8.5)
        var prefixFirst = false
        if (elidedPrefix == null && lexicon.hasPrefixTable && low.length >= 2 && isPrefixLetters(low)) {
            val i = ranked.indexOfFirst { it.isElisionPrefix }
            if (i >= 0) {
                ranked = listOf(ranked[i]) + ranked.filterIndexed { j, _ -> j != i }
                prefixFirst = true
            }
        }
        val decision = if (correctionsOnly) null else decideAutocorrection(ctx, w, validity, corrections, b, elidedPrefix != null)
        return LanguageSuggestions(languageTag, w, validity, ranked, corrections, decision?.candidate, prefixFirst,
            decision?.step ?: "-")
    }

    /**
     * 05 §8.2.1 for the typed letters [w]: (1) elision splits as corrections, also for a valid [w]; (2) elided
     * completions for an invalid [w]; (3) the prefix candidate when [w] is the letters of a prefix. Elided words are
     * costed as one word: log10 p = log10 p_pre(P′) + log10 P(F | P′).
     */
    private fun elisionCandidates(w: String, mode: CaseMode, validity: Validity, ctx: QueryContext, b: Builder, correctionsOnly: Boolean) {
        val low = toKey(w)
        val stripped = stripAll(low)
        val ed = editDistance
        for (prefix in params.elisionPrefixes) {
            val letters = prefix.dropLast(1)
            if (!low.startsWith(letters)) continue
            val preLog = prefixLog10(prefix, ctx.prev)
            if (preLog == Double.NEGATIVE_INFINITY) continue
            if (low.length == letters.length) {
                // 3 prefix candidate
                if (correctionsOnly) continue
                val d = display(prefix, mode)
                val l = LmCost.cost(10.0.pow(preLog))
                val c = b.byDisplay[d] ?: Candidate(prefix, d, l).also { b.byDisplay[d] = it }
                c.isElisionPrefix = true
                if (l < c.lm) c.lm = l
                val cost = l + Params.COMP_SURCHARGE
                if (cost < c.completionCost) c.completionCost = cost
                continue
            }
            val rest = stripped.substring(letters.length)
            for (f in prefixFollowers(prefix)) {
                val fs = stripAll(toKey(f.text))
                val split = fs == rest
                val completion = !split && !correctionsOnly && !validity.valid && rest.length >= 2 && fs.length > rest.length &&
                    fs.startsWith(rest)
                if (!split && !completion) continue
                val word = prefix + f.text
                val d = display(word, mode)
                val l = LmCost.cost(10.0.pow(preLog + f.log10p))
                if (split && validity.valid && params.elisionValidSlack != null &&
                    l + ed.weighted(w, d, ctx.taps) > validity.cost + params.elisionValidSlack
                ) continue
                val c = b.byDisplay[d] ?: Candidate(word, d, l).also { b.byDisplay[d] = it }
                if (l < c.lm) {
                    c.lm = l
                    c.word = word
                }
                if (split) {
                    // 1 elision split: a correction candidate (for a valid W only within ELISION_VALID_SLACK, fr)
                    val e = ed.weighted(w, d, ctx.taps)
                    if (l + e < c.correctionCost) {
                        c.correctionCost = l + e
                        c.ed = e
                    }
                } else if (d.length > w.length) {
                    // 2 elided completion (the first len + 1 characters of D)
                    val cost = l + Params.COMP_SURCHARGE + ed.weighted(w, d.substring(0, w.length + 1), ctx.taps)
                    if (cost < c.completionCost) c.completionCost = cost
                }
            }
        }
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

    /** A class-0 dictionary form of a language with `hideClassZero` that is not also a known learned word. */
    internal fun hidden(word: String): Boolean =
        params.hideClassZero && lexicon.exactClass(word) == 0 && !user.isKnownLearned(word)

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
        // 4 "oe"/"ae" → "œ"/"æ" in languages whose PREFIX_ACCENTS list the ligatures (fr)
        if (ligatures && (w.contains("oe", ignoreCase = true) || w.contains("ae", ignoreCase = true))) out.add(withLigatures(w))
        // 5 "ae"/"oe"/"ue" → "ä"/"ö"/"ü" in languages with UMLAUT_TRANSLIT (de); the plain W stays a prefix
        if (params.umlautTranslit) withUmlauts(w)?.let { out.add(it) }
        return out
    }

    /**
     * [w] with every "ae", "oe" and "ue" replaced by ä, ö, ü, the case of the first letter kept ("Ue" → "Ü"); null if
     * [w] has none of them.
     */
    private fun withUmlauts(w: String): String? {
        val sb = StringBuilder(w.length)
        var changed = false
        var i = 0
        while (i < w.length) {
            val a = w[i]
            val la = Character.toLowerCase(a)
            val umlaut = when (la) {
                'a' -> 'ä'
                'o' -> 'ö'
                'u' -> 'ü'
                else -> null
            }
            if (umlaut != null && i + 1 < w.length && Character.toLowerCase(w[i + 1]) == 'e') {
                sb.append(if (Character.isUpperCase(a)) Character.toUpperCase(umlaut) else umlaut)
                changed = true
                i += 2
            } else {
                sb.append(a)
                i++
            }
        }
        return if (changed) sb.toString() else null
    }

    private val ligatures: Boolean = params.prefixAccents.values.any { 'œ' in it || 'æ' in it }

    /** [w] with every "oe" replaced by "œ" and "ae" by "æ", the case of the first letter kept ("Oe" → "Œ"). */
    private fun withLigatures(w: String): String {
        val sb = StringBuilder(w.length)
        var i = 0
        while (i < w.length) {
            val a = w[i]
            val la = Character.toLowerCase(a)
            if (i + 1 < w.length && Character.toLowerCase(w[i + 1]) == 'e' && (la == 'o' || la == 'a')) {
                val lig = if (la == 'o') 'œ' else 'æ'
                sb.append(if (Character.isUpperCase(a)) Character.toUpperCase(lig) else lig)
                i += 2
            } else {
                sb.append(a)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * The completion prefix of §8.3: the shortest beginning of [d] that holds [len] characters when the fillers `'` `.`
     * `-` are not counted and a ligature œ/æ counts as two; null if [d] is too short.
     */
    internal fun completionPrefix(d: String, len: Int): String? {
        var n = 0
        for (i in d.indices) {
            if (n >= len) return d.substring(0, i)
            val c = d[i]
            n += when (Character.toLowerCase(c)) {
                '\'', '.', '-' -> 0
                'œ', 'æ' -> 2
                else -> 1
            }
        }
        return if (n >= len) d else null
    }

    /** True if the lower-cased [low] is the letters of one of the language's elision prefixes without the apostrophe. */
    private fun isPrefixLetters(low: String): Boolean = low in prefixLetters

    private val prefixLetters: Set<String> = params.elisionPrefixes.mapTo(HashSet()) { it.dropLast(1) }

    // ------------------------------------------------------------------ autocorrection (§10.1)

    /** score(c) = lm(c) + AC_EDIT_FACTOR · ed(c) / len. */
    public fun acScore(c: Candidate, len: Int): Int = c.lm + params.acEditFactor * c.ed / len

    /** The autocorrection decision of §10.1 with the step that ended it (diagnostics of 05c §5). */
    private class Decision(val candidate: Candidate?, val step: String)

    private fun decideAutocorrection(
        ctx: QueryContext,
        w: String,
        validity: Validity,
        corrections: List<Candidate>,
        b: Builder,
        elidedRemainder: Boolean,
    ): Decision {
        if (TextRules.blocksAutocorrect(ctx.charBefore)) return Decision(null, "1")
        val len = w.length
        // step 2 does not apply to a one-letter remainder after an elided prefix (05 §2.2)
        if (len == 1 && !elidedRemainder) {
            if (user.isRejected(w)) return Decision(null, "2")
            val fix = params.singleLetterFix[w] ?: return Decision(null, "2")
            val c = b.byDisplay[fix] ?: b.candidate(fix, fix)
            if (c.correctionCost == Candidate.INF) {
                c.ed = 0
            }
            return Decision(c, "2")
        }
        // 2a letters of an elision prefix ("qu", "jusqu", "dell") that are no valid word: never autocorrected
        if (!elidedRemainder && !validity.valid && isPrefixLetters(toKey(w))) return Decision(null, "2a")
        if (w.any { it.isDigit() || TextModel.isOrdinalIndicator(it) } || user.isRejected(w)) return Decision(null, "3")
        val best = corrections.firstOrNull() ?: return Decision(null, "4")
        if (validity.valid) {
            // a word valid only through an unattested key counts as rare unless the correction is unattested too (0.6.9)
            val rare = validity.cost > params.acValidRare || (validity.unattested && !lexicon.isUnattestedWord(best.word))
            if (!(rare && best.lm < params.acValidTarget)) return Decision(null, "5")
        }
        val sb = acScore(best, len)
        if (sb > params.acLimit) return Decision(null, "6")
        val second = corrections.getOrNull(1)
        if (second != null && kotlin.math.abs(sb - acScore(second, len)) <= params.acMargin &&
            !best.display.equals(second.display, ignoreCase = true)
        ) return Decision(null, "7")
        return Decision(best, "8")
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
        val pw = contextWords(preceding).takeLast(Params.LEARN_CONTEXT).map { toKey(it) }
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
        val prev = pairPrev(TextModel.previousWordForPairs(before, end))
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
    public fun emojiFor(word: String): String? = params.emoji[toKey(stripElision(word))]

    /**
     * True if [word] alone is valid (proofing, 05 §16): no context, case mode from the word itself; a word with an
     * elided prefix is known if its remainder is (§2.2).
     */
    public fun isKnown(word: String): Boolean {
        val n = elisionPrefixLength(word)
        if (n > 0 && n == word.length) return false
        val w = if (n > 0) word.substring(n) else word
        val v = validity(w, caseMode(w, emptyList()))
        return if (n > 0) remainderValidity(word.substring(0, n), w, v).valid else v.valid
    }

    private companion object {
        /** Lower-case text with every diacritic removed and the ligatures expanded (œ → oe, æ → ae; 05 §8.2.1). */
        fun stripAll(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                when (c) {
                    'œ' -> sb.append("oe")
                    'æ' -> sb.append("ae")
                    else -> sb.append(EditDistance.stripDiacritic(c))
                }
            }
            return sb.toString()
        }

        /** True if [w] starts with a vowel (a e i o u y, with or without diacritic, œ, æ) or "h" (05 §8.2.1). */
        fun startsVowelOrH(w: String): Boolean {
            if (w.isEmpty()) return false
            val low = Character.toLowerCase(w[0])
            if (low == 'œ' || low == 'æ') return true
            val c = Character.toLowerCase(EditDistance.stripDiacritic(low))
            return c in "aeiouyh"
        }
    }
}
