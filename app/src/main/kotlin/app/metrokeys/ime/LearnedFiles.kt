package app.metrokeys.ime

import android.content.Context
import app.metrokeys.engine.LearnedDataStore
import java.io.File

/**
 * Learned data in app storage (05 §12.5): `noBackupFilesDir/learned/<language>.bin` in the engine's format, written
 * to a temporary file and renamed. Used by the keyboard and by the settings' learned words page.
 */
class LearnedFiles(private val context: Context) : LearnedDataStore {
    val dir: File get() = File(context.noBackupFilesDir, "learned")

    private fun file(tag: String) = File(dir.apply { mkdirs() }, "$tag.bin")

    fun exists(languageTag: String): Boolean = File(dir, "$languageTag.bin").isFile

    override fun load(languageTag: String): ByteArray? = file(languageTag).takeIf { it.isFile }?.readBytes()

    override fun save(languageTag: String, data: ByteArray) {
        val f = file(languageTag)
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(data)
        tmp.renameTo(f)
    }
}
