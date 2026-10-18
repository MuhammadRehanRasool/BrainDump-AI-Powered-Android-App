package dev.braindump.splitter.ui.dashboard

import android.content.Context
import android.content.Intent
import dev.braindump.splitter.R
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.domain.model.IdeaStatus

/** Opens the system share sheet with the idea as plain text, ready for notes, chats or issues. */
internal fun shareIdea(context: Context, idea: Idea) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, ideaShareText(idea))
    if (idea.title.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, idea.title)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.idea_share_title)))
}

internal fun ideaShareText(idea: Idea): String {
    if (idea.status != IdeaStatus.READY) return idea.rawInput
    return buildString {
        appendLine(idea.title)
        if (idea.concept.isNotBlank()) {
            appendLine()
            appendLine(idea.concept)
        }
        appendLine()
        idea.tasks.forEach { task -> appendLine("${if (task.done) "☑" else "☐"} ${task.text}") }
        if (idea.techStack.isNotEmpty()) {
            appendLine()
            appendLine(idea.techStack.joinToString(" · "))
        }
    }.trimEnd()
}
