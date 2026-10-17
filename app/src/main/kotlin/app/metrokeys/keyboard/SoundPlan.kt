package app.metrokeys.keyboard

import app.metrokeys.keyboard.layout.Appearance
import app.metrokeys.keyboard.layout.Key
import app.metrokeys.keyboard.layout.KeyRole
import app.metrokeys.keyboard.layout.Page

/** The setting `sound_source` (07 §9.1, §9.9; 03 §1.4 "Sources"). */
enum class SoundSource(val value: String) {
    METROKEYS("metrokeys"), SYSTEM("system"), CUSTOM("custom");

    companion object {
        /** Unknown or missing values read as the default "MetroKeys". */
        fun of(v: String?): SoundSource = entries.firstOrNull { it.value == v } ?: METROKEYS
    }
}

/**
 * The seven sound slots of 03 §1.4 / 07 §9.9: [file] is the imported file's name (`<file>.wav`), [generated] the
 * generated sound the slot plays without a file (the pick slot: the function sound), [keyVoice] whether it shares the
 * voice of the key sounds.
 */
enum class SoundSlot(val file: String, val generated: GeneratedSound, val keyVoice: Boolean) {
    LETTER("letter", GeneratedSound.LETTER, true),
    SPACE("space", GeneratedSound.SPACE, true),
    FUNCTION("function", GeneratedSound.FUNCTION, true),
    PICKER("picker", GeneratedSound.PICKER, true),
    PICK("candidate", GeneratedSound.FUNCTION, false),
    DICTIONARY("dictionary", GeneratedSound.DICTIONARY, false),
    PASTE("paste", GeneratedSound.PASTE, false),
    ;

    companion object {
        fun ofFile(name: String): SoundSlot? = entries.firstOrNull { it.file == name }
    }
}

/** The platform's key-click effects (source "Android system sounds"). */
enum class ClickEffect { STANDARD, SPACEBAR, DELETE, RETURN }

/**
 * A sound event (03 §1.4): its [slot], the platform click that stands for it with the source "Android system sounds"
 * (null: the platform has none, the generated sound plays) and its gain class ("Level relation").
 */
enum class SoundKind(val slot: SoundSlot, val effect: ClickEffect?) {
    LETTER(SoundSlot.LETTER, ClickEffect.STANDARD),
    SPACE(SoundSlot.SPACE, ClickEffect.SPACEBAR),
    FUNCTION(SoundSlot.FUNCTION, ClickEffect.STANDARD),
    DELETE(SoundSlot.FUNCTION, ClickEffect.DELETE),
    RETURN(SoundSlot.FUNCTION, ClickEffect.RETURN),
    PICKER(SoundSlot.PICKER, null),
    PICK(SoundSlot.PICK, ClickEffect.STANDARD),
    DICTIONARY(SoundSlot.DICTIONARY, null),

    /** The add-to-dictionary sound as the "not recognised" signal of shape writing (06 §5.5), with its own gain. */
    NOT_RECOGNISED(SoundSlot.DICTIONARY, null),
    PASTE(SoundSlot.PASTE, null),
    ;

    companion object {
        /** The event that plays [slot] itself (previews of the settings app). */
        fun of(slot: SoundSlot): SoundKind = when (slot) {
            SoundSlot.LETTER -> LETTER
            SoundSlot.SPACE -> SPACE
            SoundSlot.FUNCTION -> FUNCTION
            SoundSlot.PICKER -> PICKER
            SoundSlot.PICK -> PICK
            SoundSlot.DICTIONARY -> DICTIONARY
            SoundSlot.PASTE -> PASTE
        }

        /**
         * The sound class of a key (03 §1.4 "Sound classes of keys"), chosen by its look: `normal` → letter,
         * `function`/`emphasized` → function, the space bar of a letter or symbols page → space. Within the function
         * class backspace and Enter name the platform's delete and return clicks.
         */
        fun forKey(key: Key, page: Page): SoundKind = when {
            key.role == KeyRole.SPACE && (page.isLetters || page.isSymbols) -> SPACE
            key.appearance == Appearance.NORMAL -> LETTER
            key.role == KeyRole.BACKSPACE -> DELETE
            key.role == KeyRole.ENTER -> RETURN
            else -> FUNCTION
        }
    }
}

/** The device state and settings that decide whether and how a sound plays (03 §1.4 "Conditions"). */
data class SoundConditions(
    val soundsOn: Boolean,
    val volumePercent: Int,
    /** The setting "key sounds in silent mode" (07 §9.8 item 2). */
    val silentMode: Boolean,
    /** Ringer mode "normal" (not silent or vibrate). */
    val ringerNormal: Boolean,
    val doNotDisturb: Boolean,
)

/** What to play for one event. */
sealed class Playback {
    /** A platform key click; [volume] as for `AudioManager.playSoundEffect` (−1 = the system's click volume). */
    data class Platform(val effect: ClickEffect, val volume: Float) : Playback()

    /**
     * A sound the keyboard plays itself: the user's file of [userSlot], else the generated [generated]; [gain] per the
     * level relation; [media] for silent-mode playback (media volume), else sonification; [keyVoice] stops the voice's
     * previous sound.
     */
    data class Sample(val userSlot: SoundSlot?, val generated: GeneratedSound?, val gain: Float, val media: Boolean, val keyVoice: Boolean) : Playback()
}

/** The sound rules of 03 §1.4 and 07 §9.8/§9.9, free of Android. */
object SoundPlan {
    /** Key and picker sounds: −8.6 dB below pick, add-to-dictionary and paste (03 §1.4 "Level relation"). */
    const val KEY_FACTOR = 0.372

    /** The "not recognised" use of the add-to-dictionary sound: a further 9 dB, capped at 1. */
    const val NOT_RECOGNISED_FACTOR = 2.82

    /** The volume gain G = (percent / 100)² of 07 §9.8 item 3. */
    fun volumeGain(percent: Int): Double = (percent.coerceIn(0, 100) / 100.0).let { it * it }

    /** The gain of a sound the keyboard plays itself (generated or the user's file). */
    fun gain(kind: SoundKind, percent: Int): Double {
        val g = volumeGain(percent)
        return when {
            kind == SoundKind.NOT_RECOGNISED -> minOf(1.0, NOT_RECOGNISED_FACTOR * g)
            kind.slot.keyVoice -> KEY_FACTOR * g
            else -> g
        }
    }

    /** The volume of a platform click (07 §9.8 item 3): the system's click volume at 100 %, else 0.5 × G. */
    fun platformVolume(percent: Int): Float = if (percent >= 100) -1f else (0.5 * volumeGain(percent)).toFloat()

    /**
     * What plays for [kind] with [source] and the user's files of [userFiles], or null when nothing plays: key sounds
     * off, volume 0, do-not-disturb, or ringer silent/vibrate without the silent-mode setting.
     */
    fun plan(kind: SoundKind, source: SoundSource, userFiles: Set<SoundSlot>, c: SoundConditions): Playback? {
        if (!c.soundsOn || c.volumePercent <= 0 || c.doNotDisturb) return null
        if (!c.ringerNormal && !c.silentMode) return null
        val media = !c.ringerNormal
        val slot = kind.slot
        val effect = kind.effect
        if (source == SoundSource.SYSTEM && effect != null && !media) return Playback.Platform(effect, platformVolume(c.volumePercent))
        val user = when {
            source != SoundSource.CUSTOM -> null
            slot in userFiles -> slot
            // the pick slot without its own file follows the function slot (the same sound, 03 §1.4)
            slot == SoundSlot.PICK && SoundSlot.FUNCTION in userFiles -> SoundSlot.FUNCTION
            else -> null
        }
        return Playback.Sample(
            userSlot = user,
            generated = if (user == null) slot.generated else null,
            gain = gain(kind, c.volumePercent).toFloat(),
            media = media,
            keyVoice = slot.keyVoice,
        )
    }
}
