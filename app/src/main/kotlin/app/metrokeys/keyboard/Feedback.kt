package app.metrokeys.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File

/** Sound kinds of spec 03 §1.4 / 07 §9.9. */
enum class SoundKind(val slot: String) {
    LETTER("letter"), SPACE("space"), FUNCTION("function"), DELETE("function"), RETURN("function"),
    PICKER("picker"), CANDIDATE("candidate"), DICTIONARY("dictionary"), PASTE("candidate"),
}

/**
 * Key sounds and vibration (spec 03 §1.4, 07 §9.8/§9.9): Android's key clicks or the user's own WAV files,
 * only in normal ringer mode, gain (percent / 100)²; optional 12 ms vibration on key down.
 */
class Feedback(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    private var pool: SoundPool? = null
    private val sampleIds = HashMap<String, Int>()

    var soundsOn = true
    var volumePercent = 100
    var vibrate = false
    private var custom = false

    /** (Re)loads the user's sound files when the source is "custom" (spec 07 §9.9). */
    fun configure(soundsOn: Boolean, volumePercent: Int, vibrate: Boolean, customSounds: Boolean) {
        this.soundsOn = soundsOn
        this.volumePercent = volumePercent
        this.vibrate = vibrate
        release()
        custom = customSounds
        if (!customSounds) return
        val dir = UserAssets.soundsDir(context)
        val files = UserAssets.SOUND_SLOTS.map { File(dir, "$it.wav") }.filter { it.isFile }
        if (files.isEmpty()) return
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build()
        for (f in files) {
            try {
                sampleIds[f.nameWithoutExtension] = p.load(f.path, 1)
            } catch (_: RuntimeException) {
            }
        }
        pool = p
    }

    fun release() {
        pool?.release()
        pool = null
        sampleIds.clear()
    }

    /** The ringer mode, read once and then updated by its broadcast (no call to the audio service per key). */
    @Volatile
    private var ringerNormalCached = readRingerNormal()
    private val ringerReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: android.content.Intent?) {
            ringerNormalCached = readRingerNormal()
        }
    }
    private var receiverRegistered = try {
        androidx.core.content.ContextCompat.registerReceiver(
            context, ringerReceiver, android.content.IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun readRingerNormal(): Boolean = audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL

    private fun ringerNormal(): Boolean = if (receiverRegistered) ringerNormalCached else readRingerNormal()

    /** Releases the sounds and stops following the ringer mode (service end). */
    fun shutdown() {
        release()
        if (receiverRegistered) {
            receiverRegistered = false
            try {
                context.unregisterReceiver(ringerReceiver)
            } catch (_: RuntimeException) {
            }
        }
    }

    fun play(kind: SoundKind) {
        if (!soundsOn || volumePercent <= 0 || !ringerNormal()) return
        val gain = (volumePercent / 100f).let { it * it }
        val p = pool
        if (custom && p != null) {
            val id = sampleIds[kind.slot] ?: when (kind) {
                SoundKind.SPACE, SoundKind.FUNCTION, SoundKind.DELETE, SoundKind.RETURN -> sampleIds["letter"]
                else -> null
            }
            if (id != null) {
                p.play(id, gain, gain, 1, 0, 1f)
                return
            }
        }
        // picker, candidate and dictionary sounds only exist as user files (spec 07 §9.9)
        val effect = when (kind) {
            SoundKind.LETTER -> AudioManager.FX_KEYPRESS_STANDARD
            SoundKind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            SoundKind.DELETE -> AudioManager.FX_KEYPRESS_DELETE
            SoundKind.RETURN -> AudioManager.FX_KEYPRESS_RETURN
            SoundKind.FUNCTION -> AudioManager.FX_KEYPRESS_STANDARD
            else -> return
        }
        val vol = if (volumePercent >= 100) -1f else 0.5f * gain
        audio?.playSoundEffect(effect, vol)
    }

    /** 12 ms pulse on key down (spec 03 §1.4), not for picker opening. */
    fun vibrate() {
        if (!vibrate) return
        vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
