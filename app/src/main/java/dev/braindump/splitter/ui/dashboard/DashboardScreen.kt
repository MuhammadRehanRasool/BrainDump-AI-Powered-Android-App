package dev.braindump.splitter.ui.dashboard

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.braindump.splitter.R
import dev.braindump.splitter.domain.model.AiStatus
import dev.braindump.splitter.tile.BrainDumpTileService
import dev.braindump.splitter.ui.capture.CaptureSheet
import dev.braindump.splitter.ui.capture.QuickShortcuts
import dev.braindump.splitter.ui.theme.AccentButton
import dev.braindump.splitter.ui.theme.Glass
import dev.braindump.splitter.ui.theme.GlassBackdrop
import dev.braindump.splitter.ui.theme.GlassIconButton
import dev.braindump.splitter.ui.theme.GlassPanel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DashboardRoute(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.onForeground()
    }

    DashboardScreen(
        state = state,
        onCapture = viewModel::capture,
        onToggleTask = viewModel::toggleTask,
        onRetry = viewModel::retry,
        onDelete = viewModel::delete,
        onUsePreviewModelChange = viewModel::setUsePreviewModel,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onCapture: (String) -> Unit,
    onToggleTask: (Long, Int) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onUsePreviewModelChange: (Boolean) -> Unit,
) {
    // Null while closed; otherwise whether the sheet starts dictating.
    var captureWithVoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val scrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val today = todayEpochDay()

    Box(Modifier.fillMaxSize()) {
        GlassBackdrop()
        Scaffold(
            containerColor = Color.Transparent,
            // A transparent container has no matching "on" color, so name it explicitly.
            contentColor = MaterialTheme.colorScheme.onSurface,
            topBar = {
                TopBar(
                    aiStatus = state.aiStatus,
                    scrolled = scrolled,
                    usePreviewModel = state.usePreviewModel,
                    onUsePreviewModelChange = onUsePreviewModelChange,
                )
            },
            floatingActionButton = {
                CaptureButtons(
                    expanded = fabExpanded,
                    onType = { captureWithVoice = false },
                    onVoice = { captureWithVoice = true },
                )
            },
        ) { innerPadding ->
            // Top padding goes on the list itself so sticky headers pin below the bar.
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
                contentPadding = PaddingValues(top = 4.dp, bottom = innerPadding.calculateBottomPadding() + 104.dp),
            ) {
                val aiStatus = state.aiStatus
                if (aiStatus is AiStatus.Unavailable || aiStatus is AiStatus.Downloading) {
                    item(key = "ai-banner", contentType = "banner") {
                        AiBanner(
                            aiStatus,
                            Modifier
                                .animateItem()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }

                if (!state.isLoading && state.sections.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        EmptyState(Modifier.fillParentMaxHeight(0.7f))
                    }
                }

                state.sections.forEach { section ->
                    stickyHeader(key = "day-${section.epochDay}", contentType = "header") {
                        DayHeader(epochDay = section.epochDay, today = today)
                    }
                    items(
                        items = section.ideas,
                        key = { it.id },
                        contentType = { "idea" },
                    ) { idea ->
                        IdeaCard(
                            idea = idea,
                            onToggleTask = onToggleTask,
                            onRetry = onRetry,
                            onDelete = onDelete,
                            modifier = Modifier
                                .animateItem()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }

    captureWithVoice?.let { voice ->
        CaptureSheet(
            startWithVoice = voice,
            splitting = false,
            hideOnSubmit = true,
            onSubmit = onCapture,
            onDismiss = { captureWithVoice = null },
        )
    }
}

/** Large title over the backdrop; turns into a frosted bar once the list scrolls beneath it. */
@Composable
private fun TopBar(
    aiStatus: AiStatus,
    scrolled: Boolean,
    usePreviewModel: Boolean,
    onUsePreviewModelChange: (Boolean) -> Unit,
) {
    val glass = Glass.colors
    val edge = MaterialTheme.colorScheme.outlineVariant
    val barAlpha by animateFloatAsState(if (scrolled) 1f else 0f, tween(250), label = "bar")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(glass.fillStrong, alpha = barAlpha)
                drawLine(
                    color = edge,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                    alpha = barAlpha * 0.6f,
                )
            }
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            StatusChip(aiStatus, Modifier.padding(top = 6.dp))
        }
        OverflowMenu(
            usePreviewModel = usePreviewModel,
            onUsePreviewModelChange = onUsePreviewModelChange,
        )
    }
}

@Composable
private fun StatusChip(status: AiStatus, modifier: Modifier = Modifier) {
    val glass = Glass.colors
    val dotColor by animateColorAsState(
        targetValue = when (status) {
            is AiStatus.Ready -> glass.success
            is AiStatus.Unavailable -> MaterialTheme.colorScheme.error
            AiStatus.Checking, is AiStatus.Downloading -> glass.warning
            AiStatus.Unknown -> MaterialTheme.colorScheme.outline
        },
        label = "dot",
    )
    val busy = status is AiStatus.Checking || status is AiStatus.Downloading
    GlassPanel(modifier, shape = CircleShape) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(dotColor, busy)
            AnimatedContent(
                targetState = aiStatusLabel(status),
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                label = "status",
            ) { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, busy: Boolean) {
    val dot = Modifier
        .size(8.dp)
        .clip(CircleShape)
    if (!busy) {
        Box(dot.background(color))
        return
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        dot
            .graphicsLayer { this.alpha = alpha }
            .background(color),
    )
}

@Composable
private fun CaptureButtons(expanded: Boolean, onType: () -> Unit, onVoice: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            onClick = onVoice,
            size = 56.dp,
            onClickLabel = stringResource(R.string.dashboard_voice),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = stringResource(R.string.dashboard_voice),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        AccentButton(
            onClick = onType,
            modifier = Modifier.height(56.dp),
            contentPadding = PaddingValues(horizontal = 18.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.dashboard_fab))
            AnimatedVisibility(
                visible = expanded,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                Text(
                    text = stringResource(R.string.dashboard_fab),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun aiStatusLabel(status: AiStatus): String = when (status) {
    AiStatus.Unknown -> stringResource(R.string.ai_status_unknown)
    AiStatus.Checking -> stringResource(R.string.ai_status_checking)
    is AiStatus.Downloading -> status.percent
        ?.let { stringResource(R.string.ai_status_downloading_percent, it) }
        ?: stringResource(R.string.ai_status_downloading)
    is AiStatus.Ready -> status.modelName
        ?.let { stringResource(R.string.ai_status_ready_model, it) }
        ?: stringResource(R.string.ai_status_ready)
    is AiStatus.Unavailable -> stringResource(R.string.ai_status_unavailable)
}

@Composable
private fun AiBanner(status: AiStatus, modifier: Modifier = Modifier) {
    when (status) {
        is AiStatus.Downloading -> GlassPanel(modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = aiStatusLabel(status),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                val progressModifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                val percent = status.percent
                if (percent != null) {
                    val progress by animateFloatAsState(percent / 100f, label = "download")
                    LinearProgressIndicator(progress = { progress }, modifier = progressModifier)
                } else {
                    LinearProgressIndicator(modifier = progressModifier)
                }
            }
        }

        is AiStatus.Unavailable -> GlassPanel(modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Column {
                    Text(stringResource(R.string.ai_status_unavailable), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = status.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        else -> Unit
    }
}

@Composable
private fun DayHeader(epochDay: Long, today: Long) {
    val label = when (epochDay) {
        today -> stringResource(R.string.day_today)
        today - 1 -> stringResource(R.string.day_yesterday)
        else -> remember(epochDay, today) {
            val date = LocalDate.ofEpochDay(epochDay)
            val pattern = if (date.year == LocalDate.ofEpochDay(today).year) "EEEE, MMM d" else "EEE, MMM d, yyyy"
            date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        GlassPanel(shape = CircleShape, strong = true) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val float = rememberInfiniteTransition(label = "float")
    val offset by float.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "offset",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GlassPanel(
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer { translationY = offset.dp.toPx() },
                shape = CircleShape,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_tile),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(44.dp),
                )
            }
            Text(
                text = stringResource(R.string.dashboard_empty_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = stringResource(R.string.dashboard_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun OverflowMenu(
    usePreviewModel: Boolean,
    onUsePreviewModelChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val canPin = remember(context) { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }
    val canAddTile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var open by remember { mutableStateOf(false) }
    Box {
        GlassIconButton(onClick = { open = true }, strong = false) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.dashboard_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.dashboard_use_preview)) },
                trailingIcon = { Checkbox(checked = usePreviewModel, onCheckedChange = null) },
                onClick = {
                    open = false
                    onUsePreviewModelChange(!usePreviewModel)
                },
            )
            if (canAddTile) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.dashboard_add_tile)) },
                    onClick = {
                        open = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestAddTile(context)
                    },
                )
            }
            if (canPin) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.dashboard_pin_voice)) },
                    onClick = {
                        open = false
                        QuickShortcuts.pinToHomeScreen(context, voice = true)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.dashboard_pin_type)) },
                    onClick = {
                        open = false
                        QuickShortcuts.pinToHomeScreen(context, voice = false)
                    },
                )
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestAddTile(context: Context) {
    val statusBarManager = context.getSystemService(StatusBarManager::class.java) ?: return
    statusBarManager.requestAddTileService(
        ComponentName(context, BrainDumpTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor,
    ) { result ->
        val message = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.dashboard_tile_added
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.dashboard_tile_already_added
            else -> R.string.dashboard_tile_not_added
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
