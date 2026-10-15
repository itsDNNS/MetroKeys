package app.metrokeys.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * In-keyboard dictation with Android's SpeechRecognizer (03 §14): partial results and the input level are shown in
 * the strip, the final result is inserted at the caret. The recognizer is kept until the keyboard hides.
 */
class Dictation(private val context: Context, private val controller: KeyboardController) {
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var partial: String? = null
    private var level = 0f

    /** Starts listening in [languageTag]; false if the permission or a recognizer is missing. */
    fun start(languageTag: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return false
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        active = true
        partial = null
        level = 0f
        publish()
        try {
            r.startListening(intent)
        } catch (_: RuntimeException) {
            end()
            return false
        }
        return true
    }

    fun stop() {
        if (!active) return
        recognizer?.stopListening()
        end()
    }

    /** Hiding the keyboard cancels a running session and releases the recognizer (03 §14.2). */
    fun cancel() {
        if (active) recognizer?.cancel()
        end()
        release()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun end() {
        active = false
        controller.onDictationState(null)
    }

    private fun publish() {
        if (active) controller.onDictationState(DictationState(true, level, partial))
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = publish()

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            level = rmsdB
            publish()
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) = end()

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            end()
            if (!text.isNullOrBlank()) controller.onDictationResult(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: partial
            publish()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
