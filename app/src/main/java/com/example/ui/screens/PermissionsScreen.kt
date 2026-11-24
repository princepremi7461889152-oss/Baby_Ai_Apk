package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.theme.withDevanagariFallback
import com.example.util.OemAutostartGuide
import com.example.util.PermissionHelper
import com.example.util.PermissionSnapshot

private data class PermissionChecklistItem(
    val id: String,
    val title: String,
    val reason: String,
    val icon: ImageVector,
    val granted: Boolean,
    val onFix: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    permissions: PermissionSnapshot,
    oemGuide: OemAutostartGuide,
    permissionHelper: PermissionHelper?,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onRefresh() }

    val items = remember(permissions) {
        listOf(
            PermissionChecklistItem(
                id = "mic",
                title = "Microphone",
                reason = "\"Hey Baby\" wake word aur voice commands sunne ke liye zaroori.",
                icon = Icons.Default.Mic,
                granted = permissions.recordAudio,
                onFix = { permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
            ),
            PermissionChecklistItem(
                id = "notifs",
                title = "Notifications",
                reason = "Persistent \"Baby is listening\" bar aur Stop button dikhane ke liye.",
                icon = Icons.Default.NotificationsActive,
                granted = permissions.postNotifications,
                onFix = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    } else onRefresh()
                }
            ),
            PermissionChecklistItem(
                id = "accessibility",
                title = "Accessibility (Phone Control)",
                reason = "Apps open, tap, scroll, macros record aur screen help ke liye.",
                icon = Icons.Default.AccessibilityNew,
                granted = permissions.accessibility,
                onFix = { permissionHelper?.openAccessibilitySettings() }
            ),
            PermissionChecklistItem(
                id = "notif_listener",
                title = "Notification Reader & Reply",
                reason = "WhatsApp/SMS notifications padhne aur Auto-Reply bhejne ke liye.",
                icon = Icons.Default.NotificationsActive,
                granted = permissions.notificationListener,
                onFix = { permissionHelper?.openNotificationListenerSettings() }
            ),
            PermissionChecklistItem(
                id = "battery",
                title = "Ignore Battery Optimization",
                reason = "Phone lock hone par bhi Baby background mein active rahe.",
                icon = Icons.Default.BatteryChargingFull,
                granted = permissions.batteryOptimizationIgnored,
                onFix = { permissionHelper?.requestIgnoreBatteryOptimizations() }
            ),
            PermissionChecklistItem(
                id = "overlay",
                title = "Floating Bubble Overlay",
                reason = "Har screen ke upar draggable Baby voice orb aur Stop button ke liye.",
                icon = Icons.Default.BubbleChart,
                granted = permissions.overlayBubble,
                onFix = { permissionHelper?.openOverlaySettings() }
            ),
            PermissionChecklistItem(
                id = "phone",
                title = "Calls, Contacts & SMS",
                reason = "\"Mummy ko call karo\" ya emergency SOS SMS bhejne ke liye.",
                icon = Icons.Default.Phone,
                granted = permissions.callPhone && permissions.readContacts && permissions.sendSms,
                onFix = {
                    permLauncher.launch(
                        arrayOf(
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.SEND_SMS
                        )
                    )
                }
            ),
            PermissionChecklistItem(
                id = "write_settings",
                title = "Modify System Settings",
                reason = "Screen brightness voice command se kam ya zyada karne ke liye.",
                icon = Icons.Default.Settings,
                granted = permissions.writeSettings,
                onFix = { permissionHelper?.openWriteSettings() }
            ),
            PermissionChecklistItem(
                id = "dnd",
                title = "Do Not Disturb Access",
                reason = "\"Movie mode\" ya routines mein DND ON/OFF karne ke liye.",
                icon = Icons.Default.DoNotDisturbOn,
                granted = permissions.dndAccess,
                onFix = { permissionHelper?.openDndAccessSettings() }
            ),
            PermissionChecklistItem(
                id = "exact_alarm",
                title = "Exact Alarms",
                reason = "Time reminders bilkul exact minute par bajane ke liye.",
                icon = Icons.Default.Alarm,
                granted = permissions.exactAlarm,
                onFix = { permissionHelper?.openExactAlarmSettings() }
            ),
            PermissionChecklistItem(
                id = "camera",
                title = "Camera (Optional Vision)",
                reason = "\"Camera se dekho ye kya hai?\" jaise visual questions ke liye.",
                icon = Icons.Default.CameraAlt,
                granted = permissions.camera,
                onFix = { permLauncher.launch(arrayOf(Manifest.permission.CAMERA)) }
            ),
            PermissionChecklistItem(
                id = "location",
                title = "Location (Optional Geofence)",
                reason = "\"Ghar pahunchu to yaad dilana\" jaise location reminders ke liye.",
                icon = Icons.Default.LocationOn,
                granted = permissions.fineLocation,
                onFix = { permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }
            )
        )
    }

    val grantedCount = items.count { it.granted }
    val progress = grantedCount.toFloat() / items.size.coerceAtLeast(1)

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
                        .padding(horizontal = spacing.sm, vertical = spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("permissions_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.permissions_title),
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "$grantedCount of ${items.size} permissions active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.xs)
                            .height(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                    Text(
                        text = stringResource(R.string.permissions_subtitle).withDevanagariFallback(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = spacing.xs)
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    items(items, key = { it.id }) { item ->
                        Card(
                            shape = RoundedCornerShape(spacing.cardRadius),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("perm_card_${item.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(spacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    tint = if (item.granted) ext.success else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = item.reason.withDevanagariFallback(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (item.granted) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                            contentDescription = null,
                                            tint = if (item.granted) ext.success else ext.warning,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = if (item.granted) stringResource(R.string.permission_granted)
                                            else stringResource(R.string.permission_missing),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (item.granted) ext.success else ext.warning
                                        )
                                    }
                                }
                                if (!item.granted) {
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = item.onFix,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("fix_perm_${item.id}")
                                    ) {
                                        Text(stringResource(R.string.permission_fix))
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            shape = RoundedCornerShape(spacing.cardRadius),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(spacing.md),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = oemGuide.manufacturerTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                oemGuide.steps.forEachIndexed { idx, line ->
                                    Text("${idx + 1}. $line", style = MaterialTheme.typography.bodySmall)
                                }
                                Spacer(Modifier.height(4.dp))
                                OutlinedButton(onClick = { permissionHelper?.openOemAutostartOrAppInfo() }) {
                                    Text("Open Autostart Settings")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Permissions Light")
@Composable
private fun PermissionsPreview() {
    BabyTheme(themeMode = BabyThemeMode.LIGHT) {
        PermissionsScreen(
            permissions = PermissionSnapshot(
                recordAudio = true, postNotifications = true, accessibility = false,
                notificationListener = true, overlayBubble = false, batteryOptimizationIgnored = true,
                callPhone = true, sendSms = true, readContacts = true, writeSettings = false, dndAccess = false
            ),
            oemGuide = OemAutostartGuide("Xiaomi / HyperOS", listOf("Turn ON Autostart", "Battery No restrictions"), emptyList()),
            permissionHelper = null,
            onRefresh = {},
            onBack = {}
        )
    }
}
