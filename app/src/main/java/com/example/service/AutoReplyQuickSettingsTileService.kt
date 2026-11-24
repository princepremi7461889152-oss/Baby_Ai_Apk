package com.example.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.core.security.AutoReplyMode
import com.example.core.security.SecureKeyStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Dedicated Quick Settings tile to toggle Baby Auto-Reply mode ON/OFF
 * directly from the Android notification shade.
 */
class AutoReplyQuickSettingsTileService : TileService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AutoReplyTileEntryPoint {
        fun keyStore(): SecureKeyStore
    }

    private fun keyStore(): SecureKeyStore =
        EntryPointAccessors.fromApplication(applicationContext, AutoReplyTileEntryPoint::class.java)
            .keyStore()

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        runCatching {
            keyStore().toggleAutoReplyQuick()
            BabyForegroundService.refreshNotificationIfRunning(applicationContext)
        }
        refreshTile()
    }

    private fun refreshTile() {
        val settings = runCatching { keyStore().settings.value }.getOrNull()
        val isOn = settings != null && settings.autoReplyEnabled && settings.autoReplyMode != AutoReplyMode.OFF
        qsTile?.apply {
            label = "Baby Auto-Reply"
            state = if (isOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (isOn) "ON (${settings.autoReplyMode.label})" else "OFF"
            }
            updateTile()
        }
    }
}
