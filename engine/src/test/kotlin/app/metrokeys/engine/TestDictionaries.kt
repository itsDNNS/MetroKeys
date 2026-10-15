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

    fun german(): LanguageEngine = LanguageEngine(LanguageParams.GERMAN, de)

    fun english(): LanguageEngine = LanguageEngine(LanguageParams.ENGLISH, en)

    /** Spanish on the Spanish fallback grid (row 2 without indent, ñ its own key). */
    fun spanish(): LanguageEngine = LanguageEngine(LanguageParams.SPANISH, es)

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
