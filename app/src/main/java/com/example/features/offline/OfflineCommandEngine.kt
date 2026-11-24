package com.example.features.offline

import android.content.Context
import android.media.AudioManager
import com.example.core.security.SecureKeyStore
import com.example.domain.tools.PhoneControlExecutor
import com.example.domain.tools.ToolExecutionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class OfflineMatchResult(
    val toolName: String,
    val args: Map<String, Any?>,
    val executionResult: ToolExecutionResult,
    val friendlyReply: String
)

@Singleton
class OfflineCommandEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
    private val executor: PhoneControlExecutor
) {
    suspend fun tryExecuteOffline(command: String, fallbackMode: Boolean = false): OfflineMatchResult? {
        if (!keyStore.settings.value.offlineCommandsEnabled) return null
        val intent = FastPathIntentMatcher.match(
            rawCommand = command,
            currentMediaVolumePercent = { currentVolumePercent() },
            fallbackMode = fallbackMode
        ) ?: return null

        val res = executor.execute(intent.toolName, intent.args)
        if (intent.toolName == "open_app" && !res.ok && !fallbackMode) {
            // Return structured outcome for app not found / ambiguous / needs permission instead of falling back blindly only when explicit
            return OfflineMatchResult(intent.toolName, intent.args, res, res.output)
        }
        val reply = when {
            res.ok && (intent.toolName == "open_app" || intent.toolName == "set_alarm" || intent.toolName == "set_timer") ->
                res.output.ifBlank { intent.defaultReply }
            res.ok -> intent.defaultReply
            else -> res.output
        }
        return OfflineMatchResult(
            toolName = intent.toolName,
            args = intent.args,
            executionResult = res,
            friendlyReply = reply
        )
    }

    private fun currentVolumePercent(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 50
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return ((cur.toFloat() / max) * 100f).toInt()
    }
}
