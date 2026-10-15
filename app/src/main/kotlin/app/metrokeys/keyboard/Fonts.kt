package app.metrokeys.keyboard

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import app.metrokeys.R
import java.io.File

/** Font weight ids of spec 02 §4.1. */
enum class Weight(val slot: String) { LIGHT("light"), SEMILIGHT("semilight"), REGULAR("regular"), SEMIBOLD("semibold"), BOLD("bold") }

/**
 * The typefaces of the keyboard: bundled Selawik, or the user's own imported fonts per weight (spec 02 §4.1,
 * 07 §9.11). Imported files live in [UserAssets.fontsDir] named after their slot; weights that were not imported
 * fall back to Selawik. Key glyphs are never taken from a font.
 */
class Fonts(context: Context, useCustom: Boolean) {
    private val bundled: Map<Weight, Typeface> = mapOf(
        Weight.LIGHT to context.resources.getFont(R.font.selawik_light),
        Weight.SEMILIGHT to context.resources.getFont(R.font.selawik_semilight),
        Weight.REGULAR to context.resources.getFont(R.font.selawik_regular),
        Weight.SEMIBOLD to context.resources.getFont(R.font.selawik_semibold),
        Weight.BOLD to context.resources.getFont(R.font.selawik_bold),
    )
    private val custom = HashMap<Weight, Typeface>()

    /** The emoji typeface: an imported emoji font, else the system default (which carries the emoji fallback). */
    val emoji: Typeface

    init {
        var emojiFace: Typeface? = null
        if (useCustom) {
            val dir = UserAssets.fontsDir(context)
            for (w in Weight.entries) load(File(dir, w.slot))?.let { custom[w] = it }
            emojiFace = load(File(dir, "emoji"))
        }
        emoji = emojiFace ?: Typeface.DEFAULT
    }

    fun get(weight: Weight): Typeface = custom[weight] ?: bundled.getValue(weight)

    private fun load(f: File): Typeface? = if (!f.isFile) null else try {
        Typeface.createFromFile(f)
    } catch (e: RuntimeException) {
        Log.w("MetroKeys", "cannot load font ${f.name}", e)
        null
    }

    companion object {
        fun weightOf(id: String): Weight = when (id) {
            "light" -> Weight.LIGHT
            "semilight" -> Weight.SEMILIGHT
            "semibold" -> Weight.SEMIBOLD
            "bold" -> Weight.BOLD
            else -> Weight.REGULAR
        }
    }
}

/** Locations of the user's imported fonts and sounds: app-private storage excluded from backups. */
object UserAssets {
    fun fontsDir(context: Context): File = File(context.noBackupFilesDir, "fonts")

    fun soundsDir(context: Context): File = File(context.noBackupFilesDir, "sounds")

    /** Sound slots of spec 07 §9.9; files are named "<slot>.wav". */
    val SOUND_SLOTS = listOf("letter", "space", "function", "picker", "candidate", "dictionary")
}
