package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.security.AutoReplyMode
import com.example.core.security.SecureKeyStore
import com.example.domain.ai.AgentOrchestrator
import com.example.domain.tools.StopSignalBus
import com.example.features.routines.RoutineManager
import com.example.features.safety.SafetyManager
import com.example.features.vision.ClipboardAiManager
import com.example.voice.AudioRecorderManager
import com.example.voice.BabyTtsManager
import com.example.voice.WakeWordDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BabyForegroundService : Service() {

    @Inject lateinit var keyStore: SecureKeyStore
    @Inject lateinit var wakeWordDetector: WakeWordDetector
    @Inject lateinit var audioRecorderManager: AudioRecorderManager
    @Inject lateinit var orchestrator: AgentOrchestrator
    @Inject lateinit var ttsManager: BabyTtsManager
    @Inject lateinit var stopSignalBus: StopSignalBus
    @Inject lateinit var floatingBubbleController: FloatingBubbleController
    @Inject lateinit var clipboardAiManager: ClipboardAiManager
    @Inject lateinit var routineManager: RoutineManager
    @Inject lateinit var safetyManager: SafetyManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var unlockReceiverRegistered = false
    private var headphoneReceiverRegistered = false
    private var powerShortcutReceiverRegistered = false
    private val recentScreenTogglesMs = ArrayDeque<Long>()
    private var activeVoiceCaptureJob: Job? = null

    private var lastWakeEnabled: Boolean? = null
    private var lastBubbleEnabled: Boolean? = null
    private var lastClipButtonEnabled: Boolean? = null

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                orchestrator.onDeviceUnlocked()
            }
        }
    }

    private val headphoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val connected = when (intent?.action) {
                AudioManager.ACTION_HEADSET_PLUG -> intent.getIntExtra("state", 0) == 1
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED ->
                    intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED
                else -> false
            }
            if (connected) {
                serviceScope.launch(Dispatchers.IO) {
                    runCatching { routineManager.runHeadphoneTriggeredRoutines() }
                }
            }
        }
    }

    private val powerShortcutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_SCREEN_OFF) return
            val settings = keyStore.settings.value
            if (!settings.emergencyModeEnabled || !settings.emergencyPowerShortcutEnabled) return

            val now = System.currentTimeMillis()
            if (shouldTriggerPowerShortcut(recentScreenTogglesMs, now)) {
                serviceScope.launch(Dispatchers.IO) {
                    runCatching { safetyManager.triggerEmergencyAlert() }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        createNotificationChannel()
        registerUnlockReceiver()
        registerHeadphoneReceiver()
        registerPowerShortcutReceiver()
        observeSettingsAndAgent()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromBoot = intent?.getBooleanExtra(EXTRA_STARTED_FROM_BOOT, false) == true
        when (intent?.action) {
            ACTION_STOP_TASK -> {
                orchestrator.stopCurrentTask("Notification Stop button")
                updateNotification()
                return START_STICKY
            }
            ACTION_CANCEL_SOS -> {
                safetyManager.cancelEmergencyCountdown("Notification Cancel SOS button")
                updateNotification()
                return START_STICKY
            }
            ACTION_TRIGGER_VOICE -> {
                startForegroundWithNotification(allowMicType = true)
                beginVoiceCapture("tile_or_notification")
                return START_STICKY
            }
            ACTION_REFRESH_NOTIFICATION -> {
                updateNotification()
                return START_STICKY
            }
            ACTION_STOP_SERVICE -> {
                wakeWordDetector.stop()
                floatingBubbleController.hide()
                releaseTransientWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // On Android 14+, BOOT_COMPLETED starts cannot claim MICROPHONE FGS type directly;
        // use SPECIAL_USE on boot and upgrade cleanly when active in foreground/tile trigger.
        startForegroundWithNotification(allowMicType = !fromBoot)
        if (keyStore.settings.value.wakeWordEnabled) {
            startWakeWordLoop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        wakeWordDetector.stop()
        floatingBubbleController.hide()
        if (unlockReceiverRegistered) {
            runCatching { unregisterReceiver(unlockReceiver) }
            unlockReceiverRegistered = false
        }
        if (headphoneReceiverRegistered) {
            runCatching { unregisterReceiver(headphoneReceiver) }
            headphoneReceiverRegistered = false
        }
        if (powerShortcutReceiverRegistered) {
            runCatching { unregisterReceiver(powerShortcutReceiver) }
            powerShortcutReceiverRegistered = false
        }
        releaseTransientWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeSettingsAndAgent() {
        serviceScope.launch {
            keyStore.settings.collect { settings ->
                if (lastWakeEnabled != settings.wakeWordEnabled) {
                    lastWakeEnabled = settings.wakeWordEnabled
                    if (settings.wakeWordEnabled) {
                        startWakeWordLoop()
                    } else {
                        wakeWordDetector.stop()
                    }
                }

                if (lastBubbleEnabled != settings.floatingBubbleEnabled ||
                    lastClipButtonEnabled != settings.clipboardAiEnabled
                ) {
                    lastBubbleEnabled = settings.floatingBubbleEnabled
                    lastClipButtonEnabled = settings.clipboardAiEnabled
                    if (settings.floatingBubbleEnabled) {
                        floatingBubbleController.show(
                            onBubbleClick = { beginVoiceCapture("floating_bubble") },
                            onStopClick = { orchestrator.stopCurrentTask("Floating bubble Stop") },
                            showClipButton = settings.clipboardAiEnabled,
                            onClipClick = {
                                if (keyStore.settings.value.clipboardAiEnabled) {
                                    com.example.features.vision.ClipboardActionActivity.launch(this@BabyForegroundService)
                                } else {
                                    ttsManager.speak("Clipboard AI Settings mein OFF hai.", "hinglish")
                                }
                            }
                        )
                    } else {
                        floatingBubbleController.hide()
                    }
                }
                updateNotification()
            }
        }

        serviceScope.launch {
            orchestrator.state.collect { execState ->
                val bubbleLabel = when {
                    execState.isRunning -> "Baby (${execState.currentStep}/15)"
                    audioRecorderManager.isRecording.value -> "Listening..."
                    else -> "Baby"
                }
                floatingBubbleController.updateStatus(bubbleLabel, execState.isRunning)
                updateNotification()
            }
        }

        serviceScope.launch {
            stopSignalBus.voiceTriggerEvents.collect { source ->
                beginVoiceCapture(source)
            }
        }
    }

    private fun startWakeWordLoop() {
        wakeWordDetector.start { event ->
            val trailing = event.trailingCommand
            if (!trailing.isNullOrBlank() && trailing.split(" ").size >= 2) {
                orchestrator.submitUserCommand(trailing, source = "wake_word")
                wakeWordDetector.resumeAfterRecording()
            } else {
                beginVoiceCapture("wake_word")
            }
        }
    }

    private fun beginVoiceCapture(source: String) {
        if (audioRecorderManager.isRecording.value) {
            finishVoiceCaptureAndTranscribe()
            return
        }
        activeVoiceCaptureJob?.cancel()
        activeVoiceCaptureJob = serviceScope.launch {
            acquireTransientWakeLock(60_000L)
            try {
                wakeWordDetector.pauseForRecording()
                ttsManager.speak("Haan bolo", "hinglish")
                delay(650L)

                val started = audioRecorderManager.startRecording(
                    maxDurationMs = 14_000L,
                    autoSilenceStop = true,
                    onSilenceDetected = {
                        serviceScope.launch { finishVoiceCaptureAndTranscribe() }
                    }
                )
                if (!started) {
                    wakeWordDetector.resumeAfterRecording()
                    releaseTransientWakeLock()
                } else {
                    floatingBubbleController.updateStatus("Sun rahi hoon...", orchestrator.state.value.isRunning)
                }
            } catch (e: CancellationException) {
                releaseTransientWakeLock()
                throw e
            } catch (_: Exception) {
                releaseTransientWakeLock()
                wakeWordDetector.resumeAfterRecording()
            }
        }
    }

    private fun finishVoiceCaptureAndTranscribe() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val wav = audioRecorderManager.stopAndBuildWav()
                if (wav != null) {
                    orchestrator.handleVoiceWav(wav)
                }
            } finally {
                releaseTransientWakeLock()
                wakeWordDetector.resumeAfterRecording()
            }
        }
    }

    private fun startForegroundWithNotification(allowMicType: Boolean) {
        val notification = buildNotification()
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val type = if (allowMicType && hasMic) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (allowMicType && hasMic) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun updateNotification() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val exec = orchestrator.state.value
        val settings = keyStore.settings.value

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val speakIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BabyForegroundService::class.java).apply { action = ACTION_TRIGGER_VOICE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BabyForegroundService::class.java).apply { action = ACTION_STOP_TASK },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val autoReplyBanner = if (settings.autoReplyEnabled && settings.autoReplyMode != AutoReplyMode.OFF) {
            "Auto-reply ON (${settings.autoReplyMode.label})"
        } else null

        val title = buildString {
            append(
                if (settings.wakeWordEnabled) getString(R.string.fg_notification_title)
                else getString(R.string.fg_notification_sleeping)
            )
            if (autoReplyBanner != null) {
                append(" • ")
                append(autoReplyBanner)
            }
        }

        val bodyText = when {
            exec.isRunning -> "Step ${exec.currentStep}/${exec.maxSteps}: ${exec.statusText}"
            exec.queuedCount > 0 -> "${exec.queuedCount} task queued for unlock"
            autoReplyBanner != null -> "$autoReplyBanner • Say \"Hey Baby\" anytime"
            settings.wakeWordEnabled -> "Say \"Hey Baby\" or \"Baby\" anytime"
            else -> "Tap Speak or open Baby to chat"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baby_tile)
            .setContentTitle(title)
            .setContentText(bodyText)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.fg_action_speak), speakIntent)

        if (autoReplyBanner != null) {
            builder.setSubText(autoReplyBanner)
        }

        if (exec.isRunning) {
            builder.addAction(0, getString(R.string.fg_action_stop), stopIntent)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.fg_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.fg_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun acquireTransientWakeLock(timeoutMs: Long) {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BabyAssistant::VoiceCaptureLock").apply {
                setReferenceCounted(false)
            }.also { wakeLock = it }
            if (!lock.isHeld) {
                lock.acquire(timeoutMs.coerceIn(5_000L, 120_000L))
            }
        }
    }

    private fun releaseTransientWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    private fun registerUnlockReceiver() {
        if (!unlockReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(
                this,
                unlockReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            unlockReceiverRegistered = true
        }
    }

    private fun registerHeadphoneReceiver() {
        if (!headphoneReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AudioManager.ACTION_HEADSET_PLUG)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(
                this,
                headphoneReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            headphoneReceiverRegistered = true
        }
    }

    private fun registerPowerShortcutReceiver() {
        if (!powerShortcutReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(
                this,
                powerShortcutReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            powerShortcutReceiverRegistered = true
        }
    }

    companion object {
        const val CHANNEL_ID = "baby_foreground_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_STARTED_FROM_BOOT = "extra_started_from_boot"
        const val ACTION_TRIGGER_VOICE = "com.example.action.TRIGGER_VOICE"
        const val ACTION_STOP_TASK = "com.example.action.STOP_TASK"
        const val ACTION_CANCEL_SOS = "com.example.action.CANCEL_SOS"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_SERVICE"
        const val ACTION_REFRESH_NOTIFICATION = "com.example.action.REFRESH_NOTIFICATION"

        fun shouldTriggerPowerShortcut(
            timestamps: ArrayDeque<Long>,
            nowMs: Long,
            requiredToggles: Int = 4,
            windowMs: Long = 3500L
        ): Boolean {
            timestamps.addLast(nowMs)
            while (timestamps.isNotEmpty() && nowMs - timestamps.first() > windowMs) {
                timestamps.removeFirst()
            }
            return if (timestamps.size >= requiredToggles) {
                timestamps.clear()
                true
            } else false
        }

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun start(context: Context, fromBoot: Boolean = false) {
            runCatching {
                val intent = Intent(context, BabyForegroundService::class.java).apply {
                    if (fromBoot) putExtra(EXTRA_STARTED_FROM_BOOT, true)
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun refreshNotificationIfRunning(context: Context) {
            if (!isServiceRunning) return
            runCatching {
                val intent = Intent(context, BabyForegroundService::class.java).apply {
                    action = ACTION_REFRESH_NOTIFICATION
                }
                context.startService(intent)
            }
        }

        fun triggerVoice(context: Context) {
            runCatching {
                val intent = Intent(context, BabyForegroundService::class.java).apply {
                    action = ACTION_TRIGGER_VOICE
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
