package com.example.domain.ai

import com.example.core.security.SensitiveDataSanitizer
import com.example.data.remote.GeminiFunctionCall
import com.example.data.remote.GroqToolCall
import com.example.domain.tools.ToolDefinitions
import com.squareup.moshi.JsonAdapter
import org.json.JSONArray
import org.json.JSONObject

data class NormalizedToolCall(
    val id: String,
    val name: String,
    val args: Map<String, Any?>,
    val missingRequired: List<String>,
    val isMalformedJson: Boolean,
    val rawCallSanitized: String
)

/**
 * Single unified tool-call normalizer shared across Mercury 3, Gemini 3.8 Flash, Groq,
 * SSE streaming responses, and inline <tool_call> payloads.
 */
object ToolCallNormalizer {

    fun isNullOrLiteralNull(value: Any?): Boolean {
        if (value == null || value == JSONObject.NULL) return true
        if (value is String) {
            val trimmed = value.trim()
            return trimmed.isEmpty() ||
                trimmed.equals("null", ignoreCase = true) ||
                trimmed.equals("undefined", ignoreCase = true)
        }
        return false
    }

    fun cleanString(value: Any?): String? {
        if (isNullOrLiteralNull(value)) return null
        val s = value.toString().trim()
        return s.takeUnless {
            it.isEmpty() ||
                it.equals("null", ignoreCase = true) ||
                it.equals("undefined", ignoreCase = true)
        }
    }

    fun hasMalformedArgsPayload(rawArgs: Any?): Boolean {
        if (rawArgs == null || rawArgs == JSONObject.NULL || rawArgs is Map<*, *> || rawArgs is JSONObject) {
            return false
        }
        val rawStr = rawArgs.toString()
        if (rawStr.isBlank() || rawStr.trim().equals("null", ignoreCase = true)) return false
        val cleaned = unwrapMarkdownAndEscapedString(rawStr)
        if (cleaned.isBlank() || cleaned == "{}" || cleaned.equals("null", ignoreCase = true)) return false
        val candidate = extractBracedJson(cleaned) ?: return true
        return runCatching {
            JSONObject(candidate)
            false
        }.getOrDefault(true)
    }

    fun normalizeOpenAiToolCall(
        tc: GroqToolCall,
        index: Int,
        providerName: String,
        mapAdapter: JsonAdapter<Map<String, Any?>>? = null,
        vararg secretKeys: String
    ): NormalizedToolCall? {
        val rawCallJson = buildOpenAiRawPreview(tc)
        val sanitizedRaw = SensitiveDataSanitizer.sanitizeToolCallPayload(rawCallJson, *secretKeys)

        var rawName = cleanString(tc.function?.name) ?: cleanString(tc.name)
        var rawArgs: Any? = tc.function?.arguments
            ?: tc.function?.parameters
            ?: tc.function?.args
            ?: tc.arguments
            ?: tc.args

        // Unwrap nested {"function": {"name", "arguments"}} or {"name", "arguments"} if present inside arguments
        if (rawName.isNullOrBlank()) {
            val nested = parseAnyToRawMap(rawArgs, mapAdapter).first
            val nestedFn = nested["function"] as? Map<*, *>
            rawName = cleanString(nestedFn?.get("name")) ?: cleanString(nested["name"])
            if (nestedFn != null) {
                rawArgs = nestedFn["arguments"] ?: nestedFn["parameters"] ?: nestedFn["args"] ?: rawArgs
            } else if (nested.containsKey("arguments") || nested.containsKey("args") || nested.containsKey("parameters")) {
                rawArgs = nested["arguments"] ?: nested["args"] ?: nested["parameters"]
            }
        }

        val canonicalName = ToolDefinitions.canonicalToolName(rawName.orEmpty())
        if (canonicalName.isBlank()) return null

        // Check if arguments itself wrapped {"function":{"name":...,"arguments":{...}}}
        val unwrappedArgs = unwrapNestedFunctionArgs(rawArgs, mapAdapter)
        val malformed = hasMalformedArgsPayload(unwrappedArgs)
        val (rawMap, parseFailed) = parseAnyToRawMap(unwrappedArgs, mapAdapter)
        val normalizedArgs = canonicalizeArgsForTool(canonicalName, rawMap)
        val missing = if (malformed || parseFailed) {
            ToolDefinitions.validateRequiredArgs(canonicalName, normalizedArgs)
        } else {
            ToolDefinitions.validateRequiredArgs(canonicalName, normalizedArgs)
        }

        val id = cleanString(tc.id) ?: "${providerName.lowercase()}_call_$index"
        return NormalizedToolCall(
            id = id,
            name = canonicalName,
            args = normalizedArgs,
            missingRequired = missing,
            isMalformedJson = malformed || parseFailed,
            rawCallSanitized = sanitizedRaw
        )
    }

    fun normalizeGeminiFunctionCall(
        fc: GeminiFunctionCall,
        index: Int,
        mapAdapter: JsonAdapter<Map<String, Any?>>? = null,
        vararg secretKeys: String
    ): NormalizedToolCall? {
        val canonicalName = ToolDefinitions.canonicalToolName(fc.name)
        if (canonicalName.isBlank()) return null

        val rawPreview = JSONObject().apply {
            put("functionCall", JSONObject().apply {
                put("name", fc.name)
                put("args", anyToJsonValue(fc.args))
            })
        }.toString()
        val sanitizedRaw = SensitiveDataSanitizer.sanitizeToolCallPayload(rawPreview, *secretKeys)

        val unwrappedArgs = unwrapNestedFunctionArgs(fc.args, mapAdapter)
        val malformed = hasMalformedArgsPayload(unwrappedArgs)
        val (rawMap, parseFailed) = parseAnyToRawMap(unwrappedArgs, mapAdapter)
        val normalizedArgs = canonicalizeArgsForTool(canonicalName, rawMap)
        val missing = ToolDefinitions.validateRequiredArgs(canonicalName, normalizedArgs)

        return NormalizedToolCall(
            id = "gem_call_$index",
            name = canonicalName,
            args = normalizedArgs,
            missingRequired = missing,
            isMalformedJson = malformed || parseFailed,
            rawCallSanitized = sanitizedRaw
        )
    }

    /**
     * Universal entry point that accepts ANY raw tool-call JSON / Map / JSONObject across providers:
     * - OpenAI/Mercury/Groq: {"id":...,"type":"function","function":{"name":...,"arguments":...}} (string or object)
     * - Nested function wrapper: {"function":{"name":...,"arguments":...}}
     * - Gemini functionCall wrapper: {"functionCall":{"name":...,"args":...}}
     * - Flat inline call: {"name":...,"arguments"/"args"/"parameters":...}
     */
    fun normalizeFromRaw(
        rawPayload: Any?,
        defaultId: String = "call_0",
        mapAdapter: JsonAdapter<Map<String, Any?>>? = null,
        vararg secretKeys: String
    ): NormalizedToolCall? {
        if (rawPayload == null || rawPayload == JSONObject.NULL) return null
        val (rootMap, rootMalformed) = parseAnyToRawMap(rawPayload, mapAdapter)
        if (rootMalformed && rootMap.isEmpty()) return null

        val rawPreview = when (rawPayload) {
            is String -> rawPayload
            else -> runCatching { anyToJsonValue(rawPayload).toString() }.getOrDefault(rawPayload.toString())
        }
        val sanitizedRaw = SensitiveDataSanitizer.sanitizeToolCallPayload(rawPreview, *secretKeys)

        val fnObj = rootMap["function"] as? Map<*, *>
        val fcObj = rootMap["functionCall"] as? Map<*, *>

        val rawId = cleanString(rootMap["id"]) ?: defaultId
        val rawName = when {
            fnObj != null -> cleanString(fnObj["name"]) ?: cleanString(rootMap["name"])
            fcObj != null -> cleanString(fcObj["name"]) ?: cleanString(rootMap["name"])
            else -> cleanString(rootMap["name"])
        } ?: return null

        val canonicalName = ToolDefinitions.canonicalToolName(rawName)
        if (canonicalName.isBlank()) return null

        val argsCandidate: Any? = when {
            fnObj != null -> fnObj["arguments"] ?: fnObj["parameters"] ?: fnObj["args"]
            fcObj != null -> fcObj["args"] ?: fcObj["arguments"] ?: fcObj["parameters"]
            rootMap.containsKey("arguments") -> rootMap["arguments"]
            rootMap.containsKey("args") -> rootMap["args"]
            rootMap.containsKey("parameters") -> rootMap["parameters"]
            else -> emptyMap<String, Any?>()
        }

        val unwrapped = unwrapNestedFunctionArgs(argsCandidate, mapAdapter)
        val malformed = hasMalformedArgsPayload(unwrapped)
        val (rawArgsMap, argsParseFailed) = parseAnyToRawMap(unwrapped, mapAdapter)
        val normalizedArgs = canonicalizeArgsForTool(canonicalName, rawArgsMap)
        val missing = ToolDefinitions.validateRequiredArgs(canonicalName, normalizedArgs)

        return NormalizedToolCall(
            id = rawId,
            name = canonicalName,
            args = normalizedArgs,
            missingRequired = missing,
            isMalformedJson = malformed || argsParseFailed,
            rawCallSanitized = sanitizedRaw
        )
    }

    fun parseArgsMapSafely(
        rawArgs: Any?,
        toolName: String? = null,
        mapAdapter: JsonAdapter<Map<String, Any?>>? = null
    ): Map<String, Any?> {
        val unwrapped = unwrapNestedFunctionArgs(rawArgs, mapAdapter)
        val (rawMap, _) = parseAnyToRawMap(unwrapped, mapAdapter)
        return if (!toolName.isNullOrBlank()) {
            canonicalizeArgsForTool(ToolDefinitions.canonicalToolName(toolName), rawMap)
        } else {
            stripNullEntries(rawMap)
        }
    }

    fun canonicalizeArgsForTool(toolName: String, rawMap: Map<String, Any?>): Map<String, Any?> {
        val cleaned = stripNullEntries(rawMap)
        val out = linkedMapOf<String, Any?>()
        out.putAll(cleaned)

        fun pickFirst(vararg keys: String): Any? {
            for (k in keys) {
                val foundKey = out.keys.firstOrNull { it.equals(k, ignoreCase = true) } ?: continue
                val v = out[foundKey]
                if (!isNullOrLiteralNull(v)) return v
            }
            return null
        }

        fun mapAlias(canonicalKey: String, vararg aliases: String) {
            if (!isNullOrLiteralNull(out[canonicalKey])) return
            val valFound = pickFirst(*aliases) ?: return
            out[canonicalKey] = valFound
        }

        fun mapBoolAlias(canonicalKey: String, vararg aliases: String) {
            val current = out[canonicalKey]
            val candidate = if (!isNullOrLiteralNull(current)) current else pickFirst(*aliases)
            if (candidate == null) return
            when (candidate) {
                is Boolean -> out[canonicalKey] = candidate
                is Number -> out[canonicalKey] = candidate.toInt() != 0
                is String -> {
                    val s = candidate.trim().lowercase()
                    when (s) {
                        "true", "1", "on", "yes", "enable", "enabled" -> out[canonicalKey] = true
                        "false", "0", "off", "no", "disable", "disabled" -> out[canonicalKey] = false
                    }
                }
            }
        }

        when (toolName) {
            "open_app" -> {
                mapAlias("app_name", "app", "appName", "package", "package_name", "packageName", "name", "target_app", "application", "app_label")
                mapBoolAlias("from_background", "fromBackground", "background")
            }
            "search_in_app" -> {
                mapAlias("app_name", "app", "appName", "package", "package_name", "packageName", "name", "target_app", "application", "app_label")
                mapAlias("query", "q", "text", "search_query", "searchQuery", "song", "video", "keyword", "term", "topic", "item")
                mapBoolAlias("auto_play", "autoPlay", "autoplay", "play_first", "play")
            }
            "search_web" -> {
                mapAlias("query", "q", "text", "search_query", "searchQuery", "keyword", "topic")
            }
            "call_contact" -> {
                mapAlias("target", "name", "contact", "contact_name", "contactName", "phone", "phone_number", "phoneNumber", "number", "recipient")
            }
            "send_sms", "send_whatsapp" -> {
                mapAlias("recipient", "to", "name", "contact", "contact_name", "contactName", "phone", "phone_number", "number", "target")
                mapAlias("message", "msg", "text", "body", "sms_body", "content")
            }
            "reply_notification" -> {
                mapAlias("key", "notification_key", "id")
                mapAlias("message", "msg", "text", "reply", "body", "content")
            }
            "set_alarm" -> {
                mapAlias("hour", "hr", "hours")
                mapAlias("minute", "min", "minutes")
                mapAlias("label", "title", "name", "message")
                if (isNullOrLiteralNull(out["hour"]) || isNullOrLiteralNull(out["minute"])) {
                    val timeStr = cleanString(pickFirst("time", "alarm_time"))
                    val m = timeStr?.let { Regex("""^(\d{1,2}):(\d{2})$""").find(it) }
                    if (m != null) {
                        out.putIfAbsent("hour", m.groupValues[1].toIntOrNull())
                        out.putIfAbsent("minute", m.groupValues[2].toIntOrNull())
                    }
                }
            }
            "set_timer" -> {
                mapAlias("seconds", "duration", "duration_seconds", "sec", "secs", "time_seconds")
                if (isNullOrLiteralNull(out["seconds"])) {
                    val mins = (pickFirst("minutes", "min") as? Number)?.toInt()
                        ?: cleanString(pickFirst("minutes", "min"))?.toIntOrNull()
                    if (mins != null && mins > 0) {
                        out["seconds"] = mins * 60
                    }
                }
                mapAlias("label", "title", "name")
            }
            "toggle_wifi", "toggle_bluetooth", "toggle_flashlight", "toggle_dnd" -> {
                mapBoolAlias("enable", "enabled", "state", "on", "value", "status", "turn_on")
            }
            "set_volume" -> {
                mapAlias("stream", "type", "channel", "volume_type")
                mapAlias("level_percent", "level", "percent", "percentage", "volume", "value")
            }
            "set_brightness" -> {
                mapAlias("level_percent", "level", "percent", "percentage", "brightness", "value")
            }
            "media_control" -> {
                mapAlias("action", "command", "control", "cmd", "state")
            }
            "type_text" -> {
                mapAlias("text", "value", "input", "content", "message")
            }
            "scroll" -> {
                mapAlias("direction", "dir")
            }
            "speak" -> {
                mapAlias("text", "message", "msg", "speech")
                mapAlias("language", "lang")
            }
            "read_notifications" -> {
                mapAlias("filter_app", "app", "app_name", "sender")
            }
            "summarize_notifications" -> {
                mapAlias("scope", "filter", "app", "app_name")
                mapBoolAlias("speak", "read_aloud")
            }
            "save_memory" -> {
                mapAlias("type", "category")
                mapAlias("key", "name", "nickname")
                mapAlias("value", "details", "info", "text")
            }
            "recall_memory" -> {
                mapAlias("query", "q", "text", "keyword", "key")
            }
            "delete_memory" -> {
                mapAlias("key", "query", "name")
            }
            "set_reminder" -> {
                mapAlias("title", "text", "message", "reminder", "name")
                mapAlias("time_expression", "time")
                mapAlias("place_label", "place")
            }
            "delete_reminder" -> {
                mapAlias("query", "q", "id", "reminder_id", "title")
            }
            "snooze_reminder" -> {
                mapAlias("reminder_id", "id")
            }
            "save_place" -> {
                mapAlias("label", "name", "place", "place_label")
            }
            "run_routine", "run_command", "run_macro" -> {
                mapAlias("trigger_phrase", "phrase", "name", "command", "macro", "routine", "query")
            }
            "delete_routine" -> {
                mapAlias("routine_name_or_phrase", "name", "trigger_phrase", "phrase", "query")
            }
            "delete_command" -> {
                mapAlias("command_name_or_phrase", "name", "trigger_phrase", "phrase", "query")
            }
            "delete_macro" -> {
                mapAlias("macro_name_or_phrase", "name", "trigger_phrase", "phrase", "query")
            }
            "stop_macro_recording" -> {
                mapAlias("name", "macro_name")
                mapAlias("trigger_phrase", "phrase")
            }
            "set_auto_reply" -> {
                mapAlias("mode", "status")
                mapBoolAlias("on_off", "enable", "enabled")
            }
            "set_auto_reply_message" -> {
                mapAlias("mode")
                mapAlias("text", "message", "msg")
            }
            "explain_screen", "read_clipboard_and_process" -> {
                mapAlias("mode", "action")
                mapAlias("target_language", "language", "lang")
            }
            "capture_and_describe" -> {
                mapAlias("question", "query", "q", "prompt", "text")
            }
        }

        // Re-strip any aliases whose values happened to be empty/"null"
        return stripNullEntries(out)
    }

    private fun unwrapNestedFunctionArgs(
        rawArgs: Any?,
        mapAdapter: JsonAdapter<Map<String, Any?>>?
    ): Any? {
        val (asMap, failed) = parseAnyToRawMap(rawArgs, mapAdapter)
        if (failed || asMap.isEmpty()) return rawArgs
        val fn = asMap["function"] as? Map<*, *>
        if (fn != null && (fn.containsKey("arguments") || fn.containsKey("parameters") || fn.containsKey("args"))) {
            return fn["arguments"] ?: fn["parameters"] ?: fn["args"]
        }
        if (asMap.containsKey("name") &&
            (asMap.containsKey("arguments") || asMap.containsKey("parameters") || asMap.containsKey("args"))
        ) {
            return asMap["arguments"] ?: asMap["parameters"] ?: asMap["args"]
        }
        return rawArgs
    }

    private fun parseAnyToRawMap(
        raw: Any?,
        mapAdapter: JsonAdapter<Map<String, Any?>>?
    ): Pair<Map<String, Any?>, Boolean> {
        if (raw == null || raw == JSONObject.NULL) return emptyMap<String, Any?>() to false
        return when (raw) {
            is Map<*, *> -> {
                val out = linkedMapOf<String, Any?>()
                raw.forEach { (k, v) ->
                    val key = k?.toString()?.trim()
                    if (!key.isNullOrBlank()) out[key] = normalizeJsonElement(v)
                }
                out to false
            }
            is JSONObject -> jsonObjectToMap(raw) to false
            is String -> {
                val rawStr = raw.trim()
                if (rawStr.isBlank() || rawStr.equals("null", ignoreCase = true)) {
                    emptyMap<String, Any?>() to false
                } else {
                    val cleaned = unwrapMarkdownAndEscapedString(rawStr)
                    if (cleaned.isBlank() || cleaned == "{}" || cleaned.equals("null", ignoreCase = true)) {
                        emptyMap<String, Any?>() to false
                    } else {
                        val candidate = extractBracedJson(cleaned)
                        if (candidate == null) {
                            emptyMap<String, Any?>() to true
                        } else {
                            val viaMoshi = if (mapAdapter != null) {
                                runCatching { mapAdapter.fromJson(candidate) }.getOrNull()
                            } else null
                            if (viaMoshi != null) {
                                viaMoshi to false
                            } else {
                                runCatching {
                                    jsonObjectToMap(JSONObject(candidate)) to false
                                }.getOrDefault(emptyMap<String, Any?>() to true)
                            }
                        }
                    }
                }
            }
            else -> emptyMap<String, Any?>() to false
        }
    }

    private fun stripNullEntries(source: Map<String, Any?>): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        source.forEach { (rawKey, rawVal) ->
            val key = rawKey.trim()
            if (key.isNotEmpty() && !isNullOrLiteralNull(rawVal)) {
                out[key] = when (rawVal) {
                    is String -> rawVal.trim()
                    else -> rawVal
                }
            }
        }
        return out
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = normalizeJsonElement(obj.opt(k))
        }
        return map
    }

    private fun normalizeJsonElement(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(v)
        is JSONArray -> (0 until v.length()).mapNotNull { i -> normalizeJsonElement(v.opt(i)) }
        else -> v
    }

    private fun anyToJsonValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is Boolean, is Number, is String -> v
        is Map<*, *> -> JSONObject().apply {
            v.forEach { (k, value) ->
                if (k != null) put(k.toString(), anyToJsonValue(value))
            }
        }
        is Collection<*> -> JSONArray().apply {
            v.forEach { put(anyToJsonValue(it)) }
        }
        else -> v.toString()
    }

    private fun buildOpenAiRawPreview(tc: GroqToolCall): String {
        val obj = JSONObject()
        tc.id?.let { obj.put("id", it) }
        tc.type?.let { obj.put("type", it) }
        if (tc.function != null) {
            val fn = JSONObject()
            tc.function.name?.let { fn.put("name", it) }
            when {
                tc.function.arguments != null -> fn.put("arguments", anyToJsonValue(tc.function.arguments))
                tc.function.parameters != null -> fn.put("parameters", anyToJsonValue(tc.function.parameters))
                tc.function.args != null -> fn.put("args", anyToJsonValue(tc.function.args))
            }
            obj.put("function", fn)
        } else {
            tc.name?.let { obj.put("name", it) }
            tc.arguments?.let { obj.put("arguments", anyToJsonValue(it)) }
            tc.args?.let { obj.put("args", anyToJsonValue(it)) }
        }
        return obj.toString()
    }

    internal fun unwrapMarkdownAndEscapedString(rawJson: String): String {
        var cleaned = rawJson.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length >= 2) {
            val unwrapped = runCatching {
                val parsed = JSONObject("{\"v\":$cleaned}")
                val raw = parsed.opt("v")
                if (raw == JSONObject.NULL) "" else raw?.toString().orEmpty()
            }.getOrNull()
            if (!unwrapped.isNullOrBlank() && !unwrapped.equals("null", ignoreCase = true)) {
                cleaned = unwrapped.trim()
            }
        }
        return cleaned
    }

    internal fun extractBracedJson(cleaned: String): String? {
        if (cleaned.startsWith("{")) return cleaned
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else null
    }
}
