package dev.braindump.splitter.data.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
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
    /** Gemini Nano can't run here; retrying won't help. */
    class Unsupported(message: String, cause: Throwable? = null) : SplitException(message, cause, retryable = false)

    /** Not now: app in background, model downloading, or quota hit. Doesn't count as an attempt. */
    class Deferred(message: String, cause: Throwable? = null) : SplitException(message, cause, retryable = true)

    /** A one-off inference hiccup; worth another go. */
    class Transient(message: String, cause: Throwable? = null) : SplitException(message, cause, retryable = true)

    /** The model answered, but never with JSON that passed validation. */
    class MalformedOutput(message: String) : SplitException(message, retryable = true)

    class EmptyInput : SplitException("Nothing to split", retryable = false)
}

/**
 * Thread-safe owner of the ML Kit GenAI Prompt client, which runs the system Gemini Nano
 * that AICore already has on the device (no app-private model copy).
 *
 * Requests are serialized through [mutex] because AICore runs one inference at a time.
 * The JSON contract is enforced in steps: a system instruction (when supported) with the
 * schema and an example, then a parser that validates and normalizes the answer, then one
 * stricter regeneration if the first answer fails validation.
 */
@Singleton
class AICoreManager @Inject constructor(
    private val json: Json,
) {
    private val mutex = Mutex()
    private val downloadMutex = Mutex()

    // Guarded by mutex.
    private var client: GenerativeModel? = null
    private var systemPromptSupported: Boolean? = null
    private var warmedUp = false

    private val _status = MutableStateFlow<AiStatus>(AiStatus.Unknown)
    val status: StateFlow<AiStatus> = _status.asStateFlow()

    /** Checks availability, downloads the model if AICore offers one, and warms it up. */
    suspend fun warmUp() {
        val featureStatus = try {
            mutex.withLock { checkStatusLocked() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = AiStatus.Unavailable(describe(e))
            return
        }

        when (featureStatus) {
            FeatureStatus.AVAILABLE -> try {
                mutex.withLock {
                    if (!warmedUp) {
                        clientLocked().warmup()
                        warmedUp = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Warm-up only shaves latency off the first request; inference reports real errors.
            }

            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> download()
        }
    }

    suspend fun splitIdea(rawIdea: String): IdeaBreakdown {
        val input = sanitize(rawIdea)
        if (input.isEmpty()) throw SplitException.EmptyInput()

        return mutex.withLock {
            val featureStatus = try {
                checkStatusLocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classify(e)
            }
            when (featureStatus) {
                FeatureStatus.AVAILABLE -> Unit
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING ->
                    throw SplitException.Deferred("Gemini Nano is still downloading")
                else -> throw SplitException.Unsupported(UNAVAILABLE_REASON)
            }

            val model = clientLocked()
            val useSystemInstruction = systemPromptSupportedLocked(model)
            var lastProblem = "Gemini Nano returned no usable JSON"

            for (attempt in 0 until MAX_GENERATION_ATTEMPTS) {
                val output = try {
                    generate(model, input, strict = attempt > 0, useSystemInstruction)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw classify(e)
                }

                if (output.isNullOrBlank()) {
                    lastProblem = "Gemini Nano returned an empty answer"
                    continue
                }
                val parsed = parseBreakdown(output)
                if (parsed != null) {
                    _status.value = AiStatus.Ready
                    return@withLock parsed
                }
                lastProblem = "Gemini Nano's answer didn't match the task schema"
            }
            throw SplitException.MalformedOutput(lastProblem)
        }
    }

    private suspend fun generate(
        model: GenerativeModel,
        input: String,
        strict: Boolean,
        useSystemInstruction: Boolean,
    ): String? {
        val instruction = if (strict) SYSTEM_PROMPT + STRICT_SUFFIX else SYSTEM_PROMPT
        val userTurn = "Input: $INPUT_OPEN$input$INPUT_CLOSE\nOutput:"
        val request = if (useSystemInstruction) {
            generateContentRequest(SystemInstruction(instruction), TextPart(userTurn)) { tune() }
        } else {
            generateContentRequest(TextPart("$instruction\n$userTurn")) { tune() }
        }
        return model.generateContent(request).candidates.firstOrNull()?.text
    }

    private fun com.google.mlkit.genai.prompt.GenerateContentRequest.Builder.tune() {
        temperature = TEMPERATURE
        topK = TOP_K
        candidateCount = 1
        maxOutputTokens = MAX_OUTPUT_TOKENS
    }

    private suspend fun checkStatusLocked(): Int {
        val featureStatus = clientLocked().checkStatus()
        when (featureStatus) {
            FeatureStatus.AVAILABLE -> if (_status.value !is AiStatus.Ready) _status.value = AiStatus.Ready
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING ->
                if (_status.value !is AiStatus.Downloading) _status.value = AiStatus.Downloading(0L, 0L)
            else -> _status.value = AiStatus.Unavailable(UNAVAILABLE_REASON)
        }
        return featureStatus
    }

    private suspend fun download() {
        if (!downloadMutex.tryLock()) return // a download is already being tracked
        try {
            val model = mutex.withLock { clientLocked() }
            var totalBytes = 0L
            model.download().collect { progress ->
                when (progress) {
                    is DownloadStatus.DownloadStarted -> {
                        totalBytes = progress.bytesToDownload
                        _status.value = AiStatus.Downloading(0L, totalBytes)
                    }
                    is DownloadStatus.DownloadProgress ->
                        _status.value = AiStatus.Downloading(progress.totalBytesDownloaded, totalBytes)
                    is DownloadStatus.DownloadCompleted -> _status.value = AiStatus.Ready
                    is DownloadStatus.DownloadFailed -> _status.value = AiStatus.Unavailable(describe(progress.e))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = AiStatus.Unavailable(describe(e))
        } finally {
            downloadMutex.unlock()
        }
    }

    private fun clientLocked(): GenerativeModel =
        client ?: Generation.getClient().also { client = it }

    private suspend fun systemPromptSupportedLocked(model: GenerativeModel): Boolean {
        systemPromptSupported?.let { return it }
        val supported = try {
            model.isSystemPromptAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        systemPromptSupported = supported
        return supported
    }

    private fun classify(e: Exception): SplitException {
        if (e is SplitException) return e
        val message = describe(e)
        if (e !is GenAiException) return SplitException.Transient(message, e)
        return when (e.errorCode) {
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            GenAiException.ErrorCode.BUSY,
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> SplitException.Deferred(message, e)

            GenAiException.ErrorCode.NOT_SUPPORTED,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
            GenAiException.ErrorCode.REQUEST_TOO_LARGE -> {
                _status.value = AiStatus.Unavailable(message)
                SplitException.Unsupported(message, e)
            }

            else -> SplitException.Transient(message, e)
        }
    }

    private fun describe(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

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

    @Serializable
    internal data class IdeaBreakdownDto(
        @SerialName("idea_title") val ideaTitle: String = "",
        @SerialName("raw_concept") val rawConcept: String = "",
        @SerialName("tech_stack_anchors") val techStackAnchors: List<String> = emptyList(),
        @SerialName("immediate_micro_tasks") val immediateMicroTasks: List<String> = emptyList(),
    )

    private companion object {
        const val TEMPERATURE = 0.2f
        const val TOP_K = 16
        const val MAX_OUTPUT_TOKENS = 256
        const val MAX_GENERATION_ATTEMPTS = 2

        // Leaves comfortable room for the instruction inside Gemini Nano's input window.
        const val MAX_INPUT_CHARS = 4_000
        const val REQUIRED_TASKS = 3
        const val MAX_ANCHORS = 5
        const val MAX_ANCHOR_CHARS = 40
        const val MAX_TITLE_CHARS = 80

        const val INPUT_OPEN = "<<<"
        const val INPUT_CLOSE = ">>>"

        const val UNAVAILABLE_REASON =
            "Gemini Nano isn't available. Check that AICore is up to date in the Play Store."

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
