package app.metrokeys.engine

import app.metrokeys.engine.lexicon.Dictionary
import app.metrokeys.engine.predict.LanguageEngine
import app.metrokeys.engine.predict.LanguageParams
import app.metrokeys.engine.predict.QueryContext
import app.metrokeys.engine.touch.KeyboardGeometry
import java.io.File

/** Bundled dictionaries, loaded once per test JVM; engines are created fresh (empty learned data). */
object TestDictionaries {
    val de: Dictionary by lazy { Dictionary.loadBundled("de-DE") }
    val en: Dictionary by lazy { Dictionary.loadBundled("en-US") }
    val es: Dictionary by lazy { Dictionary.loadBundled("es-ES") }
    val pt: Dictionary by lazy { Dictionary.loadBundled("pt-BR") }
    val it: Dictionary by lazy { Dictionary.loadBundled("it-IT") }
    val fr: Dictionary by lazy { Dictionary.loadBundled("fr-FR") }

    fun german(): LanguageEngine = LanguageEngine(LanguageParams.GERMAN, de)

    fun english(): LanguageEngine = LanguageEngine(LanguageParams.ENGLISH, en)

    /** Spanish on the Spanish fallback grid (row 2 without indent, ñ its own key). */
    fun spanish(): LanguageEngine = LanguageEngine(LanguageParams.SPANISH, es)

    /** Portuguese (Brazil) on the Portuguese fallback grid (row 2 without indent, ç its own key). */
    fun portuguese(): LanguageEngine = LanguageEngine(LanguageParams.PORTUGUESE, pt)

    /** Italian on the Italian fallback grid (the English rows, 05 §4). */
    fun italian(): LanguageEngine = LanguageEngine(LanguageParams.ITALIAN, it)

    /** French on the French fallback grid (AZERTY, row 2 without indent, row 3 from 0.2 · width, 05 §4). */
    fun french(): LanguageEngine = LanguageEngine(LanguageParams.FRENCH, fr)

    /** Several languages, [selected] chosen with the language key, multilingual typing on. */
    fun multilingual(selected: String, vararg engines: LanguageEngine): PredictionEngine =
        PredictionEngine(engines.toList(), EngineSettings(multilingual = true)).also { it.activeLanguage = selected }

    /** de + en, German selected with the language key, multilingual typing on. */
    fun multilingual(): PredictionEngine =
        PredictionEngine(listOf(german(), english()), EngineSettings(multilingual = true)).also { it.activeLanguage = "de-DE" }

    fun single(engine: LanguageEngine): PredictionEngine = PredictionEngine(listOf(engine))

    fun specFile(name: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "spec/data/$name")
            if (f.exists()) return f
            dir = dir.parentFile
        }
        error("spec/data/$name not found")
    }

    fun layoutGeometry(lang: String, page: String = "text.letters"): KeyboardGeometry =
        KeyboardGeometry.fromLayoutJson(specFile("layouts-$lang.json").readText(), page)
}

fun LanguageEngine.query(before: String) = suggest(QueryContext.of(before))

/** The entry of the ranked list (05 §8.4) with the display text [display]. */
fun app.metrokeys.engine.predict.LanguageSuggestions.entry(display: String): app.metrokeys.engine.predict.Candidate =
    ranked.firstOrNull { it.display == display } ?: error("no entry $display in $ranked")

/** The display texts the bar of this language shows (05 §9, without emoji). */
fun app.metrokeys.engine.predict.LanguageSuggestions.barTexts(): List<String> = bar().map { it.display }
