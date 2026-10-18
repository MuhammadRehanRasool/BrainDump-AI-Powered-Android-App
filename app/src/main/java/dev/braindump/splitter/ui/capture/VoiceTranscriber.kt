package dev.braindump.splitter.ui.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer as GenAiSpeechRecognizer

/**
 * Live dictation that never leaves the phone.
 *
 * First choice is ML Kit GenAI Speech Recognition in Advanced mode, which on Pixel 10 runs
 * on Gemini Nano through AICore (the SDK drops to its Basic on-device model elsewhere).
 * When that isn't ready, it uses the platform's on-device recognizer (on Pixel: the offline
 * engine behind Live Caption and Live Transcribe). Neither goes through the Google app, so
 * region restrictions on voice search don't apply.
 *
 * Offline packs exist per language *and region* (en-US, not en-PK), and Android 14+ also tags
 * the locale with regional preferences (en-US-u-mu-celsius). Asking for the phone's locale
 * verbatim therefore often finds no pack, so the language is matched against the packs that
 * are already installed before anything is downloaded.
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

    /** Smoothed input level, 0..1, for the listening indicator. */
    var level by mutableFloatStateOf(0f)
        private set

    private var onFinal: (String) -> Unit = {}
    private var onMessage: (String) -> Unit = {}

    // Recognition language: resolved once, then moved to a better-covered region if a pack is missing.
    private var speechLocale: Locale? = null
    private val triedLocales = HashSet<String>()

    /** Picks the language and engine, and runs the GenAI stream. */
    private var session: Job? = null

    // ML Kit GenAI path.
    private var genAiRecognizer: GenAiSpeechRecognizer? = null
    private var genAiLocale: Locale? = null
    private var genAiUnusable = false
    private var genAiDownloading = false
    private var genAiListening = false
    private var silenceTimer: Job? = null

    // Platform fallback.
    private var recognizer: SpeechRecognizer? = null

    fun start(onFinal: (String) -> Unit, onMessage: (String) -> Unit) {
        if (isListening) return
        this.onFinal = onFinal
        this.onMessage = onMessage
        begin()
    }

    /** Stops listening; whatever was heard is still delivered through onFinal. */
    fun stop() {
        val genAi = genAiRecognizer
        when {
            genAiListening && genAi != null -> scope.launch { stopGenAi(genAi) }
            session?.isActive == true -> {
                // Still choosing an engine, so nothing has been heard yet.
                session?.cancel()
                resetState()
            }
            else -> recognizer?.stopListening()
        }
    }

    fun release() {
        silenceTimer?.cancel()
        session?.cancel()
        session = null
        genAiListening = false
        genAiRecognizer?.close()
        genAiRecognizer = null
        genAiLocale = null
        recognizer?.destroy()
        recognizer = null
        resetState()
    }

    private fun begin() {
        partialText = ""
        level = 0f
        isListening = true
        session = scope.launch {
            val target = recognitionLocale()
            val genAi = if (genAiUnusable) null else readyGenAiRecognizer(target)
            if (genAi == null) startPlatform(target) else listenWithGenAi(genAi, target)
        }
    }

    private fun resetState() {
        isListening = false
        partialText = ""
        level = 0f
    }

    private suspend fun recognitionLocale(): Locale {
        speechLocale?.let { return it }
        val preferred = plainLocale(Locale.getDefault())
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) installedOnDeviceLanguages() else emptyList()
        val chosen = pickLocale(preferred, installed)
        triedLocales += chosen.toLanguageTag()
        speechLocale = chosen
        return chosen
    }

    /** Language tags whose offline packs are already on the phone, or empty if the system won't say. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun installedOnDeviceLanguages(): List<String> {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return emptyList()
        val probe = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            return emptyList()
        }
        return try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                suspendCancellableCoroutine<List<String>> { continuation ->
                    val callback = object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            if (continuation.isActive) continuation.resume(installedLanguages(support))
                        }

                        override fun onError(error: Int) {
                            if (continuation.isActive) continuation.resume(emptyList())
                        }
                    }
                    try {
                        probe.checkRecognitionSupport(recognitionIntent(Locale.getDefault()), context.mainExecutor, callback)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                }
            }.orEmpty()
        } finally {
            probe.destroy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun installedLanguages(support: RecognitionSupport): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            support.installedOnDeviceLanguages
        } else {
            @Suppress("DEPRECATION")
            support.installedLanguages
        }

    /** The GenAI recognizer when its model is on the device; otherwise null, kicking off a download if offered. */
    private suspend fun readyGenAiRecognizer(target: Locale): GenAiSpeechRecognizer? {
        if (genAiLocale != target) {
            genAiRecognizer?.close()
            genAiRecognizer = null
        }
        val genAi = genAiRecognizer ?: SpeechRecognition.getClient(
            speechRecognizerOptions {
                locale = target
                preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
            }
        ).also {
            genAiRecognizer = it
            genAiLocale = target
        }

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

    private suspend fun listenWithGenAi(genAi: GenAiSpeechRecognizer, target: Locale) {
        val committed = StringBuilder()
        var failed = false
        genAiListening = true
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
            genAiListening = false
            sessionCap.cancel()
            silenceTimer?.cancel()
        }

        val heard = committed.toString().trim().ifEmpty { partialText.trim() }
        if (failed && heard.isEmpty()) {
            // Don't keep failing on this path; the platform recognizer takes over from here.
            genAiUnusable = true
            genAiRecognizer?.close()
            genAiRecognizer = null
            genAiLocale = null
            partialText = ""
            startPlatform(target)
            return
        }
        resetState()
        if (heard.isNotEmpty()) onFinal(heard)
    }

    private suspend fun stopGenAi(genAi: GenAiSpeechRecognizer) {
        try {
            genAi.stopRecognition()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session?.cancel()
            genAiListening = false
            resetState()
        }
    }

    private fun join(committed: CharSequence, partial: String): String =
        if (committed.isEmpty()) partial.trim() else "$committed ${partial.trim()}".trimEnd()

    private fun startPlatform(target: Locale) {
        val activeRecognizer = recognizer ?: createRecognizer()?.also { recognizer = it }
        if (activeRecognizer == null) {
            resetState()
            message(R.string.voice_unavailable)
            return
        }
        isListening = true
        activeRecognizer.startListening(recognitionIntent(target))
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

    private fun recognitionIntent(target: Locale): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, target.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    private fun message(@StringRes id: Int, vararg args: Any) {
        onMessage(context.getString(id, *args))
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        resetState()
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
        resetState()

        when (error) {
            // Silence or nothing intelligible: keep any words already heard and stop quietly.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (heard.isNotEmpty()) onFinal(heard)

            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                val current = speechLocale ?: plainLocale(Locale.getDefault())
                val fallback = fallbackLocale(current)
                if (heard.isEmpty() && fallback != null && triedLocales.add(fallback.toLanguageTag())) {
                    // No pack for this region; the language's main region nearly always has one.
                    speechLocale = fallback
                    genAiUnusable = false
                    begin()
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    recognizer?.triggerModelDownload(recognitionIntent(current))
                }
                if (genAiDownloading) {
                    message(R.string.voice_model_downloading)
                } else {
                    message(R.string.voice_language_downloading, current.getDisplayName(current))
                }
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> message(R.string.voice_permission_denied)

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> message(R.string.voice_busy)

            else -> {
                // Client/server faults can leave the recognizer unusable; rebuild it next time.
                recognizer?.destroy()
                recognizer = null
                if (heard.isNotEmpty()) onFinal(heard) else message(R.string.voice_error)
            }
        }
    }

    override fun onRmsChanged(rmsdB: Float) {
        val target = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        level = level * (1f - LEVEL_SMOOTHING) + target * LEVEL_SMOOTHING
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
        const val PROBE_TIMEOUT_MS = 1_500L

        /** The GenAI stream has no input level, so the indicator pulses while words arrive. */
        const val PEAK_LEVEL = 0.6f
        const val LEVEL_SMOOTHING = 0.4f
    }
}

/** Language, script and region only: recognizers reject tags carrying -u- preference extensions. */
internal fun plainLocale(locale: Locale): Locale =
    Locale.forLanguageTag(listOf(locale.language, locale.script, locale.country).filter { it.isNotEmpty() }.joinToString("-"))

/**
 * The phone's own locale when its pack is installed; otherwise an installed pack in the same
 * language, preferring that language's main region (en-PK speaks through en-US).
 */
internal fun pickLocale(preferred: Locale, installedTags: List<String>): Locale {
    val installed = installedTags.map { plainLocale(Locale.forLanguageTag(it)) }
    if (preferred in installed) return preferred
    val sameLanguage = installed.filter { it.language == preferred.language }
    if (sameLanguage.isEmpty()) return preferred
    val main = fallbackLocale(preferred)
    return sameLanguage.firstOrNull { it == main } ?: sameLanguage.first()
}

/** The region of [locale]'s language with the widest offline coverage, unless [locale] is already it. */
internal fun fallbackLocale(locale: Locale): Locale? {
    val region = MAIN_REGIONS[locale.language] ?: return null
    if (locale.country.equals(region, ignoreCase = true)) return null
    return Locale.forLanguageTag("${locale.language}-$region")
}

private val MAIN_REGIONS = mapOf(
    "en" to "US",
    "es" to "US",
    "fr" to "FR",
    "de" to "DE",
    "it" to "IT",
    "pt" to "BR",
    "ja" to "JP",
    "ko" to "KR",
    "hi" to "IN",
    "ru" to "RU",
    "nl" to "NL",
    "tr" to "TR",
)

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
