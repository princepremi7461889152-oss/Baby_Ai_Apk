package com.example.features.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.os.Build
import com.example.core.security.SecureKeyStore
import com.example.data.local.ReminderDao
import com.example.data.local.ReminderEntity
import com.example.data.local.SavedPlaceDao
import com.example.data.local.SavedPlaceEntity
import com.example.data.remote.GroqApiService
import com.example.data.remote.GroqChatRequest
import com.example.data.remote.GroqMessage
import com.example.util.PermissionHelper
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class ParsedNaturalReminder(
    val title: String,
    val triggerType: String,
    val triggerAtMs: Long?,
    val delayMinutes: Int?,
    val placeLabel: String?
)

@Singleton
class ReminderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminderDao: ReminderDao,
    private val savedPlaceDao: SavedPlaceDao,
    private val groqApi: GroqApiService,
    private val permissionHelper: PermissionHelper,
    private val keyStore: SecureKeyStore
) {
    val reminders: Flow<List<ReminderEntity>> = reminderDao.observeAll()
    val savedPlaces: Flow<List<SavedPlaceEntity>> = savedPlaceDao.observeAll()

    suspend fun createNaturalReminder(
        rawCommandOrTitle: String,
        timeExpression: String? = null,
        delayMinutes: Int? = null
    ): String {
        val settings = keyStore.settings.value
        if (!settings.remindersEnabled) {
            return "Reminders Settings mein OFF hai."
        }
        val parsed = if (delayMinutes == null && !settings.dataSaverEnabled && settings.groqApiKey.isNotBlank()) {
            parseWithGroq(rawCommandOrTitle, timeExpression)
        } else null

        if (parsed != null && parsed.triggerType == "LOCATION") {
            return createLocationReminder(
                title = parsed.title.ifBlank { rawCommandOrTitle },
                placeLabel = parsed.placeLabel ?: "Home",
                latitude = null,
                longitude = null
            )
        }

        val finalTitle = (parsed?.title?.takeIf { it.isNotBlank() } ?: cleanReminderTitle(rawCommandOrTitle)).trim()
        val triggerMs = parsed?.triggerAtMs
            ?: resolveTimeMillis(timeExpression ?: rawCommandOrTitle, parsed?.delayMinutes ?: delayMinutes)

        val id = reminderDao.upsert(
            ReminderEntity(
                title = finalTitle.ifBlank { "Reminder" },
                triggerType = "TIME",
                triggerAtMs = triggerMs
            )
        )
        scheduleAlarm(id, finalTitle.ifBlank { "Reminder" }, triggerMs)
        val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val exactHint = if (!permissionHelper.hasExactAlarmPermission()) {
            " (Exact alarm permission Settings mein optional hai)"
        } else ""
        return "Reminder set: \"$finalTitle\" at ${fmt.format(Date(triggerMs))}$exactHint"
    }

    suspend fun createTimeReminder(
        title: String,
        naturalTimeOrIso: String?,
        delayMinutes: Int? = null
    ): String {
        val settings = keyStore.settings.value
        if (!settings.remindersEnabled) {
            return "Reminders Settings mein OFF hai."
        }
        val cleanTitle = title.trim().ifBlank { "Reminder" }
        val groqTimeMs = if (delayMinutes == null && !naturalTimeOrIso.isNullOrBlank() &&
            !settings.dataSaverEnabled && settings.groqApiKey.isNotBlank()
        ) {
            parseWithGroq("$cleanTitle ($naturalTimeOrIso)", naturalTimeOrIso)?.triggerAtMs
        } else null

        val triggerMs = groqTimeMs ?: resolveTimeMillis(naturalTimeOrIso, delayMinutes)
        val id = reminderDao.upsert(
            ReminderEntity(
                title = cleanTitle,
                triggerType = "TIME",
                triggerAtMs = triggerMs
            )
        )
        scheduleAlarm(id, cleanTitle, triggerMs)
        val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        return "Reminder set: \"$cleanTitle\" at ${fmt.format(Date(triggerMs))}"
    }

    @SuppressLint("MissingPermission")
    suspend fun createLocationReminder(
        title: String,
        placeLabel: String,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Float = 200f
    ): String {
        val settings = keyStore.settings.value
        if (!settings.remindersEnabled) {
            return "Reminders Settings mein OFF hai."
        }
        if (!settings.locationRemindersEnabled) {
            return "Location reminders Settings mein OFF hai. Pehle enable karo."
        }
        if (!permissionHelper.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return "Location permission missing hai. Reminders screen ya Settings mein Location allow karo."
        }

        val resolvedPlace = if (latitude == null || longitude == null) {
            resolveSavedPlace(placeLabel)
        } else null

        val lat = latitude ?: resolvedPlace?.latitude
            ?: return "'$placeLabel' save nahi hai. Reminders screen par Home/Work save karo ya map se pick karo."
        val lon = longitude ?: resolvedPlace?.longitude
            ?: return "'$placeLabel' ke coordinates nahi mile."
        val finalLabel = resolvedPlace?.label ?: placeLabel.trim().ifBlank { "Target Location" }
        val finalRadius = (resolvedPlace?.radiusMeters ?: radiusMeters).coerceIn(100f, 2000f)

        val entity = ReminderEntity(
            title = title.trim().ifBlank { "Location Reminder" },
            triggerType = "LOCATION",
            latitude = lat,
            longitude = lon,
            radiusMeters = finalRadius,
            placeLabel = finalLabel
        )
        val id = reminderDao.upsert(entity)
        registerGeofence(id, lat, lon, finalRadius)
        return "Location reminder set for $finalLabel: \"${entity.title}\""
    }

    suspend fun updateReminder(
        id: Long,
        newTitle: String,
        triggerType: String,
        timeExpressionOrIso: String?,
        placeLabel: String?,
        latitude: Double?,
        longitude: Double?,
        radiusMeters: Float = 200f
    ): String {
        val existing = reminderDao.getById(id) ?: return "Reminder #$id nahi mila."
        cancelAlarm(id)
        removeGeofence(id)

        val cleanTitle = newTitle.trim().ifBlank { existing.title }
        return if (triggerType.uppercase() == "LOCATION") {
            val saved = if (latitude == null || longitude == null) {
                resolveSavedPlace(placeLabel ?: existing.placeLabel)
            } else null
            val lat = latitude ?: saved?.latitude ?: existing.latitude
                ?: return "Location coordinates missing hain."
            val lon = longitude ?: saved?.longitude ?: existing.longitude
                ?: return "Location coordinates missing hain."
            val lbl = saved?.label ?: placeLabel?.takeIf { it.isNotBlank() } ?: existing.placeLabel
            val rad = radiusMeters.coerceIn(100f, 2000f)
            reminderDao.update(
                existing.copy(
                    title = cleanTitle,
                    triggerType = "LOCATION",
                    latitude = lat,
                    longitude = lon,
                    radiusMeters = rad,
                    placeLabel = lbl,
                    isCompleted = false
                )
            )
            if (permissionHelper.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                registerGeofence(id, lat, lon, rad)
            }
            "Location reminder updated: \"$cleanTitle\" ($lbl)"
        } else {
            val triggerMs = if (!timeExpressionOrIso.isNullOrBlank()) {
                resolveTimeMillis(timeExpressionOrIso, null)
            } else {
                existing.triggerAtMs.takeIf { it > System.currentTimeMillis() }
                    ?: (System.currentTimeMillis() + 30 * 60_000L)
            }
            reminderDao.update(
                existing.copy(
                    title = cleanTitle,
                    triggerType = "TIME",
                    triggerAtMs = triggerMs,
                    isCompleted = false
                )
            )
            scheduleAlarm(id, cleanTitle, triggerMs)
            val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            "Reminder updated: \"$cleanTitle\" at ${fmt.format(Date(triggerMs))}"
        }
    }

    suspend fun snoozeReminder(id: Long, minutes: Int = 10): String {
        val existing = reminderDao.getById(id) ?: return "Reminder #$id nahi mila."
        val newTriggerMs = System.currentTimeMillis() + minutes.coerceIn(1, 1440) * 60_000L
        reminderDao.reschedule(id, newTriggerMs)
        scheduleAlarm(id, existing.title, newTriggerMs)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(2000 + id.toInt())
        val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return "Reminder snoozed for $minutes min (${fmt.format(Date(newTriggerMs))}): \"${existing.title}\""
    }

    suspend fun deleteReminder(id: Long) {
        cancelAlarm(id)
        removeGeofence(id)
        reminderDao.deleteById(id)
    }

    suspend fun deleteReminderByQuery(queryOrId: String): String {
        val clean = queryOrId.trim()
        if (clean.isBlank()) return "Kis reminder ko delete karna hai? Naam ya ID batao."

        clean.toLongOrNull()?.let { id ->
            val found = reminderDao.getById(id) ?: return "Reminder #$id nahi mila."
            deleteReminder(id)
            return "Reminder deleted: \"${found.title}\""
        }

        val all = reminderDao.getAllActiveSnapshot()
        val match = all.firstOrNull {
            it.title.lowercase().contains(clean.lowercase()) ||
                it.placeLabel.lowercase().contains(clean.lowercase())
        } ?: return "'$clean' naam ka koi active reminder nahi mila."

        deleteReminder(match.id)
        return "Reminder deleted: \"${match.title}\""
    }

    suspend fun listRemindersSummary(includeCompleted: Boolean = false): String {
        val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        val list = if (includeCompleted) reminderDao.getAllSnapshot() else reminderDao.getAllActiveSnapshot()
        if (list.isEmpty()) {
            return if (includeCompleted) "Koi reminder saved nahi hai."
            else "Abhi koi pending reminder nahi hai."
        }
        return buildString {
            appendLine(if (includeCompleted) "Reminders (${list.size}):" else "Active Reminders (${list.size}):")
            list.forEach { r ->
                val detail = if (r.triggerType == "LOCATION") {
                    "Location: ${r.placeLabel}"
                } else {
                    "Time: ${fmt.format(Date(r.triggerAtMs))}"
                }
                val doneTag = if (r.isCompleted) " [Done]" else ""
                appendLine("• #${r.id} ${r.title} ($detail)$doneTag")
            }
        }.trim()
    }

    suspend fun savePlace(
        label: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float = 200f,
        addressHint: String = ""
    ): String {
        val key = normalizePlaceKey(label)
        val display = formatPlaceLabel(key, label)
        savedPlaceDao.upsert(
            SavedPlaceEntity(
                placeKey = key,
                label = display,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters.coerceIn(100f, 2000f),
                addressHint = addressHint.trim(),
                updatedAt = System.currentTimeMillis()
            )
        )
        return "$display place saved (${"%.4f".format(latitude)}, ${"%.4f".format(longitude)})."
    }

    @SuppressLint("MissingPermission")
    suspend fun saveCurrentLocationAsPlace(label: String, radiusMeters: Float = 200f): String {
        if (!permissionHelper.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return "Location permission missing hai. Reminders screen par Allow Location dabao."
        }
        val loc = getCurrentOrLastLocation()
            ?: return "GPS location abhi nahi mil payi. Map Picker se point select karo."
        return savePlace(
            label = label,
            latitude = loc.first,
            longitude = loc.second,
            radiusMeters = radiusMeters,
            addressHint = "Current GPS"
        )
    }

    suspend fun deleteSavedPlace(id: Long) {
        savedPlaceDao.deleteById(id)
    }

    suspend fun resolveSavedPlace(rawLabel: String): SavedPlaceEntity? {
        val key = normalizePlaceKey(rawLabel)
        return savedPlaceDao.findByKeyOrLabel(key)
            ?: savedPlaceDao.findByKeyOrLabel(rawLabel.trim())
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentOrLastLocation(): Pair<Double, Double>? {
        if (!permissionHelper.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val fresh = suspendCancellableCoroutine<Pair<Double, Double>?> { cont ->
            val cts = CancellationTokenSource()
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { l ->
                    if (cont.isActive) cont.resume(l?.let { it.latitude to it.longitude })
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
            cont.invokeOnCancellation { cts.cancel() }
        }
        if (fresh != null) return fresh
        return suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { l -> if (cont.isActive) cont.resume(l?.let { it.latitude to it.longitude }) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    }

    suspend fun geocodeAddress(query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION")
            val list = Geocoder(context, Locale.getDefault()).getFromLocationName(q, 1)
            val first = list?.firstOrNull()
            if (first != null) first.latitude to first.longitude else null
        }.getOrNull()
    }

    /**
     * Called by BootReceiver after device reboot or package replacement so all
     * pending AlarmManager alarms and Geofencing location triggers survive reboot.
     */
    suspend fun rescheduleAllActiveReminders() {
        val now = System.currentTimeMillis()
        val timeReminders = reminderDao.getActiveTimeReminders()
        timeReminders.forEach { rem ->
            val targetMs = if (rem.triggerAtMs > now) rem.triggerAtMs else now + 15_000L
            scheduleAlarm(rem.id, rem.title, targetMs)
        }
        if (keyStore.settings.value.locationRemindersEnabled &&
            permissionHelper.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            val geoReminders = reminderDao.getActiveLocationReminders()
            geoReminders.forEach { rem ->
                val lat = rem.latitude
                val lon = rem.longitude
                if (lat != null && lon != null) {
                    registerGeofence(rem.id, lat, lon, rem.radiusMeters)
                }
            }
        }
    }

    private suspend fun parseWithGroq(rawText: String, timeHint: String?): ParsedNaturalReminder? {
        val key = keyStore.settings.value.groqApiKey.trim()
        if (key.isBlank()) return null

        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm (EEEE)", Locale.ENGLISH).format(Date())
        val userPayload = buildString {
            appendLine("Current local datetime: $nowStr")
            appendLine("User reminder input: $rawText")
            if (!timeHint.isNullOrBlank()) appendLine("Time hint: $timeHint")
        }

        return runCatching {
            val resp = groqApi.chatCompletions(
                bearerAuth = "Bearer $key",
                request = GroqChatRequest(
                    model = keyStore.settings.value.groqModel,
                    messages = listOf(
                        GroqMessage(
                            role = "system",
                            content = "Parse the Hinglish/Hindi/English reminder into strict JSON ONLY (no markdown): " +
                                "{\"title\":\"short task\",\"trigger_type\":\"TIME\"|\"LOCATION\",\"iso_local\":\"yyyy-MM-dd HH:mm\" or null,\"delay_minutes\":int or null,\"place_label\":\"Home\"|\"Work\"|string or null}."
                        ),
                        GroqMessage(role = "user", content = userPayload)
                    ),
                    tools = null,
                    toolChoice = null
                )
            )
            val content = resp.body()?.choices?.firstOrNull()?.message?.content?.trim() ?: return null
            val jsonStr = content.substringAfter('{', "").let { if (it.isNotEmpty()) "{$it" else content }
                .substringBeforeLast('}', "")
                .let { if (it.isNotEmpty()) "$it}" else content }
            val obj = JSONObject(jsonStr)
            val title = obj.optString("title", "").trim()
            val type = obj.optString("trigger_type", "TIME").uppercase()
            val iso = obj.optString("iso_local", "").takeIf { it.isNotBlank() && it != "null" }
            val delay = if (obj.has("delay_minutes") && !obj.isNull("delay_minutes")) obj.optInt("delay_minutes") else null
            val place = obj.optString("place_label", "").takeIf { it.isNotBlank() && it != "null" }

            val isoMs = iso?.let {
                runCatching {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).parse(it)?.time
                }.getOrNull()
            }
            ParsedNaturalReminder(
                title = title,
                triggerType = if (type == "LOCATION") "LOCATION" else "TIME",
                triggerAtMs = isoMs ?: delay?.let { System.currentTimeMillis() + it * 60_000L },
                delayMinutes = delay,
                placeLabel = place
            )
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun registerGeofence(id: Long, lat: Double, lon: Double, radiusMeters: Float) {
        val geofencingClient = LocationServices.getGeofencingClient(context)
        val geofence = Geofence.Builder()
            .setRequestId("baby_geo_$id")
            .setCircularRegion(lat, lon, radiusMeters.coerceIn(100f, 2000f))
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val pi = PendingIntent.getBroadcast(
            context,
            id.toInt(),
            Intent(context, GeofenceBroadcastReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        runCatching { geofencingClient.addGeofences(request, pi) }
    }

    private fun removeGeofence(id: Long) {
        runCatching {
            LocationServices.getGeofencingClient(context).removeGeofences(listOf("baby_geo_$id"))
        }
    }

    private fun scheduleAlarm(reminderId: Long, title: String, triggerAtMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_FIRE_REMINDER
            putExtra("reminder_id", reminderId)
            putExtra("reminder_title", title)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true

        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    private fun cancelAlarm(id: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            id.toInt(),
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ReminderAlarmReceiver.ACTION_FIRE_REMINDER
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pi)
    }

    companion object {
        fun normalizePlaceKey(raw: String): String {
            val l = raw.trim().lowercase()
            return when {
                l.contains("ghar") || l == "home" || l.contains("house") -> "home"
                l.contains("office") || l.contains("work") || l.contains("kaam") || l.contains("daftr") -> "work"
                else -> l.replace(Regex("[^a-z0-9]"), "_").trim('_').ifBlank { "place" }
            }
        }

        private fun formatPlaceLabel(key: String, raw: String): String = when (key) {
            "home" -> "Home"
            "work" -> "Work"
            else -> raw.trim().replaceFirstChar { it.uppercase() }
        }

        fun cleanReminderTitle(raw: String): String {
            return raw
                .replace(Regex("""(?i)\b(yaad dilana|yaad dila dena|remind me( to| about)?|reminder( set karo| lagao)?)\b"""), "")
                .replace(Regex("""(?i)\b(kal|aaj|parso|subah|dopahar|shaam|raat)\b"""), "")
                .replace(Regex("""(?i)\b\d{1,2}(:\d{2})?\s*(baje|pm|am)\b"""), "")
                .trim(' ', ',', '.', '-', ':')
                .ifBlank { raw.trim() }
        }

        fun resolveTimeMillis(expr: String?, delayMinutes: Int?, nowMs: Long = System.currentTimeMillis()): Long {
            if (delayMinutes != null && delayMinutes > 0) {
                return nowMs + delayMinutes * 60_000L
            }
            val text = expr?.lowercase()?.trim().orEmpty()
            if (text.isBlank()) return nowMs + 30 * 60_000L

            if (text.contains("aadha ghanta") || text.contains("half hour")) {
                return nowMs + 30 * 60_000L
            }
            val hourRelMatch = Regex("""(\d+)\s*(ghante|ghanta|hr|hrs|hour|hours)""").find(text)
            if (hourRelMatch != null && !text.contains("baje")) {
                val hrs = hourRelMatch.groupValues[1].toIntOrNull() ?: 1
                return nowMs + hrs * 60 * 60_000L
            }
            val minMatch = Regex("""(\d+)\s*(min|minute|minutes|mint)""").find(text)
            if (minMatch != null) {
                val mins = minMatch.groupValues[1].toIntOrNull() ?: 15
                return nowMs + mins * 60_000L
            }

            val colonMatch = Regex("""(\d{1,2}):(\d{2})""").find(text)
            val bajeMatch = Regex("""(\d{1,2})\s*(baje|pm|am)""").find(text)

            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            if (text.contains("parso")) {
                cal.add(Calendar.DAY_OF_YEAR, 2)
            } else if (text.contains("kal") || text.contains("tomorrow")) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }

            if (colonMatch != null) {
                var h = colonMatch.groupValues[1].toIntOrNull() ?: cal.get(Calendar.HOUR_OF_DAY)
                val m = colonMatch.groupValues[2].toIntOrNull() ?: 0
                val isEvening = text.contains("pm") || text.contains("shaam") || text.contains("raat") || text.contains("dopahar")
                if (isEvening && h in 1..11) h += 12
                cal.set(Calendar.HOUR_OF_DAY, h.coerceIn(0, 23))
                cal.set(Calendar.MINUTE, m.coerceIn(0, 59))
                cal.set(Calendar.SECOND, 0)
            } else if (bajeMatch != null) {
                var h = bajeMatch.groupValues[1].toIntOrNull() ?: 9
                val suffix = bajeMatch.groupValues[2]
                val isEvening = suffix == "pm" || text.contains("shaam") || text.contains("raat") || text.contains("dopahar")
                if (isEvening && h in 1..11) h += 12
                if (suffix == "am" && h == 12) h = 0
                cal.set(Calendar.HOUR_OF_DAY, h.coerceIn(0, 23))
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
            } else {
                cal.add(Calendar.MINUTE, 30)
            }
            if (!text.contains("kal") && !text.contains("tomorrow") && !text.contains("parso") && cal.timeInMillis <= nowMs) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }
    }
}
