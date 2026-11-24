package com.example.util

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.example.service.BabyAccessibilityService
import com.example.service.BabyNotificationListenerService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class PermissionSnapshot(
    val recordAudio: Boolean,
    val postNotifications: Boolean,
    val accessibility: Boolean,
    val notificationListener: Boolean,
    val overlayBubble: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val callPhone: Boolean,
    val sendSms: Boolean,
    val readContacts: Boolean,
    val writeSettings: Boolean,
    val dndAccess: Boolean,
    val camera: Boolean = false,
    val readCalendar: Boolean = false,
    val fineLocation: Boolean = false,
    val exactAlarm: Boolean = true
) {
    val coreReady: Boolean
        get() = recordAudio && postNotifications && accessibility
}

data class OemAutostartGuide(
    val manufacturerTitle: String,
    val steps: List<String>,
    val componentCandidates: List<ComponentName>
)

@Singleton
class PermissionHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun getSnapshot(): PermissionSnapshot {
        return PermissionSnapshot(
            recordAudio = hasPermission(Manifest.permission.RECORD_AUDIO),
            postNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            } else true,
            accessibility = isAccessibilityEnabled(),
            notificationListener = isNotificationListenerEnabled(),
            overlayBubble = Settings.canDrawOverlays(context),
            batteryOptimizationIgnored = isIgnoringBatteryOptimizations(),
            callPhone = hasPermission(Manifest.permission.CALL_PHONE),
            sendSms = hasPermission(Manifest.permission.SEND_SMS),
            readContacts = hasPermission(Manifest.permission.READ_CONTACTS),
            writeSettings = Settings.System.canWrite(context),
            dndAccess = hasDndAccess(),
            camera = hasPermission(Manifest.permission.CAMERA),
            readCalendar = hasPermission(Manifest.permission.READ_CALENDAR),
            fineLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
            exactAlarm = hasExactAlarmPermission()
        )
    }

    fun hasExactAlarmPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isAccessibilityEnabled(): Boolean {
        val expectedComponent = ComponentName(context, BabyAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component == expectedComponent) return true
        }
        return false
    }

    fun isNotificationListenerEnabled(): Boolean {
        val expectedComponent = ComponentName(context, BabyNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return flat.split(":").any { ComponentName.unflattenFromString(it) == expectedComponent }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasDndAccess(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    fun isDeviceLocked(): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }

    fun openAccessibilitySettings() {
        launchSettingsIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openNotificationListenerSettings() {
        launchSettingsIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun openOverlaySettings() {
        launchSettingsIntent(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        if (!launchSettingsIntent(intent)) {
            launchSettingsIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openWriteSettings() {
        launchSettingsIntent(
            Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun openDndAccessSettings() {
        launchSettingsIntent(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            launchSettingsIntent(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    }

    fun openAppDetailsSettings() {
        launchSettingsIntent(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun openOemAutostartOrAppInfo() {
        val guide = getOemAutostartGuide()
        for (component in guide.componentCandidates) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchSettingsIntent(intent)) return
        }
        openAppDetailsSettings()
    }

    fun getOemAutostartGuide(): OemAutostartGuide {
        val brand = Build.MANUFACTURER.lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> OemAutostartGuide(
                manufacturerTitle = "Xiaomi / Redmi / Poco (MIUI / HyperOS)",
                steps = listOf(
                    "Open Settings > Apps > Manage apps > Baby",
                    "Turn ON Autostart / Background autostart",
                    "Tap Battery saver > Choose 'No restrictions'",
                    "Open Recents, long-press Baby card and tap Lock icon"
                ),
                componentCandidates = listOf(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                )
            )
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> OemAutostartGuide(
                manufacturerTitle = "Oppo / Realme / OnePlus (ColorOS / OxygenOS)",
                steps = listOf(
                    "Open Settings > Apps > App management > Baby",
                    "Tap Battery usage > Allow background activity & Allow auto-launch",
                    "Lock Baby in the Recent Apps switcher"
                ),
                componentCandidates = listOf(
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")
                )
            )
            brand.contains("vivo") || brand.contains("iqoo") -> OemAutostartGuide(
                manufacturerTitle = "Vivo / iQOO (Funtouch / OriginOS)",
                steps = listOf(
                    "Open iManager > App Manager > Autostart manager",
                    "Enable Autostart for Baby",
                    "Go to Settings > Battery > Background power consumption > High background power usage for Baby"
                ),
                componentCandidates = listOf(
                    ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                    ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                )
            )
            brand.contains("samsung") -> OemAutostartGuide(
                manufacturerTitle = "Samsung (One UI)",
                steps = listOf(
                    "Open Settings > Apps > Baby > Battery > select 'Unrestricted'",
                    "Open Settings > Device care > Battery > Background usage limits > Never sleeping apps > Add Baby"
                ),
                componentCandidates = emptyList()
            )
            else -> OemAutostartGuide(
                manufacturerTitle = "${Build.MANUFACTURER} Background Survival",
                steps = listOf(
                    "Set Battery usage for Baby to 'Unrestricted'",
                    "Allow Background data and Autostart if your device shows an App launch toggle",
                    "Keep Persistent Notification enabled so Android never kills Baby"
                ),
                componentCandidates = emptyList()
            )
        }
    }

    private fun launchSettingsIntent(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
