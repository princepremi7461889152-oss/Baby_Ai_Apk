package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.core.security.AppLanguage
import com.example.core.security.BabyPersonality
import com.example.core.security.BabySettings
import com.example.ui.components.BabyOrb
import com.example.ui.components.BabyOrbState
import com.example.ui.state.KeyTestResult
import com.example.ui.theme.BabyMotionTokens
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.theme.rememberReducedMotionEnabled
import com.example.ui.theme.withDevanagariFallback
import com.example.util.OemAutostartGuide
import com.example.util.PermissionHelper
import com.example.util.PermissionSnapshot

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    settings: BabySettings,
    permissions: PermissionSnapshot,
    oemGuide: OemAutostartGuide,
    permissionHelper: PermissionHelper?,
    initialStep: Int = 0,
    inceptionKeyTest: KeyTestResult = KeyTestResult.Idle,
    groqKeyTest: KeyTestResult = KeyTestResult.Idle,
    geminiKeyTest: KeyTestResult = KeyTestResult.Idle,
    onStepChange: (Int) -> Unit = {},
    onRefreshPermissions: () -> Unit,
    onSaveKeys: (String, String) -> Unit,
    onSaveAllKeys: (String, String, String) -> Unit = { _, gem, grq -> onSaveKeys(gem, grq) },
    onTestInceptionKey: (String) -> Unit = {},
    onTestGroqKey: (String) -> Unit = {},
    onTestGeminiKey: (String) -> Unit = {},
    onWakeWordToggle: (Boolean) -> Unit = {},
    onLanguageChange: (AppLanguage) -> Unit = {},
    onPersonalityChange: (BabyPersonality) -> Unit = {},
    onTtsToggle: (Boolean) -> Unit = {},
    onTrySampleCommand: (String) -> Unit = {},
    onFinishOnboarding: () -> Unit
) {
    val totalSteps = 5
    val step = initialStep.coerceIn(0, totalSteps - 1)
    var inceptionInput by remember(settings.inceptionApiKey) { mutableStateOf(settings.inceptionApiKey) }
    var geminiInput by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var groqInput by remember(settings.groqApiKey) { mutableStateOf(settings.groqApiKey) }
    var sampleExecuted by remember { mutableStateOf(false) }

    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors
    val reducedMotion = rememberReducedMotionEnabled()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val context = LocalContext.current
    val activity = context as? Activity
    var onboardingPermNotice by remember { mutableStateOf<String?>(null) }
    var pendingPermRationale by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val deniedPerms = results.filterValues { !it }.keys
        onboardingPermNotice = if (deniedPerms.isNotEmpty()) {
            val permanent = deniedPerms.any { perm ->
                permissionHelper?.isPermanentlyDenied(activity, perm) == true
            }
            if (permanent) {
                "Permission 'Don't ask again' se block hai — Open App Settings dabakar ON karein ya baad mein More > Permissions se enable karein."
            } else {
                "Kuch permissions skip ki gayi — Baby unke bina bhi safely kaam karegi."
            }
        } else null
        onRefreshPermissions()
    }

    fun requestOnboardingRuntimePerms(perms: Array<String>) {
        perms.forEach { permissionHelper?.markPermissionRequested(it) }
        val permanent = perms.any { permissionHelper?.isPermanentlyDenied(activity, it) == true }
        if (permanent) {
            permissionHelper?.openAppDetailsSettings()
        } else {
            runtimePermLauncher.launch(perms)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 620.dp)
                    .padding(horizontal = spacing.lg, vertical = spacing.md)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Step ${step + 1} of $totalSteps",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    TextButton(
                        onClick = {
                            onSaveAllKeys(inceptionInput, geminiInput, groqInput)
                            onFinishOnboarding()
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("onboarding_skip_button")
                    ) {
                        Text("Skip for now")
                    }
                }

                LinearProgressIndicator(
                    progress = { (step + 1) / totalSteps.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = spacing.xs)
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                )

                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        fadeIn(BabyMotionTokens.standardTween(reducedMotion)) togetherWith
                            fadeOut(BabyMotionTokens.fastTween(reducedMotion))
                    },
                    modifier = Modifier.weight(1f),
                    label = "onboardingStep"
                ) { activeStep ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        when (activeStep) {
                            // Step 1: Welcome
                            0 -> {
                                Image(
                                    painter = painterResource(id = R.drawable.img_onboarding_companion),
                                    contentDescription = "Baby AI phone assistant illustration",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(175.dp)
                                        .clip(RoundedCornerShape(spacing.cardRadius)),
                                    contentScale = ContentScale.Crop
                                )
                                Text(
                                    text = "Namaste! Main hoon Baby".withDevanagariFallback(),
                                    style = MaterialTheme.typography.displaySmall,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Aapki personal Hinglish voice & screen assistant. Bas bolo \"Hey Baby\" ya \"Baby\" — chahe phone pocket mein ho ya screen off ho.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OnboardingInfoCard(
                                    title = "100% On-Device Privacy",
                                    body = "Koi login ya Google Sign-In nahi. Chat history Room DB mein aur API keys Android Keystore EncryptedSharedPreferences mein safe rehti hain."
                                )
                            }

                            // Step 2: Add API Keys with Paste, Live Test, and Free-Key Links
                            1 -> {
                                Text("Add Your AI Brain Keys", style = MaterialTheme.typography.headlineMedium)
                                Text(
                                    text = "Inception Mercury (fast MIND), Gemini (vision & web search), aur Groq (Whisper voice + fallback) paste karo. Keys Keystore mein encrypted rehti hain.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                OutlinedTextField(
                                    value = inceptionInput,
                                    onValueChange = { inceptionInput = it },
                                    label = { Text("Inception API Key (Mercury 3 MIND)") },
                                    placeholder = { Text("Paste Inception key") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            clipboard.getText()?.text?.trim()?.let { inceptionInput = it }
                                        }) {
                                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste Inception key")
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("onboarding_inception_key_input")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { onTestInceptionKey(inceptionInput) },
                                        enabled = inceptionInput.isNotBlank() && inceptionKeyTest != KeyTestResult.Testing,
                                        modifier = Modifier.testTag("test_inception_key_btn")
                                    ) {
                                        if (inceptionKeyTest == KeyTestResult.Testing) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text("Test Mercury")
                                    }
                                    TextButton(onClick = {
                                        runCatching { uriHandler.openUri("https://platform.inceptionlabs.ai/") }
                                    }) {
                                        Text("Get Inception Key")
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                    }
                                }
                                KeyTestStatusLine(inceptionKeyTest)

                                OutlinedTextField(
                                    value = groqInput,
                                    onValueChange = { groqInput = it },
                                    label = { Text("Groq API Key (gsk_...)") },
                                    placeholder = { Text("Paste Groq key") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            clipboard.getText()?.text?.trim()?.let { groqInput = it }
                                        }) {
                                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste Groq key")
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("onboarding_groq_key_input")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { onTestGroqKey(groqInput) },
                                        enabled = groqInput.isNotBlank() && groqKeyTest != KeyTestResult.Testing,
                                        modifier = Modifier.testTag("test_groq_key_btn")
                                    ) {
                                        if (groqKeyTest == KeyTestResult.Testing) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text("Test Groq")
                                    }
                                    TextButton(onClick = {
                                        runCatching { uriHandler.openUri("https://console.groq.com/keys") }
                                    }) {
                                        Text("Free Groq Key")
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                    }
                                }
                                KeyTestStatusLine(groqKeyTest)

                                OutlinedTextField(
                                    value = geminiInput,
                                    onValueChange = { geminiInput = it },
                                    label = { Text("Gemini API Key (AIza...)") },
                                    placeholder = { Text("Paste Gemini key") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            clipboard.getText()?.text?.trim()?.let { geminiInput = it }
                                        }) {
                                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste Gemini key")
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("onboarding_gemini_key_input")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { onTestGeminiKey(geminiInput) },
                                        enabled = geminiInput.isNotBlank() && geminiKeyTest != KeyTestResult.Testing,
                                        modifier = Modifier.testTag("test_gemini_key_btn")
                                    ) {
                                        if (geminiKeyTest == KeyTestResult.Testing) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text("Test Gemini")
                                    }
                                    TextButton(onClick = {
                                        runCatching { uriHandler.openUri("https://aistudio.google.com/apikey") }
                                    }) {
                                        Text("Free Gemini Key")
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                    }
                                }
                                KeyTestStatusLine(geminiKeyTest)
                            }

                            // Step 3: Permissions one-by-one (non-blocking)
                            2 -> {
                                Text("Permissions Setup", style = MaterialTheme.typography.headlineMedium)
                                Text(
                                    text = "Har permission optional hai — jitni allow karoge utne features chalenge. Koi bhi skip kar sakte ho.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!onboardingPermNotice.isNullOrBlank()) {
                                    Surface(
                                        color = ext.warningContainer,
                                        contentColor = ext.onWarningContainer,
                                        shape = RoundedCornerShape(spacing.chipRadius),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = onboardingPermNotice.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(spacing.sm)
                                        )
                                    }
                                }
                                val micPermanent = !permissions.recordAudio &&
                                    (permissionHelper?.isPermanentlyDenied(activity, Manifest.permission.RECORD_AUDIO) == true)
                                val notifPermanent = !permissions.postNotifications &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    (permissionHelper?.isPermanentlyDenied(activity, Manifest.permission.POST_NOTIFICATIONS) == true)
                                val phonePerms = arrayOf(
                                    Manifest.permission.CALL_PHONE,
                                    Manifest.permission.READ_CONTACTS,
                                    Manifest.permission.SEND_SMS
                                )
                                val phonePermanent = !(permissions.callPhone && permissions.readContacts && permissions.sendSms) &&
                                    phonePerms.any { permissionHelper?.isPermanentlyDenied(activity, it) == true }

                                PermissionStepCard(
                                    icon = Icons.Default.Mic,
                                    title = "Microphone",
                                    subtitle = "\"Hey Baby\" wake word aur voice commands sunne ke liye.",
                                    granted = permissions.recordAudio,
                                    buttonLabel = if (micPermanent) "Open App Settings" else "Grant Mic",
                                    testTag = "grant_mic_button",
                                    onClick = {
                                        pendingPermRationale = Triple(
                                            "Microphone Permission",
                                            "\"Hey Baby\" wake word aur voice commands sunne ke liye mic access zaroori hai. Audio sirf aapke wake word bolne par capture hota hai."
                                        ) { requestOnboardingRuntimePerms(arrayOf(Manifest.permission.RECORD_AUDIO)) }
                                    }
                                )
                                PermissionStepCard(
                                    icon = Icons.Default.NotificationsActive,
                                    title = "Notifications & Listener",
                                    subtitle = "Persistent status bar aur WhatsApp/SMS notifications padhne ke liye.",
                                    granted = permissions.postNotifications && permissions.notificationListener,
                                    buttonLabel = when {
                                        !permissions.postNotifications && notifPermanent -> "Open App Settings"
                                        !permissions.postNotifications -> "Grant Notifications"
                                        else -> "Notification Access"
                                    },
                                    testTag = "grant_notif_button",
                                    onClick = {
                                        if (!permissions.postNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            pendingPermRationale = Triple(
                                                "Notification Bar Permission",
                                                "Background listening notification aur quick Stop button dikhane ke liye zaroori hai."
                                            ) { requestOnboardingRuntimePerms(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
                                        } else {
                                            pendingPermRationale = Triple(
                                                "Notification Reader Access",
                                                "Notifications summarize karne aur Smart Auto-Reply bhejne ke liye. OTP/Bank notifications default se masked rehti hain."
                                            ) { permissionHelper?.openNotificationListenerSettings() }
                                        }
                                    }
                                )
                                PermissionStepCard(
                                    icon = Icons.Default.AccessibilityNew,
                                    title = "Screen Control (Accessibility)",
                                    subtitle = "Apps open karna, tap/scroll/type karna aur screen explain karna.",
                                    granted = permissions.accessibility,
                                    buttonLabel = "Open Accessibility",
                                    testTag = "open_accessibility_button",
                                    onClick = {
                                        pendingPermRationale = Triple(
                                            "Accessibility Service",
                                            "Apps open karne, screen read/scroll karne aur search-and-play automate karne ke liye. Banking/UPI passwords kabhi read nahi hote."
                                        ) { permissionHelper?.openAccessibilitySettings() }
                                    }
                                )
                                PermissionStepCard(
                                    icon = Icons.Default.Phone,
                                    title = "Calls, Contacts & SMS",
                                    subtitle = "\"Mummy ko call karo\" jaise commands ke liye.",
                                    granted = permissions.callPhone && permissions.readContacts && permissions.sendSms,
                                    buttonLabel = if (phonePermanent) "Open App Settings" else "Grant Phone Access",
                                    testTag = "grant_phone_button",
                                    onClick = {
                                        pendingPermRationale = Triple(
                                            "Calls, Contacts & SMS",
                                            "Voice se call lagane, contact name match karne, aur missed call/SOS SMS bhejne ke liye."
                                        ) { requestOnboardingRuntimePerms(phonePerms) }
                                    }
                                )
                                PermissionStepCard(
                                    icon = Icons.Default.BatteryChargingFull,
                                    title = "Background Survival (${oemGuide.manufacturerTitle})",
                                    subtitle = "Screen off hone par bhi zinda rehne ke liye battery optimization off karein.",
                                    granted = permissions.batteryOptimizationIgnored,
                                    buttonLabel = "Disable Optimization",
                                    testTag = "grant_battery_button",
                                    onClick = {
                                        pendingPermRationale = Triple(
                                            "Ignore Battery Optimization",
                                            "Screen off ya lock hone par OS Baby ki background service band na kare — isliye battery optimization exempt karna helpful hai."
                                        ) { permissionHelper?.requestIgnoreBatteryOptimizations() }
                                    }
                                )
                                PermissionStepCard(
                                    icon = Icons.Default.BubbleChart,
                                    title = "Floating Bubble Overlay",
                                    subtitle = "Har app ke upar movable Baby orb dikhane ke liye.",
                                    granted = permissions.overlayBubble,
                                    buttonLabel = "Allow Overlay",
                                    testTag = "grant_overlay_button",
                                    onClick = {
                                        pendingPermRationale = Triple(
                                            "Floating Bubble Overlay",
                                            "Kisi bhi app ke upar Baby ka draggable mic bubble, Stop button aur quick menu dikhane ke liye."
                                        ) { permissionHelper?.openOverlaySettings() }
                                    }
                                )
                            }

                            // Step 4: Wake Word, Language & Personality Picker
                            3 -> {
                                Text("Wake Word & Personality", style = MaterialTheme.typography.headlineMedium)
                                Text(
                                    text = "Apne hisaab se Baby ki aawaz, language aur wake word choose karo.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Card(
                                    shape = RoundedCornerShape(spacing.cardRadius),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(spacing.md),
                                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Wake Word: \"Hey Baby\" & \"Baby\"", style = MaterialTheme.typography.titleSmall)
                                                Text(
                                                    "Hands-free voice activation background mein",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Switch(
                                                checked = settings.wakeWordEnabled,
                                                onCheckedChange = onWakeWordToggle
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Speak Replies Aloud (TTS)", style = MaterialTheme.typography.titleSmall)
                                                Text(
                                                    "Baby jawab bol kar sunayegi",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Switch(
                                                checked = settings.ttsRepliesEnabled,
                                                onCheckedChange = onTtsToggle
                                            )
                                        }
                                    }
                                }

                                Text("Language", style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AppLanguage.entries.forEach { lang ->
                                        FilterChip(
                                            selected = settings.preferredLanguage == lang,
                                            onClick = { onLanguageChange(lang) },
                                            label = { Text(lang.displayName) }
                                        )
                                    }
                                }

                                Text("Personality Tone", style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BabyPersonality.entries.forEach { p ->
                                        FilterChip(
                                            selected = settings.personality == p,
                                            onClick = { onPersonalityChange(p) },
                                            label = { Text(p.label) }
                                        )
                                    }
                                }
                            }

                            // Step 5: Interactive First Command Tutorial
                            4 -> {
                                Text("First Command Try Karo!", style = MaterialTheme.typography.headlineMedium)
                                Text(
                                    text = "Ready! Neeche diya sample command run karke dekho ya seedha Start Baby dabao.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Card(
                                    shape = RoundedCornerShape(spacing.cardRadius),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(spacing.lg),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(spacing.md)
                                    ) {
                                        BabyOrb(
                                            state = if (sampleExecuted) BabyOrbState.SPEAKING else BabyOrbState.IDLE,
                                            size = 96.dp,
                                            faceModeEnabled = settings.orbFaceModeEnabled,
                                            emotionIntensity = settings.emotionIntensity
                                        )
                                        Text(
                                            text = "Bolo: \"Hey Baby, flashlight on karo\"",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Button(
                                            onClick = {
                                                sampleExecuted = true
                                                onSaveAllKeys(inceptionInput, geminiInput, groqInput)
                                                onTrySampleCommand("Flashlight on karo")
                                                onFinishOnboarding()
                                            },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("onboarding_try_first_command_btn")
                                        ) {
                                            Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("Try \"Flashlight on karo\" & Open Chat")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(spacing.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step > 0) {
                        OutlinedButton(
                            onClick = { onStepChange(step - 1) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("onboarding_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Back")
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    Button(
                        onClick = {
                            if (step == 1) {
                                onSaveAllKeys(inceptionInput, geminiInput, groqInput)
                            }
                            if (step < totalSteps - 1) {
                                onStepChange(step + 1)
                            } else {
                                onSaveAllKeys(inceptionInput, geminiInput, groqInput)
                                onFinishOnboarding()
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("onboarding_next_button")
                    ) {
                        Text(if (step < totalSteps - 1) "Continue" else "Start Baby")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
            }
        }
    }

    pendingPermRationale?.let { (title, body, proceed) ->
        AlertDialog(
            onDismissRequest = { pendingPermRationale = null },
            title = { Text(title) },
            text = { Text(body.withDevanagariFallback(), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = {
                    pendingPermRationale = null
                    proceed()
                }) {
                    Text("Allow / Open")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPermRationale = null }) {
                    Text("Skip")
                }
            }
        )
    }
}

@Composable
private fun KeyTestStatusLine(result: KeyTestResult) {
    val ext = BabyThemeTokens.colors
    when (result) {
        KeyTestResult.Idle -> Unit
        KeyTestResult.Testing -> Text(
            text = "Checking API key…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        is KeyTestResult.Valid -> Text(
            text = result.message,
            style = MaterialTheme.typography.labelMedium,
            color = ext.success
        )
        is KeyTestResult.Invalid -> Text(
            text = result.message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun OnboardingInfoCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(BabyThemeTokens.spacing.cardRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionStepCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    buttonLabel: String,
    testTag: String,
    onClick: () -> Unit
) {
    val ext = BabyThemeTokens.colors
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                ext.successContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(BabyThemeTokens.spacing.cardRadius),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (granted) ext.success else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (granted) "Granted" else "Not granted",
                    tint = if (granted) ext.success else MaterialTheme.colorScheme.outline
                )
            }
            if (!granted) {
                Button(
                    onClick = onClick,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag(testTag)
                ) {
                    Text(buttonLabel)
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Onboarding Step 1 Dark")
@Composable
private fun OnboardingPreview() {
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        OnboardingScreen(
            settings = BabySettings(),
            permissions = PermissionSnapshot(
                recordAudio = true, postNotifications = false, accessibility = false,
                notificationListener = false, overlayBubble = false, batteryOptimizationIgnored = false,
                callPhone = false, sendSms = false, readContacts = false, writeSettings = false, dndAccess = false
            ),
            oemGuide = OemAutostartGuide("Generic Android", listOf("Set battery unrestricted"), emptyList()),
            permissionHelper = null,
            initialStep = 0,
            onRefreshPermissions = {},
            onSaveKeys = { _, _ -> },
            onFinishOnboarding = {}
        )
    }
}
