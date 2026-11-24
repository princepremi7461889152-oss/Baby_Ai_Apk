package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.features.autoreply.AutoReplyManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class CachedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val replyAction: Notification.Action?,
    val remoteInput: RemoteInput?,
    val isGroup: Boolean = false
)

class BabyNotificationListenerService : NotificationListenerService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NotificationEntryPoint {
        fun autoReplyManager(): AutoReplyManager
    }

    private val listenerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refreshFromActive()
    }

    override fun onListenerDisconnected() {
        if (instance === this) {
            instance = null
        }
        listenerScope.cancel()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val cachedEntry = cacheNotification(notification) ?: return
        val isOngoing = (notification.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (isOngoing) return
        listenerScope.launch {
            runCatching {
                val ep = EntryPointAccessors.fromApplication(
                    applicationContext,
                    NotificationEntryPoint::class.java
                )
                ep.autoReplyManager().handleIncomingNotification(
                    packageName = cachedEntry.packageName,
                    appLabel = cachedEntry.appLabel,
                    senderTitle = cachedEntry.title,
                    messageText = cachedEntry.text,
                    replyAction = cachedEntry.replyAction,
                    remoteInput = cachedEntry.remoteInput,
                    isGroup = cachedEntry.isGroup
                )
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { cached.remove(it) }
    }

    private fun refreshFromActive() {
        runCatching {
            activeNotifications?.forEach { cacheNotification(it) }
        }
    }

    private fun cacheNotification(sbn: StatusBarNotification): CachedNotification? {
        if (sbn.packageName == packageName) return null
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null

        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
            !extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE).isNullOrBlank()

        val appLabel = runCatching {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        }.getOrDefault(sbn.packageName.substringAfterLast('.'))

        var foundAction: Notification.Action? = null
        var foundRemoteInput: RemoteInput? = null
        sbn.notification.actions?.forEach { action ->
            action.remoteInputs?.firstOrNull()?.let { ri ->
                foundAction = action
                foundRemoteInput = ri
            }
        }

        val entry = CachedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            postTime = sbn.postTime,
            replyAction = foundAction,
            remoteInput = foundRemoteInput,
            isGroup = isGroup
        )
        cached[sbn.key] = entry
        return entry
    }

    fun getNotifications(filterApp: String? = null): List<CachedNotification> {
        refreshFromActive()
        val all = cached.values.sortedByDescending { it.postTime }
        return if (filterApp.isNullOrBlank()) {
            all.take(15)
        } else {
            val q = filterApp.trim().lowercase()
            all.filter {
                it.appLabel.lowercase().contains(q) ||
                    it.packageName.lowercase().contains(q) ||
                    it.title.lowercase().contains(q)
            }.take(15)
        }
    }

    fun replyToNotification(key: String, replyText: String): Result<String> {
        val entry = cached[key]
            ?: return Result.failure(IllegalStateException("Notification not found or dismissed."))
        val action = entry.replyAction
        val remoteInput = entry.remoteInput
        if (action == null || remoteInput == null) {
            return Result.failure(IllegalStateException("Notification from ${entry.appLabel} does not support inline reply."))
        }
        return try {
            val intent = Intent()
            val bundle = Bundle().apply {
                putCharSequence(remoteInput.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
            action.actionIntent.send(this, 0, intent)
            Result.success("Replied to ${entry.title.ifBlank { entry.appLabel }}: \"$replyText\"")
        } catch (e: PendingIntent.CanceledException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        @Volatile
        var instance: BabyNotificationListenerService? = null
            private set

        private val cached = ConcurrentHashMap<String, CachedNotification>()
    }
}
