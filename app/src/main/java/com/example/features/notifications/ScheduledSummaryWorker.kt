package com.example.features.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.core.security.SecureKeyStore
import com.example.features.actionlog.ActionLogRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class ScheduledSummaryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun summaryManager(): NotificationSummaryManager
        fun keyStore(): SecureKeyStore
        fun actionLogRepository(): ActionLogRepository
    }

    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(applicationContext, Entry::class.java)
        val settings = ep.keyStore().settings.value
        if (!settings.notificationSummaryEnabled) return Result.success()

        val summary = ep.summaryManager().buildSummary(
            scope = "all",
            speakAloud = settings.scheduledSummarySpeakEnabled
        )
        ep.summaryManager().showSummaryNotification(summary)
        ep.actionLogRepository().record(
            commandText = "Scheduled Notification Summary",
            toolName = "summarize_notifications",
            argsSummary = "{\"scope\":\"all\",\"scheduled\":true}",
            resultText = summary,
            status = "OK",
            source = "routine"
        )
        return Result.success()
    }
}
