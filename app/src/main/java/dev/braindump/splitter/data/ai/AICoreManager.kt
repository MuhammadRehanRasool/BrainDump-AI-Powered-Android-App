package dev.braindump.splitter.data.ai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.ai.edge.aicore.ConnectionException
import com.google.ai.edge.aicore.DownloadCallback
import com.google.ai.edge.aicore.DownloadConfig
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.braindump.splitter.domain.model.AiStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Clean, validated output of one split. */
data class IdeaBreakdown(
    val ideaTitle: String,
    val rawConcept: String,
    val techStackAnchors: List<String>,
    val immediateMicroTasks: List<String>,
)

sealed class SplitException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean,
) : Exception(message, cause) {
    /** AICore isn't on this device; retrying won't help. */
    class Unsupported(message: String) : SplitException(message, retryable = false)

    /** Binding, download or inference hiccup; worth another go later. */
    class Transient(message: String, cause: Throwable? = null) : SplitException(message, cause, retryable = true)

    /** The model answered, but never with JSON that passed validation. */
    class MalformedOutput(message: String) : SplitException(message, retryable = true)

    class EmptyInput : SplitException("Nothing to split", retryable = false)
}

/**
 * Thread-safe owner of the single on-device [GenerativeModel].
 *
 * Gemini Nano runs one request at a time inside the AICore system service, so every
 * call is serialized through [mutex]. The experimental AI Edge SDK has no native
 * system-instruction or response-schema support, so the JSON contract is enforced by
 * a strict prompt, then a parser that validates and normalizes the result, then one
 * stricter regeneration if the first answer fails validation.
 */
@Singleton
class AICoreManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val mutex = Mutex()

    // Guarded by mutex.
    private var model: GenerativeModel? = null

    private val _status = MutableStateFlow<AiStatus>(AiStatus.Unknown)
    val status: StateFlow<AiStatus> = _status.asStateFlow()

    private val downloadCallback = object : DownloadCallback {
        @Volatile
        private var totalBytes = 0L

        override fun onDownloadPending() {
            _status.value = AiStatus.Downloading(downloadedBytes = 0L, totalBytes = 0L)
        }

        override fun onDownloadStarted(bytesToDownload: Long) {
            totalBytes = bytesToDownload
            _status.value = AiStatus.Downloading(downloadedBytes = 0L, totalBytes = bytesToDownload)
        }

        override fun onDownloadProgress(totalBytesDownloaded: Long) {
            _status.value = AiStatus.Downloading(downloadedBytes = totalBytesDownloaded, totalBytes = totalBytes)
        }

        override fun onDownloadCompleted() {
            _status.value = AiStatus.Checking
        }

        override fun onDownloadFailed(failureStatus: String, e: GenerativeAIException) {
            _status.value = AiStatus.Unavailable("Gemini Nano download failed ($failureStatus)")
        }

        override fun onDownloadDidNotStart(e: GenerativeAIException) {
            _status.value = AiStatus.Unavailable(e.message ?: "Gemini Nano download didn't start")
        }
    }

    /** Binds AICore and prepares the model; failures are reflected in [status]. */
    suspend fun warmUp() {
        try {
            mutex.withLock { ensureModelLocked() }
        } catch (e: SplitException) {
            // Already surfaced through status; the worker retries independently.
        }
    }

    suspend fun splitIdea(rawIdea: String): IdeaBreakdown {
        val input = sanitize(rawIdea)
        if (input.isEmpty()) throw SplitException.EmptyInput()

        return mutex.withLock {
            val engine = ensureModelLocked()
            var lastProblem = "Gemini Nano returned no usable JSON"
            for (attempt in 0 until MAX_GENERATION_ATTEMPTS) {
                val output = try {
                    engine.generateContent(buildPrompt(input, strict = attempt > 0)).text
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GenerativeAIException) {
                    if (e is ConnectionException) releaseModelLocked()
                    throw SplitException.Transient(e.message ?: "Gemini Nano inference failed", e)
                } catch (e: Exception) {
                    releaseModelLocked()
                    throw SplitException.Transient(e.message ?: "Gemini Nano inference failed", e)
                }

                if (output.isNullOrBlank()) {
                    lastProblem = "Gemini Nano returned an empty answer"
                    continue
                }
                val parsed = parseBreakdown(output)
                if (parsed != null) return@withLock parsed
                lastProblem = "Gemini Nano's answer didn't match the task schema"
            }
            throw SplitException.MalformedOutput(lastProblem)
        }
    }

    private suspend fun ensureModelLocked(): GenerativeModel {
        model?.let { return it }

        if (!isAiCoreInstalled()) {
            val reason = "Gemini Nano (AICore) isn't available on this device"
            _status.value = AiStatus.Unavailable(reason)
            throw SplitException.Unsupported(reason)
        }

        if (_status.value !is AiStatus.Downloading) _status.value = AiStatus.Checking
        val candidate = GenerativeModel(
            generationConfig {
                context = this@AICoreManager.context
                temperature = TEMPERATURE
                topK = TOP_K
                candidateCount = 1
                maxOutputTokens = MAX_OUTPUT_TOKENS
            },
            DownloadConfig(downloadCallback),
        )
        try {
            candidate.prepareInferenceEngine()
        } catch (e: CancellationException) {
            candidate.close()
            if (_status.value is AiStatus.Checking) _status.value = AiStatus.Unknown
            throw e
        } catch (e: Exception) {
            candidate.close()
            val reason = e.message?.takeIf { it.isNotBlank() } ?: "AICore couldn't prepare Gemini Nano"
            // Keep showing download progress if the model is still arriving.
            if (_status.value !is AiStatus.Downloading) _status.value = AiStatus.Unavailable(reason)
            throw SplitException.Transient(reason, e)
        }
        model = candidate
        _status.value = AiStatus.Ready
        return candidate
    }

    private fun releaseModelLocked() {
        model?.close()
        model = null
        _status.value = AiStatus.Unknown
    }

    @Suppress("DEPRECATION")
    private fun isAiCoreInstalled(): Boolean = try {
        val packageManager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(AICORE_PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            packageManager.getApplicationInfo(AICORE_PACKAGE, 0)
        }
        info.enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    internal fun parseBreakdown(modelOutput: String): IdeaBreakdown? {
        val start = modelOutput.indexOf('{')
        val end = modelOutput.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        val dto = try {
            json.decodeFromString(IdeaBreakdownDto.serializer(), modelOutput.substring(start, end + 1))
        } catch (e: IllegalArgumentException) { // includes SerializationException
            return null
        }

        val title = cleanLine(dto.ideaTitle).take(MAX_TITLE_CHARS)
        if (title.isEmpty()) return null

        val tasks = dto.immediateMicroTasks.asSequence()
            .map(::cleanLine)
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(REQUIRED_TASKS)
            .toList()
        if (tasks.size != REQUIRED_TASKS) return null

        val anchors = dto.techStackAnchors.asSequence()
            .map(::cleanLine)
            .filter { it.isNotEmpty() && it.length <= MAX_ANCHOR_CHARS }
            .distinctBy { it.lowercase() }
            .take(MAX_ANCHORS)
            .toList()

        return IdeaBreakdown(
            ideaTitle = title,
            rawConcept = dto.rawConcept.trim(),
            techStackAnchors = anchors,
            immediateMicroTasks = tasks,
        )
    }

    private fun cleanLine(value: String): String =
        value.replace(LIST_MARKER, "").trim().trim('"', '\'', '`').trim()

    private fun sanitize(raw: String): String =
        raw.replace(INPUT_OPEN, "").replace(INPUT_CLOSE, "").trim().take(MAX_INPUT_CHARS)

    private fun buildPrompt(input: String, strict: Boolean): String = buildString {
        append(SYSTEM_PROMPT)
        if (strict) append(STRICT_SUFFIX)
        append("\nInput: ").append(INPUT_OPEN).append(input).append(INPUT_CLOSE)
        append("\nOutput:")
    }

    @Serializable
    internal data class IdeaBreakdownDto(
        @SerialName("idea_title") val ideaTitle: String = "",
        @SerialName("raw_concept") val rawConcept: String = "",
        @SerialName("tech_stack_anchors") val techStackAnchors: List<String> = emptyList(),
        @SerialName("immediate_micro_tasks") val immediateMicroTasks: List<String> = emptyList(),
    )

    private companion object {
        const val AICORE_PACKAGE = "com.google.android.aicore"

        const val TEMPERATURE = 0.2f
        const val TOP_K = 16
        const val MAX_OUTPUT_TOKENS = 256
        const val MAX_GENERATION_ATTEMPTS = 2

        // Gemini Nano's input window is small; the prompt itself uses roughly 350 tokens.
        const val MAX_INPUT_CHARS = 1_500
        const val REQUIRED_TASKS = 3
        const val MAX_ANCHORS = 5
        const val MAX_ANCHOR_CHARS = 40
        const val MAX_TITLE_CHARS = 80

        const val INPUT_OPEN = "<<<"
        const val INPUT_CLOSE = ">>>"

        val LIST_MARKER = Regex("""^\s*(?:[-*•]+|\d+[.)])\s*""")

        val SYSTEM_PROMPT = """
            You are BrainDump, a senior engineering lead. Turn the messy idea between <<< and >>> into ONE JSON object.
            Rules:
            - Output ONLY the JSON object. No markdown, no code fences, no commentary.
            - Use exactly these keys: "idea_title", "raw_concept", "tech_stack_anchors", "immediate_micro_tasks".
            - idea_title: at most 8 words, Title Case.
            - raw_concept: 1 or 2 plain sentences saying what is being built and why.
            - tech_stack_anchors: 1 to 5 short technology names that fit the idea.
            - immediate_micro_tasks: EXACTLY 3 strings. Each is a concrete developer action under 15 words, starts with a verb, ordered by leverage.
            Schema: {"idea_title":string,"raw_concept":string,"tech_stack_anchors":[string],"immediate_micro_tasks":[string,string,string]}
            Example input: <<<app that reminds me to drink water maybe with a widget idk and syncs to my watch>>>
            Example output: {"idea_title":"Hydration Reminder Widget","raw_concept":"An Android app that nudges the user to drink water from a home screen widget and mirrors reminders on a smartwatch.","tech_stack_anchors":["Kotlin","Jetpack Glance","WorkManager","Wear OS"],"immediate_micro_tasks":["Scaffold a Compose app with a Room table for intake logs","Build a Glance widget that logs one glass per tap","Schedule hourly reminders with WorkManager"]}
        """.trimIndent()

        const val STRICT_SUFFIX =
            "\nYour previous answer was invalid. Keep every string short and return exactly 3 micro tasks."
    }
}
