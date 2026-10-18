package dev.braindump.splitter.ui.dashboard

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.braindump.splitter.R
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.domain.model.IdeaCategory
import dev.braindump.splitter.domain.model.IdeaStatus
import dev.braindump.splitter.domain.model.MicroTask
import dev.braindump.splitter.ui.theme.Glass
import dev.braindump.splitter.ui.theme.GlassPanel
import dev.braindump.splitter.ui.theme.rememberPressScale
import java.util.Date

@Composable
fun IdeaCard(
    idea: Idea,
    onToggleTask: (Long, Int) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCategoryChange: (Long, IdeaCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(idea.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val time = remember(idea.createdAt) { DateFormat.getTimeFormat(context).format(Date(idea.createdAt)) }
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.98f)
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chevron",
    )

    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale()
                scaleY = scale()
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = stringResource(if (expanded) R.string.idea_collapse else R.string.idea_expand),
            ) { expanded = !expanded },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(idea)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 14.dp, end = 8.dp),
                ) {
                    when (idea.status) {
                        IdeaStatus.READY -> Text(
                            text = idea.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        IdeaStatus.PENDING, IdeaStatus.FAILED -> Text(
                            text = idea.rawInput,
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = FontStyle.Italic,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = statusLine(idea, time),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (idea.status == IdeaStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (idea.status == IdeaStatus.READY) CategoryTag(idea.category, Modifier.padding(start = 8.dp))
                    }
                }
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = chevron },
                )
            }

            if (idea.status == IdeaStatus.READY && idea.techStack.isNotEmpty()) TechAnchors(idea.techStack)

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)) +
                    fadeIn(tween(durationMillis = 220, delayMillis = 60)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium, visibilityThreshold = IntSize.VisibilityThreshold)) +
                    fadeOut(tween(durationMillis = 120)),
            ) {
                ExpandedDetails(idea, onToggleTask, onRetry, onDelete, onCategoryChange)
            }
        }
    }
}

private enum class Badge { PROGRESS, SPLITTING, WAITING, FAILED }

private fun badgeOf(idea: Idea): Badge = when (idea.status) {
    IdeaStatus.READY -> Badge.PROGRESS
    IdeaStatus.PENDING -> if (idea.error == null) Badge.SPLITTING else Badge.WAITING
    IdeaStatus.FAILED -> Badge.FAILED
}

/** Leading badge: a ring that fills as tasks get done, or the split state while there are none yet. */
@Composable
private fun StatusBadge(idea: Idea) {
    Crossfade(targetState = badgeOf(idea), animationSpec = tween(300), label = "badge") { badge ->
        Box(Modifier.size(BADGE_SIZE), contentAlignment = Alignment.Center) {
            when (badge) {
                Badge.PROGRESS -> ProgressRing(done = idea.completedCount, total = idea.tasks.size)
                Badge.SPLITTING -> CircularProgressIndicator(
                    strokeWidth = 3.dp,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier.size(26.dp),
                )
                Badge.WAITING -> BadgeIcon(Icons.Filled.Info, MaterialTheme.colorScheme.onSurfaceVariant)
                Badge.FAILED -> BadgeIcon(Icons.Filled.Warning, MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun BadgeIcon(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(BADGE_SIZE)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ProgressRing(done: Int, total: Int) {
    val glass = Glass.colors
    val complete = total > 0 && done >= total
    val progress by animateFloatAsState(
        targetValue = if (total == 0) 0f else done.toFloat() / total,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "progress",
    )
    val arcColor by animateColorAsState(
        targetValue = if (complete) glass.success else MaterialTheme.colorScheme.primary,
        label = "arc",
    )
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(BADGE_SIZE)) {
            val stroke = 3.5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                color = arcColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Crossfade(targetState = complete, label = "complete") { isComplete ->
            if (isComplete) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = glass.success, modifier = Modifier.size(20.dp))
            } else {
                Text(
                    text = stringResource(R.string.idea_progress, done, total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun statusLine(idea: Idea, time: String): String = when (idea.status) {
    IdeaStatus.PENDING -> if (idea.error == null) {
        "$time · ${stringResource(R.string.idea_splitting)}"
    } else {
        "$time · ${stringResource(R.string.idea_waiting)}"
    }
    IdeaStatus.FAILED -> "$time · ${stringResource(R.string.idea_failed)}"
    IdeaStatus.READY -> time
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TechAnchors(anchors: List<String>) {
    val primary = MaterialTheme.colorScheme.primary
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        anchors.forEach { anchor ->
            Text(
                text = anchor,
                style = MaterialTheme.typography.labelMedium,
                color = primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(primary.copy(alpha = 0.1f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ExpandedDetails(
    idea: Idea,
    onToggleTask: (Long, Int) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCategoryChange: (Long, IdeaCategory) -> Unit,
) {
    val context = LocalContext.current
    val glass = Glass.colors
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp)) {
        if (idea.status == IdeaStatus.READY) {
            CategoryPicker(idea.category, onPick = { onCategoryChange(idea.id, it) })
            if (idea.concept.isNotBlank()) {
                Text(
                    text = idea.concept,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            idea.tasks.forEach { task ->
                TaskRow(task = task, onToggle = { onToggleTask(idea.id, task.index) })
            }
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(glass.inset)
                    .padding(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.idea_original),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = idea.rawInput,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (idea.status != IdeaStatus.READY && !idea.error.isNullOrBlank()) {
            Text(
                text = idea.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            if (idea.status == IdeaStatus.FAILED) {
                ActionPill(Icons.Filled.Refresh, stringResource(R.string.idea_retry), MaterialTheme.colorScheme.primary) {
                    onRetry(idea.id)
                }
            }
            ActionPill(Icons.Filled.Share, stringResource(R.string.idea_share), MaterialTheme.colorScheme.primary) {
                shareIdea(context, idea)
            }
            ActionPill(Icons.Filled.Delete, stringResource(R.string.idea_delete), MaterialTheme.colorScheme.error) {
                confirmDelete = true
            }
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            title = idea.displayTitle,
            onConfirm = {
                confirmDelete = false
                onDelete(idea.id)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Deleting can't be undone, so it asks first and names the idea. */
@Composable
private fun DeleteDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.idea_delete_title)) },
        text = {
            Text(
                text = stringResource(R.string.idea_delete_body, title),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.idea_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.idea_delete_cancel)) }
        },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = Glass.colors.fillStrong,
        tonalElevation = 0.dp,
    )
}

/** "Category: 💼 Work ▾", for when Gemini Nano filed it in the wrong place. */
@Composable
private fun CategoryPicker(current: IdeaCategory, onPick: (IdeaCategory) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.idea_category),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Box {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.DropdownList, onClick = { open = true }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryTag(current)
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = stringResource(R.string.idea_category_change),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                IdeaCategory.entries.forEach { category ->
                    DropdownMenuItem(
                        text = { Text("${category.emoji}  ${stringResource(category.label)}") },
                        trailingIcon = if (category == current) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            open = false
                            if (category != current) onPick(category)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionPill(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.92f)
    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale()
                scaleY = scale()
            }
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.1f))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun TaskRow(task: MicroTask, onToggle: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val fill by animateColorAsState(
        targetValue = if (task.done) primary else Color.Transparent,
        animationSpec = tween(180),
        label = "fill",
    )
    val edge by animateColorAsState(
        targetValue = if (task.done) primary else MaterialTheme.colorScheme.outline,
        animationSpec = tween(180),
        label = "edge",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (task.done) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "check",
    )
    val textAlpha by animateFloatAsState(if (task.done) 0.5f else 1f, tween(200), label = "text")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = task.done, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(fill)
                .border(2.dp, edge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                    },
            )
        }
        Text(
            text = task.text,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (task.done) TextDecoration.LineThrough else null,
            modifier = Modifier
                .padding(start = 12.dp)
                .graphicsLayer { alpha = textAlpha },
        )
    }
}

private val BADGE_SIZE = 40.dp
