package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.example.data.local.ActionLogEntity
import com.example.data.local.TaskRunEntity
import com.example.ui.components.BabyEmptyStateCard
import com.example.ui.components.BabyShimmerCard
import com.example.ui.state.createSnapshotPagerFlow
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.theme.withDevanagariFallback
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private sealed interface HistoryDetailItem {
    data class Task(val entity: TaskRunEntity) : HistoryDetailItem
    data class Action(val entity: ActionLogEntity) : HistoryDetailItem
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    taskRuns: List<TaskRunEntity>,
    actionLogs: List<ActionLogEntity> = emptyList(),
    dismissedTaskIds: Set<Long> = emptySet(),
    dismissedLogIds: Set<Long> = emptySet(),
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onDismissTaskRun: (Long) -> Unit = {},
    onRestoreTaskRun: (Long) -> Unit = {},
    onDismissActionLog: (Long) -> Unit = {},
    onRestoreActionLog: (Long) -> Unit = {},
    onClearHistory: () -> Unit,
    onClearActionLogs: () -> Unit = {},
    onExportActionLogs: () -> Unit = {},
    onExportActionLogsToUri: (Uri) -> Unit = {},
    onOpenChatForCommand: () -> Unit = {}
) {
    val spacing = BabyThemeTokens.spacing
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Tasks, 1 = Action Log
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var dateFilter by remember { mutableStateOf("ALL") } // ALL, TODAY, 7D
    var sourceFilter by remember { mutableStateOf("ALL") } // ALL, voice, text, routine, auto-reply
    var appFilter by remember { mutableStateOf("ALL") }
    var detailSheetItem by remember { mutableStateOf<HistoryDetailItem?>(null) }
    var lastDismissedUndo by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }

    val saveTxtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) onExportActionLogsToUri(uri)
    }

    val visibleTasks = remember(taskRuns, dismissedTaskIds, searchQuery) {
        val q = searchQuery.trim().lowercase()
        taskRuns.filter { run ->
            run.id !in dismissedTaskIds && (
                q.isEmpty() ||
                    run.commandText.lowercase().contains(q) ||
                    run.finalReply.lowercase().contains(q) ||
                    run.status.lowercase().contains(q)
                )
        }
    }

    val availableApps = remember(actionLogs) {
        actionLogs.map { it.targetApp.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .take(8)
    }

    val filteredLogs = remember(
        actionLogs, dismissedLogIds, searchQuery, statusFilter, dateFilter, sourceFilter, appFilter
    ) {
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val sevenDaysAgo = System.currentTimeMillis() - (7L * 24L * 60L * 60L * 1000L)

        actionLogs.filter { log ->
            if (log.id in dismissedLogIds) return@filter false
            val matchStatus = statusFilter == "ALL" || log.status.equals(statusFilter, ignoreCase = true)
            val matchDate = when (dateFilter) {
                "TODAY" -> log.timestamp >= todayStart
                "7D" -> log.timestamp >= sevenDaysAgo
                else -> true
            }
            val matchSource = sourceFilter == "ALL" || log.source.equals(sourceFilter, ignoreCase = true)
            val matchApp = appFilter == "ALL" || log.targetApp.equals(appFilter, ignoreCase = true)
            val q = searchQuery.trim().lowercase()
            val matchQuery = q.isEmpty() ||
                log.commandText.lowercase().contains(q) ||
                log.toolName.lowercase().contains(q) ||
                log.resultText.lowercase().contains(q) ||
                log.targetApp.lowercase().contains(q) ||
                log.source.lowercase().contains(q)
            matchStatus && matchDate && matchSource && matchApp && matchQuery
        }
    }

    // Jetpack Paging 3 flows for smooth large-list scrolling
    val taskPagingItems = remember(visibleTasks) {
        createSnapshotPagerFlow(visibleTasks, pageSize = 25)
    }.collectAsLazyPagingItems()

    val logPagingItems = remember(filteredLogs) {
        createSnapshotPagerFlow(filteredLogs, pageSize = 30)
    }.collectAsLazyPagingItems()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = spacing.maxContentWidth)
        ) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("History & Action Log", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${visibleTasks.size} tasks • ${filteredLogs.size} tool actions",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row {
                            if (selectedTab == 1 && actionLogs.isNotEmpty()) {
                                IconButton(
                                    onClick = { saveTxtLauncher.launch("baby_action_log.txt") },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("save_action_log_file_button")
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "Save action log to .txt file")
                                }
                                IconButton(
                                    onClick = onExportActionLogs,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("export_action_log_button")
                                ) {
                                    Icon(Icons.Default.IosShare, contentDescription = "Share action log as text")
                                }
                                IconButton(
                                    onClick = onClearActionLogs,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("clear_action_log_button")
                                ) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear action log")
                                }
                            } else if (selectedTab == 0 && taskRuns.isNotEmpty()) {
                                IconButton(
                                    onClick = onClearHistory,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("clear_history_button")
                                ) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear task history")
                                }
                            }
                        }
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf("Task Runs (${visibleTasks.size})", "Action Log (${filteredLogs.size})")
                            .forEachIndexed { idx, label ->
                                SegmentedButton(
                                    selected = selectedTab == idx,
                                    onClick = { selectedTab = idx },
                                    shape = SegmentedButtonDefaults.itemShape(idx, 2),
                                    modifier = Modifier.testTag("history_tab_$idx")
                                ) {
                                    Text(label, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                    }
                }
            }

            lastDismissedUndo?.let { (isTask, id) ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.xxs)
                        .clip(RoundedCornerShape(spacing.chipRadius))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = spacing.sm, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Entry hidden", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            if (isTask) onRestoreTaskRun(id) else onRestoreActionLog(id)
                            lastDismissedUndo = null
                        }) {
                            Text("Undo")
                        }
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    if (selectedTab == 0) "Search tasks or replies…"
                                    else "Search command, tool, or app…"
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(spacing.chipRadius),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("action_log_search_field")
                        )

                        if (selectedTab == 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("ALL", "TODAY", "7D").forEach { d ->
                                    FilterChip(
                                        selected = dateFilter == d,
                                        onClick = { dateFilter = d },
                                        label = { Text(if (d == "ALL") "Any Date" else d) }
                                    )
                                }
                                listOf("ALL", "voice", "text", "routine", "auto-reply").forEach { src ->
                                    FilterChip(
                                        selected = sourceFilter == src,
                                        onClick = { sourceFilter = src },
                                        label = { Text(if (src == "ALL") "All Sources" else src) }
                                    )
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("ALL", "OK", "ERR", "AUTO_REPLY", "BLOCKED").forEach { chip ->
                                    FilterChip(
                                        selected = statusFilter == chip,
                                        onClick = { statusFilter = chip },
                                        label = { Text(chip) }
                                    )
                                }
                                if (availableApps.isNotEmpty()) {
                                    FilterChip(
                                        selected = appFilter == "ALL",
                                        onClick = { appFilter = "ALL" },
                                        label = { Text("All Apps") }
                                    )
                                    availableApps.forEach { app ->
                                        FilterChip(
                                            selected = appFilter == app,
                                            onClick = { appFilter = if (appFilter == app) "ALL" else app },
                                            label = { Text(app) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (selectedTab == 0) {
                        when {
                            taskPagingItems.loadState.refresh is LoadState.Loading -> {
                                LazyColumn(
                                    contentPadding = PaddingValues(spacing.md),
                                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                                ) {
                                    items(4) { BabyShimmerCard() }
                                }
                            }
                            visibleTasks.isEmpty() -> {
                                BabyEmptyStateCard(
                                    icon = Icons.Default.History,
                                    title = "Abhi koi task run nahi hua",
                                    subtitle = "Jab Baby phone control karegi, har task ka step-by-step audit yahan dikhega.",
                                    ctaLabel = "Ask Baby",
                                    onCtaClick = onOpenChatForCommand
                                )
                            }
                            else -> {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(spacing.md),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(
                                        count = taskPagingItems.itemCount,
                                        key = taskPagingItems.itemKey { it.id },
                                        contentType = taskPagingItems.itemContentType { "task" }
                                    ) { index ->
                                        val run = taskPagingItems[index] ?: return@items
                                        val prev = if (index > 0) taskPagingItems.peek(index - 1) else null
                                        val dayLabel = formatDayHeader(run.startedAt)
                                        val prevDay = prev?.let { formatDayHeader(it.startedAt) }
                                        if (index == 0 || dayLabel != prevDay) {
                                            DaySectionHeader(dayLabel)
                                        }
                                        SwipeDismissContainer(
                                            onDismissed = {
                                                onDismissTaskRun(run.id)
                                                lastDismissedUndo = true to run.id
                                            }
                                        ) {
                                            TaskRunCard(
                                                run = run,
                                                onClick = { detailSheetItem = HistoryDetailItem.Task(run) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        when {
                            logPagingItems.loadState.refresh is LoadState.Loading -> {
                                LazyColumn(
                                    contentPadding = PaddingValues(spacing.md),
                                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                                ) {
                                    items(4) { BabyShimmerCard() }
                                }
                            }
                            filteredLogs.isEmpty() -> {
                                BabyEmptyStateCard(
                                    icon = Icons.Default.History,
                                    title = "Koi matching action log nahi",
                                    subtitle = "Har tool call aur auto-reply timestamp, source aur app ke saath record hota hai.",
                                    ctaLabel = if (searchQuery.isNotEmpty()) "Clear Search" else null,
                                    onCtaClick = { searchQuery = "" }
                                )
                            }
                            else -> {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.xs),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(
                                        count = logPagingItems.itemCount,
                                        key = logPagingItems.itemKey { it.id },
                                        contentType = logPagingItems.itemContentType { "action_log" }
                                    ) { index ->
                                        val log = logPagingItems[index] ?: return@items
                                        val prev = if (index > 0) logPagingItems.peek(index - 1) else null
                                        val dayLabel = formatDayHeader(log.timestamp)
                                        val prevDay = prev?.let { formatDayHeader(it.timestamp) }
                                        if (index == 0 || dayLabel != prevDay) {
                                            DaySectionHeader(dayLabel)
                                        }
                                        SwipeDismissContainer(
                                            onDismissed = {
                                                onDismissActionLog(log.id)
                                                lastDismissedUndo = false to log.id
                                            }
                                        ) {
                                            ActionLogCard(
                                                log = log,
                                                onClick = { detailSheetItem = HistoryDetailItem.Action(log) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    detailSheetItem?.let { detail ->
        ModalBottomSheet(
            onDismissRequest = { detailSheetItem = null },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            HistoryDetailSheetContent(detail = detail)
        }
    }
}

@Composable
private fun DaySectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDismissContainer(
    onDismissed: () -> Unit,
    content: @Composable () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDismissed()
                true
            } else false
        }
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(BabyThemeTokens.spacing.cardRadius))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Hide entry",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        content = { content() }
    )
}

@Composable
private fun ActionLogCard(
    log: ActionLogEntity,
    onClick: () -> Unit
) {
    val ext = BabyThemeTokens.colors
    val formatter = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val statusColor = when (log.status) {
        "OK" -> ext.success
        "AUTO_REPLY" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${log.toolName}${if (log.targetApp.isNotBlank()) " • ${log.targetApp}" else ""}",
                    style = MaterialTheme.typography.titleSmall,
                    color = statusColor,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${log.source} • ${formatter.format(Date(log.timestamp))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (log.commandText.isNotBlank()) {
                Text(log.commandText.withDevanagariFallback(), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = log.resultText.withDevanagariFallback(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun TaskRunCard(
    run: TaskRunEntity,
    onClick: () -> Unit
) {
    val ext = BabyThemeTokens.colors
    val formatter = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    val (statusColor, statusIcon) = when (run.status) {
        "COMPLETED" -> ext.success to Icons.Default.CheckCircle
        "STOPPED" -> MaterialTheme.colorScheme.primary to Icons.Default.StopCircle
        "QUEUED_FOR_UNLOCK" -> MaterialTheme.colorScheme.secondary to Icons.Default.LockClock
        else -> MaterialTheme.colorScheme.error to Icons.Default.ErrorOutline
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(statusIcon, contentDescription = run.status, tint = statusColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = run.commandText.withDevanagariFallback(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${run.status} • ${run.stepsCount}/${run.maxSteps} steps",
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor
                )
                Text(
                    text = formatter.format(Date(run.startedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (run.finalReply.isNotBlank()) {
                Text(
                    text = run.finalReply.withDevanagariFallback(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun HistoryDetailSheetContent(detail: HistoryDetailItem) {
    val spacing = BabyThemeTokens.spacing
    val formatter = remember { SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.xs)
            .padding(bottom = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        when (detail) {
            is HistoryDetailItem.Task -> {
                val run = detail.entity
                Text("Task Details", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = formatter.format(Date(run.startedAt)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DetailField("Command", run.commandText)
                DetailField("Status", "${run.status} (${run.stepsCount}/${run.maxSteps} steps)")
                if (run.brainSummary.isNotBlank()) DetailField("Brain", run.brainSummary)
                if (run.finalReply.isNotBlank()) DetailField("Reply", run.finalReply)
                val steps = remember(run.stepLogsJson) { parseStepLogs(run.stepLogsJson) }
                if (steps.isNotEmpty()) {
                    Text("Step Trace", style = MaterialTheme.typography.titleSmall)
                    steps.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            is HistoryDetailItem.Action -> {
                val log = detail.entity
                Text("Action Log Details", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = formatter.format(Date(log.timestamp)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (log.commandText.isNotBlank()) DetailField("Command", log.commandText)
                DetailField("Tool", log.toolName)
                DetailField("Status & Source", "${log.status} • ${log.source}")
                if (log.targetApp.isNotBlank()) DetailField("App Touched", log.targetApp)
                if (log.argsSummary.isNotBlank()) DetailField("Arguments", log.argsSummary)
                DetailField("Result", log.resultText)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value.withDevanagariFallback(), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatDayHeader(timestampMs: Long): String {
    val nowCal = Calendar.getInstance()
    val targetCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val sameYear = nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR)
    val nowDay = nowCal.get(Calendar.DAY_OF_YEAR)
    val targetDay = targetCal.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && nowDay == targetDay -> "Today"
        sameYear && nowDay - targetDay == 1 -> "Yesterday"
        else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timestampMs))
    }
}

private fun parseStepLogs(json: String): List<String> {
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val step = obj.optInt("step")
            val brain = obj.optString("brain")
            val tool = obj.optString("tool")
            val ok = obj.optBoolean("ok")
            val out = obj.optString("output").take(140)
            "#$step [$brain] $tool (${if (ok) "OK" else "ERR"}): $out"
        }
    }.getOrDefault(emptyList())
}

@Preview(showBackground = true, name = "History Empty Dark")
@Composable
private fun HistoryEmptyPreview() {
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        HistoryScreen(
            taskRuns = emptyList(),
            actionLogs = emptyList(),
            onClearHistory = {}
        )
    }
}

@Preview(showBackground = true, name = "History Filled Light")
@Composable
private fun HistoryFilledLightPreview() {
    BabyTheme(themeMode = BabyThemeMode.LIGHT) {
        HistoryScreen(
            taskRuns = listOf(
                TaskRunEntity(
                    id = 1L,
                    commandText = "Flashlight on karo",
                    status = "COMPLETED",
                    stepsCount = 1,
                    maxSteps = 15,
                    brainSummary = "local",
                    finalReply = "Flashlight ON kar di hai."
                )
            ),
            actionLogs = listOf(
                ActionLogEntity(
                    id = 1L,
                    commandText = "Flashlight on karo",
                    toolName = "toggle_flashlight",
                    argsSummary = "{enable=true}",
                    resultText = "Flashlight turned ON",
                    status = "OK",
                    source = "voice"
                )
            ),
            onClearHistory = {}
        )
    }
}
