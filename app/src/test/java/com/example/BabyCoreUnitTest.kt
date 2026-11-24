package com.example

import com.example.domain.tools.ToolDefinitions
import com.example.features.actionlog.ActionLogInterceptor
import com.example.features.autoreply.AutoReplyManager
import com.example.features.memory.MemoryManager
import com.example.features.notifications.NotificationSummaryManager
import com.example.features.reminders.ReminderManager
import com.example.features.safety.SafetyManager
import com.example.features.vision.ClipboardAiManager
import com.example.features.vision.ScreenHelpManager
import com.example.service.BabyForegroundService
import com.example.voice.WakeWordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BabyCoreUnitTest {

    @Test
    fun `wake word detects both Hey Baby and Baby phrases`() {
        val heyBaby = WakeWordDetector.extractWakeAndCommand("Hey Baby flashlight on karo")
        assertNotNull(heyBaby)
        assertEquals("hey baby", heyBaby?.phraseMatched)
        assertEquals("flashlight on karo", heyBaby?.trailingCommand)

        val plainBaby = WakeWordDetector.extractWakeAndCommand("Baby alarm lagao 7 baje")
        assertNotNull(plainBaby)
        assertEquals("baby", plainBaby?.phraseMatched)
        assertEquals("alarm lagao 7 baje", plainBaby?.trailingCommand)

        val unrelated = WakeWordDetector.extractWakeAndCommand("Hello world weather today")
        assertNull(unrelated)
    }

    @Test
    fun `stop command triggers cancellation`() {
        assertTrue(WakeWordDetector.isStopCommand("Baby stop"))
        assertTrue(WakeWordDetector.isStopCommand("hey baby stop kar do"))
        assertFalse(WakeWordDetector.isStopCommand("Baby play music"))
    }

    @Test
    fun `sensitive actions and lock screen gating match rules`() {
        assertTrue(ToolDefinitions.isSensitiveAction("send_whatsapp", mapOf("recipient" to "Mom", "message" to "Hi")))
        assertTrue(ToolDefinitions.isSensitiveAction("tap", mapOf("description" to "Pay Rs 500 via UPI")))
        assertTrue(ToolDefinitions.isSensitiveAction("tap", mapOf("description" to "Change Screen Lock PIN")))
        assertTrue(ToolDefinitions.isSensitiveAction("tap", mapOf("description" to "Uninstall WhatsApp")))
        assertFalse(ToolDefinitions.isSensitiveAction("toggle_flashlight", mapOf("enable" to true)))

        assertTrue(ToolDefinitions.requiresUnlockedScreen("tap"))
        assertFalse(ToolDefinitions.requiresUnlockedScreen("call_contact"))
        assertFalse(ToolDefinitions.requiresUnlockedScreen("toggle_flashlight"))
    }

    @Test
    fun `memory phrases parse yaad rakhna and wipe command`() {
        val parsed = MemoryManager.extractYaadRakhna("Baby yaad rakhna Mummy ka birthday 12 May hai")
        assertNotNull(parsed)
        assertEquals("Mummy ka birthday 12 May hai", parsed?.second)

        assertTrue(MemoryManager.isWipeMemoryPhrase("Sab bhool jao"))
        assertTrue(MemoryManager.isWipeMemoryPhrase("baby wipe all memory"))
        assertFalse(MemoryManager.isWipeMemoryPhrase("yaad rakhna chai banana"))
    }

    @Test
    fun `phase 1, 2, and 3 tools exist with expected schema`() {
        val expectedTools = listOf(
            "create_routine", "run_routine", "list_routines", "delete_routine",
            "save_memory", "recall_memory", "delete_memory",
            "set_reminder", "list_reminders", "delete_reminder", "snooze_reminder", "save_place",
            "summarize_notifications", "set_auto_reply", "set_auto_reply_message",
            "explain_screen", "read_clipboard_and_process", "capture_and_describe",
            "create_command", "run_command", "list_commands", "delete_command",
            "start_macro_recording", "stop_macro_recording", "run_macro", "list_macros", "delete_macro",
            "send_emergency_alert"
        )
        expectedTools.forEach { name ->
            assertNotNull("Missing tool $name", ToolDefinitions.getSpec(name))
        }
        val saveMemProps = ToolDefinitions.getSpec("save_memory")?.properties?.keys.orEmpty()
        assertTrue(saveMemProps.containsAll(listOf("type", "key", "value")))

        val notifProps = ToolDefinitions.getSpec("summarize_notifications")?.properties?.keys.orEmpty()
        assertTrue(notifProps.contains("scope"))

        val screenProps = ToolDefinitions.getSpec("explain_screen")?.properties?.keys.orEmpty()
        assertTrue(screenProps.contains("mode"))

        val clipProps = ToolDefinitions.getSpec("read_clipboard_and_process")?.properties?.keys.orEmpty()
        assertTrue(clipProps.contains("mode"))

        val camProps = ToolDefinitions.getSpec("capture_and_describe")?.properties?.keys.orEmpty()
        assertTrue(camProps.contains("question"))

        val createCmdProps = ToolDefinitions.getSpec("create_command")?.properties?.keys.orEmpty()
        assertTrue(createCmdProps.containsAll(listOf("name", "trigger_phrase", "dnd_enable", "open_app_name")))

        val stopMacroProps = ToolDefinitions.getSpec("stop_macro_recording")?.properties?.keys.orEmpty()
        assertTrue(stopMacroProps.containsAll(listOf("name", "trigger_phrase")))
    }

    @Test
    fun `action log source normalization and auto reply schedule window work`() {
        assertEquals("voice", ActionLogInterceptor.normalizeSource("wake_word"))
        assertEquals("voice", ActionLogInterceptor.normalizeSource("voice"))
        assertEquals("routine", ActionLogInterceptor.normalizeSource("routine_ui"))
        assertEquals("auto-reply", ActionLogInterceptor.normalizeSource("auto-reply"))
        assertEquals("text", ActionLogInterceptor.normalizeSource("text"))

        // Overnight schedule: 22:00 to 07:00
        assertTrue(AutoReplyManager.isWithinSchedule(22, 0, 7, 0, nowMinutesOverride = 23 * 60 + 30))
        assertTrue(AutoReplyManager.isWithinSchedule(22, 0, 7, 0, nowMinutesOverride = 6 * 60 + 15))
        assertFalse(AutoReplyManager.isWithinSchedule(22, 0, 7, 0, nowMinutesOverride = 12 * 60))

        // Daytime schedule: 09:00 to 18:00
        assertTrue(AutoReplyManager.isWithinSchedule(9, 0, 18, 0, nowMinutesOverride = 10 * 60))
        assertFalse(AutoReplyManager.isWithinSchedule(9, 0, 18, 0, nowMinutesOverride = 19 * 60))
    }

    @Test
    fun `phase 2 otp masking, reminder time parsing, screen tree check, and clipboard modes work`() {
        // 1. OTP / Banking detection & masking
        assertTrue(NotificationSummaryManager.isSensitiveFinancialOrOtp("HDFC Bank", "Your OTP for Rs 4500 txn is 849210"))
        assertFalse(NotificationSummaryManager.isSensitiveFinancialOrOtp("Rahul", "Kal shaam chai pe milte hain"))
        val masked = NotificationSummaryManager.maskSensitiveText("OTP is 849210 for Rs 4500")
        assertFalse(masked.contains("849210"))
        assertTrue(masked.contains("[****]"))

        // 2. Reminder Hinglish parsing & place normalization
        val baseCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 22, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val sixPm = ReminderManager.resolveTimeMillis("shaam 6 baje", null, baseCal.timeInMillis)
        val resCal = Calendar.getInstance().apply { timeInMillis = sixPm }
        assertEquals(18, resCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, resCal.get(Calendar.MINUTE))
        assertEquals("home", ReminderManager.normalizePlaceKey("Ghar"))
        assertEquals("work", ReminderManager.normalizePlaceKey("Office"))
        assertEquals("doodh lena", ReminderManager.cleanReminderTitle("shaam 6 baje doodh lena yaad dilana"))

        // 3. Screen Help text-tree sufficiency
        val richTree = """
            ActiveApp: com.whatsapp
            [TextView] "Rahul" @(100,100) []
            [TextView] "Kal subah 10 baje meeting hai project review ke liye" @(200,250) []
            [Button] "Reply" @(300,400) [click]
            [TextView] "Unread messages from team" @(200,500) []
        """.trimIndent()
        assertTrue(ScreenHelpManager.hasSufficientTextTree(richTree))
        assertFalse(ScreenHelpManager.hasSufficientTextTree("ActiveApp: com.google.android.apps.photos\n[ImageView] @(500,500) []"))
        assertEquals("translate", ScreenHelpManager.normalizeMode("isko translate karo"))

        // 4. Clipboard AI modes
        assertEquals("draft_reply", ClipboardAiManager.normalizeMode("draft a reply"))
        assertEquals("translate", ClipboardAiManager.normalizeMode("translate"))
        assertEquals("summarize", ClipboardAiManager.normalizeMode("summarize"))
    }

    @Test
    fun `phase 3 safety blocked screens, sensitive fields, sos cancel, and power shortcut work`() {
        // Blocked screens
        assertNotNull(SafetyManager.isBlockedScreen("com.google.android.apps.nbu.paisa.user", null))
        assertNotNull(SafetyManager.isBlockedScreen("com.phonepe.app", null))
        assertNotNull(SafetyManager.isBlockedScreen("com.x8bit.bitwarden", null))
        assertNotNull(SafetyManager.isBlockedScreen("com.android.settings", "Confirm your PIN to continue"))
        assertNull(SafetyManager.isBlockedScreen("com.android.settings", "Display & Brightness"))
        assertNull(SafetyManager.isBlockedScreen("com.whatsapp", "Hey what's up"))

        // Sensitive fields
        assertTrue(SafetyManager.isSensitiveField(null, null, null, isPasswordField = true))
        assertTrue(SafetyManager.isSensitiveField("com.bank:id/otp_input", null, "Enter OTP", isPasswordField = false))
        assertTrue(SafetyManager.isSensitiveField("upi_pin_box", null, null, isPasswordField = false))
        assertFalse(SafetyManager.isSensitiveField("com.whatsapp:id/entry", "Hello", "Type a message", isPasswordField = false))

        // SOS voice cancel commands
        assertTrue(SafetyManager.isEmergencyCancelCommand("Cancel"))
        assertTrue(SafetyManager.isEmergencyCancelCommand("mat bhejo"))
        assertTrue(SafetyManager.isEmergencyCancelCommand("Baby stop"))
        assertFalse(SafetyManager.isEmergencyCancelCommand("Baby help"))

        // Power-button SOS multi-press shortcut (4 toggles inside 3500ms)
        val queue = ArrayDeque<Long>()
        assertFalse(BabyForegroundService.shouldTriggerPowerShortcut(queue, 1000L))
        assertFalse(BabyForegroundService.shouldTriggerPowerShortcut(queue, 1500L))
        assertFalse(BabyForegroundService.shouldTriggerPowerShortcut(queue, 2000L))
        // 4th press outside window wouldn't trigger if old ones expired:
        assertFalse(BabyForegroundService.shouldTriggerPowerShortcut(queue, 6000L))
        assertTrue(BabyForegroundService.shouldTriggerPowerShortcut(queue, 6400L) == false)
        assertFalse(BabyForegroundService.shouldTriggerPowerShortcut(queue, 6800L))
        assertTrue(BabyForegroundService.shouldTriggerPowerShortcut(queue, 7200L))
    }
}
