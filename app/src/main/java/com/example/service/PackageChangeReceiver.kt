package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.domain.tools.AppResolver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class PackageChangeReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun appResolver(): AppResolver
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_PACKAGE_ADDED ||
            action == Intent.ACTION_PACKAGE_REMOVED ||
            action == Intent.ACTION_PACKAGE_REPLACED
        ) {
            runCatching {
                val resolver = EntryPointAccessors
                    .fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
                    .appResolver()
                resolver.invalidateIndex()
                resolver.getInstalledAppsSnapshot(forceRefresh = true)
            }
        }
    }
}
