package com.example

import android.app.Application
import com.example.core.security.SecureKeyStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

import android.os.Build

@HiltAndroidApp
class BabyApplication : Application() {

    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var keyStore: SecureKeyStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (!"robolectric".equals(Build.FINGERPRINT, ignoreCase = true)) {
            warmUpNetworkConnections()
        }
    }

    private fun warmUpNetworkConnections() {
        appScope.launch {
            val cfg = keyStore.settings.value.modelConfig
            val hosts = listOf(
                cfg.inceptionBaseUrl,
                cfg.geminiBaseUrl,
                cfg.groqBaseUrl
            ).distinct()
            hosts.forEach { base ->
                runCatching {
                    val req = Request.Builder().url(base).head().build()
                    okHttpClient.newCall(req).execute().close()
                }
            }
        }
    }
}
