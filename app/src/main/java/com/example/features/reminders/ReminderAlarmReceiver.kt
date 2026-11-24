package com.example.features.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.local.ReminderDao
import com.example.voice.BabyTtsManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderAlarmReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntry {
        fun reminderDao(): ReminderDao
        fun reminderManager(): ReminderManager
        fun ttsManager(): BabyTtsManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra("reminder_id", -1L)
        val title = intent.getStringExtra("reminder_title") ?: "Reminder"
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntry::class.java)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (intent.action) {
            ACTION_SNOOZE_REMINDER -> {
                if (reminderId > 0) {
                    val pending = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            ep.reminderManager().snoozeReminder(reminderId, 10)
                            nm.cancel(2000 + reminderId.toInt())
                        } finally {
                            pending.finish()
                        }
                    }
                }
                return
            }
            ACTION_COMPLETE_REMINDER -> {
                if (reminderId > 0) {
                    val pending = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            ep.reminderDao().markCompleted(reminderId)
                            nm.cancel(2000 + reminderId.toInt())
                        } finally {
                            pending.finish()
                        }
                    }
                }
                return
            }
        }

        showNotification(context, reminderId, title)
        ep.ttsManager().speak("Yaad dilana tha: $title", "hinglish")

        if (reminderId > 0) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ep.reminderDao().markCompleted(reminderId)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private fun showNotification(context: Context, reminderId: Long, title: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "baby_reminders_channel"
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Baby Reminders", NotificationManager.IMPORTANCE_HIGH)
        )

        val snoozeIntent = PendingIntent.getBroadcast(
            context,
            10_000 + reminderId.toInt(),
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ACTION_SNOOZE_REMINDER
                putExtra("reminder_id", reminderId)
                putExtra("reminder_title", title)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val doneIntent = PendingIntent.getBroadcast(
            context,
            20_000 + reminderId.toInt(),
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = ACTION_COMPLETE_REMINDER
                putExtra("reminder_id", reminderId)
                putExtra("reminder_title", title)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_baby_tile)
            .setContentTitle("Baby Reminder")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "Snooze 10m", snoozeIntent)
            .addAction(0, "Done", doneIntent)
            .build()
        nm.notify(2000 + reminderId.toInt(), notif)
    }

    companion object {
        const val ACTION_FIRE_REMINDER = "com.example.action.FIRE_REMINDER"
        const val ACTION_SNOOZE_REMINDER = "com.example.action.SNOOZE_REMINDER"
        const val ACTION_COMPLETE_REMINDER = "com.example.action.COMPLETE_REMINDER"
    }
}
