package app.metrokeys.settings

import android.content.Context
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import app.metrokeys.R
import app.metrokeys.keyboard.UserAssets
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Imports the user's own sound and font files into no-backup app storage (07 §9.9, §9.11; QUESTIONS: storage
 * `noBackupFilesDir/sounds/<slot>.wav` and `noBackupFilesDir/fonts/<slot>`). Runs on a worker thread; the result
 * toast and the preference updates are done by the caller on the main thread with [finish].
 */
class AssetImporter(private val context: Context) {
    data class Result(val accepted: Int, val rejected: List<String>)

    private val resolver get() = context.contentResolver

    fun displayName(uri: Uri): String = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: RuntimeException) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "?"

    /** Reads at most [limit] bytes; null if the file is larger or unreadable. */
    private fun read(uri: Uri, limit: Long): ByteArray? = try {
        resolver.openInputStream(uri)?.use { readLimited(it, limit) }
    } catch (e: IOException) {
        Log.w("MetroKeys", "cannot read $uri", e)
        null
    } catch (e: SecurityException) {
        Log.w("MetroKeys", "no access to $uri", e)
        null
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun writeAtomically(dir: File, name: String, bytes: ByteArray, verify: (File) -> Boolean = { true }): Boolean {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        return try {
            tmp.writeBytes(bytes)
            if (!verify(tmp)) {
                tmp.delete()
                false
            } else {
                val target = File(dir, name)
                target.delete()
                tmp.renameTo(target)
            }
        } catch (e: IOException) {
            Log.w("MetroKeys", "cannot store $name", e)
            tmp.delete()
            false
        }
    }

    /** Multi-file sound import: each file goes to the slot named by its file name (07 §9.9). */
    fun importSounds(uris: List<Uri>): Result {
        var ok = 0
        val rejected = ArrayList<String>()
        for (u in uris) {
            val name = displayName(u)
            val slot = ImportRules.soundSlotForName(name)
            if (slot == null || !storeSound(u, slot)) rejected.add(name) else ok++
        }
        return Result(ok, rejected)
    }

    /** Assigns one file to [slot] regardless of its name. */
    fun importSound(uri: Uri, slot: String): Result {
        val name = displayName(uri)
        return if (storeSound(uri, slot)) Result(1, emptyList()) else Result(0, listOf(name))
    }

    private fun storeSound(uri: Uri, slot: String): Boolean {
        val bytes = read(uri, ImportRules.MAX_SOUND_BYTES) ?: return false
        if (!ImportRules.isWav(bytes)) return false
        return writeAtomically(UserAssets.soundsDir(context), "$slot.wav", bytes)
    }

    /** Font import: the slot comes from the file's OS/2 and name tables; the file must load as a typeface. */
    fun importFonts(uris: List<Uri>): Result {
        var ok = 0
        val rejected = ArrayList<String>()
        for (u in uris) {
            val name = displayName(u)
            val bytes = read(u, ImportRules.MAX_FONT_BYTES)
            val slot = bytes?.let { ImportRules.parseFont(it) }?.let { ImportRules.fontSlot(it) }
            val stored = slot != null && writeAtomically(UserAssets.fontsDir(context), slot, bytes) { f ->
                try {
                    Typeface.Builder(f).build() != null
                } catch (_: RuntimeException) {
                    false
                }
            }
            if (stored) ok++ else rejected.add(name)
        }
        return Result(ok, rejected)
    }

    fun removeSounds() {
        UserAssets.soundsDir(context).deleteRecursively()
    }

    fun removeFonts() {
        UserAssets.fontsDir(context).deleteRecursively()
    }

    fun soundFile(slot: String): File = File(UserAssets.soundsDir(context), "$slot.wav")

    fun fontFile(slot: String): File = File(UserAssets.fontsDir(context), slot)

    /**
     * Main thread after an import: switches the source to `custom` and updates `custom_assets_stamp` when anything
     * was accepted, and shows the result toast ("n files imported." plus "Not used: …").
     */
    fun finish(store: SettingsStore, sourceKey: String, r: Result) {
        if (r.accepted > 0) {
            store.edit {
                putString(sourceKey, "custom")
                putLong(Prefs.CUSTOM_ASSETS_STAMP, System.currentTimeMillis())
            }
        }
        val res = context.resources
        var msg = res.getQuantityString(R.plurals.import_ok, r.accepted, r.accepted)
        if (r.rejected.isNotEmpty()) msg += "\n" + res.getString(R.string.import_rejected, r.rejected.joinToString(", "))
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    /** After a removal: source back to the default and a new stamp. */
    fun removed(store: SettingsStore, sourceKey: String, default: String) {
        store.edit {
            putString(sourceKey, default)
            putLong(Prefs.CUSTOM_ASSETS_STAMP, System.currentTimeMillis())
        }
    }
}

/**
 * Sound previews of the settings app: a slot's file on long press (07 §9.9) and the letter-key sample when the
 * volume slider is released (07 §9.8).
 */
class SoundPreview(private val context: Context) {
    private var player: MediaPlayer? = null
    private val audio = context.getSystemService(AudioManager::class.java)

    fun playFile(file: File, percent: Int) {
        if (!file.isFile) return
        release()
        val gain = (percent / 100f).let { it * it }
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            p.setDataSource(file.path)
            p.setVolume(gain, gain)
            p.setOnCompletionListener { release() }
            p.prepare()
            p.start()
            player = p
        } catch (e: IOException) {
            Log.w("MetroKeys", "cannot play ${file.name}", e)
            release()
        } catch (e: RuntimeException) {
            Log.w("MetroKeys", "cannot play ${file.name}", e)
            release()
        }
    }

    /** The letter-key sound at [percent]: the user's file when the source is custom and it exists, else Android's. */
    fun playSample(percent: Int, custom: Boolean, letterFile: File) {
        if (percent <= 0 || audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (custom && letterFile.isFile) {
            playFile(letterFile, percent)
            return
        }
        val gain = (percent / 100f).let { it * it }
        audio.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, if (percent >= 100) -1f else 0.5f * gain)
    }

    fun release() {
        player?.release()
        player = null
    }
}
