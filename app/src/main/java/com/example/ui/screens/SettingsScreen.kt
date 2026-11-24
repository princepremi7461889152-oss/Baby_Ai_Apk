package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.security.AiModelConfig
import com.example.core.security.AiRole
import com.example.core.security.AppLanguage
import com.example.core.security.AutoReplyMode
import com.example.core.security.BabyPersonality
import com.example.core.security.BabySettings
import com.example.core.security.PreferredBrain
import com.example.data.local.AllowedAppEntity
import com.example.data.local.AutoReplyContactRuleEntity
import com.example.data.local.EmergencyContactEntity
import com.example.ui.state.BabyUiPreferences
import com.example.ui.state.KeyTestResult
import com.example.ui.state.ModelsTestUiState
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens
import com.example.util.OemAutostartGuide
import com.example.util.PermissionHelper
import com.example.util.PermissionSnapshot
import kotlin.math.roundToInt

private enum class DangerAction {
    WIPE_MEMORY,
    CLEAR_HISTORY_LOGS,
    CLEAR_KEYS,
    RESET_ONBOARDING
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: BabySettings,
    permissions: PermissionSnapshot,
    oemGuide: OemAutostartGuide,
    permissionHelper: PermissionHelper?,
    initialSection: String = "",
    uiPrefs: BabyUiPreferences = BabyUiPreferences(),
    inceptionKeyTest: KeyTestResult = KeyTestResult.Idle,
    groqKeyTest: KeyTestResult = KeyTestResult.Idle,
    geminiKeyTest: KeyTestResult = KeyTestResult.Idle,
    liveMercuryModels: List<String> = emptyList(),
    liveGeminiModels: List<String> = emptyList(),
    liveGroqModels: List<String> = emptyList(),
    liveModelsLoading: Boolean = false,
    liveModelsError: String? = null,
    modelsTestState: ModelsTestUiState = ModelsTestUiState(),
    onBack: (() -> Unit)? = null,
    onRefreshPermissions: () -> Unit,
    onSaveKeys: (String, String) -> Unit,
    onSaveAllKeys: (String, String, String) -> Unit = { _, gem, grq -> onSaveKeys(gem, grq) },
    onTestInceptionKey: (String) -> Unit = {},
    onTestGroqKey: (String) -> Unit = {},
    onTestGeminiKey: (String) -> Unit = {},
    onClearKeys: () -> Unit,
    onThemeModeChange: (BabyThemeMode) -> Unit = {},
    onDynamicColorChange: (Boolean) -> Unit = {},
    onSoundCuesChange: (Boolean) -> Unit = {},
    onGeminiModelChange: (String) -> Unit,
    onGroqModelChange: (String) -> Unit,
    onSaveModelConfig: (AiModelConfig) -> Unit = {},
    onResetModelConfig: () -> Unit = {},
    onRefreshLiveGroqModels: (String) -> Unit = {},
    onRefreshAllLiveModels: (String, String, String) -> Unit = { _, _, grq -> onRefreshLiveGroqModels(grq) },
    onTestSingleRole: (AiRole, AiModelConfig, String, String, String) -> Unit = { _, _, _, _, _ -> },
    onTestAllModels: (AiModelConfig, String, String) -> Unit = { _, _, _ -> },
    onTestAllRoles: (AiModelConfig, String, String, String) -> Unit = { cfg, _, gem, grq -> onTestAllModels(cfg, grq, gem) },
    onOpenCompareModels: () -> Unit = {},
    onPreferredBrainChange: (PreferredBrain) -> Unit,
    onAutoFallbackChange: (Boolean) -> Unit,
    onWakeWordToggle: (Boolean) -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onLanguageChange: (AppLanguage) -> Unit,
    onConfirmSensitiveToggle: (Boolean) -> Unit,
    onTtsRepliesToggle: (Boolean) -> Unit,
    onFloatingBubbleToggle: (Boolean) -> Unit,
    onReopenOnboarding: () -> Unit,
    onOpenPermissions: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    onClearActionLogs: () -> Unit = {},
    onWipeAllMemories: () -> Unit = {},
    autoReplyRules: List<AutoReplyContactRuleEntity> = emptyList(),
    allowedApps: List<AllowedAppEntity> = emptyList(),
    emergencyContacts: List<EmergencyContactEntity> = emptyList(),
    onFeatureToggle: (String, Boolean) -> Unit = { _, _ -> },
    onRetentionDaysChange: (Int) -> Unit = {},
    onAutoReplyModeChange: (AutoReplyMode) -> Unit = {},
    onSaveAutoReplyTemplates: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onAutoReplyScheduleChange: (Boolean, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
    onUpsertAutoReplyRule: (String, String) -> Unit = { _, _ -> },
    onDeleteAutoReplyRule: (String) -> Unit = {},
    onPersonalityChange: (BabyPersonality) -> Unit = {},
    onSetVoicePin: (String) -> Unit = {},
    onSaveEmergencyMessage: (String) -> Unit = {},
    onAddAllowedApp: (String) -> Unit = {},
    onToggleAllowedApp: (AllowedAppEntity, Boolean) -> Unit = { _, _ -> },
    onRemoveAllowedApp: (String) -> Unit = {},
    onAddEmergencyContact: (String, String) -> Unit = { _, _ -> },
    onDeleteEmergencyContact: (Long) -> Unit = {},
    onTriggerSos: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onWakeBatterySaverToggle: (Boolean) -> Unit = {},
    onTtsTierModeChange: (com.example.core.security.TtsTierMode) -> Unit = {},
    onTtsVoiceNameChange: (String) -> Unit = {},
    onTtsSpeedChange: (Float) -> Unit = {},
    onTtsVolumeChange: (Float) -> Unit = {},
    onPreviewVoice: () -> Unit = {},
    onOpenWakeLab: () -> Unit = {},
    onEmotionIntensityChange: (com.example.core.security.EmotionIntensity) -> Unit = {}
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val listState = rememberLazyListState()

    var searchQuery by remember { mutableStateOf("") }
    var inceptionInput by remember(settings.inceptionApiKey) { mutableStateOf(settings.inceptionApiKey) }
    var geminiInput by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var groqInput by remember(settings.groqApiKey) { mutableStateOf(settings.groqApiKey) }
    var showKeys by remember { mutableStateOf(false) }
    var savedBanner by remember { mutableStateOf(false) }
    var pendingDangerAction by remember { mutableStateOf<DangerAction?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onRefreshPermissions() }

    val q = searchQuery.trim().lowercase()
    fun matches(vararg keywords: String): Boolean {
        if (q.isEmpty()) return true
        return keywords.any { it.contains(q, ignoreCase = true) }
    }

    val showAppearance = matches("appearance", "theme", "dark", "light", "amoled", "material you", "dynamic color", "sound", "cue", "beep", "emotion", "mood", "orb", "face", "haptic", "feeling")
    val showKeysSection = matches("api", "key", "inception", "mercury", "groq", "gemini", "keystore", "paste", "test")
    val showAiSection = matches("ai", "brain", "routing", "model", "mercury", "whisper", "tts", "fallback", "gemini", "groq", "gpt-oss", "qwen", "stt", "compare")
    val showVoiceSection = matches("wake", "voice", "hey baby", "sensitivity", "language", "hinglish", "hindi", "english", "tts", "bubble", "overlay", "confirm", "emotion")
    val showPermissionsSection = matches("permission", "mic", "accessibility", "notification", "battery", "autostart", "oem", "dnd", "brightness")
    val showFeaturesSection = matches("module", "personality", "memory", "data saver", "offline", "routine", "command", "macro", "reminder", "location", "clipboard", "camera", "screen help", "retention", "audit")
    val showAutoReplySection = matches("auto reply", "autoreply", "whatsapp", "sms", "busy", "driving", "sleeping", "schedule", "contact")
    val showSummarySection = matches("notification summary", "otp", "banking", "morning", "evening", "schedule")
    val showSafetySection = matches("safety", "biometric", "pin", "allowed apps", "sos", "emergency", "power", "contact")
    val showDangerSection = matches("danger", "wipe", "clear", "reset", "delete", "onboarding")

    // Auto-scroll when navigated with initialSection
    LaunchedEffect(initialSection) {
        if (initialSection.isNotBlank()) {
            val sectionIndexMap = buildMap {
                var idx = 0
                if (showAppearance) put("appearance", idx++)
                if (showKeysSection) put("keys", idx++)
                if (showAiSection) put("ai", idx++)
                if (showVoiceSection) put("voice", idx++)
                if (showPermissionsSection) put("permissions", idx++)
                if (showFeaturesSection) put("features", idx++)
                if (showAutoReplySection) put("auto_reply", idx++)
                if (showSummarySection) put("notifications", idx++)
                if (showSafetySection) put("safety", idx++)
                if (showDangerSection) put("danger", idx)
            }
            sectionIndexMap[initialSection.lowercase()]?.let { target ->
                listState.animateScrollToItem(target)
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = spacing.maxContentWidth)
        ) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.sm, vertical = spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (onBack != null) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("settings_back_btn")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Settings", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Theme, keys, dual-brain AI, wake word & safety",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = onReopenOnboarding,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("rerun_onboarding_button")
                        ) {
                            Text("Setup Wizard")
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search settings (theme, key, wake word, SOS)...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(spacing.chipRadius),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.xs)
                            .testTag("settings_search_input")
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(spacing.md),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 0. Appearance & Feedback
                if (showAppearance) {
                    item(key = "sec_appearance") {
                        SectionCard(
                            icon = Icons.Default.Palette,
                            title = "Appearance & Feedback",
                            summary = "${uiPrefs.themeMode.label}${if (uiPrefs.dynamicColorEnabled) " • Dynamic Color" else ""}"
                        ) {
                            Text("Theme", style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BabyThemeMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = uiPrefs.themeMode == mode,
                                        onClick = { onThemeModeChange(mode) },
                                        label = { Text(mode.label) },
                                        modifier = Modifier.testTag("theme_mode_${mode.name.lowercase()}")
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            SettingsSwitchRow(
                                title = "Material You Dynamic Color",
                                subtitle = "Android 12+ wallpaper colors apply karein.",
                                checked = uiPrefs.dynamicColorEnabled,
                                onCheckedChange = onDynamicColorChange,
                                testTag = "dynamic_color_switch"
                            )

                            SettingsSwitchRow(
                                title = "Listening Sound Cues",
                                subtitle = "Voice sunna shuru aur band karte waqt subtle beep.",
                                checked = uiPrefs.listeningSoundCuesEnabled,
                                onCheckedChange = onSoundCuesChange,
                                testTag = "sound_cues_switch"
                            )

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Text("Emotion Intensity (Orb & Voice Tone)", style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                com.example.core.security.EmotionIntensity.entries.forEach { level ->
                                    FilterChip(
                                        selected = settings.emotionIntensity == level,
                                        onClick = { onEmotionIntensityChange(level) },
                                        label = { Text(level.label) },
                                        modifier = Modifier.testTag("emotion_intensity_${level.name.lowercase()}")
                                    )
                                }
                            }

                            SettingsSwitchRow(
                                title = "Orb Face Mode (Minimal Eyes & Smile)",
                                subtitle = "Default OFF: Bade Orb mein subtle aankhen aur smile dikhegi.",
                                checked = settings.orbFaceModeEnabled,
                                onCheckedChange = {
                                    onFeatureToggle(com.example.core.security.SecureKeyStore.KEY_ORB_FACE_MODE, it)
                                },
                                testTag = "orb_face_mode_switch"
                            )

                            SettingsSwitchRow(
                                title = "Emotional Voice Modulation (Gemini TTS)",
                                subtitle = "Mood ke hisaab se Gemini TTS tone adjust hota hai (Android TTS normal rehta hai).",
                                checked = settings.emotionalVoiceEnabled,
                                onCheckedChange = {
                                    onFeatureToggle(com.example.core.security.SecureKeyStore.KEY_EMOTIONAL_VOICE, it)
                                },
                                testTag = "emotional_voice_switch"
                            )

                            SettingsSwitchRow(
                                title = "Haptic Tap & Voice Vibrations",
                                subtitle = "Mic press aur card taps par gentle vibration.",
                                checked = settings.hapticsEnabled,
                                onCheckedChange = {
                                    onFeatureToggle(com.example.core.security.SecureKeyStore.KEY_HAPTICS_ENABLED, it)
                                },
                                testTag = "haptics_enabled_switch"
                            )

                            SettingsSwitchRow(
                                title = "Understand My Mood (Text Only)",
                                subtitle = "Default OFF: Sirf aapke text words se mood samajhti hai — koi voice stress analysis nahi.",
                                checked = settings.understandMyMoodEnabled,
                                onCheckedChange = {
                                    onFeatureToggle(com.example.core.security.SecureKeyStore.KEY_UNDERSTAND_MY_MOOD, it)
                                },
                                testTag = "understand_mood_switch"
                            )
                        }
                    }
                }

                // 1. Encrypted API Keys
                if (showKeysSection) {
                    item(key = "sec_keys") {
                        val hasInc = settings.inceptionApiKey.isNotBlank()
                        val hasGroq = settings.groqApiKey.isNotBlank()
                        val hasGemini = settings.geminiApiKey.isNotBlank()
                        val configuredCount = listOf(hasInc, hasGemini, hasGroq).count { it }
                        val keysSummary = when (configuredCount) {
                            3 -> "Mercury, Gemini & Groq ready"
                            0 -> "No keys configured"
                            else -> "$configuredCount/3 keys ready"
                        }
                        SectionCard(
                            icon = Icons.Default.Key,
                            title = "API Keys (Android Keystore Encrypted)",
                            summary = keysSummary
                        ) {
                            Text(
                                text = "Keys sirf aapke device par EncryptedSharedPreferences mein store hoti hain. Koi login nahi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = inceptionInput,
                                onValueChange = {
                                    inceptionInput = it
                                    savedBanner = false
                                },
                                label = { Text("Inception API Key (Mercury 3 MIND)") },
                                placeholder = { Text("Paste Inception key") },
                                visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = {
                                            clipboard.getText()?.text?.trim()?.let { inceptionInput = it }
                                        }) {
                                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste Inception key")
                                        }
                                        IconButton(onClick = { showKeys = !showKeys }) {
                                            Icon(
                                                if (showKeys) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = "Toggle key visibility"
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_inception_key_input")
                            )
                            KeyHealthRow(
                                label = "Test Mercury",
                                status = inceptionKeyTest,
                                onTest = { onTestInceptionKey(inceptionInput) },
                                linkText = "Get Inception Key",
                                onOpenLink = { uriHandler.openUri("https://platform.inceptionlabs.ai/") }
                            )

                            OutlinedTextField(
                                value = groqInput,
                                onValueChange = {
                                    groqInput = it
                                    savedBanner = false
                                },
                                label = { Text("Groq API Key") },
                                placeholder = { Text("gsk_...") },
                                visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
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
                                    .testTag("settings_groq_key_input")
                            )
                            KeyHealthRow(
                                label = "Test Groq",
                                status = groqKeyTest,
                                onTest = { onTestGroqKey(groqInput) },
                                linkText = "Get Groq Key",
                                onOpenLink = { uriHandler.openUri("https://console.groq.com/keys") }
                            )

                            OutlinedTextField(
                                value = geminiInput,
                                onValueChange = {
                                    geminiInput = it
                                    savedBanner = false
                                },
                                label = { Text("Gemini API Key") },
                                placeholder = { Text("AIza...") },
                                visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
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
                                    .testTag("settings_gemini_key_input")
                            )
                            KeyHealthRow(
                                label = "Test Gemini",
                                status = geminiKeyTest,
                                onTest = { onTestGeminiKey(geminiInput) },
                                linkText = "Get Gemini Key",
                                onOpenLink = { uriHandler.openUri("https://aistudio.google.com/apikey") }
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        onSaveAllKeys(inceptionInput, geminiInput, groqInput)
                                        savedBanner = true
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("save_keys_button")
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Save Encrypted")
                                }
                                if (settings.hasAnyApiKey) {
                                    OutlinedButton(
                                        onClick = { pendingDangerAction = DangerAction.CLEAR_KEYS },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text("Clear")
                                    }
                                }
                            }
                            if (savedBanner) {
                                Text(
                                    text = "Saved securely in Android Keystore!",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ext.success
                                )
                            }
                        }
                    }
                }

                // 2. AI Routing & Model Config
                if (showAiSection) {
                    item(key = "sec_ai") {
                        val cfg = settings.modelConfig
                        var mindModelInput by remember(cfg.mindModel) { mutableStateOf(cfg.mindModel) }
                        var geminiModelInput by remember(cfg.geminiModel) { mutableStateOf(cfg.geminiModel) }
                        var chatModelInput by remember(cfg.chatModel) { mutableStateOf(cfg.chatModel) }
                        var fastModelInput by remember(cfg.fastModel) { mutableStateOf(cfg.fastModel) }
                        var sttModelInput by remember(cfg.sttModel) { mutableStateOf(cfg.sttModel) }
                        var ttsModelInput by remember(cfg.ttsModel) { mutableStateOf(cfg.ttsModel) }
                        var inceptionBaseUrlInput by remember(cfg.inceptionBaseUrl) { mutableStateOf(cfg.inceptionBaseUrl) }
                        var geminiBaseUrlInput by remember(cfg.geminiBaseUrl) { mutableStateOf(cfg.geminiBaseUrl) }
                        var groqBaseUrlInput by remember(cfg.groqBaseUrl) { mutableStateOf(cfg.groqBaseUrl) }
                        var showAdvancedBaseUrls by remember { mutableStateOf(false) }
                        var modelsSavedNotice by remember { mutableStateOf(false) }

                        fun buildEditedConfig(): AiModelConfig = AiModelConfig(
                            mindModel = mindModelInput,
                            geminiModel = geminiModelInput,
                            chatModel = chatModelInput,
                            fastModel = fastModelInput,
                            sttModel = sttModelInput,
                            ttsModel = ttsModelInput,
                            ttsPremiumModel = cfg.ttsPremiumModel,
                            inceptionBaseUrl = inceptionBaseUrlInput,
                            groqBaseUrl = groqBaseUrlInput,
                            geminiBaseUrl = geminiBaseUrlInput
                        ).normalized()

                        val mindOptions = remember(liveMercuryModels) {
                            (liveMercuryModels + AiModelConfig.MERCURY_PRESETS).distinct()
                        }
                        val geminiOptions = remember(liveGeminiModels) {
                            (liveGeminiModels.filter { !it.contains("tts", ignoreCase = true) } + AiModelConfig.GEMINI_PRESETS).distinct()
                        }
                        val ttsOptions = remember(liveGeminiModels) {
                            (liveGeminiModels.filter { it.contains("tts", ignoreCase = true) } + AiModelConfig.GEMINI_TTS_PRESETS).distinct()
                        }
                        val chatOptions = remember(liveGroqModels) {
                            (liveGroqModels.filter { AiModelConfig.isChatCapableModel(it) } + AiModelConfig.GROQ_CHAT_PRESETS)
                                .distinct()
                        }
                        val fastOptions = remember(liveGroqModels) {
                            (liveGroqModels.filter { AiModelConfig.isChatCapableModel(it) } + AiModelConfig.FAST_PRIORITY_LIST)
                                .distinct()
                        }
                        val sttOptions = remember(liveGroqModels) {
                            (liveGroqModels.filter { it.lowercase().contains("whisper") } + AiModelConfig.GROQ_STT_PRESETS)
                                .distinct()
                        }

                        SectionCard(
                            icon = Icons.Default.Memory,
                            title = "AI Models & Brain Routing",
                            summary = "${settings.preferredBrain.label} • ${cfg.mindModel}"
                        ) {
                            Text("Preferred Routing", style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PreferredBrain.entries.forEach { brain ->
                                    FilterChip(
                                        selected = settings.preferredBrain == brain,
                                        onClick = { onPreferredBrainChange(brain) },
                                        label = { Text(brain.label) }
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Live Model Lists", style = MaterialTheme.typography.titleSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            onRefreshAllLiveModels(inceptionInput, geminiInput, groqInput)
                                        },
                                        enabled = !liveModelsLoading,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("load_live_groq_models_button")
                                    ) {
                                        if (liveModelsLoading) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(6.dp))
                                        } else {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        val totalLive = liveMercuryModels.size + liveGeminiModels.size + liveGroqModels.size
                                        Text(if (totalLive == 0) "Load Live Models" else "Refresh ($totalLive)")
                                    }
                                }
                            }
                            if (!liveModelsError.isNullOrBlank()) {
                                Text(
                                    text = liveModelsError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            ModelSelectorField(
                                label = "MIND (Inception Mercury)",
                                value = mindModelInput,
                                options = mindOptions,
                                onValueChange = {
                                    mindModelInput = it
                                    modelsSavedNotice = false
                                },
                                onSelectOption = {
                                    mindModelInput = it
                                    onSaveModelConfig(buildEditedConfig())
                                    modelsSavedNotice = true
                                },
                                onTestRole = {
                                    onTestSingleRole(AiRole.MIND, buildEditedConfig(), inceptionInput, geminiInput, groqInput)
                                },
                                testButtonTag = "test_role_mind_btn",
                                testTag = "settings_mind_model_input",
                                dropdownTag = "settings_mind_model_dropdown"
                            )

                            ModelSelectorField(
                                label = "Vision & Search (Gemini Flash)",
                                value = geminiModelInput,
                                options = geminiOptions,
                                onValueChange = {
                                    geminiModelInput = it
                                    modelsSavedNotice = false
                                },
                                onSelectOption = {
                                    geminiModelInput = it
                                    onGeminiModelChange(it)
                                    onSaveModelConfig(buildEditedConfig())
                                    modelsSavedNotice = true
                                },
                                onTestRole = {
                                    onTestSingleRole(AiRole.VISION_SEARCH, buildEditedConfig(), inceptionInput, geminiInput, groqInput)
                                },
                                testButtonTag = "test_role_vision_btn",
                                testTag = "settings_gemini_model_input",
                                dropdownTag = "settings_gemini_model_dropdown"
                            )

                            ModelSelectorField(
                                label = "Speech-to-Text (Groq Whisper STT)",
                                value = sttModelInput,
                                options = sttOptions,
                                onValueChange = {
                                    sttModelInput = it
                                    modelsSavedNotice = false
                                },
                                onSelectOption = {
                                    sttModelInput = it
                                    onSaveModelConfig(buildEditedConfig())
                                    modelsSavedNotice = true
                                },
                                onTestRole = {
                                    onTestSingleRole(AiRole.STT, buildEditedConfig(), inceptionInput, geminiInput, groqInput)
                                },
                                testButtonTag = "test_role_stt_btn",
                                testTag = "settings_stt_model_input",
                                dropdownTag = "settings_stt_model_dropdown"
                            )

                            ModelSelectorField(
                                label = "Voice TTS (Gemini TTS)",
                                value = ttsModelInput,
                                options = ttsOptions,
                                onValueChange = {
                                    ttsModelInput = it
                                    modelsSavedNotice = false
                                },
                                onSelectOption = {
                                    ttsModelInput = it
                                    onSaveModelConfig(buildEditedConfig())
                                    modelsSavedNotice = true
                                },
                                onTestRole = {
                                    onTestSingleRole(AiRole.TTS, buildEditedConfig(), inceptionInput, geminiInput, groqInput)
                                },
                                testButtonTag = "test_role_tts_btn",
                                testTag = "settings_tts_model_input",
                                dropdownTag = "settings_tts_model_dropdown"
                            )

                            ModelSelectorField(
                                label = "Fallback Chat & Tools (Groq)",
                                value = chatModelInput,
                                options = chatOptions,
                                onValueChange = {
                                    chatModelInput = it
                                    modelsSavedNotice = false
                                },
                                onSelectOption = {
                                    chatModelInput = it
                                    onGroqModelChange(it)
                                    onSaveModelConfig(buildEditedConfig())
                                    modelsSavedNotice = true
                                },
                                onTestRole = {
                                    onTestSingleRole(AiRole.FALLBACK_CHAT, buildEditedConfig(), inceptionInput, geminiInput, groqInput)
                                },
                                testButtonTag = "test_role_fallback_btn",
                                testTag = "settings_chat_model_input",
                                dropdownTag = "settings_chat_model_dropdown"
                            )

                            ModelSelectorField(
                                label = "Groq Fast / Simple Model",
                                value = fastModelInput,
                                options = fastOptions,
                                onValueChange = {
                                    fastModelInput = it
                                    modelsSavedNotice = false
                                },
                                onSelectOption = {
                                    fastModelInput = it
                                    onSaveModelConfig(buildEditedConfig())
                                    modelsSavedNotice = true
                                },
                                testTag = "settings_fast_model_input",
                                dropdownTag = "settings_fast_model_dropdown"
                            )

                            TextButton(onClick = { showAdvancedBaseUrls = !showAdvancedBaseUrls }) {
                                Text(if (showAdvancedBaseUrls) "Hide Provider Base URLs" else "Edit Provider Base URLs")
                            }
                            if (showAdvancedBaseUrls) {
                                OutlinedTextField(
                                    value = inceptionBaseUrlInput,
                                    onValueChange = { inceptionBaseUrlInput = it; modelsSavedNotice = false },
                                    label = { Text("Inception Base URL") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = geminiBaseUrlInput,
                                    onValueChange = { geminiBaseUrlInput = it; modelsSavedNotice = false },
                                    label = { Text("Gemini Base URL") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = groqBaseUrlInput,
                                    onValueChange = { groqBaseUrlInput = it; modelsSavedNotice = false },
                                    label = { Text("Groq Base URL") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = {
                                        val updated = buildEditedConfig()
                                        onGroqModelChange(updated.chatModel)
                                        onGeminiModelChange(updated.geminiModel)
                                        onSaveModelConfig(updated)
                                        modelsSavedNotice = true
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("save_models_button")
                                ) {
                                    Text("Save Models")
                                }

                                OutlinedButton(
                                    onClick = {
                                        onTestAllRoles(buildEditedConfig(), inceptionInput, geminiInput, groqInput)
                                    },
                                    enabled = !modelsTestState.isTesting,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("test_models_button")
                                ) {
                                    if (modelsTestState.isTesting) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text("Test models")
                                }

                                OutlinedButton(
                                    onClick = onOpenCompareModels,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("open_compare_models_btn")
                                ) {
                                    Text("Compare Models (30)")
                                }

                                TextButton(
                                    onClick = {
                                        onResetModelConfig()
                                        modelsSavedNotice = true
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("reset_models_button")
                                ) {
                                    Text("Reset Defaults")
                                }
                            }

                            if (modelsSavedNotice) {
                                Text(
                                    text = "Model config saved!",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ext.success
                                )
                            }

                            if (modelsTestState.items.isNotEmpty()) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("models_test_results")
                                ) {
                                    modelsTestState.items.forEach { item ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(
                                                imageVector = if (item.passed == true) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                                contentDescription = if (item.passed == true) "Pass" else "Fail",
                                                tint = if (item.passed == true) ext.success else MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "${item.label}: ${item.modelId}",
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                                Text(
                                                    text = item.detail,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (item.passed == true) ext.success else MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            SettingsSwitchRow(
                                title = "Auto-Fallback on Error / Rate Limit",
                                subtitle = "Agar primary brain fail ho toh next brain (Mercury -> Gemini -> Groq) par switch kare.",
                                checked = settings.autoFallbackEnabled,
                                onCheckedChange = onAutoFallbackChange
                            )
                        }
                    }
                }

                // 3. Wake Word, Voice & Safety
                if (showVoiceSection) {
                    item(key = "sec_voice") {
                        val voiceSummary = buildString {
                            append(if (settings.wakeWordEnabled) "Wake ON" else "Wake OFF")
                            append(" • ")
                            append(settings.preferredLanguage.displayName)
                        }
                        SectionCard(
                            icon = Icons.Default.Mic,
                            title = "Wake Word, Voice & Bubble",
                            summary = voiceSummary
                        ) {
                            SettingsSwitchRow(
                                title = "Wake Word (\"Hey Baby\" & \"Baby\")",
                                subtitle = "Background mein dono phrases par activate hoti hai (2s cooldown).",
                                checked = settings.wakeWordEnabled,
                                onCheckedChange = onWakeWordToggle,
                                testTag = "wake_word_switch"
                            )

                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Wake Sensitivity", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${(settings.wakeWordSensitivity * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                Slider(
                                    value = settings.wakeWordSensitivity,
                                    onValueChange = onSensitivityChange,
                                    valueRange = 0.1f..1.0f,
                                    modifier = Modifier.testTag("wake_sensitivity_slider")
                                )
                            }

                            SettingsSwitchRow(
                                title = "Battery-Saver Wake Duty Cycle",
                                subtitle = "Quiet rooms mein audio polling thoda throttle karta hai.",
                                checked = settings.wakeWordBatterySaver,
                                onCheckedChange = onWakeBatterySaverToggle,
                                testTag = "wake_battery_saver_settings_switch"
                            )

                            OutlinedButton(
                                onClick = onOpenWakeLab,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("open_wake_lab_btn")
                            ) {
                                Text("Open Wake Word & Mic Lab")
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Text("Default Language", style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppLanguage.entries.forEach { lang ->
                                    FilterChip(
                                        selected = settings.preferredLanguage == lang,
                                        onClick = { onLanguageChange(lang) },
                                        label = { Text(lang.displayName) }
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            SettingsSwitchRow(
                                title = "Speak Replies Aloud (TTS)",
                                subtitle = "Short replies Android TTS se aur lambi replies Gemini TTS se (1.5s fallback).",
                                checked = settings.ttsRepliesEnabled,
                                onCheckedChange = onTtsRepliesToggle
                            )

                            if (settings.ttsRepliesEnabled) {
                                Text("TTS Tier Mode", style = MaterialTheme.typography.titleSmall)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    com.example.core.security.TtsTierMode.entries.forEach { mode ->
                                        FilterChip(
                                            selected = settings.ttsTierMode == mode,
                                            onClick = { onTtsTierModeChange(mode) },
                                            label = { Text(mode.label) }
                                        )
                                    }
                                }

                                Text("Gemini Voice", style = MaterialTheme.typography.titleSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AiModelConfig.GEMINI_TTS_VOICES.forEach { voice ->
                                        FilterChip(
                                            selected = settings.ttsVoiceName == voice,
                                            onClick = { onTtsVoiceNameChange(voice) },
                                            label = { Text(voice) }
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Speech Speed", style = MaterialTheme.typography.titleSmall)
                                    Text("${String.format("%.2f", settings.ttsSpeed)}x", style = MaterialTheme.typography.labelMedium)
                                }
                                Slider(
                                    value = settings.ttsSpeed,
                                    onValueChange = onTtsSpeedChange,
                                    valueRange = 0.6f..1.8f
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Speech Volume", style = MaterialTheme.typography.titleSmall)
                                    Text("${(settings.ttsVolume * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                                }
                                Slider(
                                    value = settings.ttsVolume,
                                    onValueChange = onTtsVolumeChange,
                                    valueRange = 0.1f..1.0f
                                )

                                OutlinedButton(
                                    onClick = onPreviewVoice,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("preview_tts_voice_btn")
                                ) {
                                    Text("Preview Voice")
                                }
                            }

                            SettingsSwitchRow(
                                title = "Confirm Sensitive Actions",
                                subtitle = "Payments, SMS/WhatsApp send, delete ya uninstall se pehle poochegi.",
                                checked = settings.confirmSensitiveActions,
                                onCheckedChange = onConfirmSensitiveToggle,
                                testTag = "confirm_sensitive_switch"
                            )

                            SettingsSwitchRow(
                                title = "Floating Overlay Bubble",
                                subtitle = "Screen par movable Baby orb aur quick STOP pill.",
                                checked = settings.floatingBubbleEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled && !permissions.overlayBubble) {
                                        permissionHelper?.openOverlaySettings()
                                    } else {
                                        onFloatingBubbleToggle(enabled)
                                    }
                                },
                                testTag = "floating_bubble_switch"
                            )
                        }
                    }
                }

                // 4. Permissions & Background Survival Checklist
                if (showPermissionsSection) {
                    item(key = "sec_permissions") {
                        val grantedCount = listOf(
                            permissions.recordAudio,
                            permissions.postNotifications,
                            permissions.accessibility,
                            permissions.notificationListener,
                            permissions.overlayBubble,
                            permissions.batteryOptimizationIgnored,
                            permissions.callPhone && permissions.readContacts && permissions.sendSms,
                            permissions.writeSettings,
                            permissions.dndAccess
                        ).count { it }

                        SectionCard(
                            icon = Icons.Default.Security,
                            title = "Permissions & Background Checklist",
                            summary = "$grantedCount/9 active • ${oemGuide.manufacturerTitle}"
                        ) {
                            OutlinedButton(
                                onClick = onOpenPermissions,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("open_full_permissions_btn")
                            ) {
                                Text("Open Full Permissions Checklist")
                            }

                            PermissionChecklistRow(
                                label = "Microphone (Voice & Wake Word)",
                                granted = permissions.recordAudio,
                                onGrant = { permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
                            )
                            PermissionChecklistRow(
                                label = "Notifications (Persistent Listener)",
                                granted = permissions.postNotifications,
                                onGrant = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                                    } else onRefreshPermissions()
                                }
                            )
                            PermissionChecklistRow(
                                label = "Accessibility (Screen Read & Taps)",
                                granted = permissions.accessibility,
                                onGrant = { permissionHelper?.openAccessibilitySettings() }
                            )
                            PermissionChecklistRow(
                                label = "Notification Access (Read & Reply)",
                                granted = permissions.notificationListener,
                                onGrant = { permissionHelper?.openNotificationListenerSettings() }
                            )
                            PermissionChecklistRow(
                                label = "Floating Bubble Overlay",
                                granted = permissions.overlayBubble,
                                onGrant = { permissionHelper?.openOverlaySettings() }
                            )
                            PermissionChecklistRow(
                                label = "Ignore Battery Optimizations",
                                granted = permissions.batteryOptimizationIgnored,
                                onGrant = { permissionHelper?.requestIgnoreBatteryOptimizations() }
                            )
                            PermissionChecklistRow(
                                label = "Call, Contacts & SMS",
                                granted = permissions.callPhone && permissions.readContacts && permissions.sendSms,
                                onGrant = {
                                    permLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.CALL_PHONE,
                                            Manifest.permission.READ_CONTACTS,
                                            Manifest.permission.SEND_SMS
                                        )
                                    )
                                }
                            )
                            PermissionChecklistRow(
                                label = "Modify System Settings (Brightness)",
                                granted = permissions.writeSettings,
                                onGrant = { permissionHelper?.openWriteSettings() }
                            )
                            PermissionChecklistRow(
                                label = "Do Not Disturb Access",
                                granted = permissions.dndAccess,
                                onGrant = { permissionHelper?.openDndAccessSettings() }
                            )

                            Spacer(Modifier.height(4.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(spacing.chipRadius),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = oemGuide.manufacturerTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    oemGuide.steps.forEachIndexed { idx, line ->
                                        Text("${idx + 1}. $line", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    OutlinedButton(
                                        onClick = { permissionHelper?.openOemAutostartOrAppInfo() },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text("Open Autostart Settings")
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Assistant Modules, Personality & Data Saver
                if (showFeaturesSection) {
                    item(key = "sec_features") {
                        SettingsAssistantFeaturesSection(
                            settings = settings,
                            permissions = permissions,
                            onFeatureToggle = onFeatureToggle,
                            onRetentionDaysChange = onRetentionDaysChange,
                            onPersonalityChange = onPersonalityChange,
                            onOpenMemory = onOpenMemory,
                            onRefreshPermissions = onRefreshPermissions
                        )
                    }
                }

                // 6. Auto-Reply
                if (showAutoReplySection) {
                    item(key = "sec_auto_reply") {
                        SettingsAutoReplySection(
                            settings = settings,
                            rules = autoReplyRules,
                            onFeatureToggle = onFeatureToggle,
                            onModeChange = onAutoReplyModeChange,
                            onSaveTemplates = onSaveAutoReplyTemplates,
                            onScheduleChange = onAutoReplyScheduleChange,
                            onUpsertRule = onUpsertAutoReplyRule,
                            onDeleteRule = onDeleteAutoReplyRule
                        )
                    }
                }

                // 7. Scheduled Notification Summaries
                if (showSummarySection) {
                    item(key = "sec_notifications") {
                        SettingsScheduledSummariesSection(
                            settings = settings,
                            onFeatureToggle = onFeatureToggle
                        )
                    }
                }

                // 8. Safety, Voice PIN, Allowed Apps & Emergency SOS
                if (showSafetySection) {
                    item(key = "sec_safety") {
                        SettingsSafetyAndSosSection(
                            settings = settings,
                            allowedApps = allowedApps,
                            emergencyContacts = emergencyContacts,
                            onFeatureToggle = onFeatureToggle,
                            onSetVoicePin = onSetVoicePin,
                            onSaveEmergencyMessage = onSaveEmergencyMessage,
                            onAddAllowedApp = onAddAllowedApp,
                            onToggleAllowedApp = onToggleAllowedApp,
                            onRemoveAllowedApp = onRemoveAllowedApp,
                            onAddEmergencyContact = onAddEmergencyContact,
                            onDeleteEmergencyContact = onDeleteEmergencyContact,
                            onTriggerSos = onTriggerSos
                        )
                    }
                }

                // 9. Danger Zone
                if (showDangerSection) {
                    item(key = "sec_danger") {
                        Card(
                            shape = RoundedCornerShape(spacing.cardRadius),
                            colors = CardDefaults.cardColors(containerColor = ext.dangerZoneBg),
                            border = BorderStroke(1.dp, ext.dangerZoneBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_danger_zone")
                        ) {
                            Column(
                                modifier = Modifier.padding(spacing.md),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            "Danger Zone",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            "Irreversible destructive actions (confirmation required)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { pendingDangerAction = DangerAction.WIPE_MEMORY },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Wipe Memory")
                                    }
                                    OutlinedButton(
                                        onClick = { pendingDangerAction = DangerAction.CLEAR_HISTORY_LOGS },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text("Clear History & Logs")
                                    }
                                    OutlinedButton(
                                        onClick = { pendingDangerAction = DangerAction.CLEAR_KEYS },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text("Clear API Keys")
                                    }
                                    OutlinedButton(
                                        onClick = { pendingDangerAction = DangerAction.RESET_ONBOARDING },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Text("Reset Setup Wizard")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDangerAction?.let { action ->
        val (title, body, confirmLabel) = when (action) {
            DangerAction.WIPE_MEMORY -> Triple(
                "Wipe All Memories?",
                "Baby ki sabhi saved nicknames, facts aur preferences permanently delete ho jayengi.",
                "Wipe Memory"
            )
            DangerAction.CLEAR_HISTORY_LOGS -> Triple(
                "Clear History & Action Logs?",
                "Sabhi task runs aur action logs clear ho jayenge.",
                "Clear All"
            )
            DangerAction.CLEAR_KEYS -> Triple(
                "Clear Encrypted API Keys?",
                "Groq aur Gemini keys Keystore se remove ho jayengi.",
                "Clear Keys"
            )
            DangerAction.RESET_ONBOARDING -> Triple(
                "Restart Setup Wizard?",
                "Setup wizard dubara start hoga (aapka data safe rahega).",
                "Restart"
            )
        }
        AlertDialog(
            onDismissRequest = { pendingDangerAction = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                Button(
                    onClick = {
                        when (action) {
                            DangerAction.WIPE_MEMORY -> onWipeAllMemories()
                            DangerAction.CLEAR_HISTORY_LOGS -> {
                                onClearHistory()
                                onClearActionLogs()
                            }
                            DangerAction.CLEAR_KEYS -> {
                                onClearKeys()
                                geminiInput = ""
                                groqInput = ""
                                savedBanner = false
                            }
                            DangerAction.RESET_ONBOARDING -> onReopenOnboarding()
                        }
                        pendingDangerAction = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDangerAction = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ModelSelectorField(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    onSelectOption: (String) -> Unit,
    onTestRole: (() -> Unit)? = null,
    testButtonTag: String? = null,
    testTag: String,
    dropdownTag: String
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = { expanded = true },
                        modifier = Modifier.testTag(dropdownTag)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Choose $label"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(testTag)
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            expanded = false
                            onSelectOption(option)
                        }
                    )
                }
            }
        }
        if (onTestRole != null) {
            OutlinedButton(
                onClick = onTestRole,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .let { if (testButtonTag != null) it.testTag(testButtonTag) else it }
            ) {
                Text("Test")
            }
        }
    }
}

@Composable
private fun KeyHealthRow(
    label: String,
    status: KeyTestResult,
    onTest: () -> Unit,
    linkText: String,
    onOpenLink: () -> Unit
) {
    val ext = BabyThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onTest,
                enabled = status !is KeyTestResult.Testing,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                if (status is KeyTestResult.Testing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(label)
            }
            when (status) {
                is KeyTestResult.Valid -> Text(status.message, style = MaterialTheme.typography.labelSmall, color = ext.success)
                is KeyTestResult.Invalid -> Text(status.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                is KeyTestResult.Testing -> Text("Testing...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                KeyTestResult.Idle -> {}
            }
        }
        TextButton(
            onClick = onOpenLink,
            modifier = Modifier.minimumInteractiveComponentSize()
        ) {
            Text(linkText, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    content: @Composable () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    Card(
        shape = RoundedCornerShape(spacing.cardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (!summary.isNullOrBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
    }
}

@Composable
private fun PermissionChecklistRow(
    label: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    val ext = BabyThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = if (granted) "Granted" else "Missing",
            tint = if (granted) ext.success else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (!granted) {
            TextButton(
                onClick = onGrant,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text("Grant")
            }
        } else {
            Text(
                text = "Active",
                style = MaterialTheme.typography.labelSmall,
                color = ext.success
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenDarkPreview() {
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        SettingsScreenPreviewBody()
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenLightPreview() {
    BabyTheme(themeMode = BabyThemeMode.LIGHT) {
        SettingsScreenPreviewBody()
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenLargeFontPreview() {
    val curDensity = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(curDensity.density, fontScale = 2f)) {
        BabyTheme(themeMode = BabyThemeMode.DARK) {
            SettingsScreenPreviewBody()
        }
    }
}

@Composable
private fun SettingsScreenPreviewBody() {
    SettingsScreen(
        settings = BabySettings(),
        permissions = PermissionSnapshot(
            recordAudio = true,
            postNotifications = true,
            accessibility = false,
            notificationListener = false,
            overlayBubble = false,
            batteryOptimizationIgnored = false,
            callPhone = false,
            sendSms = false,
            readContacts = false,
            writeSettings = false,
            dndAccess = false
        ),
        oemGuide = OemAutostartGuide("Xiaomi / MIUI", listOf("Enable Autostart", "Disable battery saver"), emptyList()),
        permissionHelper = null,
        onBack = {},
        onRefreshPermissions = {},
        onSaveKeys = { _, _ -> },
        onClearKeys = {},
        onGeminiModelChange = {},
        onGroqModelChange = {},
        onPreferredBrainChange = {},
        onAutoFallbackChange = {},
        onWakeWordToggle = {},
        onSensitivityChange = {},
        onLanguageChange = {},
        onConfirmSensitiveToggle = {},
        onTtsRepliesToggle = {},
        onFloatingBubbleToggle = {},
        onReopenOnboarding = {}
    )
}
