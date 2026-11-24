package com.example.domain.tools

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.core.security.SecureKeyStore
import com.example.data.local.AllowedAppDao
import com.example.data.local.AppAliasDao
import com.example.data.local.AppAliasEntity
import com.example.features.safety.SafetyManager
import com.example.service.BabyAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

data class IndexedApp(
    val packageName: String,
    val label: String,
    val normalizedLabel: String
)

sealed class AppResolution {
    data class Resolved(val app: IndexedApp, val matchReason: String, val score: Int) : AppResolution()
    data class Ambiguous(val query: String, val candidates: List<IndexedApp>) : AppResolution()
    data class NotFound(val query: String) : AppResolution()
}

enum class AppOpenStatus {
    OPENED,
    AMBIGUOUS,
    NOT_FOUND,
    BLOCKED,
    NEEDS_PERMISSION,
    FALLBACK_NOTIFICATION
}

data class AppOpenOutcome(
    val status: AppOpenStatus,
    val ok: Boolean,
    val message: String,
    val resolvedApp: IndexedApp? = null,
    val candidates: List<IndexedApp> = emptyList()
)

data class AppLaunchAttemptLog(
    val timestampMs: Long,
    val query: String,
    val status: AppOpenStatus,
    val resolvedPackage: String,
    val detail: String
)

@Singleton
class AppResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appAliasDao: AppAliasDao? = null,
    private val allowedAppDao: AllowedAppDao? = null,
    private val keyStore: SecureKeyStore? = null
) {
    @Volatile
    private var cachedApps: List<IndexedApp> = emptyList()

    @Volatile
    private var cacheDirty = true

    private val _launchAttempts = MutableStateFlow<List<AppLaunchAttemptLog>>(emptyList())
    val launchAttempts: StateFlow<List<AppLaunchAttemptLog>> = _launchAttempts.asStateFlow()

    fun observeAliases(): Flow<List<AppAliasEntity>>? = appAliasDao?.observeAll()

    fun invalidateIndex() {
        cacheDirty = true
    }

    fun getInstalledAppsSnapshot(forceRefresh: Boolean = false): List<IndexedApp> {
        if (!forceRefresh && !cacheDirty && cachedApps.isNotEmpty()) {
            return cachedApps
        }
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val activities = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
        }.getOrDefault(emptyList())

        val deduped = linkedMapOf<String, IndexedApp>()
        for (ri in activities) {
            val pkg = ri.activityInfo?.packageName?.trim().orEmpty()
            if (pkg.isEmpty()) continue
            val label = runCatching { ri.loadLabel(pm)?.toString()?.trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: pkg.substringAfterLast('.')
            if (!deduped.containsKey(pkg)) {
                deduped[pkg] = IndexedApp(
                    packageName = pkg,
                    label = label,
                    normalizedLabel = normalize(label)
                )
            }
        }
        cachedApps = deduped.values.sortedBy { it.label.lowercase(Locale.ROOT) }
        cacheDirty = false
        return cachedApps
    }

    suspend fun ensureDefaultAliasesSeeded() {
        val dao = appAliasDao ?: return
        if (dao.count() > 0) return
        val defaults = DEFAULT_HINGLISH_ALIASES.map { (alias, target) ->
            AppAliasEntity(
                alias = alias,
                packageName = target.packageName,
                appLabel = target.label,
                isUserEdited = false
            )
        }
        dao.insertMissing(defaults)
    }

    suspend fun saveAlias(alias: String, packageName: String, appLabel: String) {
        val cleanAlias = normalize(alias)
        if (cleanAlias.isBlank() || (packageName.isBlank() && appLabel.isBlank())) return
        appAliasDao?.upsert(
            AppAliasEntity(
                alias = cleanAlias,
                packageName = packageName.trim(),
                appLabel = appLabel.trim().ifBlank { packageName.trim() },
                isUserEdited = true,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteAlias(alias: String) {
        appAliasDao?.deleteByAlias(alias.trim().lowercase(Locale.ROOT))
    }

    suspend fun resolveApp(rawQuery: String): AppResolution {
        val apps = getInstalledAppsSnapshot()
        val storedAliases = runCatching { appAliasDao?.getAllSnapshot().orEmpty() }.getOrDefault(emptyList())
        return resolveFromIndex(rawQuery, apps, storedAliases)
    }

    suspend fun openApp(rawQuery: String, fromBackground: Boolean = false): AppOpenOutcome {
        val query = rawQuery.trim()
        if (query.isBlank()) {
            return AppOpenOutcome(AppOpenStatus.NOT_FOUND, false, "App ka naam khali hai.").also {
                recordAttempt(query, it.status, "", it.message)
            }
        }

        SafetyManager.isBlockedScreen(query, query)?.let { blocked ->
            val msg = "Safety guard: $blocked ($query) ko Baby automate ya open nahi karegi."
            recordAttempt(query, AppOpenStatus.BLOCKED, query, msg)
            return AppOpenOutcome(AppOpenStatus.BLOCKED, false, msg)
        }

        val resolution = resolveApp(query)
        when (resolution) {
            is AppResolution.NotFound -> {
                val msg = "Device par '$query' app nahi mila. Sahi naam bolo ya Settings > App Aliases mein alias add karo."
                recordAttempt(query, AppOpenStatus.NOT_FOUND, "", msg)
                return AppOpenOutcome(AppOpenStatus.NOT_FOUND, false, msg)
            }

            is AppResolution.Ambiguous -> {
                val optionsText = resolution.candidates.take(4).joinToString(", ") { it.label }
                val msg = "Ek se zyada matching apps mile: $optionsText. Kaunsa kholna hai?"
                recordAttempt(
                    query,
                    AppOpenStatus.AMBIGUOUS,
                    resolution.candidates.joinToString(",") { it.packageName },
                    msg
                )
                return AppOpenOutcome(
                    status = AppOpenStatus.AMBIGUOUS,
                    ok = false,
                    message = msg,
                    candidates = resolution.candidates
                )
            }

            is AppResolution.Resolved -> {
                val app = resolution.app
                SafetyManager.isBlockedScreen(app.packageName, app.label)?.let { blocked ->
                    val msg = "Safety guard: $blocked (${app.label}) ko Baby open nahi karegi."
                    recordAttempt(query, AppOpenStatus.BLOCKED, app.packageName, msg)
                    return AppOpenOutcome(AppOpenStatus.BLOCKED, false, msg, resolvedApp = app)
                }

                if (keyStore?.settings?.value?.allowedAppsOnlyEnabled == true &&
                    app.packageName != context.packageName
                ) {
                    val allowed = allowedAppDao?.getAllowed().orEmpty()
                    val ok = allowed.any {
                        it.packageName.equals(app.packageName, ignoreCase = true) ||
                            it.appLabel.equals(app.label, ignoreCase = true)
                    }
                    if (!ok) {
                        val msg = "Safety guard: '${app.label}' Allowed Apps list mein nahi hai."
                        recordAttempt(query, AppOpenStatus.BLOCKED, app.packageName, msg)
                        return AppOpenOutcome(AppOpenStatus.BLOCKED, false, msg, resolvedApp = app)
                    }
                }

                val pm = context.packageManager
                val launch = pm.getLaunchIntentForPackage(app.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                if (launch == null) {
                    val msg = "${app.label} launch nahi ho sakta (no launcher intent)."
                    recordAttempt(query, AppOpenStatus.NOT_FOUND, app.packageName, msg)
                    return AppOpenOutcome(AppOpenStatus.NOT_FOUND, false, msg, resolvedApp = app)
                }

                val accActive = BabyAccessibilityService.instance != null
                val hasOverlay = Settings.canDrawOverlays(context)
                val backgroundRestricted = fromBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !accActive && !hasOverlay

                if (backgroundRestricted) {
                    val posted = postTapToOpenNotification(app, launch)
                    val msg = if (posted) {
                        "${app.label} background launch limit ki wajah se seedha nahi khul paya — notification par \"Tap to open ${app.label}\" bhej diya hai (ya Overlay/Accessibility allow karo)."
                    } else {
                        "${app.label} background se kholne ke liye Display Over Other Apps ya Accessibility permission chahiye."
                    }
                    val status = if (posted) AppOpenStatus.FALLBACK_NOTIFICATION else AppOpenStatus.NEEDS_PERMISSION
                    recordAttempt(query, status, app.packageName, msg)
                    return AppOpenOutcome(status, posted, msg, resolvedApp = app)
                }

                return try {
                    val launchCtx = BabyAccessibilityService.instance ?: context
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val opts = ActivityOptions.makeBasic()
                        launchCtx.startActivity(launch, opts.toBundle())
                    } else {
                        launchCtx.startActivity(launch)
                    }
                    val msg = "${app.label} khol diya hai!"
                    recordAttempt(query, AppOpenStatus.OPENED, app.packageName, msg)
                    AppOpenOutcome(AppOpenStatus.OPENED, true, msg, resolvedApp = app)
                } catch (e: SecurityException) {
                    val posted = postTapToOpenNotification(app, launch)
                    val msg = if (posted) {
                        "${app.label} background restriction ki wajah se block hua — notification par \"Tap to open ${app.label}\" bhej diya hai."
                    } else {
                        "${app.label} kholne ke liye overlay/accessibility permission chahiye: ${e.message}"
                    }
                    val status = if (posted) AppOpenStatus.FALLBACK_NOTIFICATION else AppOpenStatus.NEEDS_PERMISSION
                    recordAttempt(query, status, app.packageName, msg)
                    AppOpenOutcome(status, posted, msg, resolvedApp = app)
                } catch (e: Exception) {
                    val msg = "${app.label} nahi khul paya: ${e.message}"
                    recordAttempt(query, AppOpenStatus.NOT_FOUND, app.packageName, msg)
                    AppOpenOutcome(AppOpenStatus.NOT_FOUND, false, msg, resolvedApp = app)
                }
            }
        }
    }

    private fun postTapToOpenNotification(app: IndexedApp, launchIntent: Intent): Boolean {
        return runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
            val channelId = "baby_app_launch_fallback"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Tap to Open App Fallback",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
            val pi = PendingIntent.getActivity(
                context,
                app.packageName.hashCode(),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Tap to open ${app.label}")
                .setContentText("Background launch restricted — tap karke ${app.label} kholo.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build()
            nm.notify(8900 + (app.packageName.hashCode() and 0xFF), notification)
            true
        }.getOrDefault(false)
    }

    private fun recordAttempt(
        query: String,
        status: AppOpenStatus,
        resolvedPkg: String,
        detail: String
    ) {
        val entry = AppLaunchAttemptLog(
            timestampMs = System.currentTimeMillis(),
            query = query,
            status = status,
            resolvedPackage = resolvedPkg,
            detail = detail
        )
        _launchAttempts.value = (listOf(entry) + _launchAttempts.value).take(50)
    }

    internal fun resolveFromIndex(
        rawQuery: String,
        apps: List<IndexedApp>,
        userAliases: List<AppAliasEntity> = emptyList()
    ): AppResolution {
        val cleanedRaw = rawQuery.trim()
        if (cleanedRaw.isBlank()) return AppResolution.NotFound(rawQuery)
        val qNorm = normalize(cleanedRaw)
        val qLower = cleanedRaw.lowercase(Locale.ROOT)

        // 1. Direct exact package name match
        apps.firstOrNull { it.packageName.equals(cleanedRaw, ignoreCase = true) }?.let {
            return AppResolution.Resolved(it, "exact_package", 100)
        }

        // 2. User-saved Room aliases (highest priority after exact package)
        val userMatch = userAliases.firstOrNull {
            normalize(it.alias) == qNorm || it.alias.equals(qLower, ignoreCase = true)
        }
        if (userMatch != null) {
            apps.firstOrNull {
                (userMatch.packageName.isNotBlank() && it.packageName.equals(userMatch.packageName, ignoreCase = true)) ||
                    normalize(it.label) == normalize(userMatch.appLabel)
            }?.let { return AppResolution.Resolved(it, "user_alias", 99) }
        }

        // 3. Built-in Hinglish alias map
        val builtInTarget = DEFAULT_HINGLISH_ALIASES[qLower] ?: DEFAULT_HINGLISH_ALIASES[qNorm]
        if (builtInTarget != null) {
            apps.firstOrNull {
                it.packageName.equals(builtInTarget.packageName, ignoreCase = true) ||
                    it.normalizedLabel == normalize(builtInTarget.label)
            }?.let { return AppResolution.Resolved(it, "hinglish_alias", 98) }
        }

        // 4. Exact label match
        val exactLabels = apps.filter { it.normalizedLabel == qNorm || it.label.equals(cleanedRaw, ignoreCase = true) }
        if (exactLabels.size == 1) {
            return AppResolution.Resolved(exactLabels.first(), "exact_label", 97)
        } else if (exactLabels.size > 1) {
            return AppResolution.Ambiguous(cleanedRaw, exactLabels.take(4))
        }

        // 5. Fuzzy + substring + token + Levenshtein scoring across installed apps
        if (apps.isEmpty()) return AppResolution.NotFound(cleanedRaw)

        val scored = apps.map { app ->
            val score = computeFuzzyScore(qNorm, qLower, app, builtInTarget)
            app to score
        }.filter { it.second >= 58 }
            .sortedByDescending { it.second }

        if (scored.isEmpty()) return AppResolution.NotFound(cleanedRaw)

        val top = scored.first()
        val closeTies = scored.filter { it.second >= top.second - 4 && it.second >= 68 }
        if (closeTies.size > 1 && top.second < 94) {
            return AppResolution.Ambiguous(cleanedRaw, closeTies.take(4).map { it.first })
        }
        return AppResolution.Resolved(top.first, "fuzzy_${top.second}", top.second)
    }

    private fun computeFuzzyScore(
        qNorm: String,
        qLower: String,
        app: IndexedApp,
        aliasHint: AliasTarget?
    ): Int {
        if (qNorm.isEmpty()) return 0
        val labelNorm = app.normalizedLabel
        val pkgLower = app.packageName.lowercase(Locale.ROOT)
        var best = 0

        if (aliasHint != null && pkgLower == aliasHint.packageName.lowercase(Locale.ROOT)) {
            best = maxOf(best, 96)
        }
        if (labelNorm == qNorm) best = maxOf(best, 96)
        if (labelNorm.startsWith(qNorm)) best = maxOf(best, 88)
        if (labelNorm.contains(qNorm) && qNorm.length >= 3) best = maxOf(best, 82)
        if (pkgLower.contains(qNorm) && qNorm.length >= 4) best = maxOf(best, 76)

        val qWords = qLower.split(Regex("\\s+")).filter { it.length >= 2 }
        val labelWords = app.label.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.length >= 2 }
        if (qWords.isNotEmpty() && labelWords.isNotEmpty()) {
            val hits = qWords.count { qw -> labelWords.any { lw -> lw.startsWith(qw) || lw == qw } }
            if (hits == qWords.size) best = maxOf(best, 86)
            else if (hits > 0) best = maxOf(best, 64 + hits * 8)
        }

        // Whole-label and per-word Levenshtein edit similarity (catches "yutub", "whatsap", "spoitfy", "telegrame")
        val maxDist = when {
            qNorm.length <= 4 -> 1
            qNorm.length <= 8 -> 2
            else -> 3
        }
        val fullDist = levenshtein(qNorm, labelNorm)
        if (fullDist <= maxDist) {
            val sim = 92 - fullDist * 9
            best = maxOf(best, sim)
        }
        for (lw in labelWords) {
            val lwNorm = normalize(lw)
            if (lwNorm.isEmpty()) continue
            val d = levenshtein(qNorm, lwNorm)
            val wordMaxDist = if (lwNorm.length <= 5) 1 else 2
            if (d <= wordMaxDist) {
                best = maxOf(best, 86 - d * 9)
            }
        }
        return best
    }

    companion object {
        data class AliasTarget(val packageName: String, val label: String)

        val DEFAULT_HINGLISH_ALIASES: Map<String, AliasTarget> = linkedMapOf(
            "yutub" to AliasTarget("com.google.android.youtube", "YouTube"),
            "utube" to AliasTarget("com.google.android.youtube", "YouTube"),
            "yt" to AliasTarget("com.google.android.youtube", "YouTube"),
            "youtube" to AliasTarget("com.google.android.youtube", "YouTube"),
            "yt music" to AliasTarget("com.google.android.apps.youtube.music", "YouTube Music"),
            "ytmusic" to AliasTarget("com.google.android.apps.youtube.music", "YouTube Music"),
            "youtube music" to AliasTarget("com.google.android.apps.youtube.music", "YouTube Music"),
            "insta" to AliasTarget("com.instagram.android", "Instagram"),
            "ig" to AliasTarget("com.instagram.android", "Instagram"),
            "instagram" to AliasTarget("com.instagram.android", "Instagram"),
            "whatsap" to AliasTarget("com.whatsapp", "WhatsApp"),
            "watsap" to AliasTarget("com.whatsapp", "WhatsApp"),
            "whtsapp" to AliasTarget("com.whatsapp", "WhatsApp"),
            "wa" to AliasTarget("com.whatsapp", "WhatsApp"),
            "whatsapp" to AliasTarget("com.whatsapp", "WhatsApp"),
            "gpay" to AliasTarget("com.google.android.apps.nbu.paisa.user", "Google Pay"),
            "google pay" to AliasTarget("com.google.android.apps.nbu.paisa.user", "Google Pay"),
            "phonepe" to AliasTarget("com.phonepe.app", "PhonePe"),
            "phone pe" to AliasTarget("com.phonepe.app", "PhonePe"),
            "fonpe" to AliasTarget("com.phonepe.app", "PhonePe"),
            "phonpe" to AliasTarget("com.phonepe.app", "PhonePe"),
            "paytm" to AliasTarget("net.one97.paytm", "Paytm"),
            "chrome" to AliasTarget("com.android.chrome", "Chrome"),
            "krome" to AliasTarget("com.android.chrome", "Chrome"),
            "browser" to AliasTarget("com.android.chrome", "Chrome"),
            "maps" to AliasTarget("com.google.android.apps.maps", "Maps"),
            "map" to AliasTarget("com.google.android.apps.maps", "Maps"),
            "google maps" to AliasTarget("com.google.android.apps.maps", "Maps"),
            "spotify" to AliasTarget("com.spotify.music", "Spotify"),
            "fb" to AliasTarget("com.facebook.katana", "Facebook"),
            "facebook" to AliasTarget("com.facebook.katana", "Facebook"),
            "amazon" to AliasTarget("in.amazon.mShop.android.shopping", "Amazon"),
            "flipkart" to AliasTarget("com.flipkart.android", "Flipkart"),
            "play store" to AliasTarget("com.android.vending", "Play Store"),
            "playstore" to AliasTarget("com.android.vending", "Play Store"),
            "gmail" to AliasTarget("com.google.android.gm", "Gmail"),
            "telegram" to AliasTarget("org.telegram.messenger", "Telegram"),
            "camera" to AliasTarget("com.android.camera", "Camera"),
            "settings" to AliasTarget("com.android.settings", "Settings"),
            "setting" to AliasTarget("com.android.settings", "Settings"),
            "calculator" to AliasTarget("com.google.android.calculator", "Calculator"),
            "calendar" to AliasTarget("com.google.android.calendar", "Calendar")
        )

        fun normalize(raw: String): String =
            raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = i - 1
                dp[0] = i
                for (j in 1..b.length) {
                    val temp = dp[j]
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + cost)
                    prev = temp
                }
            }
            return dp[b.length]
        }
    }
}
