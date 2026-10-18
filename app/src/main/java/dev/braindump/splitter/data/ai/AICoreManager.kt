package dev.braindump.splitter.data.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import dev.braindump.splitter.domain.model.AiStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
 * schema and an example, then a lenient parser that validates and normalizes the answer, then
 * one stricter regeneration, then the other builds AICore has installed (nano-v3 misses the
 * schema more often than nano-v4), and finally a salvage of any plain numbered list.
 */
@Singleton
class AICoreManager @Inject constructor(
    private val json: Json,
    private val settings: AiSettings,
) {
    private val mutex = Mutex()
    private val downloadMutex = Mutex()

    // Guarded by mutex.
    private var client: GenerativeModel? = null
    private var activeOption: ModelOption? = null
    private var activeModelName: String? = null
    private var systemPromptSupported: Boolean? = null
    private var warmedUp = false

    // Preview builds that said AVAILABLE but then failed inference (e.g. the Pixel 10 Nano 4
    // FULL package missing its TPU config). Skipped until preview is toggled again.
    private val brokenOptions = mutableSetOf<ModelOption>()

    private val _status = MutableStateFlow<AiStatus>(AiStatus.Unknown)
    val status: StateFlow<AiStatus> = _status.asStateFlow()

    /** Drops the current client so the next call re-runs model selection (e.g. after a settings change). */
    suspend fun resetModel() {
        mutex.withLock {
            dropClientLocked()
            brokenOptions.clear()
            _status.value = AiStatus.Unknown
        }
    }

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

            var model = clientLocked()
            var useSystemInstruction = systemPromptSupportedLocked(model)
            val answers = ArrayList<String>()
            var lastProblem = "Gemini Nano returned no usable JSON"

            var attempt = 0
            while (attempt < MAX_GENERATION_ATTEMPTS) {
                val output = try {
                    // The retry puts the rules in the user turn; some Nano builds follow them better there.
                    generate(model, input, strict = attempt > 0, useSystemInstruction && attempt == 0)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val failure = classify(e)
                    if (!demotePreviewLocked(e, failure)) throw failure
                    // Same attempt again, on the next model in line (this also refreshes the status name).
                    val nextReady = try {
                        checkStatusLocked() == FeatureStatus.AVAILABLE
                    } catch (c: CancellationException) {
                        throw c
                    } catch (statusError: Exception) {
                        false
                    }
                    if (!nextReady) throw failure
                    model = clientLocked()
                    useSystemInstruction = systemPromptSupportedLocked(model)
                    continue
                }
                attempt++

                if (output.isNullOrBlank()) {
                    lastProblem = "Gemini Nano returned an empty answer"
                    continue
                }
                answers += output
                val parsed = parseBreakdown(output)
                if (parsed != null) return@withLock parsed
                lastProblem = "Gemini Nano's answer didn't match the task schema"
            }

            // This build can't follow the schema for this dump; another installed build often can.
            splitWithOtherModelsLocked(input, answers)
                ?: salvageBreakdown(answers, input)
                ?: throw SplitException.MalformedOutput(lastProblem)
        }
    }

    /**
     * Tries every other build AICore can serve right now. One that returns valid JSON becomes
     * the active model for the rest of the session, so later splits go straight to it.
     */
    private suspend fun splitWithOtherModelsLocked(input: String, answers: MutableList<String>): IdeaBreakdown? {
        val current = activeOption
        for (option in fallbackOptions()) {
            if (option == current || option in brokenOptions) continue
            val candidate = Generation.getClient(configFor(option))
            var promoted = false
            try {
                if (candidate.checkStatus() != FeatureStatus.AVAILABLE) continue
                val useSystemInstruction = try {
                    candidate.isSystemPromptAvailable()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                for (attempt in 0 until MAX_GENERATION_ATTEMPTS) {
                    val output = generate(candidate, input, strict = attempt > 0, useSystemInstruction && attempt == 0)
                    if (output.isNullOrBlank()) continue
                    answers += output
                    val parsed = parseBreakdown(output) ?: continue
                    promoteLocked(option, candidate, useSystemInstruction)
                    promoted = true
                    return parsed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // This build can't serve right now; the next one may.
            } finally {
                if (!promoted) candidate.close()
            }
        }
        return null
    }

    private suspend fun promoteLocked(option: ModelOption, model: GenerativeModel, useSystemInstruction: Boolean) {
        dropClientLocked()
        client = model
        activeOption = option
        systemPromptSupported = useSystemInstruction
        activeModelName = baseModelName(model)
        _status.value = AiStatus.Ready(activeModelName)
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
        val model = clientLocked()
        val featureStatus = model.checkStatus()
        when (featureStatus) {
            FeatureStatus.AVAILABLE -> {
                if (activeModelName == null) activeModelName = baseModelName(model)
                _status.value = AiStatus.Ready(activeModelName)
            }
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING ->
                if (_status.value !is AiStatus.Downloading) _status.value = AiStatus.Downloading(0L, 0L)
            else -> {
                _status.value = AiStatus.Unavailable(UNAVAILABLE_REASON)
                // Re-run model selection next time; AICore may have updated or finished a download meanwhile.
                dropClientLocked()
            }
        }
        return featureStatus
    }

    private fun dropClientLocked() {
        client?.close()
        client = null
        activeOption = null
        activeModelName = null
        systemPromptSupported = null
        warmedUp = false
    }

    /**
     * A preview model can report AVAILABLE and still fail every inference, so a hard
     * inference error on one marks it broken and falls back to the next option. Returns
     * whether the caller should retry on the newly selected model.
     */
    private fun demotePreviewLocked(e: Exception, failure: SplitException): Boolean {
        val option = activeOption ?: return false
        if (option.releaseStage != ModelReleaseStage.PREVIEW) return false
        if (e !is GenAiException || failure !is SplitException.Transient) return false
        brokenOptions += option
        dropClientLocked()
        return true
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
                    is DownloadStatus.DownloadCompleted -> _status.value = AiStatus.Ready()
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

    /**
     * Picks the best model AICore can serve, following Google's guidance: preview builds
     * first when opted in, then stable FULL, then stable FAST. A ready model wins over one
     * that still needs downloading. The SDK doesn't throw at client creation, so
     * checkStatus() is the availability probe.
     */
    private suspend fun clientLocked(): GenerativeModel {
        client?.let { return it }

        var downloadable: GenerativeModel? = null
        var downloadableOption: ModelOption? = null
        for (option in modelOptions().filterNot { it in brokenOptions }) {
            val candidate = Generation.getClient(configFor(option))
            val featureStatus = try {
                candidate.checkStatus()
            } catch (e: CancellationException) {
                candidate.close()
                downloadable?.close()
                throw e
            } catch (e: Exception) {
                FeatureStatus.UNAVAILABLE
            }

            when {
                featureStatus == FeatureStatus.AVAILABLE -> {
                    downloadable?.close()
                    client = candidate
                    activeOption = option
                    return candidate
                }
                downloadable == null && (featureStatus == FeatureStatus.DOWNLOADABLE ||
                    featureStatus == FeatureStatus.DOWNLOADING) -> {
                    downloadable = candidate
                    downloadableOption = option
                }
                else -> candidate.close()
            }
        }

        // Nothing ready: keep a downloadable model, or the default client so checkStatus() explains why.
        val chosen = downloadable ?: Generation.getClient()
        client = chosen
        activeOption = downloadableOption
        return chosen
    }

    private fun modelOptions(): List<ModelOption> = buildList {
        if (settings.usePreviewModel.value) addAll(previewOptions)
        add(ModelOption(ModelReleaseStage.STABLE, ModelPreference.FULL))
        add(ModelOption(ModelReleaseStage.STABLE, ModelPreference.FAST))
    }

    /** The usual order, then preview builds even when not opted in: better than failing the split. */
    private fun fallbackOptions(): List<ModelOption> = (modelOptions() + previewOptions).distinct()

    private val previewOptions = listOf(
        ModelOption(ModelReleaseStage.PREVIEW, ModelPreference.FULL),
        ModelOption(ModelReleaseStage.PREVIEW, ModelPreference.FAST),
    )

    private fun configFor(option: ModelOption) = generationConfig {
        modelConfig = modelConfig {
            releaseStage = option.releaseStage
            preference = option.preference
        }
    }

    private suspend fun baseModelName(model: GenerativeModel): String? = try {
        model.getBaseModelName()?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private data class ModelOption(val releaseStage: Int, val preference: Int)

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
        if (start < 0) return null
        return jsonCandidates(modelOutput, start)
            .mapNotNull(::decodeObject)
            .mapNotNull(::toBreakdown)
            .firstOrNull()
    }

    /** The answer as written, then repaired copies in case it was cut off at the token limit. */
    private fun jsonCandidates(output: String, start: Int): Sequence<String> = sequence {
        val end = output.lastIndexOf('}')
        if (end > start) yield(output.substring(start, end + 1))
        // Each pass also drops the last, possibly half-written, field.
        var fragment = output.substring(start)
        repeat(MAX_REPAIR_CUTS) {
            yield(closeJson(fragment))
            val cut = fragment.lastIndexOf(',')
            if (cut <= 0) return@sequence
            fragment = fragment.substring(0, cut)
        }
    }

    /** Closes whatever a cut-off answer left open: a string, arrays, the object. */
    private fun closeJson(fragment: String): String {
        val closers = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (c in fragment) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> closers.addLast('}')
                '[' -> closers.addLast(']')
                '}', ']' -> closers.removeLastOrNull()
            }
        }
        val repaired = StringBuilder(fragment)
        if (escaped) repaired.setLength(repaired.length - 1)
        if (inString) repaired.append('"')
        var end = repaired.length
        while (end > 0 && (repaired[end - 1].isWhitespace() || repaired[end - 1] == ',')) end--
        repaired.setLength(end)
        if (repaired.endsWith(':')) repaired.append("null")
        while (closers.isNotEmpty()) repaired.append(closers.removeLast())
        return repaired.toString()
    }

    private fun decodeObject(candidate: String): JsonObject? = try {
        json.parseToJsonElement(candidate) as? JsonObject
    } catch (e: IllegalArgumentException) { // includes SerializationException
        null
    }

    private fun toBreakdown(answer: JsonObject): IdeaBreakdown? {
        // Smaller builds drift on key names and shapes, so keys match loosely and lists may arrive as one string.
        val fields = answer.entries.associate { (key, value) -> key.lowercase().filter(Char::isLetterOrDigit) to value }
        fun field(names: List<String>, hint: String): JsonElement? =
            names.firstNotNullOfOrNull { fields[it] } ?: fields.entries.firstOrNull { hint in it.key }?.value

        val title = cleanLine(field(TITLE_KEYS, "title")?.let(::text).orEmpty()).take(MAX_TITLE_CHARS)
        if (title.isEmpty()) return null

        val tasks = field(TASK_KEYS, "task")?.let { items(it, TASK_SEPARATORS) }.orEmpty().asSequence()
            .map(::cleanLine)
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(REQUIRED_TASKS)
            .toList()
        if (tasks.size != REQUIRED_TASKS) return null

        val anchors = field(ANCHOR_KEYS, "stack")?.let { items(it, ANCHOR_SEPARATORS) }.orEmpty().asSequence()
            .map(::cleanLine)
            .filter { it.isNotEmpty() && it.length <= MAX_ANCHOR_CHARS }
            .distinctBy { it.lowercase() }
            .take(MAX_ANCHORS)
            .toList()

        return IdeaBreakdown(
            ideaTitle = title,
            rawConcept = field(CONCEPT_KEYS, "concept")?.let(::text).orEmpty().trim(),
            techStackAnchors = anchors,
            immediateMicroTasks = tasks,
        )
    }

    /** A value as text; for an object such as {"task": "...", "priority": 1}, its first string. */
    private fun text(element: JsonElement): String = when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonArray -> element.joinToString(" ") { text(it) }
        is JsonObject -> (element.values.firstOrNull { it is JsonPrimitive && it.isString } ?: element.values.firstOrNull())
            ?.let(::text)
            .orEmpty()
    }

    private fun items(element: JsonElement, separators: Regex): List<String> =
        if (element is JsonArray) element.map(::text) else text(element).split(separators)

    /** Last resort: an answer that ignored JSON but still listed steps keeps the dump useful. */
    private fun salvageBreakdown(answers: List<String>, input: String): IdeaBreakdown? {
        for (answer in answers.asReversed()) {
            val steps = answer.lineSequence()
                .filter { LIST_MARKER.containsMatchIn(it) }
                .map(::cleanLine)
                .filter { it.length in MIN_TASK_CHARS..MAX_TASK_CHARS && '{' !in it && '}' !in it }
                .distinctBy { it.lowercase() }
                .take(REQUIRED_TASKS)
                .toList()
            if (steps.size == REQUIRED_TASKS) {
                return IdeaBreakdown(
                    ideaTitle = titleFrom(input),
                    rawConcept = "",
                    techStackAnchors = emptyList(),
                    immediateMicroTasks = steps,
                )
            }
        }
        return null
    }

    private fun titleFrom(input: String): String =
        input.split(WHITESPACE)
            .filter { it.isNotBlank() }
            .take(SALVAGE_TITLE_WORDS)
            .joinToString(" ")
            .replaceFirstChar { it.titlecase() }
            .take(MAX_TITLE_CHARS)

    private fun cleanLine(value: String): String =
        value.replace(LIST_MARKER, "").trim().trim('"', '\'', '`').trim()

    private fun sanitize(raw: String): String =
        raw.replace(INPUT_OPEN, "").replace(INPUT_CLOSE, "").trim().take(MAX_INPUT_CHARS)

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
        const val MIN_TASK_CHARS = 4
        const val MAX_TASK_CHARS = 160
        const val SALVAGE_TITLE_WORDS = 6
        const val MAX_REPAIR_CUTS = 4

        // Keys compared lowercase with punctuation stripped, so "ideaTitle" and "idea_title" both match.
        val TITLE_KEYS = listOf("ideatitle", "title", "name")
        val CONCEPT_KEYS = listOf("rawconcept", "concept", "summary", "description")
        val ANCHOR_KEYS = listOf("techstackanchors", "techstack", "stack", "technologies", "tech", "anchors")
        val TASK_KEYS = listOf("immediatemicrotasks", "microtasks", "tasks", "immediatetasks", "nextsteps", "steps", "actions")

        val TASK_SEPARATORS = Regex("""\n|;|\s+(?=\d+[.)]\s)""")
        val ANCHOR_SEPARATORS = Regex("""[\n,;|]""")
        val WHITESPACE = Regex("""\s+""")

        const val INPUT_OPEN = "<<<"
        const val INPUT_CLOSE = ">>>"

        const val UNAVAILABLE_REASON =
            "Gemini Nano isn't available. Update AICore in the Play Store; it won't run on an unlocked bootloader."

        val LIST_MARKER = Regex("""^\s*(?:[-*•]+|\d+[.)])\s*""")

        val SYSTEM_PROMPT = """
            You are BrainDump, a senior engineering lead. Turn the messy idea between <<< and >>> into ONE JSON object.
            Rules:
            - Output ONLY the JSON object. No markdown, no code fences, no commentary.
            - Use exactly these keys: "idea_title", "raw_concept", "tech_stack_anchors", "immediate_micro_tasks".
            - Every value is a plain string or a list of plain strings. Keep it short; stop right after the closing brace.
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
