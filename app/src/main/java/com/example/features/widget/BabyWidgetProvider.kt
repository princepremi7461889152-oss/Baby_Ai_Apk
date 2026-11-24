package com.example.features.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.MainActivity
import com.example.core.security.SecureKeyStore
import com.example.service.BabyForegroundService
import com.example.service.BabyQuickSettingsTileService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BabyGlanceWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun keyStore(): SecureKeyStore
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val lastTask = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                .keyStore().settings.value.lastTaskSummary
        }.getOrDefault("Tap Mic to speak")

        provideContent {
            BabyWidgetContent(context = context, lastTask = lastTask)
        }
    }
}

@Composable
private fun BabyWidgetContent(
    context: Context,
    lastTask: String
) {
    val bg = ColorProvider(Color(0xFF121726))
    val surfaceVariant = ColorProvider(Color(0xFF1A2237))
    val primaryOrange = ColorProvider(Color(0xFFFF8A5B))
    val onPrimary = ColorProvider(Color(0xFF2B0D02))
    val secondaryTeal = ColorProvider(Color(0xFF2DD4BF))
    val onSecondary = ColorProvider(Color(0xFF032622))
    val textPrimary = ColorProvider(Color(0xFFF5F7FC))
    val textSecondary = ColorProvider(Color(0xFFB3BDD4))

    val openMainIntent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    val flashIntent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        putExtra(BabyWidgetProvider.EXTRA_WIDGET_COMMAND, "Flashlight on karo")
    }

    val sosIntent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        putExtra(BabyWidgetProvider.EXTRA_EMERGENCY_TRIGGER, true)
    }

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bg)
            .cornerRadius(22.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .clickable(actionStartActivity(openMainIntent))
        ) {
            Text(
                text = "Baby",
                style = TextStyle(
                    color = primaryOrange,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = lastTask.ifBlank { "Tap Mic to speak" },
                maxLines = 2,
                style = TextStyle(
                    color = textSecondary,
                    fontSize = 11.sp
                )
            )
        }

        Spacer(GlanceModifier.width(8.dp))

        // Quick Command 1: Flash
        Box(
            modifier = GlanceModifier
                .background(surfaceVariant)
                .cornerRadius(14.dp)
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .clickable(actionStartActivity(flashIntent)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Flash",
                style = TextStyle(
                    color = textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }

        Spacer(GlanceModifier.width(6.dp))

        // Quick Command 2: SOS
        Box(
            modifier = GlanceModifier
                .background(secondaryTeal)
                .cornerRadius(14.dp)
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .clickable(actionStartActivity(sosIntent)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "SOS",
                style = TextStyle(
                    color = onSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(GlanceModifier.width(6.dp))

        // Mic Primary Button
        Box(
            modifier = GlanceModifier
                .background(primaryOrange)
                .cornerRadius(16.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clickable(
                    actionRunCallback<TriggerMicActionCallback>(
                        actionParametersOf(ActionParameters.Key<Boolean>("voice") to true)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Mic",
                style = TextStyle(
                    color = onPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

class TriggerMicActionCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val started = runCatching {
            BabyForegroundService.triggerVoice(context)
            true
        }.getOrDefault(false)
        if (!started) {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(BabyQuickSettingsTileService.EXTRA_START_VOICE, true)
                }
            )
        }
    }
}

class BabyWidgetProvider : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = BabyGlanceWidget()

    companion object {
        const val EXTRA_EMERGENCY_TRIGGER = "extra_emergency_trigger"
        const val EXTRA_WIDGET_COMMAND = "extra_widget_command"

        fun refreshAllWidgets(context: Context) {
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    BabyGlanceWidget().updateAll(appContext)
                }
            }
        }
    }
}
