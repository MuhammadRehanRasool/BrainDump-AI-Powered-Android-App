package dev.braindump.splitter.domain.model

data class Idea(
    val id: Long,
    val rawInput: String,
    val title: String,
    val concept: String,
    val techStack: List<String>,
    val tasks: List<MicroTask>,
    val status: IdeaStatus,
    val error: String?,
    val createdAt: Long,
) {
    val completedCount: Int get() = tasks.count { it.done }
}

data class MicroTask(
    val index: Int,
    val text: String,
    val done: Boolean,
)

enum class IdeaStatus { PENDING, READY, FAILED }
