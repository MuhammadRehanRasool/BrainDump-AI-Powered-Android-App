package dev.braindump.splitter.domain.model

data class Idea(
    val id: Long,
    val rawInput: String,
    val title: String,
    val concept: String,
    val techStack: List<String>,
    val tasks: List<MicroTask>,
    val category: IdeaCategory,
    val status: IdeaStatus,
    val error: String?,
    val createdAt: Long,
) {
    val completedCount: Int get() = tasks.count { it.done }

    /** What to call the idea before Gemini Nano has titled it. */
    val displayTitle: String get() = title.ifBlank { rawInput }
}

data class MicroTask(
    val index: Int,
    val text: String,
    val done: Boolean,
)

enum class IdeaStatus { PENDING, READY, FAILED }

/** A fixed set, so ideas can be filtered and colored consistently. Gemini Nano picks one per split. */
enum class IdeaCategory {
    PERSONAL, WORK, HEALTH, LEARNING, MONEY, CREATIVE, TECH, OTHER;

    companion object {
        /** Stored or model-written name, tolerating case and common synonyms. Unknown means [OTHER]. */
        fun parse(value: String?): IdeaCategory {
            val key = value?.lowercase()?.filter(Char::isLetter).orEmpty()
            if (key.isEmpty()) return OTHER
            entries.firstOrNull { it.name.lowercase() == key }?.let { return it }
            return SYNONYMS.entries.firstOrNull { (_, words) -> words.any { key.startsWith(it) } }?.key ?: OTHER
        }

        private val SYNONYMS = mapOf(
            PERSONAL to listOf("life", "family", "home", "relationship", "habit", "time", "productivity", "self"),
            WORK to listOf("job", "career", "business", "office", "meeting", "client"),
            HEALTH to listOf("fitness", "exercise", "sleep", "diet", "food", "mental", "wellness", "sport"),
            LEARNING to listOf("study", "education", "school", "course", "reading", "book", "research"),
            MONEY to listOf("finance", "budget", "saving", "invest", "shopping", "expense"),
            CREATIVE to listOf("art", "music", "writing", "design", "content", "video", "photo"),
            TECH to listOf("software", "technology", "coding", "code", "app", "programming", "dev"),
        )
    }
}
