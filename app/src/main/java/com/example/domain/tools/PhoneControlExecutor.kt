package com.example.domain.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import com.example.core.security.SecureKeyStore
import com.example.data.local.AllowedAppDao
import com.example.data.local.MemoryDao
import com.example.features.notifications.NotificationSummaryManager
import com.example.features.safety.SafetyManager
import com.example.service.BabyAccessibilityService
import com.example.service.BabyNotificationListenerService
import com.example.util.PermissionHelper
import com.example.voice.BabyTtsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class ToolExecutionResult(
    val ok: Boolean,
    val output: String,
    val screenshotBase64: String? = null
)

@Singleton
class PhoneControlExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: PermissionHelper,
    private val ttsManager: BabyTtsManager,
    private val keyStore: SecureKeyStore,
    private val allowedAppDao: AllowedAppDao,
    private val memoryDao: MemoryDao,
    private val appResolver: AppResolver
) {
    // Legacy 6-arg constructor for unit tests that construct PhoneControlExecutor directly
    constructor(
        context: Context,
        permissionHelper: PermissionHelper,
        ttsManager: BabyTtsManager,
        keyStore: SecureKeyStore,
        allowedAppDao: AllowedAppDao,
        memoryDao: MemoryDao
    ) : this(
        context = context,
        permissionHelper = permissionHelper,
        ttsManager = ttsManager,
        keyStore = keyStore,
        allowedAppDao = allowedAppDao,
        memoryDao = memoryDao,
        appResolver = AppResolver(context = context, allowedAppDao = allowedAppDao, keyStore = keyStore)
    )

    suspend fun execute(name: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (name) {
            "open_app" -> openApp(
                query = args["app_name"]?.toString().orEmpty(),
                fromBackground = asBool(args["from_background"], false)
            )
            "call_contact" -> callContact(args["target"]?.toString().orEmpty())
            "send_sms" -> sendSms(
                args["recipient"]?.toString().orEmpty(),
                args["message"]?.toString().orEmpty()
            )
            "send_whatsapp" -> sendWhatsApp(
                args["recipient"]?.toString().orEmpty(),
                args["message"]?.toString().orEmpty()
            )
            "set_alarm" -> setAlarm(
                asInt(args["hour"], 7),
                asInt(args["minute"], 0),
                args["label"]?.toString().orEmpty()
            )
            "set_timer" -> setTimer(
                asInt(args["seconds"], 60),
                args["label"]?.toString().orEmpty()
            )
            "toggle_wifi" -> toggleWifi(asBool(args["enable"], true))
            "toggle_bluetooth" -> toggleBluetooth(asBool(args["enable"], true))
            "toggle_flashlight" -> toggleFlashlight(asBool(args["enable"], true))
            "toggle_dnd" -> toggleDnd(asBool(args["enable"], true))
            "set_volume" -> setVolume(
                args["stream"]?.toString() ?: "media",
                asInt(args["level_percent"], 50)
            )
            "set_brightness" -> setBrightness(asInt(args["level_percent"], 60))
            "media_control" -> controlMedia(args["action"]?.toString() ?: "toggle")
            "search_web" -> searchWeb(args["query"]?.toString().orEmpty())
            "read_screen" -> readScreen(asBool(args["include_screenshot"], false))
            "tap" -> tap(asFloat(args["x"], 0f), asFloat(args["y"], 0f))
            "long_press" -> longPress(asFloat(args["x"], 0f), asFloat(args["y"], 0f))
            "type_text" -> typeText(args["text"]?.toString().orEmpty())
            "swipe" -> swipe(args)
            "scroll" -> scroll(args["direction"]?.toString() ?: "down")
            "go_back" -> globalAction("back")
            "go_home" -> globalAction("home")
            "open_recents" -> globalAction("recents")
            "open_notifications" -> globalAction("notifications")
            "take_screenshot" -> takeScreenshot()
            "read_notifications" -> readNotifications(args["filter_app"]?.toString())
            "reply_notification" -> replyNotification(
                args["key"]?.toString().orEmpty(),
                args["message"]?.toString().orEmpty()
            )
            "speak" -> speak(
                args["text"]?.toString().orEmpty(),
                args["language"]?.toString() ?: "hinglish"
            )
            else -> ToolExecutionResult(false, "Unknown tool: $name")
        }
    }

    private suspend fun openApp(query: String, fromBackground: Boolean = false): ToolExecutionResult {
        val outcome = appResolver.openApp(query, fromBackground = fromBackground)
        if (outcome.status == AppOpenStatus.BLOCKED) {
            ttsManager.speak(outcome.message, "hinglish")
        }
        return ToolExecutionResult(ok = outcome.ok, output = outcome.message)
    }

    private suspend fun callContact(target: String): ToolExecutionResult {
        if (target.isBlank()) return ToolExecutionResult(false, "Contact or number missing.")
        val phoneNumber = resolvePhoneNumber(target)
            ?: return ToolExecutionResult(false, "Could not find phone number for '$target'.")

        val action = if (permissionHelper.hasPermission(Manifest.permission.CALL_PHONE)) {
            Intent.ACTION_CALL
        } else {
            Intent.ACTION_DIAL
        }
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(phoneNumber)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolExecutionResult(
                true,
                if (action == Intent.ACTION_CALL) "Calling $target ($phoneNumber)"
                else "Opened dialer for $target ($phoneNumber) — grant CALL_PHONE for instant call."
            )
        }.getOrElse { e ->
            ToolExecutionResult(false, "No phone/dialer app available: ${e.message}")
        }
    }

    private suspend fun sendSms(recipient: String, message: String): ToolExecutionResult {
        if (recipient.isBlank() || message.isBlank()) {
            return ToolExecutionResult(false, "Recipient and message are required.")
        }
        val number = resolvePhoneNumber(recipient) ?: recipient
        if (permissionHelper.hasPermission(Manifest.permission.SEND_SMS)) {
            val smsManager = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
            }.getOrNull()
            if (smsManager != null) {
                return try {
                    val parts = smsManager.divideMessage(message)
                    smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                    ToolExecutionResult(true, "SMS sent to $recipient ($number)")
                } catch (e: Exception) {
                    ToolExecutionResult(false, "SMS failed: ${e.message}")
                }
            }
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolExecutionResult(true, "Opened SMS composer for $recipient ($number)")
        }.getOrElse { e ->
            ToolExecutionResult(false, "No SMS app available on device: ${e.message}")
        }
    }

    private suspend fun sendWhatsApp(recipient: String, message: String): ToolExecutionResult {
        if (recipient.isBlank() || message.isBlank()) {
            return ToolExecutionResult(false, "Recipient and message are required.")
        }
        val number = resolvePhoneNumber(recipient)?.replace(Regex("[^0-9+]"), "")
        val encodedMsg = URLEncoder.encode(message, "UTF-8")
        return try {
            val intent = if (!number.isNullOrBlank()) {
                val cleanDigits = number.removePrefix("+")
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleanDigits?text=$encodedMsg"))
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage("com.whatsapp")
                    putExtra(Intent.EXTRA_TEXT, message)
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ToolExecutionResult(true, "Opened WhatsApp message draft for $recipient. Use read_screen & tap Send if needed.")
        } catch (_: Exception) {
            ToolExecutionResult(false, "WhatsApp is not installed or could not open chat.")
        }
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): ToolExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolExecutionResult(true, "Alarm set for %02d:%02d %s".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59), label).trim())
        }.getOrElse {
            ToolExecutionResult(false, "No clock app available to set alarm: ${it.message}")
        }
    }

    private fun setTimer(seconds: Int, label: String): ToolExecutionResult {
        val safeSec = seconds.coerceAtLeast(1)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, safeSec)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolExecutionResult(true, "Timer started for ${safeSec}s ${label}".trim())
        }.getOrElse {
            ToolExecutionResult(false, "Could not start timer: ${it.message}")
        }
    }

    private fun toggleWifi(enable: Boolean): ToolExecutionResult {
        return try {
            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_WIFI
            } else {
                Settings.ACTION_WIFI_SETTINGS
            }
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            ToolExecutionResult(
                true,
                "Opened Wi-Fi panel (requested ${if (enable) "ON" else "OFF"}). On Android 10+, tap toggle using read_screen/tap if needed."
            )
        } catch (e: Exception) {
            ToolExecutionResult(false, "Failed to open Wi-Fi settings: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleBluetooth(enable: Boolean): ToolExecutionResult {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && adapter != null) {
            val canToggle = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                permissionHelper.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
            if (canToggle) {
                @Suppress("DEPRECATION")
                val ok = runCatching { if (enable) adapter.enable() else adapter.disable() }.getOrDefault(false)
                if (ok) return ToolExecutionResult(true, "Bluetooth turned ${if (enable) "ON" else "OFF"}")
            }
        }
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return runCatching {
            context.startActivity(intent)
            ToolExecutionResult(true, "Opened Bluetooth settings (requested ${if (enable) "ON" else "OFF"}). Use tap if needed.")
        }.getOrElse { e ->
            ToolExecutionResult(false, "Cannot open Bluetooth settings: ${e.message}")
        }
    }

    private fun toggleFlashlight(enable: Boolean): ToolExecutionResult {
        return try {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return ToolExecutionResult(false, "Camera service unavailable.")
            val cameraId = camManager.cameraIdList.firstOrNull { id ->
                camManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolExecutionResult(false, "No camera flash available on this device.")
            camManager.setTorchMode(cameraId, enable)
            ToolExecutionResult(true, "Flashlight turned ${if (enable) "ON" else "OFF"}")
        } catch (e: Exception) {
            ToolExecutionResult(false, "Flashlight error: ${e.message}")
        }
    }

    private fun toggleDnd(enable: Boolean): ToolExecutionResult {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return ToolExecutionResult(false, "Notification service unavailable.")
        if (!nm.isNotificationPolicyAccessGranted) {
            permissionHelper.openDndAccessSettings()
            return ToolExecutionResult(false, "DND access not granted yet. Opened Do Not Disturb access settings.")
        }
        return runCatching {
            val filter = if (enable) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            } else {
                NotificationManager.INTERRUPTION_FILTER_ALL
            }
            nm.setInterruptionFilter(filter)
            ToolExecutionResult(true, "Do Not Disturb turned ${if (enable) "ON" else "OFF"}")
        }.getOrElse { e ->
            ToolExecutionResult(false, "Failed to update DND: ${e.message}")
        }
    }

    private fun setVolume(stream: String, levelPercent: Int): ToolExecutionResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolExecutionResult(false, "Audio service unavailable.")
        val streamType = when (stream.lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        return runCatching {
            val max = am.getStreamMaxVolume(streamType)
            val target = ((levelPercent.coerceIn(0, 100) / 100f) * max).toInt().coerceIn(0, max)
            am.setStreamVolume(streamType, target, AudioManager.FLAG_SHOW_UI)
            ToolExecutionResult(true, "Set $stream volume to ${levelPercent.coerceIn(0, 100)}% ($target/$max)")
        }.getOrElse { e ->
            ToolExecutionResult(false, "Failed to change volume: ${e.message}")
        }
    }

    private fun setBrightness(levelPercent: Int): ToolExecutionResult {
        if (!Settings.System.canWrite(context)) {
            permissionHelper.openWriteSettings()
            return ToolExecutionResult(false, "Write Settings permission missing. Opened Modify System Settings screen.")
        }
        return runCatching {
            val clamped = levelPercent.coerceIn(5, 100)
            val value = ((clamped / 100f) * 255f).toInt().coerceIn(12, 255)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            ToolExecutionResult(true, "Screen brightness set to $clamped%")
        }.getOrElse { e ->
            ToolExecutionResult(false, "Failed to set brightness: ${e.message}")
        }
    }

    private fun controlMedia(action: String): ToolExecutionResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolExecutionResult(false, "Audio service unavailable.")
        val keyCode = when (action.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return ToolExecutionResult(true, "Media command dispatched: $action")
    }

    private fun searchWeb(query: String): ToolExecutionResult {
        if (query.isBlank()) return ToolExecutionResult(false, "Search query missing.")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolExecutionResult(true, "Searching web for \"$query\"")
        }.getOrElse { e ->
            ToolExecutionResult(false, "No browser available: ${e.message}")
        }
    }

    private suspend fun checkUiSafetyGuard(acc: BabyAccessibilityService): ToolExecutionResult? {
        val activePkg = acc.getActivePackageName().orEmpty()
        val treePreview = if (activePkg == "com.android.settings" || activePkg == "com.google.android.gms" || activePkg == "com.android.systemui") {
            acc.readUiTree(maxNodes = 40)
        } else null
        SafetyManager.isBlockedScreen(activePkg, treePreview)?.let { blocked ->
            val msg = "Safety guard: $blocked screen par automation aur screenshot blocked hain."
            return ToolExecutionResult(false, msg)
        }
        if (keyStore.settings.value.allowedAppsOnlyEnabled &&
            activePkg.isNotBlank() &&
            !activePkg.equals(context.packageName, ignoreCase = true)
        ) {
            val allowed = allowedAppDao.getAllowed()
            val ok = allowed.any {
                activePkg.equals(it.packageName, ignoreCase = true) ||
                    activePkg.contains(it.packageName, ignoreCase = true) ||
                    activePkg.contains(it.appLabel, ignoreCase = true)
            }
            if (!ok) {
                val msg = "Safety guard: $activePkg Allowed Apps list mein nahi hai."
                ttsManager.speak(msg, "hinglish")
                return ToolExecutionResult(false, msg)
            }
        }
        return null
    }

    private suspend fun readScreen(includeScreenshot: Boolean): ToolExecutionResult {
        val acc = BabyAccessibilityService.instance
            ?: return ToolExecutionResult(false, "AccessibilityService is not enabled. Ask user to enable Baby Phone Control in Accessibility Settings.")
        checkUiSafetyGuard(acc)?.let { return it }
        val tree = acc.readUiTree()
        SafetyManager.isBlockedScreen(acc.getActivePackageName(), tree)?.let { blocked ->
            return ToolExecutionResult(false, "Safety guard: $blocked screen hidden.")
        }
        val allowShot = includeScreenshot && !keyStore.settings.value.dataSaverEnabled
        val shot = if (allowShot) acc.takeScreenshotBase64() else null
        return ToolExecutionResult(true, tree, screenshotBase64 = shot)
    }

    private suspend fun tap(x: Float, y: Float): ToolExecutionResult {
        val acc = BabyAccessibilityService.instance
            ?: return ToolExecutionResult(false, "AccessibilityService is not enabled.")
        checkUiSafetyGuard(acc)?.let { return it }
        val ok = acc.tap(x, y)
        return ToolExecutionResult(ok, if (ok) "Tapped ($x, $y)" else "Tap failed at ($x, $y)")
    }

    private suspend fun longPress(x: Float, y: Float): ToolExecutionResult {
        val acc = BabyAccessibilityService.instance
            ?: return ToolExecutionResult(false, "AccessibilityService is not enabled.")
        checkUiSafetyGuard(acc)?.let { return it }
        val ok = acc.longPress(x, y)
        return ToolExecutionResult(ok, if (ok) "Long-pressed ($x, $y)" else "Long-press failed at ($x, $y)")
    }

    private suspend fun typeText(text: String): ToolExecutionResult {
        val acc = BabyAccessibilityService.instance
            ?: return ToolExecutionResult(false, "AccessibilityService is not enabled.")
        checkUiSafetyGuard(acc)?.let { return it }
        val ok = acc.typeText(text)
        return ToolExecutionResult(ok, if (ok) "Typed \"$text\"" else "No editable field found on screen.")
    }

    private suspend fun swipe(args: Map<String, Any?>): ToolExecutionResult {
        val acc = BabyAccessibilityService.instance
            ?: return ToolExecutionResult(false, "AccessibilityService is not enabled.")
        checkUiSafetyGuard(acc)?.let { return it }
        val dir = args["direction"]?.toString()
        val ok = if (!dir.isNullOrBlank() && args["start_x"] == null) {
            acc.scroll(dir)
        } else {
            val dm = context.resources.displayMetrics
            val sx = asFloat(args["start_x"], dm.widthPixels * 0.5f)
            val sy = asFloat(args["start_y"], dm.heightPixels * 0.7f)
            val ex = asFloat(args["end_x"], dm.widthPixels * 0.5f)
            val ey = asFloat(args["end_y"], dm.heightPixels * 0.3f)
            acc.swipe(sx, sy, ex, ey)
        }
        return ToolExecutionResult(ok, if (ok) "Swiped" else "Swipe failed")
    }

    private suspend fun scroll(direction: String): ToolExecutionResult {
        val acc = BabyAccessibilityService.instance
            ?: return ToolExecutionResult(false, "AccessibilityService is not enabled.")
        checkUiSafetyGuard(acc)?.let { return it }
        val ok = acc.scroll(direction)
        return ToolExecutionResult(ok, if (ok) "Scrolled $direction" else "Scroll failed")
    }

    private fun globalAction(type: String): ToolExecutionResult {
        val acc = BabyAccessibilityService.instance
            ?: return ToolExecutionResult(false, "AccessibilityService is not enabled.")
        val ok = when (type) {
            "back" -> acc.performBack()
            "home" -> acc.performHome()
            "recents" -> acc.performRecents()
            "notifications" -> acc.openNotifications()
            else -> false
        }
        return ToolExecutionResult(ok, if (ok) "Performed $type" else "Failed $type")
    }

    private suspend fun takeScreenshot(): ToolExecutionResult {
        val acc = BabyAccessibilityService.instance
            ?: return ToolExecutionResult(false, "AccessibilityService is not enabled.")
        checkUiSafetyGuard(acc)?.let { return it }
        if (keyStore.settings.value.dataSaverEnabled) {
            return ToolExecutionResult(false, "Data Saver ON hai — screenshot capture band rakha gaya hai.")
        }
        val shot = acc.takeScreenshotBase64()
        return if (shot != null) {
            ToolExecutionResult(true, "Screenshot captured for visual inspection.", screenshotBase64 = shot)
        } else {
            ToolExecutionResult(false, "Screenshot unavailable (requires Android 11+ and active screen).")
        }
    }

    private fun readNotifications(filterApp: String?): ToolExecutionResult {
        val listener = BabyNotificationListenerService.instance
            ?: return ToolExecutionResult(false, "Notification access is not enabled for Baby.")
        val list = listener.getNotifications(filterApp)
        if (list.isEmpty()) {
            return ToolExecutionResult(true, "No active notifications${if (!filterApp.isNullOrBlank()) " matching $filterApp" else ""}.")
        }
        val allowSensitive = keyStore.settings.value.readSensitiveNotificationsEnabled
        val text = list.joinToString("\n") { n ->
            val replyHint = if (n.replyAction != null) " [can_reply key=${n.key}]" else ""
            val safeBody = if (!allowSensitive && NotificationSummaryManager.isSensitiveFinancialOrOtp(n.title, n.text)) {
                "[OTP/Bank alert masked]"
            } else if (!allowSensitive) {
                NotificationSummaryManager.maskSensitiveText(n.text)
            } else {
                n.text
            }
            "- [${n.appLabel}] ${n.title}: $safeBody$replyHint"
        }
        return ToolExecutionResult(true, text)
    }

    private fun replyNotification(key: String, message: String): ToolExecutionResult {
        val listener = BabyNotificationListenerService.instance
            ?: return ToolExecutionResult(false, "Notification access is not enabled.")
        val res = listener.replyToNotification(key, message)
        return res.fold(
            onSuccess = { ToolExecutionResult(true, it) },
            onFailure = { ToolExecutionResult(false, it.message ?: "Reply failed") }
        )
    }

    private fun speak(text: String, language: String): ToolExecutionResult {
        if (text.isBlank()) return ToolExecutionResult(false, "Nothing to speak.")
        ttsManager.speak(text, language)
        return ToolExecutionResult(true, "Spoke aloud: \"$text\"")
    }

    private suspend fun resolvePhoneNumber(target: String): String? {
        val trimmed = target.trim()
        if (trimmed.matches(Regex("^[+0-9][0-9\\s\\-()]{4,}$"))) {
            return trimmed
        }

        // Resolve nickname or saved contact number from Baby's on-device Memory first
        var lookupName = trimmed
        if (keyStore.settings.value.memoryEnabled) {
            val aliasMem = runCatching {
                memoryDao.findByKey(trimmed)
                    ?: memoryDao.getAllSnapshot().firstOrNull {
                        it.memoryKey.equals(trimmed, ignoreCase = true) ||
                            (it.category.equals("NICKNAME", ignoreCase = true) &&
                                (it.memoryKey.contains(trimmed, ignoreCase = true) ||
                                    trimmed.contains(it.memoryKey, ignoreCase = true)))
                    }
            }.getOrNull()
            if (aliasMem != null) {
                val embeddedNumber = Regex("""[+]?[0-9][0-9\s\-()]{6,}""").find(aliasMem.memoryValue)?.value?.trim()
                if (!embeddedNumber.isNullOrBlank()) {
                    return embeddedNumber
                }
                lookupName = aliasMem.memoryValue.trim()
            }
        }

        if (!permissionHelper.hasPermission(Manifest.permission.READ_CONTACTS)) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(
            uri,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$lookupName%"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numIdx >= 0) return cursor.getString(numIdx)
            }
        }
        return null
    }

    private fun asInt(v: Any?, default: Int): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.toDoubleOrNull()?.toInt() ?: default
        else -> default
    }

    private fun asFloat(v: Any?, default: Float): Float = when (v) {
        is Number -> v.toFloat()
        is String -> v.toFloatOrNull() ?: default
        else -> default
    }

    private fun asBool(v: Any?, default: Boolean): Boolean = when (v) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        else -> default
    }
}
