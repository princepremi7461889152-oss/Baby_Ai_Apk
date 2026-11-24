package com.example.features.safety

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.MainActivity
import com.example.R
import com.example.core.security.SecureKeyStore
import com.example.data.local.AllowedAppDao
import com.example.data.local.AllowedAppEntity
import com.example.data.local.EmergencyContactDao
import com.example.data.local.EmergencyContactEntity
import com.example.domain.tools.StopSignalBus
import com.example.features.actionlog.ActionLogRepository
import com.example.service.BabyForegroundService
import com.example.util.PermissionHelper
import com.example.voice.BabyTtsManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class EmergencyCountdownState(
    val active: Boolean = false,
    val secondsRemaining: Int = 0,
    val alsoCall: Boolean? = null,
    val contactCount: Int = 0
)

@Singleton
class SafetyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
    private val allowedAppDao: AllowedAppDao,
    private val emergencyContactDao: EmergencyContactDao,
    private val permissionHelper: PermissionHelper,
    private val ttsManager: BabyTtsManager,
    private val actionLog: ActionLogRepository,
    private val stopSignalBus: StopSignalBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val allowedApps: Flow<List<AllowedAppEntity>> = allowedAppDao.observeAll()
    val emergencyContacts: Flow<List<EmergencyContactEntity>> = emergencyContactDao.observeAll()

    private val _emergencyCountdown = MutableStateFlow(EmergencyCountdownState())
    val emergencyCountdown: StateFlow<EmergencyCountdownState> = _emergencyCountdown.asStateFlow()

    private var countdownJob: Job? = null

    @Volatile
    private var currentActivity: FragmentActivity? = null

    fun attachActivity(activity: FragmentActivity?) {
        currentActivity = activity
    }

    suspend fun setAppAllowed(packageName: String, appLabel: String, allowed: Boolean) {
        allowedAppDao.upsert(AllowedAppEntity(packageName, appLabel, allowed))
    }

    suspend fun removeAllowedApp(packageName: String) {
        allowedAppDao.deleteByPackage(packageName)
    }

    suspend fun isAppAllowedToControl(appNameOrPackage: String): Boolean {
        if (!keyStore.settings.value.allowedAppsOnlyEnabled) return true
        val q = appNameOrPackage.trim().lowercase()
        if (q == context.packageName.lowercase()) return true
        val allowed = allowedAppDao.getAllowed()
        if (allowed.isEmpty()) return false
        return allowed.any {
            it.packageName.lowercase() == q ||
                it.appLabel.lowercase() == q ||
                it.appLabel.lowercase().contains(q) ||
                it.packageName.lowercase().contains(q)
        }
    }

    fun speakAllowedAppsRefusal(appLabelOrPackage: String): String {
        val msg = "Safety guard: $appLabelOrPackage Allowed Apps list mein nahi hai, isliye control nahi kar sakti."
        ttsManager.speak(msg, "hinglish")
        return msg
    }

    suspend fun addEmergencyContact(name: String, phone: String): Boolean {
        if (name.isBlank() || phone.isBlank()) return false
        if (emergencyContactDao.getAll().size >= MAX_EMERGENCY_CONTACTS) return false
        emergencyContactDao.upsert(EmergencyContactEntity(name = name.trim(), phoneNumber = phone.trim()))
        return true
    }

    suspend fun deleteEmergencyContact(id: Long) = emergencyContactDao.deleteById(id)

    fun requiresBiometricCheck(): Boolean {
        val s = keyStore.settings.value
        if (!s.biometricSafetyEnabled) return false
        val bm = BiometricManager.from(context)
        return bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    suspend fun verifyBiometric(): Boolean {
        val activity = currentActivity ?: return false
        return withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<Boolean>()
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (!deferred.isCompleted) deferred.complete(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!deferred.isCompleted) deferred.complete(false)
                    }

                    override fun onAuthenticationFailed() {
                        // Let user retry until error/cancel
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Baby Safety Verification")
                .setSubtitle("Verify identity before sensitive phone action")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            prompt.authenticate(info)
            deferred.await()
        }
    }

    fun verifyVoicePin(enteredPin: String): Boolean = keyStore.verifyVoicePin(enteredPin)

    suspend fun triggerEmergencyAlert(alsoCall: Boolean? = null, skipCountdown: Boolean = false): String {
        val settings = keyStore.settings.value
        if (!settings.emergencyModeEnabled) {
            return "Emergency mode Settings mein disabled hai."
        }
        val contacts = emergencyContactDao.getAll()
        if (contacts.isEmpty()) {
            return "Koi emergency contact saved nahi hai. Settings > Safety mein emergency contact add karein."
        }

        if (skipCountdown) {
            return dispatchEmergencyAlertNow(alsoCall)
        }

        if (_emergencyCountdown.value.active) {
            return "Emergency SOS countdown pehle se chal raha hai (${_emergencyCountdown.value.secondsRemaining}s bache hain). Cancel bolkar rok sakte ho."
        }

        val gen = stopSignalBus.currentGeneration()
        countdownJob?.cancel()
        _emergencyCountdown.value = EmergencyCountdownState(
            active = true,
            secondsRemaining = EMERGENCY_COUNTDOWN_SECONDS,
            alsoCall = alsoCall,
            contactCount = contacts.size
        )
        postCountdownNotification(EMERGENCY_COUNTDOWN_SECONDS)
        ttsManager.speak("Emergency SOS 5 seconds mein bheja jayega. Cancel bol kar rok sakte ho.", "hinglish")

        countdownJob = scope.launch {
            for (sec in EMERGENCY_COUNTDOWN_SECONDS downTo 1) {
                if (stopSignalBus.isStoppedSince(gen) || !_emergencyCountdown.value.active) {
                    dismissCountdownNotification()
                    return@launch
                }
                _emergencyCountdown.value = _emergencyCountdown.value.copy(secondsRemaining = sec)
                postCountdownNotification(sec)
                delay(1000L)
            }
            if (stopSignalBus.isStoppedSince(gen) || !_emergencyCountdown.value.active) {
                dismissCountdownNotification()
                return@launch
            }
            _emergencyCountdown.value = EmergencyCountdownState()
            dismissCountdownNotification()
            dispatchEmergencyAlertNow(alsoCall)
        }

        return "Emergency SOS 5-second countdown shuru! Cancel dabao ya \"Cancel\" bolo rokne ke liye."
    }

    fun cancelEmergencyCountdown(reason: String = "User cancelled SOS"): Boolean {
        val wasActive = _emergencyCountdown.value.active || countdownJob?.isActive == true
        countdownJob?.cancel()
        countdownJob = null
        _emergencyCountdown.value = EmergencyCountdownState()
        dismissCountdownNotification()
        if (wasActive) {
            ttsManager.speak("Emergency alert cancel kar diya hai.", "hinglish")
            scope.launch {
                actionLog.record("Emergency SOS", "send_emergency_alert", reason, "SOS countdown cancelled", status = "CANCELLED")
            }
        }
        return wasActive
    }

    @SuppressLint("MissingPermission")
    private suspend fun dispatchEmergencyAlertNow(alsoCall: Boolean?): String {
        val settings = keyStore.settings.value
        if (!settings.emergencyModeEnabled) {
            return "Emergency mode Settings mein disabled hai."
        }
        val contacts = emergencyContactDao.getAll().take(MAX_EMERGENCY_CONTACTS)
        if (contacts.isEmpty()) {
            return "Koi emergency contact saved nahi hai."
        }

        val locationLink = fetchLiveLocationLink()
        val baseMessage = settings.emergencyCustomMessage.ifBlank {
            com.example.core.security.BabySettings.DEFAULT_EMERGENCY_MSG
        }
        val sosMessage = buildString {
            append(baseMessage)
            if (locationLink != null) {
                append(" Live location: $locationLink")
            }
        }

        var sentCount = 0
        val hasSms = permissionHelper.hasPermission(Manifest.permission.SEND_SMS)
        if (hasSms) {
            val smsManager = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
            }.getOrNull()
            if (smsManager != null) {
                contacts.forEach { contact ->
                    runCatching {
                        val parts = smsManager.divideMessage(sosMessage)
                        smsManager.sendMultipartTextMessage(contact.phoneNumber, null, parts, null, null)
                        sentCount++
                    }
                }
            } else {
                val first = contacts.first()
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(first.phoneNumber)}")).apply {
                    putExtra("sms_body", sosMessage)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
            }
        } else {
            val first = contacts.first()
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(first.phoneNumber)}")).apply {
                putExtra("sms_body", sosMessage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }

        val shouldCall = alsoCall ?: settings.emergencyAutoCall
        if (shouldCall) {
            val first = contacts.first()
            val callAction = if (permissionHelper.hasPermission(Manifest.permission.CALL_PHONE)) {
                Intent.ACTION_CALL
            } else Intent.ACTION_DIAL
            val callIntent = Intent(callAction, Uri.parse("tel:${Uri.encode(first.phoneNumber)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(callIntent) }
        }

        val summary = if (sentCount > 0) {
            "SOS SMS sent to $sentCount contact(s)${if (locationLink != null) " with live location" else ""}."
        } else {
            "SOS message composer opened for ${contacts.first().name}."
        }
        ttsManager.speak("Emergency alert bhej diya hai.", "hinglish")
        actionLog.record("Baby help", "send_emergency_alert", "contacts=${contacts.size}", summary, status = "OK")
        return summary
    }

    private fun postCountdownNotification(secondsRemaining: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            SOS_CHANNEL_ID,
            "Baby Emergency SOS",
            NotificationManager.IMPORTANCE_HIGH
        )
        nm.createNotificationChannel(channel)

        val cancelIntent = PendingIntent.getService(
            context,
            8801,
            Intent(context, BabyForegroundService::class.java).apply {
                action = BabyForegroundService.ACTION_CANCEL_SOS
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openAppIntent = PendingIntent.getActivity(
            context,
            8802,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, SOS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baby_tile)
            .setContentTitle("Emergency SOS in ${secondsRemaining}s")
            .setContentText("Tap Cancel SOS or say \"Cancel\" to stop sending alert.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Cancel SOS", cancelIntent)
            .build()
        nm.notify(SOS_NOTIFICATION_ID, notif)
    }

    private fun dismissCountdownNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(SOS_NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchLiveLocationLink(): String? {
        if (!permissionHelper.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !permissionHelper.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            return null
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val fresh = withTimeoutOrNull(4000L) {
            suspendCancellableCoroutine<String?> { cont ->
                val cts = CancellationTokenSource()
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                    .addOnSuccessListener { loc ->
                        if (cont.isActive) {
                            cont.resume(loc?.let { "https://maps.google.com/?q=${it.latitude},${it.longitude}" })
                        }
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
                cont.invokeOnCancellation { cts.cancel() }
            }
        }
        if (fresh != null) return fresh
        return withTimeoutOrNull(1500L) {
            suspendCancellableCoroutine<String?> { cont ->
                client.lastLocation
                    .addOnSuccessListener { loc ->
                        if (cont.isActive) {
                            cont.resume(loc?.let { "https://maps.google.com/?q=${it.latitude},${it.longitude}" })
                        }
                    }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    companion object {
        const val MAX_EMERGENCY_CONTACTS = 5
        const val EMERGENCY_COUNTDOWN_SECONDS = 5
        private const val SOS_CHANNEL_ID = "baby_emergency_sos_channel"
        private const val SOS_NOTIFICATION_ID = 4409

        private val BANKING_UPI_PACKAGES = listOf(
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app",
            "net.one97.paytm",
            "in.org.npci.upiapp", // BHIM
            "com.dreamplug.androidapp", // CRED
            "com.sbi.",
            "com.hdfc",
            "com.icici",
            "com.axis",
            "com.kotak",
            "com.csam.icici",
            "com.snapwork.hdfc",
            "com.yono",
            "com.mobikwik",
            "com.freecharge"
        )

        private val PASSWORD_MANAGER_PACKAGES = listOf(
            "com.agilebits.onepassword",
            "com.x8bit.bitwarden",
            "com.lastpass.lpandroid",
            "keepass",
            "com.dashlane",
            "io.enpass",
            "proton.android.pass",
            "com.kunzisoft.keepass"
        )

        private val SYSTEM_SECURITY_PACKAGES = listOf(
            "com.android.settings",
            "com.google.android.gms",
            "com.android.systemui",
            "com.miui.securitycenter",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.samsung.android.lool",
            "com.iqoo.secure",
            "com.vivo.permissionmanager"
        )

        private val SYSTEM_SECURITY_MARKERS = listOf(
            "choose screen lock", "confirm your pin", "confirm your pattern",
            "confirm your password", "device admin", "biometrics", "fingerprint",
            "face unlock", "factory reset", "erase all data", "change password",
            "encryption", "credential storage", "install unknown apps",
            "screen lock", "security & privacy"
        )

        private val PAYMENT_TEXT_MARKERS = listOf(
            "enter upi pin", "upi pin", "enter mpin", "netbanking",
            "cvv", "card number", "authorize payment", "confirm payment",
            "imobile", "yono", "bhim upi"
        )

        fun isBlockedScreen(packageName: String?, screenText: String? = null): String? {
            val pkg = packageName?.trim()?.lowercase().orEmpty()
            val text = screenText?.lowercase().orEmpty()

            if (PASSWORD_MANAGER_PACKAGES.any { pkg.contains(it) } ||
                text.contains("password manager") ||
                text.contains("master password") ||
                text.contains("saved passwords")
            ) {
                return "Password Manager"
            }

            if (BANKING_UPI_PACKAGES.any { pkg.contains(it) } ||
                PAYMENT_TEXT_MARKERS.any { text.contains(it) }
            ) {
                return "Banking / UPI Payment screen"
            }

            if (SYSTEM_SECURITY_PACKAGES.any { pkg == it }) {
                if (SYSTEM_SECURITY_MARKERS.any { text.contains(it) }) {
                    return "System Security settings screen"
                }
            }
            return null
        }

        fun isSensitiveField(
            viewId: String?,
            text: String?,
            hint: String?,
            isPasswordField: Boolean
        ): Boolean {
            if (isPasswordField) return true
            val combined = listOfNotNull(viewId, text, hint).joinToString(" ").lowercase()
            val markers = listOf(
                "password", "passwd", "passcode", "pin_input", "otp",
                "verification_code", "one_time", "cvv", "mpin", "upi_pin",
                "secret", "passphrase"
            )
            return markers.any { combined.contains(it) }
        }

        fun isEmergencyCancelCommand(command: String): Boolean {
            val l = command.lowercase().trim()
            return l == "cancel" || l == "abort" || l == "stop" || l == "ruko" ||
                l == "nahi" || l == "mat bhejo" || l == "cancel sos" ||
                l == "baby cancel" || l == "baby stop" || l.contains("cancel emergency")
        }
    }
}
