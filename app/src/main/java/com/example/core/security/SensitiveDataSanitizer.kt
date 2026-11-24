package com.example.core.security

/**
 * Strips or masks API keys, bearer tokens, OTPs, passwords, PINs, card numbers,
 * and bank account/IFSC/IBAN strings before any text leaves the device to remote
 * AI endpoints or gets persisted into Action Logs / Crash / Export text.
 */
object SensitiveDataSanitizer {

    private val GEMINI_KEY_REGEX = Regex("""AIza[0-9A-Za-z\-_]{20,}""")
    private val GROQ_OR_OPENAI_KEY_REGEX = Regex("""\b(?:gsk[_-]|sk-|inc[_-])[0-9A-Za-z\-_]{14,}\b""")
    private val BEARER_TOKEN_REGEX = Regex("""(?i)\bBearer\s+[A-Za-z0-9\-._~+/]+=*""")

    private val OTP_AFTER_LABEL_REGEX = Regex(
        """(?i)\b(otp|one[\s-]?time[\s-]?password|verification\s*code|passcode|mpin|upi\s*pin|atm\s*pin|cvv|security\s*code)\b([^0-9\r\n]{0,24})(\d{3,8})\b"""
    )
    private val OTP_BEFORE_LABEL_REGEX = Regex(
        """(?i)\b(\d{4,8})(\s+(?:is\s+(?:your\s+)?)?(?:otp|one[\s-]?time[\s-]?password|verification\s*code|passcode|mpin|pin))\b"""
    )
    private val PASSWORD_KEY_VALUE_REGEX = Regex(
        """(?i)\b(password|passwd|pwd|passphrase|secret[\s_-]?key|api[\s_-]?key|access[\s_-]?token|voice[\s_-]?pin)\b(\s*[:=]\s*|\s+is\s+)([^\s,;]+)"""
    )
    private val CARD_NUMBER_REGEX = Regex("""\b\d(?:[ -]?\d){12,18}\b""")
    private val BANK_ACCOUNT_REGEX = Regex(
        """(?i)\b(a/c|acct|account(?:\s*(?:no\.?|number|#))?|iban|ifsc)\b([^0-9A-Za-z\r\n]{0,16})([0-9X*]{6,22}|[A-Za-z]{4}0[A-Za-z0-9]{6})\b"""
    )
    private val REDACTED_TAG_REGEX = Regex("""\[REDACTED_[A-Z_]+\]|\[OTP/Bank[^\]]*]|\[AMOUNT]|\[\*{4}]""")

    fun sanitizeForRemoteAi(text: String): String {
        if (text.isBlank()) return text
        var out = maskSecretsAndPii(text)
        out = BANK_ACCOUNT_REGEX.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}[REDACTED_ACCOUNT]"
        }
        return out
    }

    fun sanitizeForLogs(text: String): String {
        if (text.isBlank()) return text
        var out = maskSecretsAndPii(text)
        out = BANK_ACCOUNT_REGEX.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}[REDACTED_ACCOUNT]"
        }
        return out
    }

    fun sanitizeError(message: String?, vararg secretKeys: String): String {
        var out = message.orEmpty().ifBlank { "Unknown error" }
        secretKeys.filter { it.isNotBlank() }.forEach { key ->
            out = out.replace(key.trim(), "[REDACTED_KEY]")
        }
        return maskSecretsAndPii(out)
    }

    private val TOOL_CONTENT_FIELD_JSON_REGEX = Regex(
        """(?i)("?(?:message|msg|text|body|sms_body|reply|content|password|passcode|pin|voice_pin|token|secret|api_key|apikey)"?\s*:\s*)("(?:\\.|[^"\\])*"|[^,\}\]]+)"""
    )
    private val TOOL_ESCAPED_CONTENT_FIELD_REGEX = Regex(
        """(?i)(\\?"(?:message|msg|text|body|sms_body|reply|content|password|passcode|pin|voice_pin|token|secret|api_key|apikey)\\?"\s*:\s*)(\\?"(?:\\\\.|\\"|[^"\\])*\\?")"""
    )

    fun sanitizeToolCallPayload(rawPayload: String?, vararg secretKeys: String): String {
        if (rawPayload.isNullOrBlank()) return "{}"
        var out = sanitizeError(rawPayload, *secretKeys)
        out = TOOL_ESCAPED_CONTENT_FIELD_REGEX.replace(out) { m ->
            "${m.groupValues[1]}\"[MASKED_CONTENT]\""
        }
        out = TOOL_CONTENT_FIELD_JSON_REGEX.replace(out) { m ->
            "${m.groupValues[1]}\"[MASKED_CONTENT]\""
        }
        return out
    }

    fun containsOnlyRedactedContent(sanitizedText: String): Boolean {
        val remaining = REDACTED_TAG_REGEX.replace(sanitizedText, "")
            .replace(Regex("""[\s:;,.\-_/|()\[\]{}]+"""), "")
        return remaining.isEmpty()
    }

    private fun maskSecretsAndPii(input: String): String {
        var out = GEMINI_KEY_REGEX.replace(input, "[REDACTED_KEY]")
        out = GROQ_OR_OPENAI_KEY_REGEX.replace(out, "[REDACTED_KEY]")
        out = BEARER_TOKEN_REGEX.replace(out, "Bearer [REDACTED_TOKEN]")
        out = PASSWORD_KEY_VALUE_REGEX.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}[REDACTED_SECRET]"
        }
        out = CARD_NUMBER_REGEX.replace(out) { m ->
            val digitsOnly = m.value.count { it.isDigit() }
            if (digitsOnly in 13..19) "[REDACTED_CARD]" else m.value
        }
        out = OTP_AFTER_LABEL_REGEX.replace(out) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}[REDACTED_OTP]"
        }
        out = OTP_BEFORE_LABEL_REGEX.replace(out) { m ->
            "[REDACTED_OTP]${m.groupValues[2]}"
        }
        return out
    }
}
