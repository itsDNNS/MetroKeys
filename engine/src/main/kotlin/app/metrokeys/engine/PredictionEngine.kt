package app.metrokeys.engine

import app.metrokeys.engine.lexicon.Dictionary
import app.metrokeys.engine.predict.Candidate
import app.metrokeys.engine.predict.ContactNameSource
import app.metrokeys.engine.predict.ContactNames
import app.metrokeys.engine.predict.LanguageEngine
import app.metrokeys.engine.predict.LanguageParams
import app.metrokeys.engine.predict.LanguageSuggestions
import app.metrokeys.engine.predict.LearnedWordList
import app.metrokeys.engine.predict.LmCost
import app.metrokeys.engine.predict.Params
import app.metrokeys.engine.predict.QueryContext
import app.metrokeys.engine.swipe.ShapeResult
import app.metrokeys.engine.swipe.ShapeTrace
import app.metrokeys.engine.swipe.ShapeWriter
import app.metrokeys.engine.text.FieldInfo
import app.metrokeys.engine.text.FieldPolicy
import app.metrokeys.engine.text.TextModel
import app.metrokeys.engine.text.TextRules
import app.metrokeys.engine.text.toKey
import app.metrokeys.engine.touch.KeyboardGeometry
import app.metrokeys.engine.touch.Tap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10

/** Settings the engine depends on (07-settings §9.6). */
public data class EngineSettings(
    val suggestions: Boolean = true,
    val autocorrect: Boolean = true,
    val emojiSuggestions: Boolean = true,
    val learning: Boolean = true,
    val multilingual: Boolean = false,
    val shapeWriting: Boolean = true,
)

public enum class SuggestionKind { CORRECTION, COMPLETION, PREDICTION, EMOJI }

/** A candidate-bar entry. [score] = −cost, comparable only within one result. */
public data class Suggestion(
    val text: String,
    val kind: SuggestionKind,
    val score: Int,
    val languageTag: String? = null,
    /** True for the entry that is applied as autocorrection (shown marked). */
    val isAutoCorrection: Boolean = false,
    /** True for an elision prefix ("l'", 05 §8.2.1 item 3): picked without a trailing space (04 §4.3). */
    val isElisionPrefix: Boolean = false,
)

/** Result of a query (05 §2.1). */
public data class Prediction(
    val typed: String,
    val suggestions: List<Suggestion>,
    /** Word that replaces [typed] when an autocorrection trigger is typed, or null. */
    val autoCorrection: String?,
    val typedIsValid: Boolean,
    val autoCorrectionLanguage: String? = null,
) {
    public companion object {
        @JvmField
        public val EMPTY: Prediction = Prediction("", emptyList(), null, false)
    }
}

/** A query: text around the caret, the taps of the current word and the field. */
public data class Query(
    /** Up to ~100 characters before the caret, including the current word. */
    val before: String,
    /** Up to ~20 characters after the caret. */
    val after: String = "",
    /** Taps of the current word (aligned from the end), null if unknown. */
    val taps: List<Tap>? = null,
    val field: FieldInfo = FieldInfo(FieldPolicy.TEXT),
)

/** Persistence of learned data per language (private app storage on Android). */
public interface LearnedDataStore {
    public fun load(languageTag: String): ByteArray?

    public fun save(languageTag: String, data: ByteArray)
}

/**
 * Public entry point of the engine: one [LanguageEngine] per keyboard language, the multilingual merging of
 * spec 05 §14, learning, contacts, proofing and shape writing. Queries may run concurrently on worker threads.
 */
public class PredictionEngine(
    public val languages: List<LanguageEngine>,
    settings: EngineSettings = EngineSettings(),
) {
    init {
        require(languages.isNotEmpty()) { "at least one language" }
    }

    @Volatile
    public var settings: EngineSettings = settings

    /** The language selected with the language key. */
    @Volatile
    public var activeLanguage: String = languages.first().languageTag
        set(value) {
            require(languages.any { it.languageTag == value }) { "unknown language $value" }
            field = value
        }

    private val shapeWriters = HashMap<String, ShapeWriter>()

    public val active: LanguageEngine get() = languages.first { it.languageTag == activeLanguage }

    public fun language(tag: String): LanguageEngine? = languages.firstOrNull { it.languageTag == tag }

    /** True if multilingual typing is in effect (setting on and two or more languages). */
    public val isMultilingual: Boolean get() = settings.multilingual && languages.size >= 2

    private fun runningEngines(): List<LanguageEngine> = if (isMultilingual) languages else listOf(active)

    // ------------------------------------------------------------------ configuration

    /** The letter-page geometry of the current layout (the key sequence of a live swipe, 06 §5.3). */
    public val geometry: KeyboardGeometry get() = languages.first().geometry

    /** Sets the letter-page geometry (all languages use the geometry of the current layout). */
    public fun setGeometry(geometry: KeyboardGeometry) {
        for (l in languages) l.geometry = geometry
    }

    /** Sets the contact names (null or empty = word source off). Every language gets the same names. */
    public fun setContactNames(names: List<String>?) {
        val c = if (names.isNullOrEmpty()) ContactNames.EMPTY else ContactNames.build(names)
        for (l in languages) l.contacts = c
        synchronized(shapeWriters) { shapeWriters.values.forEach { it.invalidateContacts() } }
    }

    public fun refreshContacts(source: ContactNameSource?) {
        setContactNames(source?.contactNames())
    }

    public fun loadLearnedData(store: LearnedDataStore) {
        for (l in languages) l.user.restore(store.load(l.languageTag))
    }

    public fun saveLearnedData(store: LearnedDataStore) {
        for (l in languages) store.save(l.languageTag, l.user.serialize())
    }

    /** "Delete learned words": all learned data of all languages. */
    public fun deleteLearnedData() {
        for (l in languages) l.user.clear()
    }

    // ------------------------------------------------------------------ language weights (§14.1)

    /** Language weights from the words before the current word ([newestFirst], up to 8 are used). */
    public fun languageWeights(newestFirst: List<String>): Map<String, Double> {
        val n = languages.size
        if (n == 1) return mapOf(languages[0].languageTag to 1.0)
        val scores = DoubleArray(n) { i ->
            if (languages[i].languageTag == activeLanguage) ln(Params.LANG_PRIOR) else ln((1 - Params.LANG_PRIOR) / (n - 1))
        }
        var decay = 1.0
        for (word in newestFirst.take(Params.LANG_WORDS)) {
            if (word.any { it.isDigit() }) continue
            val lps = DoubleArray(n) { languages[it].languageLog10(word) }
            val best = lps.max()
            for (i in 0 until n) scores[i] += decay * maxOf(lps[i] - best, -Params.LANG_CLAMP) * ln(10.0)
            decay *= Params.LANG_DECAY
        }
        val max = scores.max()
        val exps = scores.map { exp(it - max) }
        val sum = exps.sum()
        return languages.indices.associate { languages[it].languageTag to exps[it] / sum }
    }

    private fun weightPenalty(w: Double): Double = Params.LM_COST_PER_DECADE * -log10(maxOf(w, Params.LANG_W_MIN))

    /** Language penalty of a shape-writing result (06 §4.1): the class drop that multiplies p by the weight. */
    internal fun shapePenalty(w: Double, e: LanguageEngine): Double =
        ShapeWriter.LM_PER_CLASS * -log10(maxOf(w, Params.LANG_W_MIN)) / e.bc

    // ------------------------------------------------------------------ queries (§2, §9, §11, §14)

    /**
     * Called at the start of every [query] on the calling thread: lets the keyboard (debug builds, tests) check that
     * queries do not run on its UI thread.
     */
    @Volatile
    public var queryObserver: ((Query) -> Unit)? = null

    /** Suggestions for the current word, or next-word predictions when the current word is empty. */
    public fun query(q: Query): Prediction {
        queryObserver?.invoke(q)
        if (TextRules.caretInsideWord(q.after)) return Prediction.EMPTY
        val ctx = QueryContext.of(q.before, q.taps, q.field.nameField)
        if (ctx.word.isEmpty()) return predictNext(q)
        val s = settings
        val policy = q.field.policy
        val engines = runningEngines()
        val len = ctx.word.length
        if (!policy.prediction || len > Params.WORD_MAX_LEN || (!s.suggestions && !s.autocorrect)) {
            val valid = engines.any { it.typedValidity(ctx).valid }
            return Prediction(ctx.word, emptyList(), null, valid)
        }
        val results = engines.map { it.suggest(ctx) }
        val valid = results.any { it.validity.valid }
        val acAllowed = s.autocorrect && policy.autocorrection
        val emojiAllowed = s.emojiSuggestions && policy.emojiSuggestion

        if (engines.size == 1) {
            val r = results[0]
            val ac = if (acAllowed) r.autoCorrection else null
            val bar = ArrayList<Suggestion>()
            val seen = HashSet<String>()
            ac?.let {
                bar.add(Suggestion(it.display, SuggestionKind.CORRECTION, -it.cost.coerceAtMost(9999), r.languageTag, true))
                seen.add(it.display)
            }
            for (c in r.ranked) {
                if (bar.size >= Params.BAR_MAX) break
                if (seen.add(c.display)) bar.add(c.toSuggestion(r.languageTag, -c.cost))
            }
            if (emojiAllowed) appendEmoji(bar, engines[0], ac?.word ?: ctx.word)
            return Prediction(ctx.word, if (s.suggestions) bar else emptyList(), ac?.display, valid, ac?.let { r.languageTag })
        }

        val weights = languageWeights(ctx.preceding.reversed())
        val topIdx = engines.indices.maxBy { weights.getValue(engines[it].languageTag) }
        val top = results[topIdx]
        val topWeight = weights.getValue(top.languageTag)
        var ac: Candidate? = null
        if (acAllowed && topWeight >= Params.LANG_AC_MIN) {
            val x = top.autoCorrection
            if (x != null && !foreignWordBlock(ctx.word, x, engines[topIdx], engines, results, weights)) ac = x
        }
        data class Entry(val c: Candidate, val tag: String, val cost: Double)
        val entries = ArrayList<Entry>()
        for ((i, r) in results.withIndex()) {
            val pen = weightPenalty(weights.getValue(engines[i].languageTag))
            for (c in r.ranked.take(Params.BAR_MAX)) entries.add(Entry(c, r.languageTag, c.cost + pen))
        }
        entries.sortWith(compareBy<Entry>({ it.cost }, { it.c.display }))
        val bar = ArrayList<Suggestion>()
        val seen = HashSet<String>()
        if (ac != null) {
            val pen = weightPenalty(topWeight)
            bar.add(Suggestion(ac.display, SuggestionKind.CORRECTION, -LmCost.roundHalfAway(ac.cost + pen), top.languageTag, true))
            seen.add(ac.display)
        }
        // letters of an elision prefix of the top language ("qu"): its prefix candidate first (05 §8.5)
        if (top.prefixFirst && ac == null) {
            val p = top.ranked[0]
            if (seen.add(p.display)) bar.add(p.toSuggestion(top.languageTag, -LmCost.roundHalfAway(p.cost + weightPenalty(topWeight))))
        }
        for (e in entries) {
            if (bar.size >= Params.BAR_MAX) break
            if (seen.add(e.c.display)) bar.add(e.c.toSuggestion(e.tag, -LmCost.roundHalfAway(e.cost)))
        }
        if (emojiAllowed) appendEmoji(bar, engines[topIdx], ac?.word ?: ctx.word)
        return Prediction(ctx.word, if (s.suggestions) bar else emptyList(), ac?.display, valid, ac?.let { top.languageTag })
    }

    /** Foreign-word block of 05 §14.3. */
    private fun foreignWordBlock(
        typed: String,
        x: Candidate,
        lang: LanguageEngine,
        engines: List<LanguageEngine>,
        results: List<LanguageSuggestions>,
        weights: Map<String, Double>,
    ): Boolean {
        val wl = maxOf(weights.getValue(lang.languageTag), Params.LANG_W_MIN)
        val rhs = lang.languageLog10(x.word) + log10(wl) - Params.LANG_TYPO
        for ((i, v) in engines.withIndex()) {
            if (v === lang || !results[i].validity.valid) continue
            val wv = maxOf(weights.getValue(v.languageTag), Params.LANG_W_MIN)
            if (v.languageLog10(typed) + log10(wv) >= rhs) return true
        }
        return false
    }

    private fun Candidate.toSuggestion(tag: String, score: Int): Suggestion =
        Suggestion(display, if (isCorrection) SuggestionKind.CORRECTION else SuggestionKind.COMPLETION, score, tag,
            isElisionPrefix = isElisionPrefix)

    private fun appendEmoji(bar: MutableList<Suggestion>, engine: LanguageEngine, word: String) {
        val emoji = engine.emojiFor(word) ?: return
        if (bar.size >= Params.BAR_MAX) bar.removeAt(bar.size - 1)
        val score = bar.lastOrNull()?.score ?: -255
        bar.add(Suggestion(emoji, SuggestionKind.EMOJI, score, engine.languageTag))
    }

    /** Next-word predictions (05 §11) for the text before the caret. */
    public fun predictNext(q: Query): Prediction {
        val s = settings
        if (!q.field.policy.prediction || !s.suggestions) return Prediction.EMPTY
        val engines = runningEngines()
        val preceding = TextModel.precedingWords(q.before, q.before.length, Params.LANG_WORDS)
        val bar = ArrayList<Suggestion>()
        val emojiEngine: LanguageEngine
        if (engines.size == 1) {
            for (p in engines[0].predictNext(q.before, q.field.nameField)) {
                bar.add(Suggestion(p.text, SuggestionKind.PREDICTION, -p.cost, engines[0].languageTag))
            }
            emojiEngine = engines[0]
        } else {
            val weights = languageWeights(preceding.reversed())
            data class Entry(val text: String, val tag: String, val cost: Double)
            val entries = ArrayList<Entry>()
            for (e in engines) {
                val pen = weightPenalty(weights.getValue(e.languageTag))
                for (p in e.predictNext(q.before, q.field.nameField)) entries.add(Entry(p.text, e.languageTag, p.cost + pen))
            }
            entries.sortWith(compareBy<Entry>({ it.cost }, { it.text }))
            val seen = HashSet<String>()
            for (e in entries) {
                if (bar.size >= Params.BAR_MAX) break
                if (seen.add(e.text)) bar.add(Suggestion(e.text, SuggestionKind.PREDICTION, -LmCost.roundHalfAway(e.cost), e.tag))
            }
            emojiEngine = engines.maxBy { weights.getValue(it.languageTag) }
        }
        if (s.emojiSuggestions && q.field.policy.emojiSuggestion && preceding.isNotEmpty()) {
            appendEmoji(bar, emojiEngine, preceding.last())
        }
        return Prediction("", bar, null, false)
    }

    /**
     * Correction search for a resumed word (03 §6.4): corrections only, no autocorrection, no completions;
     * the word before the caret is searched with its context.
     */
    public fun resumeCorrections(before: String, field: FieldInfo = FieldInfo(FieldPolicy.TEXT)): List<Suggestion> {
        val ctx = QueryContext.of(before, null, field.nameField)
        if (ctx.word.isEmpty() || ctx.word.length > Params.WORD_MAX_LEN) return emptyList()
        return mergedCorrections(ctx, Params.BAR_MAX)
    }

    /** Up to [limit] correction suggestions for a word alone (spelling-mark suggestions, 05 §16). */
    public fun spellingSuggestions(word: String, limit: Int = 5): List<String> =
        mergedCorrections(QueryContext.standalone(word), limit).map { it.text }

    private fun mergedCorrections(ctx: QueryContext, limit: Int): List<Suggestion> {
        val engines = runningEngines()
        val weights = if (engines.size > 1) languageWeights(ctx.preceding.reversed()) else mapOf(engines[0].languageTag to 1.0)
        data class Entry(val c: Candidate, val tag: String, val cost: Double)
        val entries = ArrayList<Entry>()
        for (e in engines) {
            val pen = if (engines.size > 1) weightPenalty(weights.getValue(e.languageTag)) else 0.0
            for (c in e.suggest(ctx, correctionsOnly = true).corrections) entries.add(Entry(c, e.languageTag, c.correctionCost + pen))
        }
        entries.sortWith(compareBy<Entry>({ it.cost }, { it.c.display }))
        val seen = HashSet<String>()
        val out = ArrayList<Suggestion>()
        for (e in entries) {
            if (out.size >= limit) break
            if (seen.add(e.c.display)) out.add(Suggestion(e.c.display, SuggestionKind.CORRECTION, -LmCost.roundHalfAway(e.cost), e.tag))
        }
        return out
    }

    // ------------------------------------------------------------------ validity / proofing

    /**
     * True if [word] (the word before the caret) is exactly an elision prefix of a loaded language ("l'", "dell'", "d'"):
     * a word swiped after it gets no leading space (06 §5.1 item 1).
     */
    public fun isElisionPrefix(word: String): Boolean = languages.any { it.isElisionPrefix(word) }

    /** True if [word] alone is known in any running language (05 §16, §14.3). */
    public fun isKnown(word: String): Boolean = runningEngines().any { it.isKnown(word) }

    /**
     * Highest frequency class of a dictionary word of any loaded language that starts with [prefix]
     * (case-insensitive), or −1 if no word starts with it. Used for the key choice at touch-down (03 §1.1a).
     */
    public fun prefixClass(prefix: String): Int {
        var best = -1
        for (l in languages) {
            val c = l.lexicon.completions(prefix, 1).firstOrNull()?.frequencyClass ?: continue
            if (c > best) best = c
        }
        return best
    }

    // ------------------------------------------------------------------ learning (§12, §14.4)

    /** Language that learns [word] (05 §14.4). */
    public fun learningLanguage(word: String, previousWords: List<String>): LanguageEngine {
        val engines = runningEngines()
        if (engines.size == 1) return engines[0]
        val parts = word.split(' ', '-').map { TextModel.trimNonWord(it) }.filter { it.isNotEmpty() }
        val knowing = engines.filter { e -> parts.isNotEmpty() && parts.all { e.lexicon.keyIndex(e.stripElision(it)) >= 0 } }
        if (knowing.size == 1) return knowing[0]
        val weights = languageWeights(previousWords.flatMap { it.split(' ') }.filter { it.isNotEmpty() }.reversed())
        val pool = knowing.ifEmpty { engines }
        return pool.maxBy { weights.getValue(it.languageTag) }
    }

    /**
     * A word was committed (05 §12.1); [previousWords] are the up to two words before it, oldest first. [accepted]:
     * the user picked the typed word itself in the candidate bar, which makes an unknown word known (05 §12.5).
     */
    @JvmOverloads
    public fun learn(
        word: String,
        previousWords: List<String>,
        field: FieldInfo = FieldInfo(FieldPolicy.TEXT),
        accepted: Boolean = false,
    ) {
        if (!settings.learning || !field.policy.learning) return
        val target = learningLanguage(word, previousWords)
        // a word with an elided prefix ("d'água", "dell'anno") is learned as its remainder after the prefix (05 §2.2, §12.1)
        target.learn(word, previousWords, accepted)
    }

    /** "Add to dictionary" (05 §12.4). Returns true if the word was added. */
    public fun addWord(word: String, field: FieldInfo = FieldInfo(FieldPolicy.TEXT)): Boolean {
        if (!settings.learning || !field.policy.learning) return false
        val engines = runningEngines()
        val target = engines.firstOrNull { it.lexicon.keyIndex(it.stripElision(word)) >= 0 } ?: active
        return target.user.addWord(target.stripElision(word))
    }

    /**
     * The user rejected the autocorrection [typed] → [correction] (05 §10.4); applies to all languages. [accepted]:
     * the rejection was a pick of the typed word in the candidate bar (05 §12.5), not further editing of the word.
     */
    @JvmOverloads
    public fun rejectAutoCorrection(
        typed: String,
        correction: String,
        field: FieldInfo = FieldInfo(FieldPolicy.TEXT),
        accepted: Boolean = false,
    ) {
        val learn = settings.learning && field.policy.learning
        for (l in languages) l.user.reject(l.stripElision(typed), l.stripElision(correction), learn, accepted)
    }

    /** True if a dictionary of any loaded language knows [word] in some casing. */
    public fun dictionaryKnows(word: String): Boolean = languages.any { it.lexicon.keyIndex(word) >= 0 }

    /**
     * True if [word] (a candidate text, in any case form) can be forgotten (03 §6.3 item 4, 05 §12.5): no loaded
     * dictionary knows it, and a language learned it as a known word.
     */
    public fun isForgettable(word: String): Boolean {
        val w = TextModel.trimNonWord(word)
        if (w.isEmpty() || dictionaryKnows(w)) return false
        val key = toKey(w)
        return languages.any { l -> l.user.knownLearnedWords().any { toKey(it.first) == key } }
    }

    /**
     * Forget(word) of 05 §12.5 in every language: removes all learned data of the word. Dictionary words (known to
     * any loaded dictionary) cannot be forgotten. Returns true if anything was removed.
     */
    public fun forget(word: String): Boolean {
        val w = TextModel.trimNonWord(word)
        if (w.isEmpty() || dictionaryKnows(w)) return false
        var changed = false
        for (l in languages) if (l.user.forget(w)) changed = true
        return changed
    }

    /**
     * The learned words list of 05 §12.5 (settings, 07 §9.13): every stored word that no loaded dictionary knows,
     * with its use counts summed over the languages, sorted alphabetically.
     */
    public fun learnedWords(): List<Pair<String, Int>> =
        LearnedWordList.of(languages.map { it.user }) { keys -> keys.filterTo(HashSet()) { dictionaryKnows(it) } }

    // ------------------------------------------------------------------ shape writing (06)

    private fun shapeWriter(engine: LanguageEngine): ShapeWriter = synchronized(shapeWriters) {
        shapeWriters.getOrPut(engine.languageTag) { ShapeWriter(engine) }
    }

    /**
     * Recognises a shape-writing trace (06 §3–§4) with the text before the caret as context. In multilingual mode
     * every language's index is searched on the current geometry with its own classes and pairs; each language's
     * totals are raised by its weight penalty in frequency classes (06 §4.1: `swLmPerClass × −log10(max(w, 10⁻⁴)) / Bc`)
     * and the results are merged.
     */
    public fun recognizeShape(trace: ShapeTrace, before: String, field: FieldInfo = FieldInfo(FieldPolicy.TEXT)): ShapeResult {
        if (!settings.shapeWriting || !field.policy.shapeWriting) return ShapeResult.NONE
        val engines = runningEngines()
        if (engines.size == 1) return shapeWriter(engines[0]).recognize(trace, before)
        val weights = languageWeights(TextModel.precedingWords(before, before.length, Params.LANG_WORDS).reversed())
        val results = engines.map { e -> e to shapeWriter(e).recognize(trace, before) }
        return ShapeResult.merge(results.map { (e, r) -> r to shapePenalty(weights.getValue(e.languageTag), e) })
    }

    public companion object {
        /** Engine with the bundled dictionaries of the given languages ("de-DE", "en-US"). */
        @JvmStatic
        public fun withBundledDictionaries(tags: List<String>, settings: EngineSettings = EngineSettings()): PredictionEngine =
            PredictionEngine(tags.map { LanguageEngine(LanguageParams.forLanguage(it), Dictionary.loadBundled(it)) }, settings)
    }
}
