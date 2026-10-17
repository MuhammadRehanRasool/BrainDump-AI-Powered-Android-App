package dev.braindump.splitter.ui.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.braindump.splitter.R
import java.util.Locale

/**
 * Live dictation through the phone's own on-device recognizer (on Pixel: the same offline
 * engine behind Recorder and Live Caption). Unlike the RecognizerIntent popup it never
 * goes through the Google app, so region restrictions on voice search don't apply.
 * Falls back to the default system recognizer, still preferring offline, only when the
 * device has no on-device service.
 */
@Stable
class VoiceTranscriber(private val context: Context) : RecognitionListener {

    var isListening by mutableStateOf(false)
        private set

    /** Words recognized so far in the current utterance. */
    var partialText by mutableStateOf("")
        private set

    /** Normalized input level, 0..1, for the listening indicator. */
    var level by mutableFloatStateOf(0f)
        private set

    private var recognizer: SpeechRecognizer? = null
    private var onFinal: (String) -> Unit = {}
    private var onMessage: (Int) -> Unit = {}

    fun start(onFinal: (String) -> Unit, onMessage: (Int) -> Unit) {
        if (isListening) return
        this.onFinal = onFinal
        this.onMessage = onMessage

        val activeRecognizer = recognizer ?: createRecognizer()?.also { recognizer = it }
        if (activeRecognizer == null) {
            onMessage(R.string.voice_unavailable)
            return
        }
        partialText = ""
        level = 0f
        isListening = true
        activeRecognizer.startListening(recognitionIntent())
    }

    /** Stops listening; whatever was heard is still delivered through onFinal. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        isListening = false
        partialText = ""
    }

    private fun createRecognizer(): SpeechRecognizer? {
        val created = when {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) ->
                SpeechRecognizer.createSpeechRecognizer(context)
            else -> null
        }
        created?.setRecognitionListener(this)
        return created
    }

    private fun recognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        isListening = false
        partialText = ""
        level = 0f
        if (!text.isNullOrEmpty()) onFinal(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialText = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
    }

    override fun onError(error: Int) {
        val heard = partialText.trim()
        isListening = false
        partialText = ""
        level = 0f

        when (error) {
            // Silence or nothing intelligible: keep any words already heard and stop quietly.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (heard.isNotEmpty()) onFinal(heard)

            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    recognizer?.triggerModelDownload(recognitionIntent())
                }
                onMessage(R.string.voice_language_downloading)
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onMessage(R.string.voice_permission_denied)

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> onMessage(R.string.voice_busy)

            else -> {
                // Client/server faults can leave the recognizer unusable; rebuild it next time.
                recognizer?.destroy()
                recognizer = null
                if (heard.isNotEmpty()) onFinal(heard) else onMessage(R.string.voice_error)
            }
        }
    }

    override fun onRmsChanged(rmsdB: Float) {
        level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

@Composable
fun rememberVoiceTranscriber(): VoiceTranscriber {
    val context = LocalContext.current
    val transcriber = remember(context) { VoiceTranscriber(context) }
    DisposableEffect(transcriber) {
        onDispose { transcriber.release() }
    }
    return transcriber
}
