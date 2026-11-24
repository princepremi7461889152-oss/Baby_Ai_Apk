package com.example.features.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.local.ReminderDao
import com.example.voice.BabyTtsManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GeoEntry {
        fun reminderDao(): ReminderDao
        fun ttsManager(): BabyTtsManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        val ep = EntryPointAccessors.fromApplication(context.applicationContext, GeoEntry::class.java)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "baby_reminders_channel"
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Baby Reminders", NotificationManager.IMPORTANCE_HIGH)
        )

        val triggered = event.triggeringGeofences.orEmpty()
        if (triggered.isEmpty()) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                triggered.forEach { geofence ->
                    val id = geofence.requestId.removePrefix("baby_geo_").toLongOrNull() ?: return@forEach
                    val reminder = ep.reminderDao().getById(id) ?: return@forEach
                    ep.reminderDao().markCompleted(id)
                    val msg = "${reminder.placeLabel}: ${reminder.title}"
                    ep.ttsManager().speak("Aap ${reminder.placeLabel} pahunch gaye — ${reminder.title}", "hinglish")
                    val notif = NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(R.drawable.ic_baby_tile)
                        .setContentTitle("Baby Location Reminder")
                        .setContentText(msg)
                        .setAutoCancel(true)
                        .build()
                    nm.notify(3000 + id.toInt(), notif)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
