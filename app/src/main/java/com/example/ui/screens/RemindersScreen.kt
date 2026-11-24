package com.example.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.core.security.BabySettings
import com.example.data.local.ReminderEntity
import com.example.data.local.SavedPlaceEntity
import com.example.ui.components.BabyEmptyStateCard
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.theme.withDevanagariFallback
import com.example.util.PermissionSnapshot
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemindersScreen(
    settings: BabySettings,
    permissions: PermissionSnapshot,
    reminders: List<ReminderEntity>,
    savedPlaces: List<SavedPlaceEntity>,
    onBack: (() -> Unit)? = null,
    onCreateTimeReminder: (String, String) -> Unit,
    onCreateLocationReminder: (String, String, Double?, Double?) -> Unit,
    onUpdateReminder: (Long, String, String, String?, String?, Double?, Double?, Float) -> Unit,
    onSnoozeReminder: (Long, Int) -> Unit,
    onDeleteReminder: (Long) -> Unit,
    onSavePlace: (String, Double, Double, Float, String) -> Unit,
    onSaveCurrentGpsAsPlace: (String) -> Unit,
    onDeleteSavedPlace: (Long) -> Unit,
    onGeocodeAddress: suspend (String) -> Pair<Double, Double>?,
    onFetchCurrentGps: suspend () -> Pair<Double, Double>?,
    onOpenExactAlarmSettings: () -> Unit,
    onRefreshPermissions: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingReminder by remember { mutableStateOf<ReminderEntity?>(null) }
    var snoozeTarget by remember { mutableStateOf<ReminderEntity?>(null) }
    var showMapPickerForLabel by remember { mutableStateOf<String?>(null) }
    var pendingGpsLabel by remember { mutableStateOf<String?>(null) }

    val locPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        onRefreshPermissions()
        val label = pendingGpsLabel
        pendingGpsLabel = null
        if (granted && label != null) {
            onSaveCurrentGpsAsPlace(label)
        }
    }

    val endOfTodayMs = remember(reminders) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    val activeReminders = remember(reminders) { reminders.filter { !it.isCompleted } }
    val todayReminders = remember(activeReminders, endOfTodayMs) {
        activeReminders.filter { it.triggerType == "LOCATION" || it.triggerAtMs <= endOfTodayMs }
    }
    val upcomingReminders = remember(activeReminders, endOfTodayMs) {
        activeReminders.filter { it.triggerType != "LOCATION" && it.triggerAtMs > endOfTodayMs }
    }
    val completedReminders = remember(reminders) { reminders.filter { it.isCompleted } }
    val fmt = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = spacing.maxContentWidth)
        ) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.sm, vertical = spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("reminders_back_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Reminders & Places", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Alarms + Geofence Reminders (Survives Reboot)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("add_reminder_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New")
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("reminders_list"),
                contentPadding = PaddingValues(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                if (!settings.remindersEnabled) {
                    item {
                        Surface(
                            color = ext.warningContainer,
                            shape = RoundedCornerShape(spacing.chipRadius),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Reminders Settings mein OFF hai. Enable karne ke liye Settings kholo.".withDevanagariFallback(),
                                style = MaterialTheme.typography.bodySmall,
                                color = ext.onWarningContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                if (settings.remindersEnabled && !permissions.exactAlarm) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(spacing.chipRadius),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Exact Alarm permission optional hai — exact timing ke liye allow karein.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = onOpenExactAlarmSettings,
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Text("Allow")
                                }
                            }
                        }
                    }
                }

                // Saved Places Card
                item {
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
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Saved Places (Home / Work)", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                "Voice se bolo: \"Ghar pahunchu to doodh yaad dilana\" ya Map / GPS se save karo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showMapPickerForLabel = "Home" },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("pick_home_map_btn")
                                ) {
                                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pick Home")
                                }
                                OutlinedButton(
                                    onClick = { showMapPickerForLabel = "Work" },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("pick_work_map_btn")
                                ) {
                                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pick Work")
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (permissions.fineLocation) {
                                            onSaveCurrentGpsAsPlace("Home")
                                        } else {
                                            pendingGpsLabel = "Home"
                                            locPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                        }
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("save_current_home_gps_btn")
                                ) {
                                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("GPS -> Home")
                                }
                            }
                            if (savedPlaces.isNotEmpty()) {
                                savedPlaces.forEach { place ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("${place.label} (${place.radiusMeters.toInt()}m)", style = MaterialTheme.typography.labelLarge)
                                            Text(
                                                "${"%.4f".format(place.latitude)}, ${"%.4f".format(place.longitude)} ${if (place.addressHint.isNotBlank()) "• ${place.addressHint}" else ""}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteSavedPlace(place.id) },
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete saved place")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (activeReminders.isEmpty() && completedReminders.isEmpty()) {
                    item {
                        BabyEmptyStateCard(
                            icon = Icons.Default.Alarm,
                            title = "Koi reminder nahi hai",
                            subtitle = "\"New\" dabao ya Baby se bolo: \"Shaam 6 baje doodh yaad dilana\".",
                            ctaLabel = "New Reminder",
                            onCtaClick = { showCreateDialog = true }
                        )
                    }
                }

                if (todayReminders.isNotEmpty()) {
                    item {
                        Text(
                            "Today & Places (${todayReminders.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.testTag("reminders_group_today")
                        )
                    }
                    items(todayReminders, key = { it.id }) { rem ->
                        ReminderItemCard(
                            reminder = rem,
                            timeText = if (rem.triggerType == "LOCATION") {
                                "Location: ${rem.placeLabel} (${rem.radiusMeters.toInt()}m)"
                            } else {
                                fmt.format(Date(rem.triggerAtMs))
                            },
                            onQuickSnooze = { mins -> onSnoozeReminder(rem.id, mins) },
                            onEdit = { editingReminder = rem },
                            onSnooze = { snoozeTarget = rem },
                            onDelete = { onDeleteReminder(rem.id) }
                        )
                    }
                }

                if (upcomingReminders.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Upcoming (${upcomingReminders.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("reminders_group_upcoming")
                        )
                    }
                    items(upcomingReminders, key = { it.id }) { rem ->
                        ReminderItemCard(
                            reminder = rem,
                            timeText = fmt.format(Date(rem.triggerAtMs)),
                            onQuickSnooze = { mins -> onSnoozeReminder(rem.id, mins) },
                            onEdit = { editingReminder = rem },
                            onSnooze = { snoozeTarget = rem },
                            onDelete = { onDeleteReminder(rem.id) }
                        )
                    }
                }

                if (completedReminders.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Done (${completedReminders.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("reminders_group_done")
                        )
                    }
                    items(completedReminders, key = { it.id }) { rem ->
                        ReminderItemCard(
                            reminder = rem,
                            timeText = if (rem.triggerType == "LOCATION") {
                                "Done • ${rem.placeLabel}"
                            } else {
                                "Done • ${fmt.format(Date(rem.triggerAtMs))}"
                            },
                            onQuickSnooze = null,
                            onEdit = { editingReminder = rem },
                            onSnooze = { snoozeTarget = rem },
                            onDelete = { onDeleteReminder(rem.id) }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog || editingReminder != null) {
        ReminderEditorDialog(
            initial = editingReminder,
            locationEnabled = settings.locationRemindersEnabled,
            hasLocationPerm = permissions.fineLocation,
            savedPlaces = savedPlaces,
            onRequestLocationPerm = { locPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            onDismiss = {
                showCreateDialog = false
                editingReminder = null
            },
            onSave = { title, triggerType, timeExpr, placeLabel, lat, lon, radius ->
                val cur = editingReminder
                if (cur == null) {
                    if (triggerType == "LOCATION") {
                        onCreateLocationReminder(title, placeLabel ?: "Home", lat, lon)
                    } else {
                        onCreateTimeReminder(title, timeExpr.orEmpty())
                    }
                } else {
                    onUpdateReminder(cur.id, title, triggerType, timeExpr, placeLabel, lat, lon, radius)
                }
                showCreateDialog = false
                editingReminder = null
            }
        )
    }

    snoozeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { snoozeTarget = null },
            title = { Text("Snooze Reminder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\"${target.title}\" ko kitni der baad yaad dilayein?")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10 to "10 min", 30 to "30 min", 60 to "1 hour", 180 to "3 hours").forEach { (mins, lbl) ->
                            Button(
                                onClick = {
                                    onSnoozeReminder(target.id, mins)
                                    snoozeTarget = null
                                },
                                modifier = Modifier.testTag("snooze_${mins}m_btn")
                            ) { Text(lbl) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { snoozeTarget = null }) { Text("Cancel") }
            }
        )
    }

    showMapPickerForLabel?.let { initialLabel ->
        MapPlacePickerDialog(
            initialLabel = initialLabel,
            hasLocationPerm = permissions.fineLocation,
            onRequestLocationPerm = { locPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            onGeocodeAddress = onGeocodeAddress,
            onFetchCurrentGps = onFetchCurrentGps,
            onDismiss = { showMapPickerForLabel = null },
            onConfirm = { label, lat, lon, radius, hint ->
                onSavePlace(label, lat, lon, radius, hint)
                showMapPickerForLabel = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderItemCard(
    reminder: ReminderEntity,
    timeText: String,
    onQuickSnooze: ((Int) -> Unit)?,
    onEdit: () -> Unit,
    onSnooze: () -> Unit,
    onDelete: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors
    Card(
        shape = RoundedCornerShape(spacing.cardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("reminder_card_${reminder.id}")
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (reminder.isCompleted) ext.success.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when {
                                reminder.isCompleted -> Icons.Default.CheckCircle
                                reminder.triggerType == "LOCATION" -> Icons.Default.LocationOn
                                else -> Icons.Default.Alarm
                            },
                            contentDescription = null,
                            tint = if (reminder.isCompleted) ext.success else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(reminder.title.withDevanagariFallback(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        timeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // One-tap quick snooze chips for active time reminders
            if (!reminder.isCompleted && reminder.triggerType != "LOCATION" && onQuickSnooze != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(10 to "10m", 30 to "30m", 60 to "1h").forEach { (mins, label) ->
                        AssistChip(
                            onClick = { onQuickSnooze(mins) },
                            leadingIcon = {
                                Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(15.dp))
                            },
                            label = { Text("Snooze $label") },
                            modifier = Modifier.testTag("quick_snooze_${label}_${reminder.id}")
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (reminder.triggerType != "LOCATION") {
                    OutlinedButton(
                        onClick = onSnooze,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("snooze_reminder_${reminder.id}")
                    ) {
                        Icon(Icons.Default.Snooze, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Snooze")
                    }
                }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("edit_reminder_${reminder.id}")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("delete_reminder_${reminder.id}")
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderEditorDialog(
    initial: ReminderEntity?,
    locationEnabled: Boolean,
    hasLocationPerm: Boolean,
    savedPlaces: List<SavedPlaceEntity>,
    onRequestLocationPerm: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String?, String?, Double?, Double?, Float) -> Unit
) {
    var isLocation by remember { mutableStateOf(initial?.triggerType == "LOCATION") }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var timeExpr by remember { mutableStateOf(if (initial == null) "shaam 6 baje" else "") }
    var placeLabel by remember { mutableStateOf(initial?.placeLabel?.ifBlank { "Home" } ?: "Home") }
    var latStr by remember { mutableStateOf(initial?.latitude?.toString() ?: "") }
    var lonStr by remember { mutableStateOf(initial?.longitude?.toString() ?: "") }
    var radius by remember { mutableFloatStateOf(initial?.radiusMeters ?: 200f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New Reminder" else "Edit Reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !isLocation, onClick = { isLocation = false }, label = { Text("Time (Alarm)") })
                    FilterChip(
                        selected = isLocation,
                        onClick = {
                            isLocation = true
                            if (!hasLocationPerm) onRequestLocationPerm()
                        },
                        enabled = locationEnabled,
                        label = { Text("Location") }
                    )
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Remind me about") })
                if (!isLocation) {
                    OutlinedTextField(
                        value = timeExpr,
                        onValueChange = { timeExpr = it },
                        label = { Text(if (initial == null) "Time (e.g. shaam 6 baje, 15 min)" else "New Time (leave blank to keep)") }
                    )
                } else {
                    if (savedPlaces.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            savedPlaces.forEach { p ->
                                FilterChip(
                                    selected = placeLabel.equals(p.label, true),
                                    onClick = {
                                        placeLabel = p.label
                                        latStr = p.latitude.toString()
                                        lonStr = p.longitude.toString()
                                        radius = p.radiusMeters
                                    },
                                    label = { Text(p.label) }
                                )
                            }
                        }
                    }
                    OutlinedTextField(value = placeLabel, onValueChange = { placeLabel = it }, label = { Text("Place (Home / Work)") })
                    OutlinedTextField(value = latStr, onValueChange = { latStr = it }, label = { Text("Latitude (optional if saved)") })
                    OutlinedTextField(value = lonStr, onValueChange = { lonStr = it }, label = { Text("Longitude (optional if saved)") })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanTitle = title.ifBlank { "Reminder" }
                    if (!isLocation) {
                        onSave(cleanTitle, "TIME", timeExpr.takeIf { it.isNotBlank() }, null, null, null, radius)
                    } else {
                        onSave(
                            cleanTitle,
                            "LOCATION",
                            null,
                            placeLabel,
                            latStr.toDoubleOrNull(),
                            lonStr.toDoubleOrNull(),
                            radius
                        )
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapPlacePickerDialog(
    initialLabel: String,
    hasLocationPerm: Boolean,
    onRequestLocationPerm: () -> Unit,
    onGeocodeAddress: suspend (String) -> Pair<Double, Double>?,
    onFetchCurrentGps: suspend () -> Pair<Double, Double>?,
    onDismiss: () -> Unit,
    onConfirm: (String, Double, Double, Float, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(initialLabel) }
    var addressQuery by remember { mutableStateOf("") }
    var lat by remember { mutableDoubleStateOf(28.6139) }
    var lon by remember { mutableDoubleStateOf(77.2090) }
    var radius by remember { mutableFloatStateOf(200f) }
    var statusHint by remember { mutableStateOf("Tap or drag crosshair on map canvas, search address, or use GPS") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick Place on Map") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Home", "Work", "Gym").forEach { preset ->
                        FilterChip(selected = label.equals(preset, true), onClick = { label = preset }, label = { Text(preset) })
                    }
                }
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Place Label") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = addressQuery,
                        onValueChange = { addressQuery = it },
                        label = { Text("Search address / landmark") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                val res = onGeocodeAddress(addressQuery)
                                if (res != null) {
                                    lat = res.first
                                    lon = res.second
                                    statusHint = "Centered on: $addressQuery"
                                } else {
                                    statusHint = "Address nahi mila — GPS ya map drag use karo."
                                }
                            }
                        }
                    ) { Icon(Icons.Default.Search, contentDescription = "Search place") }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(155.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val dx = ((offset.x / size.width) - 0.5f) * 0.02
                                val dy = ((0.5f - (offset.y / size.height))) * 0.02
                                lat = (lat + dy).coerceIn(-85.0, 85.0)
                                lon = (lon + dx).coerceIn(-180.0, 180.0)
                                statusHint = "Pin adjusted on map"
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { _, dragAmount ->
                                lat = (lat + (dragAmount.y / size.height) * 0.015).coerceIn(-85.0, 85.0)
                                lon = (lon - (dragAmount.x / size.width) * 0.015).coerceIn(-180.0, 180.0)
                                statusHint = "Pin adjusted on map"
                            }
                        }
                ) {
                    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    val accentColor = MaterialTheme.colorScheme.primary
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cols = 6
                        val rows = 4
                        for (c in 1 until cols) {
                            val x = size.width * c / cols
                            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                        }
                        for (r in 1 until rows) {
                            val y = size.height * r / rows
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        }
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(accentColor.copy(alpha = 0.16f), radius = (radius / 1000f).coerceIn(0.15f, 0.8f) * size.minDimension * 0.5f, center = center)
                        drawCircle(accentColor, radius = (radius / 1000f).coerceIn(0.15f, 0.8f) * size.minDimension * 0.5f, center = center, style = Stroke(width = 3f))
                        drawCircle(accentColor, radius = 9f, center = center)
                    }
                    Text(
                        text = "${"%.4f".format(lat)}, ${"%.4f".format(lon)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            if (!hasLocationPerm) {
                                onRequestLocationPerm()
                            } else {
                                scope.launch {
                                    val cur = onFetchCurrentGps()
                                    if (cur != null) {
                                        lat = cur.first
                                        lon = cur.second
                                        statusHint = "GPS coordinates loaded"
                                    } else {
                                        statusHint = "GPS signal weak — search ya drag karo"
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Center on GPS")
                    }
                    Text("${radius.roundToInt()}m radius", style = MaterialTheme.typography.labelSmall)
                }
                Slider(value = radius, onValueChange = { radius = it }, valueRange = 100f..1000f)
                Text(statusHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        label.ifBlank { "Home" },
                        lat,
                        lon,
                        radius,
                        addressQuery.ifBlank { "Map Pick" }
                    )
                }
            ) { Text("Save Place") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Preview(showBackground = true)
@Composable
private fun RemindersScreenPreviewDark() {
    val now = System.currentTimeMillis()
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        RemindersScreen(
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
                dndAccess = true,
                fineLocation = true,
                exactAlarm = true
            ),
            reminders = listOf(
                ReminderEntity(id = 1L, title = "Doodh lena hai", triggerType = "TIME", triggerAtMs = now + 3600_000L),
                ReminderEntity(id = 2L, title = "Dentist appointment", triggerType = "TIME", triggerAtMs = now + 3 * 86_400_000L),
                ReminderEntity(id = 3L, title = "Medicine lena", triggerType = "TIME", triggerAtMs = now - 3600_000L, isCompleted = true)
            ),
            savedPlaces = listOf(
                SavedPlaceEntity(id = 1L, placeKey = "home", label = "Home", latitude = 28.6139, longitude = 77.2090, radiusMeters = 200f, addressHint = "New Delhi")
            ),
            onBack = {},
            onCreateTimeReminder = { _, _ -> },
            onCreateLocationReminder = { _, _, _, _ -> },
            onUpdateReminder = { _, _, _, _, _, _, _, _ -> },
            onSnoozeReminder = { _, _ -> },
            onDeleteReminder = {},
            onSavePlace = { _, _, _, _, _ -> },
            onSaveCurrentGpsAsPlace = {},
            onDeleteSavedPlace = {},
            onGeocodeAddress = { null },
            onFetchCurrentGps = { null },
            onOpenExactAlarmSettings = {},
            onRefreshPermissions = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RemindersScreenLightPreview() {
    BabyTheme(themeMode = BabyThemeMode.LIGHT) {
        RemindersScreen(
            settings = BabySettings(),
            permissions = PermissionSnapshot(
                recordAudio = false,
                postNotifications = false,
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
            reminders = emptyList(),
            savedPlaces = emptyList(),
            onCreateTimeReminder = { _, _ -> },
            onCreateLocationReminder = { _, _, _, _ -> },
            onUpdateReminder = { _, _, _, _, _, _, _, _ -> },
            onSnoozeReminder = { _, _ -> },
            onDeleteReminder = {},
            onSavePlace = { _, _, _, _, _ -> },
            onSaveCurrentGpsAsPlace = {},
            onDeleteSavedPlace = {},
            onGeocodeAddress = { null },
            onFetchCurrentGps = { null },
            onOpenExactAlarmSettings = {},
            onRefreshPermissions = {}
        )
    }
}
