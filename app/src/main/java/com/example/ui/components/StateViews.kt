package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.theme.rememberReducedMotionEnabled
import com.example.ui.theme.withDevanagariFallback
import org.json.JSONObject

@Composable
fun BabyShimmerCard(modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotionEnabled()
    val ext = BabyThemeTokens.colors
    val infinite = rememberInfiniteTransition(label = "shimmer")
    val shift by infinite.animateFloat(
        initialValue = -200f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerShift"
    )
    val brush = if (reducedMotion) {
        Brush.linearGradient(listOf(ext.shimmerBase, ext.shimmerBase))
    } else {
        Brush.linearGradient(
            colors = listOf(ext.shimmerBase, ext.shimmerHighlight, ext.shimmerBase),
            start = Offset(shift, 0f),
            end = Offset(shift + 320f, 180f)
        )
    }

    Card(
        shape = RoundedCornerShape(BabyThemeTokens.spacing.cardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(BabyThemeTokens.spacing.md),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(brush)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(brush)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(brush)
            )
        }
    }
}

@Composable
fun BabyEmptyStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    ctaLabel: String? = null,
    onCtaClick: (() -> Unit)? = null,
    ctaTestTag: String? = null,
    modifier: Modifier = Modifier
) {
    val spacing = BabyThemeTokens.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            shape = CircleShape,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        Text(
            text = title.withDevanagariFallback(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = subtitle.withDevanagariFallback(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (ctaLabel != null && onCtaClick != null) {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onCtaClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .let { if (ctaTestTag != null) it.let { m -> m } else it }
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(ctaLabel)
            }
        }
    }
}

data class ToolUiDisplay(
    val icon: ImageVector,
    val title: String
)

fun presentToolAction(toolName: String?, argsJson: String?, resultText: String?): ToolUiDisplay {
    val args = runCatching {
        if (argsJson.isNullOrBlank()) null else JSONObject(argsJson)
    }.getOrNull()

    return when (toolName) {
        "open_app" -> {
            val app = args?.optString("app_name")?.takeIf { it.isNotBlank() } ?: "App"
            ToolUiDisplay(Icons.Default.Apps, "Opened $app")
        }
        "toggle_flashlight" -> {
            val on = args?.optBoolean("enable", true) ?: true
            ToolUiDisplay(Icons.Default.FlashlightOn, "Flashlight ${if (on) "ON" else "OFF"}")
        }
        "set_alarm" -> ToolUiDisplay(Icons.Default.Alarm, "Set Alarm")
        "call_contact" -> {
            val who = args?.optString("name")?.takeIf { it.isNotBlank() } ?: "Contact"
            ToolUiDisplay(Icons.Default.Call, "Calling $who")
        }
        "toggle_dnd" -> {
            val on = args?.optBoolean("enable", true) ?: true
            ToolUiDisplay(Icons.Default.DoNotDisturbOn, "Do Not Disturb ${if (on) "ON" else "OFF"}")
        }
        "set_volume" -> ToolUiDisplay(Icons.Default.VolumeUp, "Adjusted Volume")
        "toggle_wifi", "open_wifi_settings" -> ToolUiDisplay(Icons.Default.Wifi, "Wi-Fi Controls")
        "summarize_notifications" -> ToolUiDisplay(Icons.Default.Notifications, "Summarized Notifications")
        "save_memory", "recall_memory", "delete_memory" -> ToolUiDisplay(Icons.Default.Psychology, "Updated Memory")
        "set_reminder", "list_reminders", "delete_reminder", "snooze_reminder" ->
            ToolUiDisplay(Icons.Default.Alarm, "Updated Reminder")
        "run_routine", "create_routine" -> ToolUiDisplay(Icons.Default.AutoAwesome, "Ran Routine")
        "explain_screen", "read_screen" -> ToolUiDisplay(Icons.Default.Screenshot, "Analyzed Screen")
        "capture_and_describe" -> ToolUiDisplay(Icons.Default.CameraAlt, "Camera Vision")
        "tap", "swipe", "scroll", "type_text" -> ToolUiDisplay(Icons.Default.TouchApp, "Screen Action ($toolName)")
        "set_brightness" -> ToolUiDisplay(Icons.Default.Settings, "Adjusted Brightness")
        else -> {
            val clean = toolName?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Tool Action"
            val suffix = resultText?.takeIf { it.isNotBlank() && it.length < 24 }?.let { " • $it" }.orEmpty()
            ToolUiDisplay(Icons.Default.Build, "$clean$suffix")
        }
    }
}
