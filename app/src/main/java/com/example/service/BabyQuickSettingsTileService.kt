package com.example.service

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.MainActivity
import com.example.core.security.AutoReplyMode
import com.example.core.security.SecureKeyStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class BabyQuickSettingsTileService : TileService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TileEntryPoint {
        fun keyStore(): SecureKeyStore
    }

    override fun onStartListening() {
        super.onStartListening()
        val settings = runCatching {
            EntryPointAccessors.fromApplication(applicationContext, TileEntryPoint::class.java)
                .keyStore().settings.value
        }.getOrNull()

        qsTile?.apply {
            label = "Baby"
            state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (settings != null && settings.autoReplyEnabled && settings.autoReplyMode != AutoReplyMode.OFF) {
                    "Auto-reply ON (${settings.autoReplyMode.label})"
                } else {
                    "Tap to talk"
                }
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        BabyForegroundService.triggerVoice(applicationContext)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_START_VOICE, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = android.app.PendingIntent.getActivity(
                this,
                10,
                openIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            launchLegacyAndCollapse(openIntent)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchLegacyAndCollapse(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    companion object {
        const val EXTRA_START_VOICE = "extra_start_voice"
    }
}
