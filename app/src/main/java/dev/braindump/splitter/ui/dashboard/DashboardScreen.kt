package dev.braindump.splitter.ui.dashboard

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onCapture: (String) -> Unit,
    onToggleTask: (Long, Int) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onUsePreviewModelChange: (Boolean) -> Unit,
) {
    var showCapture by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val today = todayEpochDay()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        Text(
                            text = aiStatusLabel(state.aiStatus),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    OverflowMenu(
                        usePreviewModel = state.usePreviewModel,
                        onUsePreviewModelChange = onUsePreviewModelChange,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCapture = true },
                expanded = fabExpanded,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.dashboard_fab)) },
            )
        },
    ) { innerPadding ->
        // Top padding goes on the list itself so sticky headers pin below the app bar.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding() + 96.dp),
        ) {
            val aiStatus = state.aiStatus
            if (aiStatus is AiStatus.Unavailable || aiStatus is AiStatus.Downloading) {
                item(key = "ai-banner", contentType = "banner") {
                    AiBanner(aiStatus, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
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
                            .padding(horizontal = 16.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }

    if (showCapture) {
        CaptureSheet(
            startWithVoice = false,
            splitting = false,
            hideOnSubmit = true,
            onSubmit = onCapture,
            onDismiss = { showCapture = false },
        )
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
        is AiStatus.Downloading -> Column(modifier.fillMaxWidth()) {
            Text(
                text = aiStatusLabel(status),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            val percent = status.percent
            if (percent != null) {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        is AiStatus.Unavailable -> Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Column {
                    Text(stringResource(R.string.ai_status_unavailable), style = MaterialTheme.typography.titleSmall)
                    Text(status.reason, style = MaterialTheme.typography.bodySmall)
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
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_tile),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(R.string.dashboard_empty_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
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
        IconButton(onClick = { open = true }) {
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
