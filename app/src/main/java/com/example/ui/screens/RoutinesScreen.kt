package com.example.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.core.security.BabySettings
import com.example.data.local.MacroEntity
import com.example.data.local.RoutineEntity
import com.example.features.macros.MacroManager
import com.example.features.macros.MacroStep
import com.example.features.routines.RoutineManager
import com.example.features.routines.RoutineStep
import com.example.ui.components.BabyEmptyStateCard
import com.example.ui.components.presentToolAction
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.theme.withDevanagariFallback
import com.example.util.PermissionSnapshot
import kotlin.math.roundToInt

private data class RoutineIconChoice(
    val key: String,
    val label: String,
    val icon: ImageVector
)

private val ROUTINE_ICON_CHOICES = listOf(
    RoutineIconChoice("sparkles", "Magic", Icons.Default.AutoAwesome),
    RoutineIconChoice("morning", "Morning", Icons.Default.WbSunny),
    RoutineIconChoice("night", "Night", Icons.Default.NightsStay),
    RoutineIconChoice("home", "Home", Icons.Default.Home),
    RoutineIconChoice("work", "Work", Icons.Default.Work),
    RoutineIconChoice("movie", "Movie", Icons.Default.Movie),
    RoutineIconChoice("music", "Music", Icons.Default.MusicNote),
    RoutineIconChoice("fitness", "Fitness", Icons.Default.FitnessCenter)
)

private fun resolveRoutineIcon(triggerPhrase: String, storedIconKey: String?): ImageVector {
    val key = storedIconKey?.lowercase()
    ROUTINE_ICON_CHOICES.firstOrNull { it.key == key }?.let { return it.icon }
    val phrase = triggerPhrase.lowercase()
    return when {
        phrase.contains("morning") || phrase.contains("subah") -> Icons.Default.WbSunny
        phrase.contains("night") || phrase.contains("raat") -> Icons.Default.NightsStay
        phrase.contains("home") || phrase.contains("ghar") -> Icons.Default.Home
        phrase.contains("movie") -> Icons.Default.Movie
        phrase.contains("work") || phrase.contains("office") -> Icons.Default.Work
        phrase.contains("music") || phrase.contains("gaana") -> Icons.Default.MusicNote
        else -> Icons.Default.AutoAwesome
    }
}

private fun formatStepShort(step: RoutineStep): String {
    return when (step.tool) {
        "toggle_dnd" -> {
            val enable = step.args["enable"] as? Boolean ?: true
            "DND ${if (enable) "ON" else "OFF"}"
        }
        "set_brightness" -> {
            val pct = (step.args["level_percent"] as? Number)?.toInt() ?: 25
            "Brightness $pct%"
        }
        "set_volume" -> {
            val pct = (step.args["level_percent"] as? Number)?.toInt() ?: 40
            "Volume $pct%"
        }
        "open_app" -> {
            val app = step.args["app_name"]?.toString()?.ifBlank { "App" } ?: "App"
            "Open $app"
        }
        "toggle_flashlight" -> {
            val enable = step.args["enable"] as? Boolean ?: true
            "Flash ${if (enable) "ON" else "OFF"}"
        }
        "summarize_notifications" -> "Read Notifications"
        "stop_alarm" -> "Stop Alarm"
        "speak_weather" -> "Weather"
        else -> presentToolAction(step.tool, null, null).title
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(
    settings: BabySettings,
    permissions: PermissionSnapshot,
    allRoutines: List<RoutineEntity>,
    macros: List<MacroEntity>,
    isRecordingMacro: Boolean,
    recordedSteps: List<MacroStep>,
    recordingWarning: String? = null,
    initialTab: Int = 0,
    routineIcons: Map<String, String> = emptyMap(),
    onSelectRoutineIcon: (String, String) -> Unit = { _, _ -> },
    onBack: (() -> Unit)? = null,
    onRunRoutine: (RoutineEntity) -> Unit,
    onToggleRoutine: (RoutineEntity, Boolean) -> Unit,
    onCreateRoutine: (String, String, List<RoutineStep>, String, String) -> Unit,
    onUpdateRoutine: (Long, String, String, List<RoutineStep>, String, String) -> Unit = { _, _, _, _, _, _ -> },
    onDeleteRoutine: (Long) -> Unit,
    onCreateCustomCommand: (String, String, List<RoutineStep>) -> Unit = { _, _, _ -> },
    onUpdateCustomCommand: (Long, String, String, List<RoutineStep>) -> Unit = { _, _, _, _ -> },
    onRunCustomCommand: (RoutineEntity) -> Unit = {},
    onStartMacroRecording: () -> Unit,
    onStopAndSaveMacro: (String, String) -> Unit,
    onCancelMacroRecording: () -> Unit,
    onToggleMacro: (MacroEntity, Boolean) -> Unit = { _, _ -> },
    onRunMacro: (MacroEntity) -> Unit,
    onDeleteMacro: (Long) -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors

    val routines = remember(allRoutines) {
        allRoutines.filter { it.scheduleType != RoutineManager.SCHEDULE_TYPE_COMMAND }
    }
    val customCommands = remember(allRoutines) {
        allRoutines.filter { it.scheduleType == RoutineManager.SCHEDULE_TYPE_COMMAND }
    }

    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var showCreateRoutine by remember { mutableStateOf(false) }
    var editingRoutine by remember { mutableStateOf<RoutineEntity?>(null) }
    var showCreateCommand by remember { mutableStateOf(false) }
    var editingCommand by remember { mutableStateOf<RoutineEntity?>(null) }
    var showSaveMacroDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
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
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onBack != null) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("routines_back_btn")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Routines, Commands & Macros", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Daily Routines, Custom Multi-Action Phrases & Accessibility Macros",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (selectedTab == 0 || selectedTab == 1) {
                            IconButton(
                                onClick = {
                                    if (selectedTab == 0) showCreateRoutine = true
                                    else showCreateCommand = true
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("routines_add_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add item")
                            }
                        }
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf(
                            "Routines (${routines.size})",
                            "Commands (${customCommands.size})",
                            "Macros (${macros.size})"
                        ).forEachIndexed { idx, label ->
                            SegmentedButton(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                shape = SegmentedButtonDefaults.itemShape(idx, 3),
                                modifier = Modifier.testTag("automation_tab_$idx")
                            ) {
                                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                when (selectedTab) {
                    0 -> {
                        if (!settings.routinesEnabled) {
                            item { DisabledBanner("Daily Routines feature Settings mein OFF hai.") }
                        }
                        if (routines.isEmpty()) {
                            item {
                                BabyEmptyStateCard(
                                    icon = Icons.Default.AutoAwesome,
                                    title = "Koi routine nahi mili",
                                    subtitle = "Apni custom routine banao — jaise subah alarm band karke weather aur notifications sunna.",
                                    ctaLabel = "Create Routine",
                                    onCtaClick = { showCreateRoutine = true }
                                )
                            }
                        } else {
                            items(routines, key = { it.id }) { routine ->
                                val steps = remember(routine.stepsJson) { RoutineManager.decodeSteps(routine.stepsJson) }
                                val icon = resolveRoutineIcon(
                                    routine.triggerPhrase,
                                    routineIcons[routine.triggerPhrase.lowercase()]
                                )
                                RoutineAutomationCard(
                                    title = routine.name,
                                    triggerPhrase = routine.triggerPhrase,
                                    scheduleBadge = if (routine.scheduleType == "TIME" && routine.scheduleTime.isNotBlank()) {
                                        "TIME • ${routine.scheduleTime}"
                                    } else {
                                        routine.scheduleType
                                    },
                                    steps = steps,
                                    icon = icon,
                                    enabled = routine.isEnabled,
                                    featureEnabled = settings.routinesEnabled,
                                    canDelete = !routine.isBuiltIn,
                                    runTestTag = "run_routine_${routine.id}",
                                    editTestTag = "edit_routine_${routine.id}",
                                    onToggle = { onToggleRoutine(routine, it) },
                                    onRun = { onRunRoutine(routine) },
                                    onEdit = { editingRoutine = routine },
                                    onDelete = { onDeleteRoutine(routine.id) }
                                )
                            }
                        }
                    }

                    1 -> {
                        if (!settings.customCommandsEnabled) {
                            item { DisabledBanner("Custom Commands feature Settings mein OFF hai.") }
                        }
                        item {
                            Card(
                                shape = RoundedCornerShape(spacing.cardRadius),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(spacing.md),
                                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    Text("Custom Phrase Commands", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Ek phrase bolo aur multiple actions ordered steps mein chalao. Voice: \"Naya command banao Movie mode\".",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                                    ) {
                                        Button(
                                            onClick = { showCreateCommand = true },
                                            enabled = settings.customCommandsEnabled,
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("create_custom_command_btn")
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("New Command")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                onSelectRoutineIcon("movie mode", "movie")
                                                onCreateCustomCommand(
                                                    "Movie mode",
                                                    "movie mode",
                                                    listOf(
                                                        RoutineStep("toggle_dnd", mapOf("enable" to true)),
                                                        RoutineStep("set_brightness", mapOf("level_percent" to 20)),
                                                        RoutineStep("set_volume", mapOf("stream" to "media", "level_percent" to 40)),
                                                        RoutineStep("open_app", mapOf("app_name" to "Netflix"))
                                                    )
                                                )
                                            },
                                            enabled = settings.customCommandsEnabled,
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("preset_movie_mode_btn")
                                        ) {
                                            Text("+ Movie mode preset")
                                        }
                                    }
                                }
                            }
                        }

                        if (customCommands.isEmpty()) {
                            item {
                                BabyEmptyStateCard(
                                    icon = Icons.Default.Bolt,
                                    title = "Abhi koi custom command nahi hai",
                                    subtitle = "'+ Movie mode preset' ya 'New Command' se apna pehla shortcut banao.",
                                    ctaLabel = "New Command",
                                    onCtaClick = { showCreateCommand = true }
                                )
                            }
                        } else {
                            items(customCommands, key = { it.id }) { cmd ->
                                val steps = remember(cmd.stepsJson) { RoutineManager.decodeSteps(cmd.stepsJson) }
                                val icon = resolveRoutineIcon(
                                    cmd.triggerPhrase,
                                    routineIcons[cmd.triggerPhrase.lowercase()]
                                )
                                RoutineAutomationCard(
                                    title = cmd.name,
                                    triggerPhrase = cmd.triggerPhrase,
                                    scheduleBadge = "${steps.size} steps",
                                    steps = steps,
                                    icon = icon,
                                    enabled = cmd.isEnabled,
                                    featureEnabled = settings.customCommandsEnabled,
                                    canDelete = true,
                                    runTestTag = "run_command_${cmd.id}",
                                    editTestTag = "edit_command_${cmd.id}",
                                    onToggle = { onToggleRoutine(cmd, it) },
                                    onRun = { onRunCustomCommand(cmd) },
                                    onEdit = { editingCommand = cmd },
                                    onDelete = { onDeleteRoutine(cmd.id) }
                                )
                            }
                        }
                    }

                    else -> {
                        if (!settings.macrosEnabled) {
                            item { DisabledBanner("Macro Recording feature Settings mein OFF hai.") }
                        }
                        item {
                            Card(
                                shape = RoundedCornerShape(spacing.cardRadius),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(spacing.md),
                                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    Text("Macro Recorder (Element-Verified Accessibility)", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (isRecordingMacro) {
                                            "Recording ON — ${recordedSteps.size} steps captured. Password, OTP aur payment screens automatically skip hoti hain."
                                        } else {
                                            "Bolo \"Macro record shuru karo\" ya Record dabao. Replay har UI element verify karta hai."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (!recordingWarning.isNullOrBlank()) {
                                        Surface(
                                            color = ext.warningContainer,
                                            shape = RoundedCornerShape(spacing.chipRadius),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(spacing.sm),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.WarningAmber,
                                                    contentDescription = null,
                                                    tint = ext.onWarningContainer,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    recordingWarning.withDevanagariFallback(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = ext.onWarningContainer
                                                )
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (!isRecordingMacro) {
                                            Button(
                                                onClick = onStartMacroRecording,
                                                enabled = settings.macrosEnabled && permissions.accessibility,
                                                modifier = Modifier
                                                    .minimumInteractiveComponentSize()
                                                    .testTag("start_macro_record_btn")
                                            ) {
                                                Icon(Icons.Default.FiberManualRecord, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Record Macro")
                                            }
                                        } else {
                                            Button(
                                                onClick = { showSaveMacroDialog = true },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                                modifier = Modifier
                                                    .minimumInteractiveComponentSize()
                                                    .testTag("stop_macro_record_btn")
                                            ) {
                                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Stop & Save (${recordedSteps.size})")
                                            }
                                            OutlinedButton(
                                                onClick = onCancelMacroRecording,
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Text("Discard")
                                            }
                                        }
                                    }
                                    if (!permissions.accessibility) {
                                        Text(
                                            "Macros record & replay karne ke liye Accessibility Service enable karo.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        if (macros.isEmpty()) {
                            item {
                                BabyEmptyStateCard(
                                    icon = Icons.Default.FiberManualRecord,
                                    title = "Koi macro saved nahi hai",
                                    subtitle = "Kisi app ke repeatable steps record karke ek phrase se replay karo."
                                )
                            }
                        } else {
                            items(macros, key = { it.id }) { macro ->
                                val steps = remember(macro.stepsJson) { MacroManager.decodeSteps(macro.stepsJson) }
                                Card(
                                    shape = RoundedCornerShape(spacing.cardRadius),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(spacing.md),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                                shape = CircleShape,
                                                modifier = Modifier.size(40.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        Icons.Default.FiberManualRecord,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(macro.name, style = MaterialTheme.typography.titleMedium)
                                                Text(
                                                    "Say: \"${macro.triggerPhrase} chalao\" • ${steps.size} steps",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                                if (steps.isNotEmpty()) {
                                                    Text(
                                                        steps.take(3).joinToString(" → ") { it.label.ifBlank { it.tool } },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            Switch(
                                                checked = macro.isEnabled,
                                                onCheckedChange = { onToggleMacro(macro, it) }
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = { onRunMacro(macro) },
                                                enabled = macro.isEnabled && settings.macrosEnabled,
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Run")
                                            }
                                            OutlinedButton(
                                                onClick = { onDeleteMacro(macro.id) },
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete macro", modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Delete")
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
    }

    if (showCreateRoutine || editingRoutine != null) {
        val current = editingRoutine
        RoutineBuilderSheet(
            title = if (current == null) "Create Routine" else "Edit Routine",
            initialName = current?.name ?: "",
            initialPhrase = current?.triggerPhrase ?: "",
            initialIconKey = current?.let { routineIcons[it.triggerPhrase.lowercase()] } ?: "sparkles",
            initialSteps = current?.let { RoutineManager.decodeSteps(it.stepsJson) }.orEmpty(),
            initialScheduleType = current?.scheduleType ?: "MANUAL",
            initialScheduleTime = current?.scheduleTime?.ifBlank { "08:00" } ?: "08:00",
            showScheduleControls = true,
            onDismiss = {
                showCreateRoutine = false
                editingRoutine = null
            },
            onSave = { name, phrase, iconKey, steps, schedType, schedTime ->
                onSelectRoutineIcon(phrase, iconKey)
                if (current == null) {
                    onCreateRoutine(name, phrase, steps, schedType, schedTime)
                } else {
                    onUpdateRoutine(current.id, name, phrase, steps, schedType, schedTime)
                }
                showCreateRoutine = false
                editingRoutine = null
            }
        )
    }

    if (showCreateCommand || editingCommand != null) {
        val current = editingCommand
        RoutineBuilderSheet(
            title = if (current == null) "New Custom Command" else "Edit Custom Command",
            initialName = current?.name ?: "Movie mode",
            initialPhrase = current?.triggerPhrase ?: "movie mode",
            initialIconKey = current?.let { routineIcons[it.triggerPhrase.lowercase()] } ?: "movie",
            initialSteps = current?.let { RoutineManager.decodeSteps(it.stepsJson) } ?: listOf(
                RoutineStep("toggle_dnd", mapOf("enable" to true)),
                RoutineStep("set_brightness", mapOf("level_percent" to 20)),
                RoutineStep("set_volume", mapOf("stream" to "media", "level_percent" to 40))
            ),
            initialScheduleType = RoutineManager.SCHEDULE_TYPE_COMMAND,
            initialScheduleTime = "",
            showScheduleControls = false,
            onDismiss = {
                showCreateCommand = false
                editingCommand = null
            },
            onSave = { name, phrase, iconKey, steps, _, _ ->
                onSelectRoutineIcon(phrase, iconKey)
                if (current == null) {
                    onCreateCustomCommand(name, phrase, steps)
                } else {
                    onUpdateCustomCommand(current.id, name, phrase, steps)
                }
                showCreateCommand = false
                editingCommand = null
            }
        )
    }

    if (showSaveMacroDialog) {
        SaveNamedPhraseDialog(
            title = "Save Recorded Macro",
            defaultName = "My Macro",
            defaultPhrase = "my macro",
            onDismiss = { showSaveMacroDialog = false },
            onSave = { n, p ->
                onStopAndSaveMacro(n, p)
                showSaveMacroDialog = false
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineAutomationCard(
    title: String,
    triggerPhrase: String,
    scheduleBadge: String,
    steps: List<RoutineStep>,
    icon: ImageVector,
    enabled: Boolean,
    featureEnabled: Boolean,
    canDelete: Boolean,
    runTestTag: String,
    editTestTag: String,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    Card(
        shape = RoundedCornerShape(spacing.cardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    shape = CircleShape,
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Say: \"$triggerPhrase\" • $scheduleBadge",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle
                )
            }

            if (steps.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    steps.forEachIndexed { idx, step ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(spacing.chipRadius)
                        ) {
                            Text(
                                text = "${idx + 1}. ${formatStepShort(step)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRun,
                    enabled = enabled && featureEnabled,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag(runTestTag)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Run")
                }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag(editTestTag)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
                if (canDelete) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun DisabledBanner(msg: String) {
    val ext = BabyThemeTokens.colors
    Surface(
        color = ext.warningContainer,
        shape = RoundedCornerShape(BabyThemeTokens.spacing.chipRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            msg.withDevanagariFallback(),
            style = MaterialTheme.typography.bodySmall,
            color = ext.onWarningContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RoutineBuilderSheet(
    title: String,
    initialName: String,
    initialPhrase: String,
    initialIconKey: String,
    initialSteps: List<RoutineStep>,
    initialScheduleType: String,
    initialScheduleTime: String,
    showScheduleControls: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, List<RoutineStep>, String, String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = BabyThemeTokens.spacing

    var name by remember { mutableStateOf(initialName) }
    var phrase by remember { mutableStateOf(initialPhrase) }
    var selectedIconKey by remember { mutableStateOf(initialIconKey) }
    var schedType by remember { mutableStateOf(initialScheduleType) }
    var schedTime by remember { mutableStateOf(initialScheduleTime) }
    val steps = remember { mutableStateListOf<RoutineStep>().apply { addAll(initialSteps) } }
    var customAppInput by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)

            // Icon selector
            Text("Icon", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ROUTINE_ICON_CHOICES.forEach { choice ->
                    FilterChip(
                        selected = selectedIconKey == choice.key,
                        onClick = { selectedIconKey = choice.key },
                        leadingIcon = {
                            Icon(choice.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text(choice.label) }
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = phrase,
                onValueChange = { phrase = it },
                label = { Text("Voice Trigger Phrase") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (showScheduleControls) {
                Text("Trigger Mode", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("MANUAL", "TIME", "CHARGING", "HEADPHONES").forEach { mode ->
                        FilterChip(
                            selected = schedType == mode,
                            onClick = { schedType = mode },
                            label = { Text(mode) }
                        )
                    }
                }
                if (schedType == "TIME") {
                    OutlinedTextField(
                        value = schedTime,
                        onValueChange = { schedTime = it },
                        label = { Text("Time (HH:mm)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text("Ordered Action Steps (${steps.size})", style = MaterialTheme.typography.titleSmall)
            if (steps.isEmpty()) {
                Text(
                    "Niche se actions add karo. Arrows se execution order set karein.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                steps.forEachIndexed { index, step ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(spacing.chipRadius),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${index + 1}. ${formatStepShort(step)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    enabled = index > 0,
                                    onClick = {
                                        val moved = steps.removeAt(index)
                                        steps.add(index - 1, moved)
                                    },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move step up", modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    enabled = index < steps.lastIndex,
                                    onClick = {
                                        val moved = steps.removeAt(index)
                                        steps.add(index + 1, moved)
                                    },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move step down", modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { steps.removeAt(index) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove step", modifier = Modifier.size(18.dp))
                                }
                            }
                            if (step.tool == "set_brightness" || step.tool == "set_volume") {
                                val currentPct = (step.args["level_percent"] as? Number)?.toInt() ?: 40
                                Slider(
                                    value = currentPct.toFloat(),
                                    onValueChange = { value ->
                                        val newArgs = step.args.toMutableMap()
                                        newArgs["level_percent"] = value.roundToInt().coerceIn(5, 100)
                                        steps[index] = step.copy(args = newArgs)
                                    },
                                    valueRange = 5f..100f
                                )
                            }
                        }
                    }
                }
            }

            Text("Add Quick Step", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = false,
                    onClick = { steps.add(RoutineStep("toggle_dnd", mapOf("enable" to true))) },
                    label = { Text("+ DND ON") }
                )
                FilterChip(
                    selected = false,
                    onClick = { steps.add(RoutineStep("set_brightness", mapOf("level_percent" to 25))) },
                    label = { Text("+ Brightness") }
                )
                FilterChip(
                    selected = false,
                    onClick = { steps.add(RoutineStep("set_volume", mapOf("stream" to "media", "level_percent" to 40))) },
                    label = { Text("+ Volume") }
                )
                FilterChip(
                    selected = false,
                    onClick = { steps.add(RoutineStep("toggle_flashlight", mapOf("enable" to true))) },
                    label = { Text("+ Flashlight") }
                )
                FilterChip(
                    selected = false,
                    onClick = { steps.add(RoutineStep("summarize_notifications", mapOf("scope" to "unread"))) },
                    label = { Text("+ Notifications") }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customAppInput,
                    onValueChange = { customAppInput = it },
                    label = { Text("Add Open App step (e.g. Spotify)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        val app = customAppInput.trim()
                        if (app.isNotEmpty()) {
                            steps.add(RoutineStep("open_app", mapOf("app_name" to app)))
                            customAppInput = ""
                        }
                    },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text("Add")
                }
            }

            Text(
                "Note: Sensitive actions (payments, delete, uninstall, SMS/WhatsApp) are blocked inside routines.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        val cleanName = name.ifBlank { "Custom Routine" }
                        val cleanPhrase = phrase.ifBlank { cleanName.lowercase() }
                        onSave(cleanName, cleanPhrase, selectedIconKey, steps.toList(), schedType, schedTime)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("save_routine_builder_btn")
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun SaveNamedPhraseDialog(
    title: String,
    defaultName: String,
    defaultPhrase: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    var phrase by remember { mutableStateOf(defaultPhrase) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Macro Name") })
                OutlinedTextField(value = phrase, onValueChange = { phrase = it }, label = { Text("Voice Trigger Phrase") })
            }
        },
        confirmButton = { Button(onClick = { onSave(name, phrase) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Preview(showBackground = true)
@Composable
private fun RoutinesScreenPreviewDark() {
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        RoutinesScreen(
            settings = BabySettings(),
            permissions = PermissionSnapshot(
                recordAudio = true,
                postNotifications = true,
                accessibility = true,
                notificationListener = true,
                overlayBubble = true,
                batteryOptimizationIgnored = true,
                callPhone = true,
                sendSms = true,
                readContacts = true,
                writeSettings = true,
                dndAccess = true
            ),
            allRoutines = listOf(
                RoutineEntity(
                    id = 1L,
                    name = "Good Morning",
                    triggerPhrase = "good morning",
                    stepsJson = RoutineManager.encodeSteps(
                        listOf(
                            RoutineStep("stop_alarm"),
                            RoutineStep("summarize_notifications")
                        )
                    ),
                    scheduleType = "TIME",
                    scheduleTime = "07:30",
                    isBuiltIn = true
                ),
                RoutineEntity(
                    id = 2L,
                    name = "Movie mode",
                    triggerPhrase = "movie mode",
                    stepsJson = RoutineManager.encodeSteps(
                        listOf(
                            RoutineStep("toggle_dnd", mapOf("enable" to true)),
                            RoutineStep("open_app", mapOf("app_name" to "Netflix"))
                        )
                    ),
                    scheduleType = RoutineManager.SCHEDULE_TYPE_COMMAND
                )
            ),
            macros = emptyList(),
            isRecordingMacro = false,
            recordedSteps = emptyList(),
            onBack = {},
            onRunRoutine = {},
            onToggleRoutine = { _, _ -> },
            onCreateRoutine = { _, _, _, _, _ -> },
            onDeleteRoutine = {},
            onStartMacroRecording = {},
            onStopAndSaveMacro = { _, _ -> },
            onCancelMacroRecording = {},
            onRunMacro = {},
            onDeleteMacro = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RoutinesScreenEmptyMacrosLightPreview() {
    BabyTheme(themeMode = BabyThemeMode.LIGHT) {
        RoutinesScreen(
            settings = BabySettings(),
            permissions = PermissionSnapshot(
                recordAudio = true,
                postNotifications = true,
                accessibility = false,
                notificationListener = false,
                overlayBubble = false,
                batteryOptimizationIgnored = false,
                callPhone = false,
                sendSms = false,
                readContacts = false,
                writeSettings = false,
                dndAccess = false
            ),
            allRoutines = emptyList(),
            macros = emptyList(),
            isRecordingMacro = false,
            recordedSteps = emptyList(),
            initialTab = 2,
            onRunRoutine = {},
            onToggleRoutine = { _, _ -> },
            onCreateRoutine = { _, _, _, _, _ -> },
            onDeleteRoutine = {},
            onStartMacroRecording = {},
            onStopAndSaveMacro = { _, _ -> },
            onCancelMacroRecording = {},
            onRunMacro = {},
            onDeleteMacro = {}
        )
    }
}
