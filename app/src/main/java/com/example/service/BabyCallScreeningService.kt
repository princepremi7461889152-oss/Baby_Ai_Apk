package com.example.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import com.example.features.calls.CallAssistantManager
import com.example.features.calls.CallScreeningAction
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BabyCallScreeningService : CallScreeningService() {

    @Inject lateinit var callAssistantManager: CallAssistantManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onScreenCall(callDetails: Call.Details) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            callDetails.callDirection != Call.Details.DIRECTION_INCOMING
        ) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val rawNumber = callDetails.handle?.schemeSpecificPart.orEmpty()
        val displayName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            callDetails.callerDisplayName
        } else {
            null
        }

        serviceScope.launch {
            val decision = runCatching {
                callAssistantManager.screenAndLogCall(rawNumber, displayName)
            }.getOrNull()

            val response = when (decision?.action) {
                CallScreeningAction.SILENCE -> CallResponse.Builder()
                    .setSilenceCall(true)
                    .setDisallowCall(false)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
                    .build()
                CallScreeningAction.REJECT_WITH_REPLY -> CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSilenceCall(true)
                    .setSkipCallLog(false)
                    .setSkipNotification(false)
                    .build()
                CallScreeningAction.ALLOW,
                CallScreeningAction.LEVEL2_ANSWER,
                null -> CallResponse.Builder().build()
            }
            respondToCall(callDetails, response)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
