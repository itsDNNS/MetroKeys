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
import java.text.Normalizer
import kotlin.math.floor
import kotlin.math.pow

/** Case mode of the typed word (spec 05 §6). */
public enum class CaseMode { AS_TYPED, FIRST_UPPER, ALL_CAPS }

/**
 * Validity of a typed word (spec 05 §7) with its validity cost (255 if invalid). [unattested]: valid only through an
 * unattested dictionary key and no learned or contact word (05 §10.1 step 5, rule off). [classZero]: valid only as a
 * stored form of frequency class 0 (rules 1–2), which counts as not valid for the autocorrection decision (05 §7,
 * §10.1 step 4, 0.7.1).
 */
public data class Validity(
    val valid: Boolean,
    val cost: Int,
    val rule: Int = 0,
    val unattested: Boolean = false,
    val classZero: Boolean = false,
) {
    public companion object {
        @JvmField
        public val INVALID: Validity = Validity(false, 255, 0)
    }
}

/** Where a candidate came from (05 §8; diagnostics of 05c §5). */
public enum class CandidateSource(public val label: String) {
    KEY_SLIP("key-slip search"),
    USER_WORDS("user words"),
    SPELLING("spelling source"),
    TRANSLITERATION("transliteration"),
    SPLIT("split"),
    ELISION("elision"),
    SINGLE_LETTER("single-letter fix"),
}

/** One entry of the ranked list of a per-language query (05 §8.3, §8.4). */
public class Candidate internal constructor(
    /** The dictionary/learned/contact word behind the display form. */
    public val word: String,
    /** Display form (shown in the bar). */
    public val display: String,
    /** lm of 05 §3.5 in the query's context (or the cost given by the source). */
    public val lm: Int,
    /** True for a correction, false for a completion (05 §8). */
    public val isCorrection: Boolean,
    /** ED of the correction, or ED of the completion prefix plus `COMP_SURCHARGE` (05 §8.3). */
    public val ed: Int,
    /** UNITS of 05 §5 (of the completion prefix for a completion). */
    public val units: Int,
    public val source: CandidateSource,
) {
    /** The ranking score lm + ed (05 §8.3; lower is better). */
    public val score: Int get() = lm + ed

    /** Same as [score]. */
    public val cost: Int get() = score

    /**
     * True for a prefix candidate (05 §8.2.1 item 3): the display is an elision prefix ("l'"), picked without a trailing
     * space (04 §4.3).
     */
    public var isElisionPrefix: Boolean = false
        internal set

    /** True for an unpaired elision candidate (05 §8.2.1 item 1a): offered, never applied. */
    public var isUnpairedElision: Boolean = false
        internal set

    /** For an elision split or unpaired elision (05 §8.2.1 items 1, 1a): the display after the prefix, else null. */
    public var elisionRemainder: String? = null
        internal set

    /** True if the form carries the do-not-suggest flag and is not a learned word with count ≥ 2 (05 §8). */
    public var isFlagged: Boolean = false
        internal set

    /** True if the display text equals the typed word: kept in the ranked list, never shown (05 §8.4). */
    public var isTyped: Boolean = false
        internal set

    /** The AC edit distance and AC score of 05 §10.1 step 4 (set for the corrections of the decision). */
    public var acEd: Int = INF
        internal set
    public var acScore: Int = INF
        internal set

    override fun toString(): String = "$display(${if (isCorrection) "corr" else "comp"}, lm=$lm, ed=$ed, score=$score)"

    internal fun copyWithDisplay(newDisplay: String): Candidate =
        Candidate(word, newDisplay, lm, isCorrection, ed, units, source).also {
            it.isElisionPrefix = isElisionPrefix
            it.isUnpairedElision = isUnpairedElision
            it.elisionRemainder = elisionRemainder
            it.isFlagged = isFlagged
            it.isTyped = isTyped
            it.acEd = acEd
            it.acScore = acScore
        }

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
    /** Checked by the long-running parts of the query (05 §2.3); null = never cancelled. */
    public val cancel: CancellationCheck? = null,
) {
    internal fun with(prev: PrevContext = this.prev, word: String = this.word, wordStart: Int = this.wordStart, taps: TapSequence = this.taps) =
        QueryContext(before, word, wordStart, charBefore, prev, preceding, taps, nameField, cancel)

    public companion object {
        /** Builds the context for the current word at the end of [before]. */
        @JvmStatic
        @JvmOverloads
        public fun of(
            before: String,
            taps: List<app.metrokeys.engine.touch.Tap>? = null,
            nameField: Boolean = false,
            cancel: CancellationCheck? = null,
        ): QueryContext {
            val start = TextModel.currentWordStart(before)
            val word = before.substring(start)
            return QueryContext(
                before, word, start, if (start > 0) before[start - 1] else null,
                TextModel.previousWordForPairs(before, start),
                TextModel.precedingWords(before, start, Params.LANG_WORDS),
                TapSequence.align(word, taps), nameField, cancel,
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
    /** The ranked list of 05 §8.4 (the typed word and flagged entries included), after §8.5. */
    public val ranked: List<Candidate>,
    /** Autocorrection decided by this language (05 §10.1), or null. */
    public val autoCorrection: Candidate?,
    /** True if the typed word is the letters of an elision prefix and [ranked] starts with that prefix (05 §8.5). */
    public val prefixFirst: Boolean = false,
    /**
     * The step of 05 §10.1 that ended the autocorrection decision ("1", "2", "2a", "3", "4", "4t", "4f", "4o", "4m",
     * "5", "6", "7", "7e", "7o", "7r", "7s", "8"), "-" when no decision was made. Diagnostics of 05c §5; not public API.
     */
    internal val decisionStep: String = "-",
    /** Node visits of the spelling source (05d §7), for the latency measurements. */
    internal val spellingVisits: Int = 0,
) {
    /** The correction entries of the ranked list without the typed word and flagged entries (05 §16, 03 §6.4). */
    public val corrections: List<Candidate> get() = ranked.filter { it.isCorrection && !it.isTyped && !it.isFlagged }

    /**
     * Candidate-bar entries of this language alone (05 §9), without emoji: the autocorrection first, then the ranked
     * list; of the first `BAR_MAX` entries of that order the typed word and flagged entries are not shown.
     */
    public fun bar(): List<Candidate> {
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        var examined = 0
        autoCorrection?.let {
            out.add(it)
            seen.add(it.display)
            examined++
        }
        for (c in ranked) {
            if (examined >= Params.BAR_MAX) break
            if (c === autoCorrection || (autoCorrection != null && c.display == autoCorrection.display)) continue
            examined++
            if (c.isTyped || c.isFlagged) continue
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

    /** The anchored cost scale of 05 §3.2 of this word list. */
    public val scale: LmScale = LmScale.of(lexicon, a, bc)

    /** The spelling source of 05d (§8.1b). */
    public val spelling: SpellingSource = SpellingSource(lexicon, SpellingParams.forLanguage(params.languageTag))

    @Volatile
    private var touchModel: TouchModel = TouchModel(KeyboardGeometry.fallback(params.languageTag), params)

    @Volatile
    private var editDistance: EditDistance = EditDistance(params, touchModel)

    @Volatile
    private var slipTable: SlipTable = SlipTable(touchModel, params)

    @Volatile
    public var contacts: ContactNames = ContactNames.EMPTY

    public var geometry: KeyboardGeometry
        get() = touchModel.geometry
        set(value) {
            val t = TouchModel(value, params)
            editDistance = EditDistance(params, t)
            slipTable = SlipTable(t, params)
            touchModel = t
        }

    public val touch: TouchModel get() = touchModel

    // ------------------------------------------------------------------ LM cost (§3)

    /** log10 p_uni of frequency class [k]. */
    public fun unigramLog10(k: Int): Double = a + k * bc

    /** cost(p) of 05 §3.2 on this list's scale. */
    public fun cost(p: Double): Int = scale.cost(p)

    /** log10 p_uni of [word] for the language vote (05 §14.1): unknown → −9. */
    public fun languageLog10(word: String): Double {
        val k = lexicon.frequency(stripElision(word))
        return if (k < 0) Params.LANG_UNKNOWN else unigramLog10(k)
    }

    /** Static probability of [word] (an exact form, else 0) with the pair context [prev] (§3.1, §3.3). */
    public fun staticProbability(word: String, prev: PrevContext): Double {
        val k = lexicon.exactClass(word)
        if (k < 0) return 0.0
        val key = prev.pairKey
        val b = if (key == null) -1 else dictionary.pairs?.bigram(key, word) ?: -1
        return staticProbability(k, b)
    }

    private fun staticProbability(k: Int, pairClass: Int): Double {
        val pUni = 10.0.pow(unigramLog10(k))
        if (pairClass < 0) return pUni
        val pPair = LmCost.pairProbability(pairClass)
        return maxOf(pUni, Params.PAIR_LAMBDA * pPair + (1 - Params.PAIR_LAMBDA) * pUni)
    }

    /** p after the user interpolation of §3.4. */
    public fun probability(word: String, prev: PrevContext): Double = interpolate(word, staticProbability(word, prev), lexicon.exact(word))

    private fun interpolate(word: String, pStatic: Double, inDictionary: Boolean): Double {
        val n = user.count(word)
        val r = user.recentCount(word)
        val c = n + Params.RECENT_FACTOR * r
        if (c == 0) return pStatic
        val lambda = minOf(c * Params.USER_LAMBDA_STEP, Params.USER_LAMBDA_CAP)
        val pUser = c.toDouble() / (user.totalCount + Params.USER_TOTAL_OFFSET)
        return when {
            inDictionary -> maxOf(pStatic, lambda * pUser + (1 - lambda) * pStatic)
            user.isKnownLearned(word) -> lambda * pUser
            else -> 0.0
        }
    }

    /** lm(w) of §3.5: min(cost(p), contactCost(w)). */
    @JvmOverloads
    public fun lm(word: String, prev: PrevContext = PrevContext.None, nameField: Boolean = false): Int {
        val c = scale.cost(probability(word, prev))
        val contact = contacts.cost(word, nameField, scale)
        return minOf(c, contact)
    }

    /** lm of a dictionary form of class [k] whose pair class after the previous word is [pairClass] (−1: none). */
    private fun lmOf(word: String, k: Int, pairClass: Int, nameField: Boolean): Int {
        val c = scale.cost(interpolate(word, staticProbability(k, pairClass), true))
        return minOf(c, contacts.cost(word, nameField, scale))
    }

    /** True if [word] carries the do-not-suggest flag and is not a learned word with count ≥ `LEARN_MIN_COUNT` (05 §8). */
    public fun isFlagged(word: String): Boolean = lexicon.isFlaggedWord(word) && user.count(word) < Params.LEARN_MIN_COUNT

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
        fun dictionary(form: String, rule: Int): Validity {
            val zero = lexicon.exactClass(form) == 0 && !learnedOrContact(word, decap) && !user.isRejected(word)
            return Validity(true, lm(form, prev, nameField), rule,
                params.unattestedKeysRule && unattestedLowerCase(form) && !learnedOrContact(word, decap), zero)
        }
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
     * (05 §10.1 step 5, rule off): unattested keys written mainly with a capital (names) keep blocking corrections.
     */
    private fun unattestedLowerCase(form: String): Boolean {
        val k = lexicon.keyIndex(form)
        if (!lexicon.isUnattested(k)) return false
        return lexicon.formText(k, 0).none { Character.isUpperCase(it) }
    }

    /** True if [word] (or [decap]) is also a known learned word or a contact name. */
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
     * 05 §2.1 `typedIsAbbreviation` (0.7.1): the key of [word] (of an elided word, its remainder) carries the
     * abbreviation bit of 05b §1.1. The text rules then do not apply an autocorrection on a typed period (04 §3.2
     * item 9); the decision of 05 §10.1 itself does not depend on it.
     */
    public fun isAbbreviation(word: String): Boolean = word.isNotEmpty() && lexicon.isAbbreviationWord(stripElision(word))

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
     * Runs the candidate generation of §8 and the autocorrection decision of §10.1 for the current word ([correctionsOnly]:
     * no decision). A current word with an elided prefix (§2.2) is predicted as its remainder with the prefix as
     * previous word; every candidate is the prefix as typed followed by the remainder's candidate. The prefix alone gives
     * the elided words of §2.2 "bare prefix" as completions.
     */
    @JvmOverloads
    public fun suggest(ctx: QueryContext, correctionsOnly: Boolean = false): LanguageSuggestions {
        val n = elisionPrefixLength(ctx.word)
        if (n == 0) {
            val prev = pairPrev(ctx.prev)
            return suggestWord(if (prev === ctx.prev) ctx else ctx.with(prev = prev), correctionsOnly, null)
        }
        if (n == ctx.word.length) {
            val ranked = if (correctionsOnly) emptyList() else barePrefixCompletions(ctx)
            return LanguageSuggestions(languageTag, ctx.word, Validity.INVALID, ranked, null)
        }
        val prefix = ctx.word.substring(0, n)
        val sub = ctx.with(prev = PrevContext.Word(Lexicon.normalizePrefix(prefix)), word = ctx.word.substring(n),
            wordStart = ctx.wordStart + n, taps = ctx.taps.drop(n))
        val r = suggestWord(sub, correctionsOnly, prefix)
        val mapped = HashMap<Candidate, Candidate>()
        fun map(c: Candidate): Candidate = mapped.getOrPut(c) { c.copyWithDisplay(prefix + c.display) }
        return LanguageSuggestions(
            languageTag, ctx.word, r.validity, r.ranked.map(::map), r.autoCorrection?.let(::map),
            decisionStep = r.decisionStep, spellingVisits = r.spellingVisits,
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
                if (c >= Params.LEARN_MIN_COUNT && (user.dictionaryKnows(f) || user.isKnownLearned(f))) put(f, scale.cost(weight * c / sum))
            }
        }
        dictionary.pairs?.let { pairs ->
            for (f in pairs.followers(prefix, Params.NEXT_PAIR_FETCH)) {
                if (isFlagged(f.text)) continue
                put(f.text, scale.cost(LmCost.pairProbability(f.pairClass)) + Params.COMP_SURCHARGE)
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
            if (old == null || c < old.score) {
                merged[d] = Candidate(typed + w, d, c - Params.COMP_SURCHARGE, false, Params.COMP_SURCHARGE, 0, CandidateSource.ELISION)
            }
        }
        return merged.values.sortedWith(compareBy<Candidate>({ it.score }, { it.display })).take(Params.BAR_MAX)
    }

    // ------------------------------------------------------------------ candidates (§8)

    /** The candidates of one query before merging (05 §8.3): every entry with its display, kind, lm, ed and units. */
    private inner class Builder(val ctx: QueryContext, val w: String, val mode: CaseMode) {
        val len = w.length
        val maxUnits = maxOf(Params.UNIT_FLOOR, floor(len * Params.UNIT_SLOPE).toInt())
        val ed = editDistance
        private val lmCache = HashMap<String, Int>()
        val all = ArrayList<Candidate>()

        fun lm(word: String): Int = lmCache.getOrPut(word) { this@LanguageEngine.lm(word, ctx.prev, ctx.nameField) }

        private fun units(cand: String): Int = EditDistance.units(w, cand, params.capsUnaccented, params.accentSwap,
            params.umlautTranslit, params.edDiacritic != 0)

        /**
         * Scores and filters one candidate (05 §8.3): a correction against its display [d], a completion against its
         * display [prefix]; returns it if it passes the filters.
         */
        fun add(word: String, d: String, lm: Int, correction: Boolean, prefix: String?, source: CandidateSource, flagged: Boolean): Candidate? {
            val target = if (correction) d else prefix ?: d
            val u = units(target)
            if (u > maxUnits) return null
            if (lm + Params.COMP_EDIT_FACTOR * u / len > Params.COMP_LIMIT) return null
            val e = ed.weighted(w, target, ctx.taps) + if (correction) 0 else Params.COMP_SURCHARGE
            val c = Candidate(word, d, lm, correction, e, u, source)
            c.isFlagged = flagged
            c.isTyped = d == w
            all.add(c)
            return c
        }

        /** Merge by display text (lower score kept, equal: the correction) and order (05 §8.4). */
        fun ranked(): List<Candidate> {
            val by = HashMap<String, Candidate>()
            for (c in all) {
                val old = by[c.display]
                if (old == null || c.score < old.score || (c.score == old.score && c.isCorrection && !old.isCorrection)) by[c.display] = c
            }
            return by.values.sortedWith { x, y ->
                if (x.score != y.score) x.score.compareTo(y.score) else y.display.compareTo(x.display)
            }
        }
    }

    /**
     * Dictionary forms whose lm in this query is below their static cost (05 §8.1): the stored pair followers of the
     * previous word, forms with learned counts and contact words that are dictionary forms.
     */
    private fun specialForms(ctx: QueryContext): List<KeySlipSearch.Special> {
        val out = ArrayList<KeySlipSearch.Special>()
        val seen = HashSet<Long>()
        fun add(k: Int, f: Int, lm: Int) {
            if (k < 0 || f < 0) return
            val id = (k.toLong() shl 8) or f.toLong()
            if (seen.add(id)) out.add(KeySlipSearch.Special(k, f, lm))
        }
        val key = ctx.prev.pairKey
        val pairs = dictionary.pairs
        if (key != null && pairs != null) {
            for (pf in pairs.followers(key, Int.MAX_VALUE)) {
                if (pf.isPrefix) continue
                val f = lexicon.formIndexOf(pf.keyIndex, pf.text)
                if (f < 0) continue
                add(pf.keyIndex, f, lmOf(pf.text, lexicon.formClass(pf.keyIndex, f), pf.pairClass, ctx.nameField))
            }
        }
        for ((word, _) in user.learnedWords(1)) {
            val k = lexicon.keyIndex(word)
            val f = lexicon.formIndexOf(k, word)
            if (f >= 0) add(k, f, lm(word, ctx.prev, ctx.nameField))
        }
        for (word in contacts.words) {
            val k = lexicon.keyIndex(word)
            val f = lexicon.formIndexOf(k, word)
            if (f >= 0) add(k, f, lm(word, ctx.prev, ctx.nameField))
        }
        return out
    }

    /**
     * The key-slip search of 05 §8.1 on [low] (the typed word or a transliterated spelling of it), handed over. A run
     * on a transliterated spelling ([source] `TRANSLITERATION`) hands over a completion only if its form is longer than
     * the typed word (05 §8.1c, 0.7.1): shorter and equally long ones do not continue what was typed and are dropped
     * (the walk on W and the spelling source deliver them as corrections).
     */
    private fun slipCandidates(b: Builder, low: String, specials: List<KeySlipSearch.Special>, source: CandidateSource) {
        val search = KeySlipSearch(lexicon, slipTable, params, scale::classCost)
        for (e in search.search(low, specials, b.ctx.cancel).handOver()) {
            if (e.completion && source == CandidateSource.TRANSLITERATION && e.text.length <= b.w.length) continue
            val d = display(e.text, b.mode)
            val flagged = isFlagged(e.text)
            if (e.completion) {
                val key = if (e.keyIndex >= 0) lexicon.keyString(e.keyIndex) else toKey(e.text)
                val prefix = display(KeySlipSearch.formPrefix(e.text, key, e.prefixKeyLength), b.mode)
                b.add(e.text, d, b.lm(e.text), false, prefix, source, flagged)
            } else {
                b.add(e.text, d, b.lm(e.text), true, null, source, flagged)
            }
        }
    }

    /**
     * 05 §8.1a: the same walk over the learned words that count as known, the dictionary forms with a learned count ≥
     * `LEARN_MIN_COUNT` and the contact words, with its own lists and hand-over; never flagged.
     */
    private fun userWordCandidates(b: Builder, low: String) {
        val words = LinkedHashSet<String>()
        // a learned case error ("haus" for "Haus") never counts as known (§7 rule 3), so it is no user word either
        for ((lw, _) in user.knownLearnedWords()) if (!isCaseError(lw, CaseMode.AS_TYPED)) words.add(lw)
        for (cw in contacts.words) words.add(cw)
        if (words.isEmpty()) return
        val same = SlipList(Params.SLIP_SAME_KEEP)
        val comp = SlipList(Params.SLIP_COMP_KEEP)
        val table = slipTable
        for (word in words) {
            val key = toKey(word)
            val m = KeySlipSearch.matchKey(key, low, table, params.slipFiller) ?: continue
            val l = b.lm(word)
            val cls = maxOf(0, lexicon.exactClass(word))
            val allExact = key == low || (low.none { SlipTable.isFiller(it) } && key.filterNot { SlipTable.isFiller(it) } == low)
            if (m.sameSlip >= 0) same.offer(word, cls, if (allExact) 1 else l + m.sameSlip, false, 0)
            if (m.compSlip >= 0) comp.offer(word, cls, l + m.compSlip, true, m.compPrefix)
        }
        for (e in SlipResult(same, comp).handOver()) {
            val d = display(e.text, b.mode)
            if (e.completion) {
                b.add(e.text, d, b.lm(e.text), false, display(KeySlipSearch.formPrefix(e.text, toKey(e.text), e.prefixKeyLength), b.mode),
                    CandidateSource.USER_WORDS, false)
            } else {
                b.add(e.text, d, b.lm(e.text), true, null, CandidateSource.USER_WORDS, false)
            }
        }
    }

    /** 05 §8.1c: the transliterated spellings of [w] (ß for ss; œ/æ for oe/ae where `SLIP_ACCENTS` list them; umlauts). */
    internal fun transliterations(w: String): List<String> {
        val out = ArrayList<String>()
        if (w.contains("ss", ignoreCase = true)) out.add(replaceDigraphs(w, mapOf("ss" to 'ß')))
        if (ligatures && (w.contains("oe", ignoreCase = true) || w.contains("ae", ignoreCase = true))) {
            out.add(replaceDigraphs(w, mapOf("oe" to 'œ', "ae" to 'æ')))
        }
        if (params.umlautTranslit && (w.contains("ae", true) || w.contains("oe", true) || w.contains("ue", true))) {
            out.add(replaceDigraphs(w, mapOf("ae" to 'ä', "oe" to 'ö', "ue" to 'ü')))
        }
        return out.filter { it != w }.distinct()
    }

    private fun replaceDigraphs(w: String, map: Map<String, Char>): String {
        val sb = StringBuilder(w.length)
        var i = 0
        while (i < w.length) {
            if (i + 1 < w.length) {
                val pair = toKey(w.substring(i, i + 2))
                val r = map[pair]
                if (r != null) {
                    sb.append(if (Character.isUpperCase(w[i]) && r != 'ß') Character.toUpperCase(r) else r)
                    i += 2
                    continue
                }
            }
            sb.append(w[i])
            i++
        }
        return sb.toString()
    }

    private val ligatures: Boolean = params.slipAccents.values.any { 'œ' in it || 'æ' in it }

    /** 05 §8.1b: the first `SPELL_SLOTS` words of the spelling source as corrections. */
    private fun spellingCandidates(b: Builder, validity: Validity): Int {
        val res = spelling.suggest(b.w, validity.valid, b.ctx.cancel)
        for (word in res.words.take(Params.SPELL_SLOTS)) {
            if (word.length == 1 && word[0].isDigit()) continue
            val spaces = word.count { it == ' ' }
            if (spaces > 0) {
                if (!params.missingSpaceSplit || spaces > 1) continue
                val a = word.substringBefore(' ')
                val c = word.substringAfter(' ')
                val l = splitLm(splitPart(a), splitPart(c))
                val flagged = isFlagged(splitPart(a)) || isFlagged(splitPart(c))
                b.add(word, display(word, b.mode), l, true, null, CandidateSource.SPELLING, flagged)
                continue
            }
            val k = lexicon.keyIndex(word)
            var form: String? = null
            if (k >= 0) {
                val fi = lexicon.formIndexOf(k, word)
                form = if (fi >= 0) word else lexicon.formText(k, 0)
            }
            if (form != null && lexicon.isOfferedWord(form)) {
                b.add(form, display(form, b.mode), b.lm(form), true, null, CandidateSource.SPELLING, isFlagged(form))
            } else {
                // no form, or a form that is not offered: the word as suggested with the unknown cost (05 §8.1b item 3)
                val flagged = form != null && isFlagged(form)
                b.add(form ?: word, display(form ?: word, b.mode), Params.SPELL_UNKNOWN_LM, true, null, CandidateSource.SPELLING, flagged)
            }
        }
        return res.visits
    }

    /** A part of a two-word spelling suggestion as a form of the list: as given, else decapitalised. */
    private fun splitPart(p: String): String = if (lexicon.exact(p)) p else decapitalize(p).takeIf { lexicon.exact(it) } ?: p

    /** [elidedPrefix]: the prefix as typed when [ctx] is the remainder of an elided word (05 §2.2), else null. */
    private fun suggestWord(ctx: QueryContext, correctionsOnly: Boolean, elidedPrefix: String?): LanguageSuggestions {
        val w = ctx.word
        val len = w.length
        val mode = caseMode(w, ctx.preceding)
        val validity = validity(w, mode, ctx.prev, ctx.nameField).let { v ->
            if (elidedPrefix != null) remainderValidity(elidedPrefix, w, v) else v
        }
        if (len == 0 || len > Params.WORD_MAX_LEN) {
            return LanguageSuggestions(languageTag, w, validity, emptyList(), null)
        }
        val low = toKey(w)
        val b = Builder(ctx, w, mode)
        val specials = specialForms(ctx)

        // 8.1 key-slip search, 8.1a user words, 8.1c transliterated spellings
        slipCandidates(b, low, specials, CandidateSource.KEY_SLIP)
        userWordCandidates(b, low)
        for (t in transliterations(w)) slipCandidates(b, toKey(t), specials, CandidateSource.TRANSLITERATION)
        // 8.1b spelling source
        val visits = spellingCandidates(b, validity)

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
                val parts = bestText.split(' ')
                b.add(bestText, display(bestText, mode), bestLm, true, null, CandidateSource.SPLIT,
                    parts.any { isFlagged(it) })
            }
        }

        // 8.2.1 elision: apostrophe split, unpaired elision, elided completions, prefix candidates
        if (elidedPrefix == null && lexicon.hasPrefixTable && w.all { Character.isLetter(it) }) {
            elisionCandidates(b, validity)
        }

        // 8.3, 8.4 scoring, filters, merge and order
        var ranked = b.ranked()
        // 8.5 letters of an elision prefix with at least two letters ("qu", "jusqu", "dell"): the prefix candidate first
        var prefixFirst = false
        if (elidedPrefix == null && lexicon.hasPrefixTable && low.length >= 2 && isPrefixLetters(low)) {
            val i = ranked.indexOfFirst { it.isElisionPrefix }
            if (i >= 0) {
                ranked = listOf(ranked[i]) + ranked.filterIndexed { j, _ -> j != i }
                prefixFirst = true
            }
        }
        val decision = if (correctionsOnly) null else decideAutocorrection(ctx, w, validity, ranked, b, elidedPrefix != null)
        return LanguageSuggestions(languageTag, w, validity, ranked, decision?.candidate, prefixFirst, decision?.step ?: "-", visits)
    }

    /**
     * 05 §8.2.1 for the typed letters of [b]: (1) elision splits as corrections, also for a valid word; (1a) the unpaired
     * elision when no follower matched; (2) elided completions for an invalid word; (3) the prefix candidate when the
     * word is the letters of a prefix. Elided words are costed as one word: log10 p = log10 p_pre(P′) + log10 P(F | P′).
     */
    private fun elisionCandidates(b: Builder, validity: Validity) {
        val w = b.w
        val mode = b.mode
        val ctx = b.ctx
        val low = toKey(w)
        val stripped = stripAll(low)
        for (prefix in params.elisionPrefixes) {
            val letters = prefix.dropLast(1)
            if (!low.startsWith(letters)) continue
            val preLog = prefixLog10(prefix, ctx.prev)
            if (preLog == Double.NEGATIVE_INFINITY) continue
            if (low.length == letters.length) {
                // 3 prefix candidate
                val d = display(prefix, mode)
                val l = scale.cost(10.0.pow(preLog))
                b.add(prefix, d, l, false, d, CandidateSource.ELISION, false)?.isElisionPrefix = true
                continue
            }
            val rest = stripped.substring(letters.length)
            var matched = false
            for (f in prefixFollowers(prefix)) {
                val fs = stripAll(toKey(f.text))
                val split = fs == rest
                val completion = !split && !validity.valid && rest.length >= 2 && fs.length > rest.length && fs.startsWith(rest)
                if (!split && !completion) continue
                val word = prefix + f.text
                val d = display(word, mode)
                val l = scale.cost(10.0.pow(preLog + f.log10p))
                if (split) {
                    matched = true
                    // 1 elision split: a correction candidate (for a valid W only within ELISION_VALID_SLACK, fr)
                    if (validity.valid && params.elisionValidSlack != null &&
                        l + b.ed.weighted(w, d, ctx.taps) > validity.cost + params.elisionValidSlack
                    ) continue
                    b.add(word, d, l, true, null, CandidateSource.ELISION, isFlagged(f.text))?.elisionRemainder = d.substring(prefix.length)
                } else if (d.length > w.length) {
                    // 2 elided completion (the first len + 1 characters of D)
                    b.add(word, d, l, false, d.substring(0, w.length + 1), CandidateSource.ELISION, isFlagged(f.text))
                }
            }
            // 1a unpaired elision (offered, never applied)
            if (!matched && rest.length >= 2) {
                val r = low.substring(letters.length)
                val form = if (lexicon.exact(r)) r else lexicon.canonical(r)
                if (form != null && startsVowelYOrH(form)) {
                    val pairs = dictionary.pairs
                    val full = params.elisionFullWords[prefix] ?: listOf(letters)
                    val paired = pairs != null && full.any { pairs.bigram(it, form) >= 0 }
                    if (!paired) {
                        val k = lexicon.exactClass(form)
                        val pre = scale.cost(10.0.pow(unigramLog10(lexicon.prefixClass(prefix))))
                        val stat = if (k < 0) Params.SPLIT_UNKNOWN else scale.classCost(k)
                        val l = Math.floorDiv(pre + stat, 2) + Params.SPLIT_SURCHARGE
                        val d = display(prefix + form, mode)
                        // a valid W keeps it, like an elision split, only within ELISION_VALID_SLACK (fr; spec/QUESTIONS.md)
                        if (validity.valid && params.elisionValidSlack != null &&
                            l + b.ed.weighted(w, d, ctx.taps) > validity.cost + params.elisionValidSlack
                        ) continue
                        b.add(prefix + form, d, l, true, null, CandidateSource.ELISION, isFlagged(form))?.let {
                            it.isUnpairedElision = true
                            it.elisionRemainder = d.substring(prefix.length)
                        }
                    }
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
            if (pair >= 0) return scale.costLog10(unigramLog10(ka) + LmCost.pairLog10(pair))
        }
        val la = if (ka < 0) Params.SPLIT_UNKNOWN else scale.classCost(ka)
        val lb = if (kb < 0) Params.SPLIT_UNKNOWN else scale.classCost(kb)
        return Math.floorDiv(la + lb, 2) + Params.SPLIT_SURCHARGE
    }

    /**
     * The completion prefix of a display text: the shortest beginning of [d] that holds [len] characters when the
     * fillers `'` `.` `-` are not counted and a ligature œ/æ counts as two; null if [d] is too short.
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

    /** The AC score of 05 §10.1 step 4: lm + AC_EDIT_FACTOR · ed_ac / len (ed_ac with the AC edit weights). */
    public fun acScore(c: Candidate, typed: String, taps: TapSequence? = null): Int {
        val e = editDistance.weighted(typed, c.display, taps, ac = true)
        return c.lm + params.acEditFactor * e / maxOf(1, typed.length)
    }

    /** The autocorrection decision of §10.1 with the step that ended it (diagnostics of 05c §5). */
    private class Decision(val candidate: Candidate?, val step: String)

    private fun decideAutocorrection(
        ctx: QueryContext,
        w: String,
        validity: Validity,
        ranked: List<Candidate>,
        b: Builder,
        elidedRemainder: Boolean,
    ): Decision {
        if (TextRules.blocksAutocorrect(ctx.charBefore)) return Decision(null, "1")
        val len = w.length
        // step 2 does not apply to a one-letter remainder after an elided prefix (05 §2.2)
        if (len == 1 && !elidedRemainder) {
            if (user.isRejected(w)) return Decision(null, "2")
            val fix = params.singleLetterFix[w] ?: return Decision(null, "2")
            val c = ranked.firstOrNull { it.display == fix && it.isCorrection }
                ?: Candidate(fix, fix, lm(fix, ctx.prev, ctx.nameField), true, 0, 0, CandidateSource.SINGLE_LETTER)
            return Decision(c, "2")
        }
        // 2a letters of an elision prefix ("qu", "jusqu", "dell") that are no valid word: never autocorrected
        if (!elidedRemainder && !validity.valid && isPrefixLetters(toKey(w))) return Decision(null, "2a")
        if (w.any { it.isDigit() || TextModel.isOrdinalIndicator(it) } || user.isRejected(w)) return Decision(null, "3")
        // 4 class-0 typed forms count as not valid; their own entry is no correction candidate. The entry of the typed
        // word's key includes its other class-0 case forms ("zi" → "Zi", the all-exact key of §8.1): the whole key is
        // the real-word typo (05b §3.6), so "zi" + space gives "zu" (05 §17.7a; spec/QUESTIONS.md, 0.7.1 review)
        val valid = validity.valid && !validity.classZero
        val wKey = letterKey(w)
        val ownKey = toKey(w)
        fun ownEntry(c: Candidate): Boolean =
            c.isTyped || (toKey(c.word) == ownKey && lexicon.exactClass(c.word) == 0)
        val corr = ranked.filter { c ->
            c.isCorrection && !c.isUnpairedElision && !(validity.classZero && ownEntry(c)) &&
                (' ' !in c.display || letterKey(c.display.replace(" ", "")) == wKey)
        }
        if (corr.isEmpty()) return Decision(null, "4")
        // 4o orthographic candidates first
        val ortho = corr.filter { ' ' !in it.display && keepsTypedLetters(w, it.display) }
        if (ortho.isNotEmpty()) {
            val best = ortho[0]
            if (best.isTyped) return Decision(null, "4t")
            if (best.isFlagged) return Decision(null, "4f")
            if (valid && !(rareValid(validity, best) && best.lm < params.acValidTarget)) return Decision(null, "5")
            val second = ortho.getOrNull(1)
            if (second != null && kotlin.math.abs(second.score - best.score) <= params.acMargin &&
                !best.display.equals(second.display, ignoreCase = true)
            ) return Decision(null, "4m")
            return Decision(best, "4o")
        }
        for (c in corr) {
            c.acEd = editDistance.weighted(w, c.display, ctx.taps, ac = true)
            c.acScore = c.lm + params.acEditFactor * c.acEd / len
        }
        val order = corr.withIndex().sortedWith(compareBy({ it.value.acScore }, { it.index })).map { it.value }
        val best = order[0]
        if (best.isTyped) return Decision(null, "4t")
        if (best.isFlagged) return Decision(null, "4f")
        // 5 valid word
        if (valid && !(rareValid(validity, best) && best.lm < params.acValidTarget)) return Decision(null, "5")
        // 6 limit
        if (best.acScore > params.acLimit) return Decision(null, "6")
        // 7 runner-up: only for a typed word that is valid for the decision; an unknown word or a class-0 form never
        // stays because two corrections are close (owner decision 0.7.1 review)
        val second = order.getOrNull(1)
        if (valid && second != null && kotlin.math.abs(best.acScore - second.acScore) <= params.acMargin &&
            !best.display.equals(second.display, ignoreCase = true)
        ) return Decision(null, "7")
        // 7a guards, only for a best entry that differs from W in more than case and diacritics
        if (foldText(best.display) != foldText(w)) {
            if (lexicon.hasPrefixTable) {
                val bl = toKey(best.display)
                if (ranked.any { it.elisionRemainder != null && toKey(it.elisionRemainder!!) == bl }) return Decision(null, "7e")
            }
            val wl = toKey(w)
            // omission guard; not applied when W is not valid for the decision and best itself is W with one letter
            // inserted: best is then already the omission reading (0.7.1 review)
            val omissionLm = best.lm + Params.AC_OMISSION_MARGIN
            val bestIsOmission = !valid && isOneLetterInserted(wl, toKey(best.display))
            if (!bestIsOmission &&
                ranked.any { it !== best && isOneLetterInserted(wl, toKey(it.display)) && it.lm <= omissionLm }
            ) {
                // for a W that is not valid for the decision the guard applies the omission reading: the first
                // correction in AC-score order that is W with one letter inserted, within the guard's lm margin and
                // AC_LIMIT ("Kanst" → "Kannst" although "Knast" ranks first); none only if there is no such entry
                // (0.7.1 review)
                if (!valid) {
                    val reading = order.firstOrNull {
                        it !== best && !it.isTyped && !it.isFlagged && isOneLetterInserted(wl, toKey(it.display)) &&
                            it.lm <= omissionLm && it.acScore <= params.acLimit
                    }
                    if (reading != null) return Decision(reading, "7r")
                }
                return Decision(null, "7o")
            }
            if (!params.missingSpaceSplit && splitsIntoFrequentKeys(wl)) return Decision(null, "7s")
        }
        return Decision(best, "8")
    }

    /**
     * §10.1 step 5: the valid typed word counts as rare when its cost is above `AC_VALID_RARE`, or (rule switched off by
     * the owner decision of 0.7.0, `UNATTESTED_KEYS_RULE`) when it is valid only through an unattested key and [best] is
     * not unattested itself.
     */
    private fun rareValid(validity: Validity, best: Candidate): Boolean =
        validity.cost > params.acValidRare || (validity.unattested && !lexicon.isUnattestedWord(best.word))

    /** Missing-space guard (§10.1 step 7a): [low] cut into two parts of ≥ 2 letters, both keys whose best form has the guard class. */
    private fun splitsIntoFrequentKeys(low: String): Boolean {
        if (low.length < 4) return false
        val g = lexicon.guardClass
        for (k in 2..low.length - 2) {
            val a = lexicon.keyIndex(low.substring(0, k))
            if (a < 0 || lexicon.formClass(a, 0) < g) continue
            val c = lexicon.keyIndex(low.substring(k))
            if (c >= 0 && lexicon.formClass(c, 0) >= g) return true
        }
        return false
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
        // 1 learned n-grams (offered whatever their flag)
        val pw = contextWords(preceding).takeLast(Params.LEARN_CONTEXT).map { toKey(it) }
        val longest = minOf(pw.size, Params.LEARN_CONTEXT)
        for (k in longest downTo 1) {
            val followers = user.followers(pw.takeLast(k).joinToString(" ")) ?: continue
            val sum = followers.values.sum().toDouble()
            val weight = if (k == longest) 1.0 else Params.NEXT_BACKOFF
            for ((f, c) in followers) {
                if (c >= Params.LEARN_MIN_COUNT && (user.dictionaryKnows(f) || user.isKnownLearned(f))) put(f, scale.cost(weight * c / sum))
            }
        }
        // 2 static pairs (flagged forms skipped)
        val prev = pairPrev(TextModel.previousWordForPairs(before, end))
        val key = prev.pairKey
        val pairs = dictionary.pairs
        if (key != null && pairs != null) {
            for (f in pairs.followers(key, Params.NEXT_PAIR_FETCH)) {
                if (isFlagged(f.text)) continue
                put(f.text, scale.cost(LmCost.pairProbability(f.pairClass)) + Params.COMP_SURCHARGE)
            }
        }
        // 3 contact name runs
        for ((f, c) in contactRunFollowers(before, nameField)) put(f, c)
        // 4 filler (flagged forms skipped)
        if (costs.size < Params.BAR_MAX) {
            val floor = (costs.values.maxOrNull() ?: -1) + 1
            for (f in lexicon.completions("", Params.NEXT_FILLER, skipFlagged = true)) {
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
                out[w] = ContactNames.surcharge(nameField) + Params.COMP_SURCHARGE + scale.cost(ContactNames.lambda(c) * c / sum)
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

    internal companion object {
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

        /** 05 §8.2.1 item 1a: the first letter, diacritics removed, is a vowel, y or h. */
        fun startsVowelYOrH(w: String): Boolean = startsVowelOrH(w)

        private fun decompose(c: Char): Char {
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            return if (d.isEmpty()) c else d[0]
        }

        /**
         * Letter units of 05 §10.1 step 4o: each character lower-cased with its case, ß written "ss", œ "oe", æ "ae",
         * diacritics removed (canonical decomposition), the characters `'` `’` `-` `.` dropped.
         */
        private fun letterUnits(s: String, keys: StringBuilder, upper: MutableList<Boolean>) {
            for (ch in s) {
                if (ch == '\'' || ch == '’' || ch == '-' || ch == '.') continue
                val up = Character.isUpperCase(ch)
                when (val l = Character.toLowerCase(ch)) {
                    'ß' -> { keys.append("ss"); upper.add(up); upper.add(up) }
                    'œ' -> { keys.append("oe"); upper.add(up); upper.add(up) }
                    'æ' -> { keys.append("ae"); upper.add(up); upper.add(up) }
                    else -> { keys.append(decompose(l)); upper.add(up) }
                }
            }
        }

        /** The letter key of 05 §10.1 step 4o. */
        fun letterKey(s: String): String {
            val sb = StringBuilder()
            letterUnits(s, sb, ArrayList())
            return sb.toString()
        }

        /**
         * 05 §10.1 step 4o: [cand] keeps every typed letter of [w]: equal letter keys and, the fillers dropped, the same
         * case at every aligned letter except the first.
         */
        fun keepsTypedLetters(w: String, cand: String): Boolean {
            val ka = StringBuilder()
            val ua = ArrayList<Boolean>()
            letterUnits(w, ka, ua)
            val kb = StringBuilder()
            val ub = ArrayList<Boolean>()
            letterUnits(cand, kb, ub)
            if (ka.toString() != kb.toString()) return false
            for (i in 1 until ua.size) if (ua[i] != ub[i]) return false
            return true
        }

        /** Lower case with diacritics removed (05 §10.1 step 7a: "more than case and diacritics"). */
        fun foldText(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) sb.append(EditDistance.stripDiacritic(Character.toLowerCase(c)))
            return sb.toString()
        }

        /** True if [longer] is [w] with exactly one letter inserted (both lower-cased). */
        fun isOneLetterInserted(w: String, longer: String): Boolean {
            if (longer.length != w.length + 1) return false
            var i = 0
            while (i < w.length && w[i] == longer[i]) i++
            if (!Character.isLetter(longer[i])) return false
            return longer.regionMatches(i + 1, w, i, w.length - i)
        }
    }
}
