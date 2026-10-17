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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import dev.braindump.splitter.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer as GenAiSpeechRecognizer

/**
 * Live dictation that never leaves the phone.
 *
 * First choice is ML Kit GenAI Speech Recognition in Advanced mode, which on Pixel 10 runs
 * on Gemini Nano through AICore (the SDK drops to its Basic on-device model elsewhere).
 * When that isn't ready, it uses the platform's on-device recognizer (on Pixel: the offline
 * engine behind Recorder, Live Caption and Live Transcribe). Neither goes through the Google
 * app, so region restrictions on voice search don't apply.
 */
@Stable
class VoiceTranscriber(
    private val context: Context,
    private val scope: CoroutineScope,
) : RecognitionListener {

    var isListening by mutableStateOf(false)
        private set

    /** Words recognized so far in the current dictation. */
    var partialText by mutableStateOf("")
        private set

    /** Normalized input level, 0..1, for the listening indicator. */
    var level by mutableFloatStateOf(0f)
        private set

    private var onFinal: (String) -> Unit = {}
    private var onMessage: (Int) -> Unit = {}

    // ML Kit GenAI path.
    private var genAiRecognizer: GenAiSpeechRecognizer? = null
    private var genAiUnusable = false
    private var genAiDownloading = false
    private var genAiSession: Job? = null
    private var silenceTimer: Job? = null

    // Platform fallback.
    private var recognizer: SpeechRecognizer? = null

    fun start(onFinal: (String) -> Unit, onMessage: (Int) -> Unit) {
        if (isListening) return
        this.onFinal = onFinal
        this.onMessage = onMessage
        partialText = ""
        level = 0f
        isListening = true

        if (genAiUnusable) {
            startPlatform()
            return
        }
        genAiSession = scope.launch {
            val genAi = readyGenAiRecognizer()
            if (genAi == null) startPlatform() else listenWithGenAi(genAi)
        }
    }

    /** Stops listening; whatever was heard is still delivered through onFinal. */
    fun stop() {
        val genAi = genAiRecognizer
        if (genAiSession?.isActive == true && genAi != null) {
            scope.launch { stopGenAi(genAi) }
        } else {
            recognizer?.stopListening()
        }
    }

    fun release() {
        silenceTimer?.cancel()
        genAiSession?.cancel()
        genAiSession = null
        genAiRecognizer?.close()
        genAiRecognizer = null
        recognizer?.destroy()
        recognizer = null
        isListening = false
        partialText = ""
    }

    /** The GenAI recognizer when its model is on the device; otherwise null, kicking off a download if offered. */
    private suspend fun readyGenAiRecognizer(): GenAiSpeechRecognizer? {
        val genAi = genAiRecognizer ?: SpeechRecognition.getClient(
            speechRecognizerOptions {
                locale = Locale.getDefault()
                preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
            }
        ).also { genAiRecognizer = it }

        val status = try {
            genAi.checkStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FeatureStatus.UNAVAILABLE
        }
        return when (status) {
            FeatureStatus.AVAILABLE -> genAi
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                downloadGenAi(genAi)
                null
            }
            else -> {
                genAiUnusable = true
                null
            }
        }
    }

    /** Fetches the model in the background so the next dictation can use it. */
    private fun downloadGenAi(genAi: GenAiSpeechRecognizer) {
        if (genAiDownloading) return
        genAiDownloading = true
        scope.launch {
            try {
                genAi.download().collect {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The platform recognizer keeps working; try again on a later launch.
            } finally {
                genAiDownloading = false
            }
        }
    }

    private suspend fun listenWithGenAi(genAi: GenAiSpeechRecognizer) {
        val committed = StringBuilder()
        var failed = false
        val sessionCap = scope.launch {
            delay(MAX_SESSION_MS)
            stopGenAi(genAi)
        }
        try {
            genAi.startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromMic() })
                .collect { response ->
                    when (response) {
                        is SpeechRecognizerResponse.PartialTextResponse -> {
                            silenceTimer?.cancel()
                            partialText = join(committed, response.text)
                            level = PEAK_LEVEL
                        }
                        is SpeechRecognizerResponse.FinalTextResponse -> {
                            val segment = response.text.trim()
                            if (segment.isNotEmpty()) {
                                if (committed.isNotEmpty()) committed.append(' ')
                                committed.append(segment)
                            }
                            partialText = committed.toString()
                            level = 0f
                            // A pause after a finished phrase ends the dictation, like the platform recognizer.
                            silenceTimer?.cancel()
                            silenceTimer = scope.launch {
                                delay(SILENCE_MS)
                                stopGenAi(genAi)
                            }
                        }
                        is SpeechRecognizerResponse.ErrorResponse -> failed = true
                        else -> Unit // CompletedResponse: the flow ends right after.
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        } finally {
            sessionCap.cancel()
            silenceTimer?.cancel()
        }

        val heard = committed.toString().trim().ifEmpty { partialText.trim() }
        if (failed && heard.isEmpty()) {
            // Don't keep failing on this path; the platform recognizer takes over from here.
            genAiUnusable = true
            genAiRecognizer?.close()
            genAiRecognizer = null
            partialText = ""
            startPlatform()
            return
        }
        isListening = false
        partialText = ""
        level = 0f
        if (heard.isNotEmpty()) onFinal(heard)
    }

    private suspend fun stopGenAi(genAi: GenAiSpeechRecognizer) {
        try {
            genAi.stopRecognition()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            genAiSession?.cancel()
            isListening = false
            partialText = ""
            level = 0f
        }
    }

    private fun join(committed: CharSequence, partial: String): String =
        if (committed.isEmpty()) partial.trim() else "$committed ${partial.trim()}".trimEnd()

    private fun startPlatform() {
        val activeRecognizer = recognizer ?: createRecognizer()?.also { recognizer = it }
        if (activeRecognizer == null) {
            isListening = false
            onMessage(R.string.voice_unavailable)
            return
        }
        isListening = true
        activeRecognizer.startListening(recognitionIntent())
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

    private companion object {
        /** Pause after a finished phrase that ends the dictation. */
        const val SILENCE_MS = 2_500L
        const val MAX_SESSION_MS = 90_000L

        /** The GenAI stream has no input level, so the indicator pulses while words arrive. */
        const val PEAK_LEVEL = 0.6f
    }
}

@Composable
fun rememberVoiceTranscriber(): VoiceTranscriber {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transcriber = remember(context, scope) { VoiceTranscriber(context, scope) }
    DisposableEffect(transcriber) {
        onDispose { transcriber.release() }
    }
    return transcriber
}
