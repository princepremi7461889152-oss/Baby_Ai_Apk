package com.example.features.vision

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.features.actionlog.ActionLogInterceptor
import com.example.ui.theme.BabyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lightweight on-demand Clipboard AI sheet launched when user taps "Clip" on the Floating Bubble.
 * Reading clipboard inside an Activity with window focus ensures Android 10+ compatibility
 * while strictly honoring "read the clipboard only when the user asks, never in the background".
 */
@AndroidEntryPoint
class ClipboardActionActivity : ComponentActivity() {

    @Inject lateinit var clipboardAiManager: ClipboardAiManager
    @Inject lateinit var actionLogInterceptor: ActionLogInterceptor

    private var hasWindowFocusReady by mutableStateOf(false)
    private var clipboardPreview by mutableStateOf<String?>(null)

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preselectedMode = intent.getStringExtra(EXTRA_MODE)

        setContent {
            BabyTheme(darkTheme = true) {
                val scope = rememberCoroutineScope()
                var isProcessing by remember { mutableStateOf(false) }
                var resultText by remember { mutableStateOf<String?>(null) }

                fun runMode(mode: String) {
                    if (isProcessing) return
                    scope.launch {
                        isProcessing = true
                        val clip = clipboardPreview ?: clipboardAiManager.readClipboardText()
                        clipboardPreview = clip
                        val res = actionLogInterceptor.executeLogged(
                            commandText = "Clipboard bubble: $mode",
                            toolName = "read_clipboard_and_process",
                            args = mapOf("mode" to mode),
                            source = "text"
                        ) {
                            clipboardAiManager.processClipboard(
                                mode = mode,
                                explicitText = clip,
                                speakResult = true
                            )
                        }
                        resultText = res.output
                        isProcessing = false
                    }
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("Baby Clipboard AI", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    text = clipboardPreview?.let { "Copied: \"${it.take(140)}${if (it.length > 140) "..." else ""}\"" }
                                        ?: "Tap an action to read your copied text on demand:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { runMode("summarize") },
                                        enabled = !isProcessing,
                                        modifier = Modifier.testTag("clip_summarize_btn")
                                    ) { Text("Summarize") }
                                    Button(
                                        onClick = { runMode("translate") },
                                        enabled = !isProcessing,
                                        modifier = Modifier.testTag("clip_translate_btn")
                                    ) { Text("Translate") }
                                    Button(
                                        onClick = { runMode("draft_reply") },
                                        enabled = !isProcessing,
                                        modifier = Modifier.testTag("clip_reply_btn")
                                    ) { Text("Draft Reply") }
                                }

                                if (isProcessing) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.height(22.dp))
                                        Text("Baby padh rahi hai...", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }

                                resultText?.let { out ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = out,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    if (resultText != null) {
                                        OutlinedButton(
                                            onClick = {
                                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                cm.setPrimaryClip(ClipData.newPlainText("Baby Clipboard AI", resultText))
                                            }
                                        ) { Text("Copy Result") }
                                    }
                                    TextButton(onClick = { finish() }) {
                                        Text("Close")
                                    }
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(hasWindowFocusReady, preselectedMode) {
                    if (preselectedMode != null && resultText == null && !isProcessing && hasWindowFocusReady) {
                        runMode(preselectedMode)
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasWindowFocusReady) {
            hasWindowFocusReady = true
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clipboardPreview = clip.getItemAt(0).coerceToText(this)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
    }

    companion object {
        const val EXTRA_MODE = "clipboard_mode"

        fun launch(context: Context, mode: String? = null) {
            val intent = Intent(context, ClipboardActionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (mode != null) putExtra(EXTRA_MODE, mode)
            }
            context.startActivity(intent)
        }
    }
}
