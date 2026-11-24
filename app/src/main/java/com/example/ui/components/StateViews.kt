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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
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

enum class ToolActionStatus {
    RUNNING,
    SUCCESS,
    ERROR
}

data class ToolUiDisplay(
    val icon: ImageVector,
    val title: String,
    val status: ToolActionStatus = ToolActionStatus.SUCCESS,
    val errorSummary: String? = null,
    val rawCallDetails: String = "",
    val rawResultDetails: String = ""
)

private fun cleanUiValue(raw: String?): String? {
    val t = raw?.trim() ?: return null
    if (t.isEmpty() ||
        t.equals("null", ignoreCase = true) ||
        t.equals("undefined", ignoreCase = true) ||
        t.contains("\$TOOL", ignoreCase = true) ||
        t.contains("TOOL_NAME", ignoreCase = false)
    ) {
        return null
    }
    return t
}

fun cleanToolArgsForSignature(argsJson: String?): String {
    if (argsJson.isNullOrBlank()) return "{}"
    val obj = runCatching { JSONObject(argsJson) }.getOrNull() ?: return "{}"
    val cleanObj = JSONObject()
    val keys = obj.keys().asSequence().toList().sorted()
    for (k in keys) {
        if (k == "_raw_call") continue
        val raw = obj.opt(k)
        if (raw == null || raw == JSONObject.NULL) continue
        val str = cleanUiValue(raw.toString()) ?: continue
        cleanObj.put(k, if (raw is Number || raw is Boolean) raw else str)
    }
    return cleanObj.toString()
}

fun presentToolAction(
    toolName: String?,
    argsJson: String?,
    resultText: String?,
    detailText: String? = null,
    isRunning: Boolean = false
): ToolUiDisplay {
    val parsedObj = runCatching {
        if (argsJson.isNullOrBlank()) null else JSONObject(argsJson)
    }.getOrNull()

    val rawCallFromMeta = parsedObj?.let { obj ->
        if (obj.has("_raw_call")) cleanUiValue(obj.opt("_raw_call")?.takeIf { it != JSONObject.NULL }?.toString())
        else null
    }

    val cleanArgsObj = parsedObj?.let { obj ->
        JSONObject().apply {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "_raw_call") continue
                val v = obj.opt(k)
                if (v != null && v != JSONObject.NULL && !v.toString().trim().equals("null", ignoreCase = true)) {
                    put(k, v)
                }
            }
        }
    }

    fun argStr(vararg keys: String): String? {
        if (cleanArgsObj == null) return null
        for (k in keys) {
            if (!cleanArgsObj.has(k)) continue
            val raw = cleanArgsObj.opt(k)
            if (raw == null || raw == JSONObject.NULL) continue
            val clean = cleanUiValue(raw.toString())
            if (clean != null) return clean
        }
        return null
    }

    fun argBool(key: String, default: Boolean = true): Boolean {
        if (cleanArgsObj == null || !cleanArgsObj.has(key)) return default
        return when (val v = cleanArgsObj.opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> when (v.trim().lowercase()) {
                "false", "0", "off", "no" -> false
                "true", "1", "on", "yes" -> true
                else -> default
            }
            else -> default
        }
    }

    val canonicalName = com.example.domain.tools.ToolDefinitions.canonicalToolName(toolName.orEmpty())
    val status = when {
        isRunning -> ToolActionStatus.RUNNING
        resultText.equals("ERR", ignoreCase = true) ||
            resultText.equals("ERROR", ignoreCase = true) ||
            resultText.equals("FAILED", ignoreCase = true) ||
            resultText.orEmpty().startsWith("ERR", ignoreCase = true) ||
            resultText.orEmpty().contains("fail", ignoreCase = true) -> ToolActionStatus.ERROR
        else -> ToolActionStatus.SUCCESS
    }

    val (icon, title) = when (canonicalName) {
        "open_app" -> {
            val app = argStr("app_name", "app", "appName", "package", "package_name", "packageName", "name", "target_app")
            val label = if (app != null) "Opening $app" else "Working on it"
            Icons.Default.Apps to label
        }
        "search_in_app" -> {
            val app = argStr("app_name", "app", "appName", "package", "package_name", "packageName", "name", "target_app")
            val query = argStr("query", "q", "text", "search_query", "searchQuery", "song", "video", "keyword", "term")
            val label = when {
                app != null && query != null -> "Searching '$query' in $app"
                app != null -> "Searching in $app"
                query != null -> "Searching '$query'"
                else -> "Working on it"
            }
            Icons.Default.Search to label
        }
        "search_web" -> {
            val query = argStr("query", "q", "text", "search_query", "searchQuery")
            val label = if (query != null) "Searching '$query'" else "Working on it"
            Icons.Default.Language to label
        }
        "toggle_flashlight" -> {
            val on = argBool("enable", true)
            Icons.Default.FlashlightOn to "Flashlight ${if (on) "ON" else "OFF"}"
        }
        "toggle_bluetooth" -> {
            val on = argBool("enable", true)
            Icons.Default.Bluetooth to "Bluetooth ${if (on) "ON" else "OFF"}"
        }
        "set_alarm" -> Icons.Default.Alarm to "Setting alarm"
        "set_timer" -> Icons.Default.Timer to "Setting timer"
        "call_contact" -> {
            val who = argStr("target", "name", "contact", "contact_name", "phone", "phone_number", "number")
            Icons.Default.Call to if (who != null) "Calling $who" else "Calling contact"
        }
        "send_sms" -> {
            val who = argStr("recipient", "to", "name", "contact", "phone")
            Icons.Default.Sms to if (who != null) "Sending SMS to $who" else "Sending SMS"
        }
        "send_whatsapp" -> {
            val who = argStr("recipient", "to", "name", "contact", "phone")
            Icons.Default.Sms to if (who != null) "Messaging $who on WhatsApp" else "Sending WhatsApp"
        }
        "media_control" -> {
            val act = argStr("action", "command", "control")
            val label = if (act != null) "Media: ${act.replaceFirstChar { it.uppercase() }}" else "Media controls"
            Icons.Default.MusicNote to label
        }
        "toggle_dnd" -> {
            val on = argBool("enable", true)
            Icons.Default.DoNotDisturbOn to "Do Not Disturb ${if (on) "ON" else "OFF"}"
        }
        "set_volume" -> {
            val lvl = argStr("level_percent", "level", "percent", "volume")
            val label = if (lvl != null) "Setting volume to $lvl%" else "Adjusting volume"
            Icons.AutoMirrored.Filled.VolumeUp to label
        }
        "toggle_wifi", "open_wifi_settings" -> Icons.Default.Wifi to "Wi-Fi controls"
        "summarize_notifications", "read_notifications", "reply_notification" ->
            Icons.Default.Notifications to "Checking notifications"
        "save_memory", "recall_memory", "delete_memory" ->
            Icons.Default.Psychology to "Updating memory"
        "set_reminder", "list_reminders", "delete_reminder", "snooze_reminder", "save_place" ->
            Icons.Default.Alarm to "Updating reminder"
        "run_routine", "create_routine", "list_routines", "delete_routine",
        "create_command", "run_command", "list_commands", "delete_command",
        "start_macro_recording", "stop_macro_recording", "run_macro", "list_macros", "delete_macro" ->
            Icons.Default.AutoAwesome to "Running automation"
        "explain_screen", "read_screen", "take_screenshot" ->
            Icons.Default.Screenshot to "Reading screen"
        "capture_and_describe" -> Icons.Default.CameraAlt to "Camera vision"
        "tap", "long_press", "swipe", "scroll", "type_text", "go_back", "go_home", "open_recents", "open_notifications" ->
            Icons.Default.TouchApp to "Screen action"
        "set_brightness" -> {
            val lvl = argStr("level_percent", "level", "percent")
            val label = if (lvl != null) "Setting brightness to $lvl%" else "Adjusting brightness"
            Icons.Default.Settings to label
        }
        else -> {
            val safeName = cleanUiValue(canonicalName.takeIf { it.isNotBlank() }?.replace('_', ' '))
                ?.replaceFirstChar { it.uppercase() }
                ?.takeIf { it.isNotBlank() }
                ?: "Working on it"
            Icons.Default.Build to safeName
        }
    }

    val safeDetail = cleanUiValue(detailText)
    val safeResult = cleanUiValue(resultText)
    val errorSummary = if (status == ToolActionStatus.ERROR) {
        val candidate = safeDetail?.lineSequence()?.firstOrNull()?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("ERR", ignoreCase = true) }
        candidate?.take(110) ?: "Action complete nahi ho paya — details dekhein."
    } else null

    val sanitizedArgsDisplay = cleanArgsObj?.toString() ?: "{}"
    val fallbackRawCall = buildString {
        append(canonicalName.ifBlank { "tool" })
        append("(")
        append(sanitizedArgsDisplay)
        append(")")
    }
    val rawCallDetails = com.example.core.security.SensitiveDataSanitizer.sanitizeToolCallPayload(
        rawCallFromMeta ?: fallbackRawCall
    )
    val rawResultDetails = com.example.core.security.SensitiveDataSanitizer.sanitizeForLogs(
        listOfNotNull(safeResult, safeDetail)
            .distinct()
            .joinToString(" — ")
            .ifBlank { if (isRunning) "Running..." else "Completed" }
    )

    return ToolUiDisplay(
        icon = icon,
        title = title,
        status = status,
        errorSummary = errorSummary,
        rawCallDetails = rawCallDetails,
        rawResultDetails = rawResultDetails
    )
}
