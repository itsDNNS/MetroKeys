package app.metrokeys.engine.eval

import app.metrokeys.engine.EngineSettings
import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.Query
import app.metrokeys.engine.QueryDiagnostics
import app.metrokeys.engine.lexicon.Dictionary
import app.metrokeys.engine.predict.LanguageEngine
import app.metrokeys.engine.predict.LanguageParams
import app.metrokeys.engine.predict.Params
import app.metrokeys.engine.predict.QueryContext
import app.metrokeys.engine.text.FieldInfo
import app.metrokeys.engine.text.FieldPolicy
import app.metrokeys.engine.text.toKey
import app.metrokeys.engine.touch.KeyboardGeometry
import app.metrokeys.engine.touch.Tap
import java.io.File

/** The evaluated data of one language: its word list and pair table (files of the evaluated build) and test inputs. */
internal class LanguageData(
    val tag: String,
    val wordsFile: File,
    val pairsFile: File,
    val lexicon: EvalLexicon,
    val provenance: Provenance?,
    val layoutFile: File,
) {
    val dictionary: Dictionary by lazy { Dictionary.load(wordsFile.inputStream(), pairsFile.inputStream()) }
    val layoutJson: String by lazy { layoutFile.readText() }
}

/** The outcome of one item (05c §5): the result's autocorrection and bar, with the diagnostics of the test build. */
internal class Outcome(
    val item: EvalItem,
    val language: String,
    val typed: String,
    /** The typed text is valid in the evaluated word list (05 §7 rules 1–2): collision bucket, not counted (§3.5). */
    val collision: Boolean,
    /** The typed key is stored with class 0 (F2z, §3.7). */
    val classZero: Boolean,
    val autoCorrection: String?,
    val bar: List<String>,
    val diagnostics: QueryDiagnostics?,
)

/** Error set (expected autocorrection) or false-positive set (§6): E8 is a false-positive check in German. */
internal fun isErrorSet(set: String, language: String): Boolean =
    (set.startsWith("E") && !(set == "E8" && language == "de-DE")) || set == "R"

/**
 * One profile (05c §5): the languages that run, the selected language, the settings (suggestions and autocorrection
 * on, emoji off, learning off, normal text field, no learned data, no contacts) and the letter page of the selected
 * language. Queries never change state, so items may run in any order and on any thread.
 */
internal class ProfileRunner(val profile: String, val selected: LanguageData, languages: List<LanguageData>) {
    private val engines = languages.map { LanguageEngine(LanguageParams.forLanguage(it.tag), it.dictionary) }
    val engine = PredictionEngine(
        engines,
        EngineSettings(suggestions = true, autocorrect = true, emojiSuggestions = false, learning = false,
            multilingual = languages.size > 1, shapeWriting = false),
    ).also {
        it.activeLanguage = selected.tag
        it.setGeometry(KeyboardGeometry.fromLayoutJson(selected.layoutJson, "text.letters"))
    }
    private val geometry = engine.geometry
    private val field = FieldInfo(FieldPolicy.TEXT)

    /** Taps at the centres of the keys of the typed letters (05c §3.4); characters without a key are unknown. */
    fun taps(word: String): List<Tap> = word.map { ch ->
        val k = if (Character.isLetter(ch)) geometry.touchKey(ch) else null
        if (k == null) Tap.unknown(ch) else Tap(ch, k.centerX, k.centerY)
    }

    fun run(item: EvalItem, language: String): Outcome {
        val ctx = QueryContext.of(item.before)
        val typed = ctx.word
        val (p, d) = engine.queryDiagnosed(Query(item.before, "", taps(typed), field))
        val le = engine.language(language) ?: error("language $language not in profile $profile")
        val v = le.typedValidity(ctx)
        val error = isErrorSet(item.set, language)
        val collision = error && (v.valid && (v.rule == 1 || v.rule == 2) ||
            item.set in VARIANT_COLLISION_SETS && CaseAccentFolds.of(le.lexicon).otherKey(typed, item.expected))
        val k = le.lexicon.keyIndex(le.stripElision(typed))
        val classZero = k >= 0 && le.lexicon.formClass(k, 0) == 0
        return Outcome(item, language, typed, collision, classZero, p.autoCorrection,
            p.suggestions.take(Params.BAR_MAX).map { it.text }, d)
    }
}

/** The sets whose items are collisions when the typed text is a case or accent variant of another key (05c §3.5). */
internal val VARIANT_COLLISION_SETS = setOf("E1", "E2", "E2a", "E3", "E4", "E9")

/**
 * 05c §3.5 (0.7.0 amendment): the keys of an evaluated word list by their fold (lower case, diacritics removed as in E5:
 * canonical decomposition without combining marks, ß → ss, œ → oe, æ → ae). An E1–E4, E2a or E9 item whose typed text
 * has the fold of a key other than its target is a case or accent variant of that word: a collision, not an error item.
 * Built from the evaluated list at run time; the test sets do not change.
 */
internal class CaseAccentFolds private constructor(private val lexicon: app.metrokeys.engine.lexicon.Lexicon) {
    /** fold -> key index, or MANY when several keys share the fold. */
    private val byFold = HashMap<String, Int>(lexicon.keyCount * 2)

    init {
        for (k in 0 until lexicon.keyCount) {
            val f = fold(lexicon.keyString(k))
            val old = byFold[f]
            byFold[f] = if (old == null || old == k) k else MANY
        }
    }

    fun otherKey(typed: String, expected: String): Boolean {
        val k = byFold[fold(typed)] ?: return false
        return k == MANY || k != lexicon.keyIndex(expected)
    }

    companion object {
        private const val MANY = -2
        private val cache = java.util.IdentityHashMap<app.metrokeys.engine.lexicon.Lexicon, CaseAccentFolds>()

        fun of(lexicon: app.metrokeys.engine.lexicon.Lexicon): CaseAccentFolds =
            synchronized(cache) { cache.getOrPut(lexicon) { CaseAccentFolds(lexicon) } }

        fun fold(text: String): String {
            val sb = StringBuilder(text.length + 2)
            for (ch in java.text.Normalizer.normalize(text.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)) {
                when {
                    Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> {}
                    ch == 'ß' -> sb.append("ss")
                    ch == 'œ' -> sb.append("oe")
                    ch == 'æ' -> sb.append("ae")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}

/** Counts of one cell (profile, language, set, variant, half, band, length band). */
internal class Counts {
    var n = 0
    var fixed = 0
    var wrong = 0
    var kept = 0
    var top1 = 0
    var top3 = 0
    var offer = 0
    var changed = 0
    var offer3 = 0
    var collisions = 0

    fun add(o: Counts) {
        n += o.n; fixed += o.fixed; wrong += o.wrong; kept += o.kept; top1 += o.top1; top3 += o.top3
        offer += o.offer; changed += o.changed; offer3 += o.offer3; collisions += o.collisions
    }

    fun record(o: Outcome, error: Boolean) {
        if (o.collision) {
            collisions++
            return
        }
        n++
        val x = o.item.expected
        if (error) {
            when (o.autoCorrection) {
                null -> kept++
                x -> fixed++
                else -> wrong++
            }
            if (o.bar.firstOrNull() == x) top1++
            if (x in o.bar.take(3)) top3++
            if (x in o.bar) offer++
        } else {
            if (o.autoCorrection != null && o.autoCorrection != o.typed) changed++
            if (o.item.set == "F7" && o.bar.take(3).any { toKey(it) == o.item.baseKey }) offer3++
        }
    }
}

internal data class CellKey(
    val profile: String,
    val language: String,
    val set: String,
    val variant: String,
    val half: String,
    val band: String,
    val lengthBand: String,
)

/** The set an outcome is counted in: F2 items whose key the list stores with class 0 form F2z (§3.7). */
internal fun countedSet(o: Outcome): String = if (o.item.set == "F2" && o.classZero) "F2z" else o.item.set
