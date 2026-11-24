package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.features.notifications.NotificationSummaryManager
import com.example.features.reminders.ReminderManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootEntryPoint {
        fun reminderManager(): ReminderManager
        fun summaryManager(): NotificationSummaryManager
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            runCatching {
                BabyForegroundService.start(ctx)
            }
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val ep = EntryPointAccessors.fromApplication(ctx.applicationContext, BootEntryPoint::class.java)
                    ep.reminderManager().rescheduleAllActiveReminders()
                    ep.summaryManager().syncScheduledSummaries()
                } catch (_: Throwable) {
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
