package com.example.domain.tools

import com.example.data.remote.GeminiFunctionDeclaration
import com.example.data.remote.GeminiTool
import com.example.data.remote.GroqFunctionSpec
import com.example.data.remote.GroqTool

data class ToolSpec(
    val name: String,
    val description: String,
    val properties: Map<String, Map<String, Any>>,
    val required: List<String> = emptyList(),
    val requiresUnlockedScreen: Boolean = false,
    val alwaysSensitive: Boolean = false
) {
    fun parametersSchema(): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to properties,
        "required" to required
    )
}

object ToolDefinitions {

    private fun strProp(desc: String) = mapOf("type" to "string", "description" to desc)
    private fun intProp(desc: String) = mapOf("type" to "integer", "description" to desc)
    private fun numProp(desc: String) = mapOf("type" to "number", "description" to desc)
    private fun boolProp(desc: String) = mapOf("type" to "boolean", "description" to desc)

    val allTools: List<ToolSpec> = listOf(
        ToolSpec(
            name = "open_app",
            description = "Open an installed app by app name (e.g. WhatsApp, YouTube, Settings, Chrome) or package name.",
            properties = mapOf("app_name" to strProp("App name or package name to launch")),
            required = listOf("app_name"),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "call_contact",
            description = "Call a phone contact by name or phone number.",
            properties = mapOf("target" to strProp("Contact name or phone number to call")),
            required = listOf("target"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "send_sms",
            description = "Send an SMS text message to a contact name or phone number.",
            properties = mapOf(
                "recipient" to strProp("Contact name or phone number"),
                "message" to strProp("SMS message body")
            ),
            required = listOf("recipient", "message"),
            requiresUnlockedScreen = false,
            alwaysSensitive = true
        ),
        ToolSpec(
            name = "send_whatsapp",
            description = "Open WhatsApp chat or send a WhatsApp message to a contact or phone number.",
            properties = mapOf(
                "recipient" to strProp("Contact name or phone number with country code"),
                "message" to strProp("Message text to send on WhatsApp")
            ),
            required = listOf("recipient", "message"),
            requiresUnlockedScreen = true,
            alwaysSensitive = true
        ),
        ToolSpec(
            name = "set_alarm",
            description = "Set an alarm at a specific hour and minute (24-hour format).",
            properties = mapOf(
                "hour" to intProp("Hour in 24h format (0-23)"),
                "minute" to intProp("Minute (0-59)"),
                "label" to strProp("Optional alarm label")
            ),
            required = listOf("hour", "minute"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "set_timer",
            description = "Start a countdown timer for a number of seconds.",
            properties = mapOf(
                "seconds" to intProp("Duration in seconds"),
                "label" to strProp("Optional timer label")
            ),
            required = listOf("seconds"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "toggle_wifi",
            description = "Open or toggle Wi-Fi settings panel / state.",
            properties = mapOf("enable" to boolProp("True to turn on, false to turn off")),
            required = listOf("enable"),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "toggle_bluetooth",
            description = "Enable or open Bluetooth settings.",
            properties = mapOf("enable" to boolProp("True to turn on, false to turn off")),
            required = listOf("enable"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "toggle_flashlight",
            description = "Turn phone flashlight / torch ON or OFF.",
            properties = mapOf("enable" to boolProp("True for ON, false for OFF")),
            required = listOf("enable"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "toggle_dnd",
            description = "Turn Do Not Disturb mode ON or OFF.",
            properties = mapOf("enable" to boolProp("True to enable DND, false to disable")),
            required = listOf("enable"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "set_volume",
            description = "Set phone volume level (0-100%) for media, ring, alarm, or call.",
            properties = mapOf(
                "stream" to strProp("One of: media, ring, alarm, call, notification"),
                "level_percent" to intProp("Volume percentage 0 to 100")
            ),
            required = listOf("stream", "level_percent"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "set_brightness",
            description = "Set screen brightness percentage (5 to 100).",
            properties = mapOf("level_percent" to intProp("Brightness percentage 5 to 100")),
            required = listOf("level_percent"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "media_control",
            description = "Control active media playback.",
            properties = mapOf("action" to strProp("One of: play, pause, toggle, next, previous, stop")),
            required = listOf("action"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "search_web",
            description = "Search the live web using Google Search grounding and answer concisely with sources.",
            properties = mapOf("query" to strProp("Search query")),
            required = listOf("query"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "search_in_app",
            description = "Open an app (YouTube, YouTube Music, Spotify, Instagram, Facebook, Amazon, Flipkart, Maps, Play Store, Chrome), search a query inside it, and optionally play or open the first clean result.",
            properties = mapOf(
                "app_name" to strProp("Target app e.g. YouTube, Spotify, Maps, Amazon"),
                "query" to strProp("Search query"),
                "auto_play" to boolProp("True to play/open first clean result (skip ads/Shorts)")
            ),
            required = listOf("app_name", "query"),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "read_screen",
            description = "Observe the current visible UI elements on the phone screen and optionally capture a screenshot.",
            properties = mapOf("include_screenshot" to boolProp("Set true if visual screenshot inspection is needed")),
            required = emptyList(),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "tap",
            description = "Tap at screen pixel coordinates (x, y).",
            properties = mapOf(
                "x" to numProp("X coordinate in pixels"),
                "y" to numProp("Y coordinate in pixels"),
                "description" to strProp("Short description of what is being tapped")
            ),
            required = listOf("x", "y"),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "long_press",
            description = "Long-press at screen pixel coordinates (x, y).",
            properties = mapOf(
                "x" to numProp("X coordinate in pixels"),
                "y" to numProp("Y coordinate in pixels"),
                "description" to strProp("Short description of target element")
            ),
            required = listOf("x", "y"),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "type_text",
            description = "Type text into the currently focused or editable input field on screen.",
            properties = mapOf("text" to strProp("Text to type")),
            required = listOf("text"),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "swipe",
            description = "Swipe on screen either by direction (up, down, left, right) or explicit coordinates.",
            properties = mapOf(
                "direction" to strProp("Optional direction: up, down, left, right"),
                "start_x" to numProp("Optional start X"),
                "start_y" to numProp("Optional start Y"),
                "end_x" to numProp("Optional end X"),
                "end_y" to numProp("Optional end Y")
            ),
            required = emptyList(),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "scroll",
            description = "Scroll the current screen in a direction (down, up, left, right).",
            properties = mapOf("direction" to strProp("Direction: down, up, left, right")),
            required = listOf("direction"),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "go_back",
            description = "Press the Android system Back button.",
            properties = emptyMap(),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "go_home",
            description = "Press the Android system Home button.",
            properties = emptyMap(),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "open_recents",
            description = "Open Android Recent Apps switcher.",
            properties = emptyMap(),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "open_notifications",
            description = "Pull down the Android notification shade.",
            properties = emptyMap(),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "take_screenshot",
            description = "Capture a screenshot of the current screen so Baby can see visual layout.",
            properties = emptyMap(),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "read_notifications",
            description = "Read recent active notifications (WhatsApp, SMS, etc.). Works even while screen is locked.",
            properties = mapOf("filter_app" to strProp("Optional app or sender name filter")),
            required = emptyList(),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "reply_notification",
            description = "Send an inline reply to a notification using its key from read_notifications.",
            properties = mapOf(
                "key" to strProp("Notification key returned by read_notifications"),
                "message" to strProp("Reply text to send")
            ),
            required = listOf("key", "message"),
            requiresUnlockedScreen = false,
            alwaysSensitive = true
        ),
        ToolSpec(
            name = "speak",
            description = "Speak a short message aloud to the user via TextToSpeech.",
            properties = mapOf(
                "text" to strProp("Short text to speak aloud"),
                "language" to strProp("hinglish, hindi, or english")
            ),
            required = listOf("text"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "create_routine",
            description = "Create a reusable non-sensitive daily routine with a trigger phrase, ordered tool actions, and optional trigger (MANUAL, TIME, CHARGING, HEADPHONES).",
            properties = mapOf(
                "name" to strProp("Routine display name"),
                "trigger_phrase" to strProp("Voice/text trigger phrase"),
                "actions_json" to strProp("JSON array of {tool, args} objects"),
                "tool" to strProp("Single tool name if simple"),
                "schedule_type" to strProp("MANUAL, TIME, CHARGING, or HEADPHONES"),
                "schedule_time" to strProp("HH:mm time if schedule_type is TIME")
            ),
            required = listOf("name", "trigger_phrase"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "run_routine",
            description = "Run a saved or built-in daily routine by trigger phrase (e.g., Good morning, Good night, Leaving home, Reached home).",
            properties = mapOf("trigger_phrase" to strProp("Routine trigger phrase")),
            required = listOf("trigger_phrase"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "list_routines",
            description = "List all enabled built-in and custom routines with their trigger phrases and schedules.",
            properties = emptyMap(),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "delete_routine",
            description = "Delete a custom saved routine by name or trigger phrase.",
            properties = mapOf("routine_name_or_phrase" to strProp("Routine name or trigger phrase to delete")),
            required = listOf("routine_name_or_phrase"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "save_memory",
            description = "Remember a contact nickname (Mummy, Bhaiya), user preference, fact, or habit in on-device memory.",
            properties = mapOf(
                "type" to strProp("One of: nickname, preference, fact, habit"),
                "key" to strProp("Short memory key e.g. Mummy"),
                "value" to strProp("Memory details e.g. Real contact name or phone number")
            ),
            required = listOf("type", "key", "value"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "recall_memory",
            description = "Search saved on-device memories relevant to a topic or contact.",
            properties = mapOf("query" to strProp("Search keyword")),
            required = listOf("query"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "delete_memory",
            description = "Delete a specific saved memory item by key.",
            properties = mapOf("key" to strProp("Memory key to remove")),
            required = listOf("key"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "set_reminder",
            description = "Set a natural-language time reminder via AlarmManager (e.g. 'shaam 6 baje doodh') or a location reminder via Geofencing (e.g. 'ghar pahunchu to bolna').",
            properties = mapOf(
                "title" to strProp("What to remind about"),
                "trigger_type" to strProp("TIME or LOCATION"),
                "time_expression" to strProp("Natural time expression e.g. 'shaam 6 baje', 'kal subah 8 baje', '18:30'"),
                "delay_minutes" to intProp("Optional relative minutes from now"),
                "place_label" to strProp("Place label for LOCATION reminder e.g. Home, Work, Ghar"),
                "latitude" to numProp("Optional latitude for LOCATION reminder"),
                "longitude" to numProp("Optional longitude for LOCATION reminder")
            ),
            required = listOf("title"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "list_reminders",
            description = "List all active scheduled time and location reminders with their IDs.",
            properties = mapOf("include_completed" to boolProp("Include completed reminders if true")),
            required = emptyList(),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "delete_reminder",
            description = "Delete and cancel an active reminder by its ID or title/place keyword.",
            properties = mapOf("query" to strProp("Reminder ID or keyword from reminder title")),
            required = listOf("query"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "snooze_reminder",
            description = "Snooze an active reminder by ID for a given number of minutes (default 10).",
            properties = mapOf(
                "reminder_id" to intProp("Reminder ID"),
                "minutes" to intProp("Snooze minutes (default 10)")
            ),
            required = listOf("reminder_id"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "save_place",
            description = "Save Home, Work, or a custom place coordinate (or current GPS location) for geofence location reminders.",
            properties = mapOf(
                "label" to strProp("Place name e.g. Home, Work, Gym"),
                "use_current_gps" to boolProp("True to save the user's current GPS coordinates"),
                "latitude" to numProp("Optional latitude"),
                "longitude" to numProp("Optional longitude"),
                "radius_meters" to numProp("Optional geofence radius in meters (default 200)")
            ),
            required = listOf("label"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "summarize_notifications",
            description = "Give a short Hinglish summary of active notifications grouped by app and importance (people messages first, promos last; OTPs & banking masked by default).",
            properties = mapOf(
                "scope" to strProp("Scope: all, people, updates, promos, or app name like whatsapp"),
                "speak" to boolProp("Optional: speak summary aloud")
            ),
            required = emptyList(),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "set_auto_reply",
            description = "Turn Auto-Reply mode ON or OFF and select mode (BUSY, DRIVING, SLEEPING, CUSTOM, OFF).",
            properties = mapOf(
                "mode" to strProp("BUSY, DRIVING, SLEEPING, CUSTOM, or OFF"),
                "on_off" to boolProp("True to enable auto-reply, false to turn OFF")
            ),
            required = listOf("mode"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "set_auto_reply_message",
            description = "Update the default auto-reply message template for a mode (BUSY, DRIVING, SLEEPING, or CUSTOM).",
            properties = mapOf(
                "mode" to strProp("BUSY, DRIVING, SLEEPING, or CUSTOM"),
                "text" to strProp("New auto-reply message text")
            ),
            required = listOf("mode", "text"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "explain_screen",
            description = "Explain, translate, or summarize what is currently on the phone screen using Accessibility UI tree first (and Gemini Vision screenshot only if tree is not enough and Data Saver is OFF).",
            properties = mapOf(
                "mode" to strProp("One of: explain, translate, summarize"),
                "target_language" to strProp("Optional target language for translate mode e.g. Hinglish, Hindi, English"),
                "question" to strProp("Optional specific user question about the screen")
            ),
            required = listOf("mode"),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "read_clipboard_and_process",
            description = "Read the user's copied clipboard text on demand and summarize, translate, or draft a reply in Hinglish.",
            properties = mapOf(
                "mode" to strProp("One of: summarize, translate, draft_reply, explain"),
                "target_language" to strProp("Optional target language for translate mode")
            ),
            required = listOf("mode"),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "capture_and_describe",
            description = "Open the phone camera, take a single temporary photo (not saved to gallery, deleted immediately after API call), and describe or answer the user's question in Hinglish via Gemini Vision.",
            properties = mapOf(
                "question" to strProp("User's question about what the camera sees e.g. 'Ye kya hai?'")
            ),
            required = listOf("question"),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "create_command",
            description = "Create a custom multi-action phrase command (e.g. 'Movie mode' -> DND on, brightness low, volume 40%, open Netflix) reusing the Routines engine.",
            properties = mapOf(
                "name" to strProp("Display name e.g. Movie mode"),
                "trigger_phrase" to strProp("Voice/text trigger phrase e.g. movie mode"),
                "actions_json" to strProp("Optional JSON array of {tool, args} objects"),
                "tool" to strProp("Optional single tool name"),
                "dnd_enable" to boolProp("Optional: turn Do Not Disturb ON/OFF"),
                "brightness_percent" to intProp("Optional: brightness percentage 5-100"),
                "volume_percent" to intProp("Optional: media volume percentage 0-100"),
                "open_app_name" to strProp("Optional: app name to open e.g. Netflix")
            ),
            required = listOf("name", "trigger_phrase"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "run_command",
            description = "Run a saved custom command by trigger phrase (e.g. Movie mode).",
            properties = mapOf("trigger_phrase" to strProp("Custom command trigger phrase")),
            required = listOf("trigger_phrase"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "list_commands",
            description = "List all enabled custom multi-action commands and their trigger phrases.",
            properties = emptyMap(),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "delete_command",
            description = "Delete a custom command by name or trigger phrase.",
            properties = mapOf("command_name_or_phrase" to strProp("Command name or trigger phrase")),
            required = listOf("command_name_or_phrase"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "start_macro_recording",
            description = "Start recording Accessibility UI steps (taps, scrolls, non-password text inputs) for a reusable macro ('Macro record shuru karo').",
            properties = emptyMap(),
            requiresUnlockedScreen = true
        ),
        ToolSpec(
            name = "stop_macro_recording",
            description = "Stop recording and save the recorded macro steps into Room under a name and trigger phrase ('Macro save karo [name]').",
            properties = mapOf(
                "name" to strProp("Macro name"),
                "trigger_phrase" to strProp("Optional trigger phrase (defaults to macro name)")
            ),
            required = listOf("name"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "run_macro",
            description = "Replay a saved macro by name or trigger phrase ('[name] chalao'), verifying each UI element step.",
            properties = mapOf("trigger_phrase" to strProp("Macro name or trigger phrase")),
            required = listOf("trigger_phrase"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "list_macros",
            description = "List all enabled recorded macros and their trigger phrases.",
            properties = emptyMap(),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "delete_macro",
            description = "Delete a saved macro by name or trigger phrase.",
            properties = mapOf("macro_name_or_phrase" to strProp("Macro name or trigger phrase")),
            required = listOf("macro_name_or_phrase"),
            requiresUnlockedScreen = false
        ),
        ToolSpec(
            name = "send_emergency_alert",
            description = "Trigger Emergency SOS alert: 5-second countdown with cancel option, then send SMS with live location to emergency contacts and optionally call.",
            properties = mapOf("also_call" to boolProp("Whether to also place a call")),
            required = emptyList(),
            requiresUnlockedScreen = false
        )
    )

    private val byName: Map<String, ToolSpec> = allTools.associateBy { it.name }

    fun getSpec(name: String): ToolSpec? = byName[name]

    fun requiresUnlockedScreen(name: String): Boolean =
        byName[name]?.requiresUnlockedScreen ?: false

    fun isSensitiveAction(name: String, args: Map<String, Any?>): Boolean {
        val spec = byName[name]
        if (spec?.alwaysSensitive == true) return true
        val combined = listOfNotNull(
            args["description"]?.toString(),
            args["text"]?.toString(),
            args["app_name"]?.toString(),
            args["query"]?.toString(),
            args["action"]?.toString()
        ).joinToString(" ").lowercase()
        val riskyKeywords = listOf(
            "pay", "upi", "send money", "transfer", "gpay", "phonepe", "paytm", "bhim",
            "delete", "remove", "erase", "format", "uninstall", "confirm order",
            "checkout", "purchase", "buy now", "bhugtan", "paise",
            "security", "password", "pin", "lock screen", "screen lock",
            "biometric", "fingerprint", "device admin", "developer options", "factory reset"
        )
        return riskyKeywords.any { combined.contains(it) }
    }

    fun formatConfirmationSummary(name: String, args: Map<String, Any?>): String {
        return when (name) {
            "send_sms" -> "Send SMS to ${args["recipient"]}: \"${args["message"]}\""
            "send_whatsapp" -> "Send WhatsApp message to ${args["recipient"]}: \"${args["message"]}\""
            "reply_notification" -> "Reply to notification: \"${args["message"]}\""
            "tap", "long_press" -> "${name.replace('_', ' ')} on \"${args["description"] ?: "screen element"}\""
            else -> "Execute $name with ${args.entries.joinToString { "${it.key}=${it.value}" }}"
        }
    }

    private val coreToolNames = setOf(
        "open_app", "call_contact", "toggle_flashlight", "set_volume", "set_brightness",
        "toggle_wifi", "toggle_bluetooth", "toggle_dnd", "set_alarm", "set_timer",
        "media_control", "search_web", "search_in_app", "speak", "read_notifications"
    )

    private fun relevantSpecs(userPrompt: String, dataSaver: Boolean): List<ToolSpec> {
        val lower = userPrompt.lowercase()
        val selectedNames = LinkedHashSet(coreToolNames)

        if (lower.contains("whatsapp") || lower.contains("sms") || lower.contains("message") ||
            lower.contains("bhejo") || lower.contains("reply") || lower.contains("chat")
        ) {
            selectedNames += listOf("send_sms", "send_whatsapp", "reply_notification", "read_notifications")
        }
        if (lower.contains("screen") || lower.contains("tap") || lower.contains("click") ||
            lower.contains("scroll") || lower.contains("swipe") || lower.contains("type") ||
            lower.contains("translate") || lower.contains("explain") || lower.contains("summarize") ||
            lower.contains("isko") || lower.contains("ye kya") || lower.contains("back") ||
            lower.contains("home") || lower.contains("recents")
        ) {
            selectedNames += listOf(
                "read_screen", "explain_screen", "tap", "long_press", "type_text",
                "swipe", "scroll", "go_back", "go_home", "open_recents", "open_notifications"
            )
            if (!dataSaver) {
                selectedNames += "take_screenshot"
            }
        }
        if (lower.contains("camera") || lower.contains("photo") || lower.contains("samne") || lower.contains("dekho")) {
            selectedNames += "capture_and_describe"
        }
        if (lower.contains("clipboard") || lower.contains("copy") || lower.contains("copied")) {
            selectedNames += "read_clipboard_and_process"
        }
        if (lower.contains("remind") || lower.contains("yaad dila") || lower.contains("alarm") ||
            lower.contains("baje") || lower.contains("place") || lower.contains("ghar") || lower.contains("office")
        ) {
            selectedNames += listOf("set_reminder", "list_reminders", "delete_reminder", "snooze_reminder", "save_place")
        }
        if (lower.contains("remember") || lower.contains("yaad rakh") || lower.contains("memory") ||
            lower.contains("bhool") || lower.contains("mummy") || lower.contains("papa") || lower.contains("bhai")
        ) {
            selectedNames += listOf("save_memory", "recall_memory", "delete_memory")
        }
        if (lower.contains("routine") || lower.contains("morning") || lower.contains("night") || lower.contains("mode")) {
            selectedNames += listOf(
                "create_routine", "run_routine", "list_routines", "delete_routine",
                "create_command", "run_command", "list_commands", "delete_command"
            )
        }
        if (lower.contains("macro") || lower.contains("record") || lower.contains("chalao")) {
            selectedNames += listOf(
                "start_macro_recording", "stop_macro_recording", "run_macro", "list_macros", "delete_macro"
            )
        }
        if (lower.contains("notif") || lower.contains("auto") || lower.contains("busy") ||
            lower.contains("driving") || lower.contains("sleeping")
        ) {
            selectedNames += listOf("summarize_notifications", "set_auto_reply", "set_auto_reply_message", "read_notifications")
        }
        if (lower.contains("help") || lower.contains("sos") || lower.contains("emergency")) {
            selectedNames += "send_emergency_alert"
        }

        // If prompt doesn't strongly match any narrow domain and dataSaver is OFF, still allow a bounded set
        val specs = allTools.filter { spec ->
            spec.name in selectedNames && (!dataSaver || spec.name != "take_screenshot")
        }
        return if (specs.isNotEmpty()) specs else allTools.filter { !dataSaver || it.name != "take_screenshot" }
    }

    fun selectRelevantTools(userPrompt: String, dataSaver: Boolean): List<GroqTool> =
        relevantSpecs(userPrompt, dataSaver).map { spec ->
            GroqTool(
                type = "function",
                function = GroqFunctionSpec(
                    name = spec.name,
                    description = spec.description,
                    parameters = spec.parametersSchema()
                )
            )
        }

    fun selectRelevantGeminiTools(userPrompt: String, dataSaver: Boolean): List<GeminiTool> = listOf(
        GeminiTool(
            functionDeclarations = relevantSpecs(userPrompt, dataSaver).map { spec ->
                GeminiFunctionDeclaration(
                    name = spec.name,
                    description = spec.description,
                    parameters = spec.parametersSchema()
                )
            }
        )
    )

    fun toGroqTools(): List<GroqTool> = allTools.map { spec ->
        GroqTool(
            type = "function",
            function = GroqFunctionSpec(
                name = spec.name,
                description = spec.description,
                parameters = spec.parametersSchema()
            )
        )
    }

    fun toGeminiTools(): List<GeminiTool> = listOf(
        GeminiTool(
            functionDeclarations = allTools.map { spec ->
                GeminiFunctionDeclaration(
                    name = spec.name,
                    description = spec.description,
                    parameters = spec.parametersSchema()
                )
            }
        )
    )
}
