package com.example.ui.screens

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.security.AutoReplyMode
import com.example.core.security.BabySettings
import com.example.data.local.ChatMessageEntity
import com.example.domain.ai.AgentExecutionState
import com.example.domain.ai.BrainType
import com.example.domain.ai.ConfirmationRequest
import com.example.ui.components.BabyMarkdownText
import com.example.ui.components.BabyOrb
import com.example.ui.components.BabyOrbState
import com.example.ui.components.BabyWaveformStrip
import com.example.ui.components.QuickStatusSheet
import com.example.ui.components.headerStatusLabel
import com.example.ui.components.presentToolAction
import com.example.ui.state.BabyUiPreferences
import com.example.ui.state.QuickCommandItem
import com.example.ui.theme.BabyHaptics
import com.example.ui.theme.BabyMotionTokens
import com.example.ui.theme.BabyTheme
import com.example.ui.theme.BabyThemeMode
import com.example.ui.theme.BabyThemeTokens
import com.example.ui.theme.rememberReducedMotionEnabled
import com.example.ui.theme.withDevanagariFallback
import com.example.ui.viewmodel.BabyHeaderStatus
import com.example.util.PermissionSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CHAT_TIME_FORMATTER = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
}

private fun formatChatTimestamp(timestampMs: Long): String =
    CHAT_TIME_FORMATTER.get()?.format(Date(timestampMs)).orEmpty()

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessageEntity>,
    agentState: AgentExecutionState,
    settings: BabySettings,
    uiPrefs: BabyUiPreferences = BabyUiPreferences(),
    permissions: PermissionSnapshot,
    headerStatus: BabyHeaderStatus = BabyHeaderStatus.LISTENING,
    isRecording: Boolean,
    micAmplitude: Float,
    isWakeListening: Boolean,
    isTtsSpeaking: Boolean = false,
    isOffline: Boolean = false,
    isRateLimited: Boolean = false,
    onSendCommand: (String) -> Unit,
    onToggleVoice: () -> Unit,
    onStartHoldToTalk: () -> Unit = {},
    onStopHoldToTalk: () -> Unit = {},
    onOpenVoiceOverlay: () -> Unit = {},
    onStopTask: () -> Unit,
    onApproveConfirmation: (Boolean, String?) -> Unit,
    onClearChat: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenAiSettings: () -> Unit = onNavigateToSettings,
    onOpenPermissions: () -> Unit = onNavigateToSettings,
    onRegenerateMessage: (Long) -> Unit = {},
    onSpeakMessage: (String) -> Unit = {},
    onDismissApiKeyBanner: () -> Unit = {},
    onDismissRateLimitBanner: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onToggleQuickCommandPin: (String) -> Unit = {},
    onEditQuickCommandPhrase: (String, String) -> Unit = { _, _ -> },
    onWakeWordToggle: (Boolean) -> Unit = {},
    onAutoReplyToggle: (Boolean) -> Unit = {},
    onAutoReplyModeChange: (AutoReplyMode) -> Unit = {},
    onDataSaverToggle: (Boolean) -> Unit = {},
    onTtsToggle: (Boolean) -> Unit = {},
    onSoundCuesToggle: (Boolean) -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }
    var showQuickStatusSheet by remember { mutableStateOf(false) }
    var editingQuickCommand by remember { mutableStateOf<QuickCommandItem?>(null) }
    var hasPerformedInitialScroll by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors
    val reducedMotion = rememberReducedMotionEnabled()

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onToggleVoice()
    }

    val camPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onSendCommand("Camera se dekho ye kya hai?")
    }

    val showJumpToLatest by remember(messages) {
        derivedStateOf {
            if (messages.isEmpty()) false
            else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible < messages.lastIndex - 1
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (!hasPerformedInitialScroll) {
                hasPerformedInitialScroll = true
                listState.scrollToItem(messages.lastIndex)
            } else {
                listState.animateScrollToItem(messages.lastIndex)
            }
        } else {
            hasPerformedInitialScroll = false
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
            // 1. Interactive Status Header
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showQuickStatusSheet = true }
                        .padding(horizontal = spacing.md, vertical = spacing.sm)
                        .testTag("chat_status_header"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        StatusDotIndicator(status = headerStatus, reducedMotion = reducedMotion)
                        Spacer(Modifier.width(spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(spacing.chipRadius)
                                ) {
                                    Text(
                                        text = headerStatusLabel(headerStatus),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when (headerStatus) {
                                            BabyHeaderStatus.LISTENING, BabyHeaderStatus.SPEAKING -> ext.success
                                            BabyHeaderStatus.THINKING -> MaterialTheme.colorScheme.primary
                                            BabyHeaderStatus.OFFLINE, BabyHeaderStatus.LOCKED_QUEUE_PENDING,
                                            BabyHeaderStatus.NO_API_KEY -> ext.warning
                                            BabyHeaderStatus.RATE_LIMITED -> MaterialTheme.colorScheme.error
                                            BabyHeaderStatus.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                if (isRecording || isWakeListening || isTtsSpeaking) {
                                    BabyWaveformStrip(
                                        isActive = isRecording || isTtsSpeaking,
                                        amplitude = micAmplitude
                                    )
                                }
                            }
                            Text(
                                text = when {
                                    isRecording -> "Sun rahi hoon… release ya tap se bhejo"
                                    agentState.isRunning -> "Step ${agentState.currentStep}/${agentState.maxSteps} • ${agentState.statusText}"
                                    agentState.queuedCount > 0 -> "${agentState.queuedCount} task unlock par chalenge • Tap for quick controls"
                                    else -> "Tap status bar for quick toggles"
                                }.withDevanagariFallback(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (agentState.isRunning || agentState.pendingConfirmation != null) {
                            Button(
                                onClick = onStopTask,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("emergency_stop_button")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Emergency Stop", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("STOP", style = MaterialTheme.typography.labelLarge)
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    if (permissions.recordAudio) onOpenVoiceOverlay()
                                    else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("open_voice_overlay_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = "Open full-screen voice mode",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                            if (messages.isNotEmpty()) {
                                IconButton(
                                    onClick = onClearChat,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("clear_chat_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Clear chat"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. High-Contrast Dismissible API Key Banner
            AnimatedVisibility(
                visible = !settings.hasAnyApiKey && !uiPrefs.apiKeyBannerDismissed,
                enter = expandVertically() + fadeIn(BabyMotionTokens.fastTween(reducedMotion)),
                exit = shrinkVertically() + fadeOut(BabyMotionTokens.fastTween(reducedMotion))
            ) {
                Surface(
                    color = ext.warningContainer,
                    contentColor = ext.onWarningContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.xs)
                        .clip(RoundedCornerShape(spacing.chipRadius))
                        .border(1.dp, ext.warning.copy(alpha = 0.65f), RoundedCornerShape(spacing.chipRadius))
                        .clickable { onNavigateToSettings() }
                        .testTag("missing_keys_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = ext.onWarningContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(spacing.xs))
                        Text(
                            text = stringResource(R.string.banner_no_key_title).withDevanagariFallback(),
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.onWarningContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.testTag("banner_add_key_cta")
                        ) {
                            Text(
                                text = stringResource(R.string.banner_no_key_cta),
                                color = ext.onWarningContainer,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        IconButton(
                            onClick = onDismissApiKeyBanner,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("dismiss_keys_banner")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss API key banner",
                                tint = ext.onWarningContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Friendly Hinglish Offline & Rate-Limit Banners
            AnimatedVisibility(visible = isOffline) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.xxs)
                        .clip(RoundedCornerShape(spacing.chipRadius))
                        .testTag("offline_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.WifiOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(spacing.xs))
                        Text(
                            text = stringResource(R.string.banner_offline_msg).withDevanagariFallback(),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            AnimatedVisibility(visible = isRateLimited && !isOffline) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.xxs)
                        .clip(RoundedCornerShape(spacing.chipRadius))
                        .testTag("rate_limit_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.banner_rate_limit_msg).withDevanagariFallback(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onNavigateToSettings) {
                            Text("Switch", color = MaterialTheme.colorScheme.error)
                        }
                        IconButton(
                            onClick = onDismissRateLimitBanner,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss rate limit banner", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            AnimatedVisibility(visible = !agentState.isRunning && !agentState.lastError.isNullOrBlank()) {
                val lastUserCommand = remember(messages) {
                    messages.lastOrNull { it.role == "user" }?.content
                }
                var bannerDetailsExpanded by remember(agentState.lastError, agentState.lastErrorDetails) {
                    mutableStateOf(false)
                }
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.xxs)
                        .clip(RoundedCornerShape(spacing.chipRadius))
                        .testTag("agent_error_banner")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(spacing.xs))
                            Text(
                                text = agentState.lastError.orEmpty().withDevanagariFallback(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onDismissError,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("agent_error_dismiss_button")
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Dismiss error",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (!lastUserCommand.isNullOrBlank()) {
                                TextButton(
                                    onClick = {
                                        onDismissError()
                                        onSendCommand(lastUserCommand)
                                    },
                                    modifier = Modifier.testTag("agent_error_retry_button")
                                ) {
                                    Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                            TextButton(
                                onClick = onOpenAiSettings,
                                modifier = Modifier.testTag("agent_error_open_ai_settings_button")
                            ) {
                                Text("Open AI settings", color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            if (!agentState.lastErrorDetails.isNullOrBlank()) {
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = { bannerDetailsExpanded = !bannerDetailsExpanded },
                                    modifier = Modifier.testTag("agent_error_details_toggle")
                                ) {
                                    Text(
                                        text = if (bannerDetailsExpanded) "Hide details" else "Details",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = bannerDetailsExpanded && !agentState.lastErrorDetails.isNullOrBlank()) {
                            Text(
                                text = agentState.lastErrorDetails.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                                modifier = Modifier
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                    .testTag("agent_error_details_text")
                            )
                        }
                    }
                }
            }

            // 3. Main Chat Feed + Collapsing Greeting Card
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = messages.isEmpty(),
                        enter = fadeIn(BabyMotionTokens.standardTween(reducedMotion)) + expandVertically(),
                        exit = fadeOut(BabyMotionTokens.fastTween(reducedMotion)) + shrinkVertically()
                    ) {
                        CompactGreetingAndQuickCommands(
                            quickCommands = uiPrefs.orderedQuickCommands,
                            permissions = permissions,
                            onSendCommand = { cmd ->
                                BabyHaptics.tap(haptic)
                                onSendCommand(cmd)
                            },
                            onLongPressCommand = { item -> editingQuickCommand = item },
                            onOpenPermissions = onOpenPermissions
                        )
                    }

                    if (messages.isNotEmpty()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            itemsIndexed(
                                items = messages,
                                key = { _, msg -> msg.id },
                                contentType = { _, msg -> msg.role }
                            ) { index, msg ->
                                val isLatestAssistant = index == messages.lastIndex &&
                                    msg.role != "user" && msg.role != "tool"
                                ChatBubbleItem(
                                    msg = msg,
                                    isLatestAssistant = isLatestAssistant,
                                    reducedMotion = reducedMotion,
                                    onRegenerate = { onRegenerateMessage(msg.id) },
                                    onSpeak = { onSpeakMessage(msg.content) },
                                    modifier = Modifier.animateItem()
                                )
                            }

                            if (agentState.isRunning) {
                                item(key = "typing_indicator", contentType = "typing") {
                                    TypingIndicatorBubble(reducedMotion = reducedMotion)
                                }
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }

                // Jump to Latest FAB
                androidx.compose.animation.AnimatedVisibility(
                    visible = showJumpToLatest && messages.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = spacing.md, bottom = spacing.sm)
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                if (messages.isNotEmpty()) {
                                    listState.animateScrollToItem(messages.lastIndex)
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.testTag("jump_to_latest_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = stringResource(R.string.chat_jump_latest)
                        )
                    }
                }
            }

            // Expandable Multi-Step Progress Panel
            AnimatedVisibility(visible = agentState.isRunning) {
                MultiStepProgressPanel(
                    agentState = agentState,
                    onStop = onStopTask
                )
            }

            // Sensitive Action Confirmation Card
            AnimatedVisibility(visible = agentState.pendingConfirmation != null) {
                agentState.pendingConfirmation?.let { req ->
                    ConfirmationGateCard(
                        request = req,
                        onApprove = { pin -> onApproveConfirmation(true, pin) },
                        onDeny = { onApproveConfirmation(false, null) }
                    )
                }
            }

            // 4. Bottom Voice + Text Composer
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.sm, vertical = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mic Orb: supports BOTH Tap-to-toggle AND Press-and-Hold-to-talk
                    Box(
                        modifier = Modifier
                            .pointerInput(permissions.recordAudio) {
                                detectTapGestures(
                                    onTap = {
                                        BabyHaptics.tap(haptic)
                                        if (permissions.recordAudio) {
                                            onToggleVoice()
                                        } else {
                                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                    onLongPress = {
                                        BabyHaptics.voiceStart(haptic)
                                        if (permissions.recordAudio) {
                                            onStartHoldToTalk()
                                        } else {
                                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                    onPress = {
                                        val released = tryAwaitRelease()
                                        if (released && isRecording) {
                                            BabyHaptics.voiceStop(haptic)
                                            onStopHoldToTalk()
                                        }
                                    }
                                )
                            }
                            .testTag("voice_capture_button")
                    ) {
                        BabyOrb(
                            state = when {
                                isRecording -> BabyOrbState.LISTENING
                                agentState.isRunning -> BabyOrbState.THINKING
                                else -> BabyOrbState.IDLE
                            },
                            amplitude = micAmplitude,
                            size = 52.dp,
                            contentDesc = if (isRecording) "Stop voice recording" else "Tap to toggle mic or hold to talk"
                        )
                    }

                    Spacer(Modifier.width(spacing.xs))

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                        singleLine = false,
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (inputText.isNotBlank()) {
                                    onSendCommand(inputText)
                                    inputText = ""
                                }
                            }
                        ),
                        shape = RoundedCornerShape(spacing.cardRadius),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_input_field")
                    )

                    Spacer(Modifier.width(spacing.xxs))

                    if (settings.cameraVisionEnabled) {
                        IconButton(
                            onClick = {
                                if (permissions.camera) {
                                    onSendCommand("Camera se dekho ye kya hai?")
                                } else {
                                    camPermLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("camera_vision_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = "Ask Baby with Camera",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                BabyHaptics.tap(haptic)
                                onSendCommand(inputText)
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .testTag("send_message_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send command",
                            tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showQuickStatusSheet) {
        QuickStatusSheet(
            headerStatus = headerStatus,
            settings = settings,
            uiPrefs = uiPrefs,
            onDismiss = { showQuickStatusSheet = false },
            onWakeWordToggle = onWakeWordToggle,
            onAutoReplyToggle = onAutoReplyToggle,
            onAutoReplyModeChange = onAutoReplyModeChange,
            onDataSaverToggle = onDataSaverToggle,
            onTtsToggle = onTtsToggle,
            onSoundCuesToggle = onSoundCuesToggle,
            onOpenPermissions = onOpenPermissions
        )
    }

    editingQuickCommand?.let { cmd ->
        QuickCommandEditDialog(
            item = cmd,
            onDismiss = { editingQuickCommand = null },
            onTogglePin = {
                onToggleQuickCommandPin(cmd.id)
                editingQuickCommand = null
            },
            onSavePhrase = { newPhrase ->
                onEditQuickCommandPhrase(cmd.id, newPhrase)
                editingQuickCommand = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun CompactGreetingAndQuickCommands(
    quickCommands: List<QuickCommandItem>,
    permissions: PermissionSnapshot,
    onSendCommand: (String) -> Unit,
    onLongPressCommand: (QuickCommandItem) -> Unit,
    onOpenPermissions: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compactHeight = maxHeight < 520.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(if (compactHeight) spacing.sm else spacing.md)
        ) {
            // Compact Greeting Card (responsive height so it never crowds small/landscape screens)
            Card(
                shape = RoundedCornerShape(spacing.cardRadius),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (compactHeight) 112.dp else 152.dp)
                    .testTag("compact_greeting_card")
            ) {
                Row(
                    modifier = Modifier.padding(if (compactHeight) spacing.sm else spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md)
                ) {
                    BabyOrb(
                        state = BabyOrbState.IDLE,
                        size = if (compactHeight) 44.dp else 56.dp,
                        showIcon = true,
                        contentDesc = "Baby companion orb"
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.chat_greeting_title).withDevanagariFallback(),
                            style = if (compactHeight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.chat_greeting_subtitle).withDevanagariFallback(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (compactHeight) 2 else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Quick Commands",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Hold to edit or pin",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    quickCommands.forEach { cmd ->
                        Surface(
                            color = if (cmd.isPinned) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(spacing.chipRadius),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(spacing.chipRadius))
                                .border(
                                    width = 1.dp,
                                    color = if (cmd.isPinned) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(spacing.chipRadius)
                                )
                                .combinedClickable(
                                    onClick = { onSendCommand(cmd.phrase) },
                                    onLongClick = { onLongPressCommand(cmd) }
                                )
                                .testTag("quick_command_${cmd.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (cmd.isPinned) Icons.Default.PushPin else Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = cmd.phrase.withDevanagariFallback(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

        if (!permissions.accessibility) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(spacing.chipRadius),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(spacing.chipRadius))
            ) {
                Row(
                    modifier = Modifier.padding(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Tip: Screen taps aur macros ke liye Accessibility ON karein.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onOpenPermissions) {
                        Text("Checklist")
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubbleItem(
    msg: ChatMessageEntity,
    isLatestAssistant: Boolean,
    reducedMotion: Boolean,
    onRegenerate: () -> Unit,
    onSpeak: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = BabyThemeTokens.spacing
    val ext = BabyThemeTokens.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val formattedTime = remember(msg.timestamp) { formatChatTimestamp(msg.timestamp) }
    var showTimestamp by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    when (msg.role) {
        "user" -> {
            Column(
                modifier = modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(ext.userBubbleGradientStart, ext.userBubbleGradientEnd)
                                )
                            )
                            .combinedClickable(
                                onClick = { showTimestamp = !showTimestamp },
                                onLongClick = {
                                    BabyHaptics.tap(haptic)
                                    showMenu = true
                                }
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = msg.content.withDevanagariFallback(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = ext.onUserBubble
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_action_copy)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                clipboard.setText(AnnotatedString(msg.content))
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_action_share)) },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, msg.content)
                                            },
                                            "Share message"
                                        )
                                    )
                                }
                            }
                        )
                    }
                }
                AnimatedVisibility(visible = showTimestamp) {
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp, end = 4.dp)
                    )
                }
            }
        }

        "tool" -> {
            var expanded by remember { mutableStateOf(false) }
            val toolUi = remember(msg.toolName, msg.toolArgsJson, msg.toolResult) {
                presentToolAction(msg.toolName, msg.toolArgsJson, msg.toolResult)
            }
            val isOk = !msg.toolResult.orEmpty().contains("err", ignoreCase = true) &&
                !msg.toolResult.orEmpty().contains("fail", ignoreCase = true)

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                shape = RoundedCornerShape(spacing.chipRadius),
                modifier = modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(spacing.chipRadius))
                    .clickable { expanded = !expanded }
            ) {
                Column(modifier = Modifier.padding(horizontal = spacing.sm, vertical = 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(26.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = toolUi.icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = toolUi.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = if (isOk) "Completed" else "Status",
                            tint = if (isOk) ext.success else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Expand tool details",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    AnimatedVisibility(visible = expanded) {
                        Column(modifier = Modifier.padding(top = 6.dp)) {
                            Text(
                                text = msg.content.take(400),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        else -> {
            // Smooth streaming reveal for newest incoming Baby message
            var revealedChars by remember(msg.id, isLatestAssistant) {
                mutableIntStateOf(
                    if (!isLatestAssistant || reducedMotion || msg.content.length < 18) msg.content.length
                    else 0
                )
            }
            LaunchedEffect(msg.id, msg.content, isLatestAssistant) {
                if (isLatestAssistant && !reducedMotion && revealedChars < msg.content.length) {
                    val step = (msg.content.length / 18).coerceIn(3, 14)
                    while (revealedChars < msg.content.length) {
                        revealedChars = (revealedChars + step).coerceAtMost(msg.content.length)
                        delay(18L)
                    }
                } else {
                    revealedChars = msg.content.length
                }
            }
            val displayText = if (revealedChars >= msg.content.length) {
                msg.content
            } else {
                msg.content.substring(0, revealedChars)
            }

            val isErrorMsg = msg.toolName == "ai_error"
            var detailsExpanded by remember(msg.id) { mutableStateOf(false) }

            Column(
                modifier = modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Box {
                    Surface(
                        color = if (isErrorMsg) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isErrorMsg) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
                        modifier = Modifier
                            .widthIn(max = 330.dp)
                            .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
                            .combinedClickable(
                                onClick = { showTimestamp = !showTimestamp },
                                onLongClick = {
                                    BabyHaptics.tap(haptic)
                                    showMenu = true
                                }
                            )
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            BabyMarkdownText(
                                markdown = displayText,
                                color = if (isErrorMsg) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (isErrorMsg && !msg.toolResult.isNullOrBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { detailsExpanded = !detailsExpanded }
                                        .padding(vertical = 4.dp, horizontal = 2.dp)
                                        .testTag("chat_error_details_toggle")
                                ) {
                                    Text(
                                        text = "Details",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = "Toggle error details",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                AnimatedVisibility(visible = detailsExpanded) {
                                    Text(
                                        text = msg.toolResult.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .testTag("chat_error_details_body")
                                    )
                                }
                            }
                            msg.brainUsed?.takeIf { it != "local" }?.let { brain ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Baby • $brain",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_action_copy)) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            onClick = {
                                clipboard.setText(AnnotatedString(msg.content))
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_action_share)) },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, msg.content)
                                            },
                                            "Share reply"
                                        )
                                    )
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_action_regenerate)) },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onRegenerate()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_action_speak)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onSpeak()
                            }
                        )
                    }
                }
                AnimatedVisibility(visible = showTimestamp) {
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp, start = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MultiStepProgressPanel(
    agentState: AgentExecutionState,
    onStop: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    var expanded by remember { mutableStateOf(false) }
    val progress = (agentState.currentStep.toFloat() / agentState.maxSteps.coerceAtLeast(1)).coerceIn(0.05f, 1f)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.sm)
            .clickable { expanded = !expanded }
            .testTag("multi_step_progress_panel")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Step ${agentState.currentStep}/${agentState.maxSteps} • ${agentState.statusText}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onStop,
                    modifier = Modifier.testTag("progress_stop_button")
                ) {
                    Text("Stop", color = MaterialTheme.colorScheme.error)
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = "Active brain: ${agentState.activeBrain?.label ?: "Routing..."} • Tap header to collapse",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TypingIndicatorBubble(reducedMotion: Boolean) {
    val infinite = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { idx ->
            val alpha by infinite.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = if (reducedMotion) 1200 else 500,
                        delayMillis = idx * 140
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$idx"
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun ConfirmationGateCard(
    request: ConfirmationRequest,
    onApprove: (String?) -> Unit,
    onDeny: () -> Unit
) {
    val spacing = BabyThemeTokens.spacing
    var pinInput by remember(request) { mutableStateOf("") }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
        ),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.sm)
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Confirm Sensitive Action (${request.toolName})",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Text(
                text = request.summary.withDevanagariFallback(),
                style = MaterialTheme.typography.bodyMedium
            )
            if (request.argsPreview.isNotBlank()) {
                Text(
                    text = request.argsPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (request.requiresVoicePin) {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("Enter Safety Voice PIN") },
                    supportingText = if (pinInput.isBlank()) {
                        { Text("Voice PIN required to authorize this action") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("confirm_voice_pin_input")
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("cancel_action_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Deny")
                }
                Button(
                    onClick = { onApprove(if (request.requiresVoicePin) pinInput else null) },
                    enabled = !request.requiresVoicePin || pinInput.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("confirm_action_button")
                ) {
                    Text("Allow")
                }
            }
        }
    }
}

@Composable
private fun QuickCommandEditDialog(
    item: QuickCommandItem,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onSavePhrase: (String) -> Unit
) {
    var phrase by remember(item) { mutableStateOf(item.phrase) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick Command") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text("Command phrase") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = onTogglePin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (item.isPinned) "Unpin from Top" else "Pin to Top")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (phrase.isNotBlank()) onSavePhrase(phrase) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun StatusDotIndicator(status: BabyHeaderStatus, reducedMotion: Boolean) {
    val ext = BabyThemeTokens.colors
    val infinite = rememberInfiniteTransition(label = "statusDot")
    val pulse by infinite.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "statusScale"
    )
    val shouldPulse = !reducedMotion && (
        status == BabyHeaderStatus.LISTENING ||
            status == BabyHeaderStatus.THINKING ||
            status == BabyHeaderStatus.SPEAKING
        )
    val dotColor = when (status) {
        BabyHeaderStatus.LISTENING, BabyHeaderStatus.SPEAKING -> ext.success
        BabyHeaderStatus.THINKING -> MaterialTheme.colorScheme.primary
        BabyHeaderStatus.OFFLINE, BabyHeaderStatus.LOCKED_QUEUE_PENDING, BabyHeaderStatus.NO_API_KEY -> ext.warning
        BabyHeaderStatus.RATE_LIMITED -> MaterialTheme.colorScheme.error
        BabyHeaderStatus.PAUSED -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .scale(if (shouldPulse) pulse else 1f)
            .clip(CircleShape)
            .background(dotColor)
            .semantics { contentDescription = "Baby status ${headerStatusLabel(status)}" }
    )
}

@Preview(showBackground = true, name = "Chat Empty Dark")
@Composable
private fun ChatScreenEmptyPreview() {
    BabyTheme(themeMode = BabyThemeMode.DARK) {
        ChatScreen(
            messages = emptyList(),
            agentState = AgentExecutionState(),
            settings = BabySettings(),
            permissions = PermissionSnapshot(
                recordAudio = true, postNotifications = true, accessibility = false,
                notificationListener = true, overlayBubble = true, batteryOptimizationIgnored = true,
                callPhone = true, sendSms = true, readContacts = true, writeSettings = true, dndAccess = true
            ),
            headerStatus = BabyHeaderStatus.NO_API_KEY,
            isRecording = false,
            micAmplitude = 0f,
            isWakeListening = false,
            onSendCommand = {},
            onToggleVoice = {},
            onStopTask = {},
            onApproveConfirmation = { _, _ -> },
            onClearChat = {},
            onNavigateToSettings = {}
        )
    }
}

@Preview(showBackground = true, name = "Chat Messages Light Large Font", fontScale = 2.0f)
@Composable
private fun ChatScreenLightLargeFontPreview() {
    BabyTheme(themeMode = BabyThemeMode.LIGHT) {
        ChatScreen(
            messages = listOf(
                ChatMessageEntity(id = 1, role = "user", content = "Flashlight on karo"),
                ChatMessageEntity(id = 2, role = "tool", toolName = "toggle_flashlight", toolResult = "ON", content = "{}"),
                ChatMessageEntity(id = 3, role = "assistant", content = "**Done!** Flashlight ON kar di hai.", brainUsed = "groq")
            ),
            agentState = AgentExecutionState(isRunning = false, activeBrain = BrainType.GROQ),
            settings = BabySettings(groqApiKey = "gsk_demo"),
            permissions = PermissionSnapshot(
                recordAudio = true, postNotifications = true, accessibility = true,
                notificationListener = true, overlayBubble = true, batteryOptimizationIgnored = true,
                callPhone = true, sendSms = true, readContacts = true, writeSettings = true, dndAccess = true
            ),
            headerStatus = BabyHeaderStatus.LISTENING,
            isRecording = false,
            micAmplitude = 0f,
            isWakeListening = true,
            onSendCommand = {},
            onToggleVoice = {},
            onStopTask = {},
            onApproveConfirmation = { _, _ -> },
            onClearChat = {},
            onNavigateToSettings = {}
        )
    }
}
