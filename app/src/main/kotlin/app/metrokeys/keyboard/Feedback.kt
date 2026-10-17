package app.metrokeys.keyboard

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * The generated sounds as WAV files (03 Appendix A): computed in memory and written once into the cache directory,
 * named by their CRC-32, so a changed algorithm never plays an old file. Shared by the keyboard and the settings app.
 */
object GeneratedSoundFiles {
    private const val DIR = "generated-sounds"

    /** The files of all generated sounds; writes the missing ones (call off the main thread). */
    @Synchronized
    fun ensure(context: Context): Map<GeneratedSound, File> {
        val dir = File(context.cacheDir, DIR)
        dir.mkdirs()
        val files = GeneratedSound.entries.associateWith { s ->
            val pcm = SoundGenerator.pcm(s)
            val crc = SoundGenerator.crc32(pcm)
            if (crc != SoundGenerator.EXPECTED_CRC[s]) Log.w("MetroKeys", "generated sound $s: CRC-32 %08x differs from Appendix A".format(crc))
            val f = File(dir, "%s-%08x.wav".format(s.fileName, crc))
            if (!f.isFile || f.length() != 44L + 2L * pcm.size) {
                val tmp = File(dir, f.name + ".tmp")
                tmp.writeBytes(SoundGenerator.wav(pcm))
                tmp.renameTo(f)
            }
            f
        }
        val keep = files.values.map { it.name }.toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        return files
    }
}

/**
 * Key and event sounds and vibration (03 §1.4, 07 §9.8/§9.9). The rules are [SoundPlan]; this class plays: platform
 * clicks through [AudioManager.playSoundEffect], everything else from preloaded [SoundPool]s (sonification usage in
 * normal ringer mode, media usage for silent-mode playback). Key and picker sounds share one voice per pool.
 * Ringer mode and do-not-disturb are followed by their broadcasts, so playing asks the system nothing.
 */
class Feedback(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "mk-sounds").apply { isDaemon = true } }

    var soundsOn = true
        private set
    var volumePercent = 100
        private set
    private var vibrate = false
    private var silentMode = false
    private var source = SoundSource.METROKEYS

    /** Slots with a usable file of the user (source "my own sounds"). */
    @Volatile
    private var userFiles: Set<SoundSlot> = emptySet()

    /** A preloaded pool: sample ids by sound (the user's slot file or a generated sound) and its key voice. */
    private inner class Pool(media: Boolean) {
        val pool: SoundPool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (media) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build()
        private val pending = HashMap<Int, Any>()
        private val ready = HashMap<Any, Int>()
        private var released = false
        var keyStream = 0

        init {
            pool.setOnLoadCompleteListener { _, id, status ->
                synchronized(this) {
                    val what = pending.remove(id)
                    if (status == 0 && what != null) ready[what] = id
                }
            }
        }

        /** Loads [file] as [what] (loader thread); the pool decodes it in the background. */
        fun load(what: Any, file: File) = synchronized(this) {
            if (released) return@synchronized
            try {
                pending[pool.load(file.path, 1)] = what
            } catch (e: RuntimeException) {
                Log.w("MetroKeys", "cannot load sound ${file.name}", e)
            }
        }

        fun id(what: Any): Int? = synchronized(this) { ready[what] }

        fun release() = synchronized(this) {
            released = true
            pool.release()
        }
    }

    private var sonification: Pool? = null
    private var media: Pool? = null
    private var loadKey: Any? = null

    /**
     * Applies the settings; the sounds are (re)loaded only when the source, the silent-mode setting or the user's
     * files ([assetsStamp]) changed (03 §1.4 "Latency").
     */
    fun configure(
        soundsOn: Boolean, volumePercent: Int, vibrate: Boolean, source: SoundSource, silentMode: Boolean, assetsStamp: Long,
    ) {
        this.soundsOn = soundsOn
        this.volumePercent = volumePercent
        this.vibrate = vibrate
        this.source = source
        this.silentMode = silentMode
        val key = listOf(source, silentMode, if (source == SoundSource.CUSTOM) assetsStamp else 0L)
        if (key == loadKey && sonification != null) return
        loadKey = key
        release()
        val pools = listOfNotNull(Pool(media = false).also { sonification = it }, if (silentMode) Pool(media = true).also { media = it } else null)
        val custom = source == SoundSource.CUSTOM
        userFiles = emptySet()
        loader.execute {
            val generated = try {
                GeneratedSoundFiles.ensure(context)
            } catch (e: Exception) {
                Log.w("MetroKeys", "cannot write the generated sounds", e)
                emptyMap()
            }
            val user = if (custom) {
                val dir = UserAssets.soundsDir(context)
                SoundSlot.entries.associateWith { File(dir, "${it.file}.wav") }.filterValues { it.isFile }
            } else {
                emptyMap()
            }
            for (p in pools) {
                for ((s, f) in generated) p.load(s, f)
                for ((slot, f) in user) p.load(slot, f)
            }
            userFiles = user.keys
        }
    }

    private fun release() {
        sonification?.release()
        media?.release()
        sonification = null
        media = null
    }

    /** The ringer mode and do-not-disturb, read once and then updated by their broadcasts. */
    @Volatile
    private var ringerNormalCached = readRingerNormal()

    @Volatile
    private var dndCached = readDnd()
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            ringerNormalCached = readRingerNormal()
            dndCached = readDnd()
        }
    }
    private var receiverRegistered = try {
        val filter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
        filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        ContextCompat.registerReceiver(context, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun readRingerNormal(): Boolean = audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL

    private fun readDnd(): Boolean = soundDndActive(notifications)

    private fun conditions(): SoundConditions = SoundConditions(
        soundsOn = soundsOn,
        volumePercent = volumePercent,
        silentMode = silentMode,
        ringerNormal = if (receiverRegistered) ringerNormalCached else readRingerNormal(),
        doNotDisturb = if (receiverRegistered) dndCached else readDnd(),
    )

    /** Releases the sounds and stops following the ringer mode (service end). */
    fun shutdown() {
        release()
        loadKey = null
        loader.shutdown()
        if (receiverRegistered) {
            receiverRegistered = false
            try {
                context.unregisterReceiver(stateReceiver)
            } catch (_: RuntimeException) {
            }
        }
    }

    /** Plays the sound of [kind] (03 §1.4); never waits for loading or playback. */
    fun play(kind: SoundKind) {
        when (val p = SoundPlan.plan(kind, source, userFiles, conditions()) ?: return) {
            is Playback.Platform -> audio?.playSoundEffect(effectOf(p.effect), p.volume)
            is Playback.Sample -> {
                val pool = (if (p.media) media else sonification) ?: return
                val id = pool.id(p.userSlot ?: p.generated ?: return) ?: return
                if (p.keyVoice) {
                    if (pool.keyStream != 0) pool.pool.stop(pool.keyStream)
                    pool.keyStream = pool.pool.play(id, p.gain, p.gain, 1, 0, 1f)
                } else {
                    pool.pool.play(id, p.gain, p.gain, 1, 0, 1f)
                }
            }
        }
    }

    /** A key-sound event (03 §1.4 "When key sounds play"): its sound and the optional vibration. */
    fun key(kind: SoundKind) {
        play(kind)
        vibrate()
    }

    /**
     * Keeps the output ready when the keyboard is shown (03 §1.4 "Latency"): a silent play of the letter sound opens
     * the pool's output track, so the first key sound is neither late nor cut.
     */
    fun prepare() {
        val p = SoundPlan.plan(SoundKind.LETTER, source, userFiles, conditions()) as? Playback.Sample ?: return
        val pool = (if (p.media) media else sonification) ?: return
        val id = pool.id(p.userSlot ?: p.generated ?: return) ?: return
        pool.pool.play(id, 0f, 0f, 0, 0, 1f)
    }

    /** 12 ms pulse (03 §1.4 "Vibration"), independent of the sound settings and the ringer mode. */
    fun vibrate() {
        if (!vibrate) return
        vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        fun effectOf(e: ClickEffect): Int = when (e) {
            ClickEffect.STANDARD -> AudioManager.FX_KEYPRESS_STANDARD
            ClickEffect.SPACEBAR -> AudioManager.FX_KEYPRESS_SPACEBAR
            ClickEffect.DELETE -> AudioManager.FX_KEYPRESS_DELETE
            ClickEffect.RETURN -> AudioManager.FX_KEYPRESS_RETURN
        }

        /** Do-not-disturb is on: any interruption filter other than "all" (03 §1.4 "Conditions"). */
        fun soundDndActive(n: NotificationManager?): Boolean {
            val f = n?.currentInterruptionFilter ?: return false
            return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
    }
}
