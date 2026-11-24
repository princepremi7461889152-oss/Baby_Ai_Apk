package com.example.features.calls

data class ScamAnalysisResult(
    val isSuspicious: Boolean,
    val score: Int,
    val reasons: List<String>,
    val summary: String
)

/**
 * Pure Kotlin spam / scam classifier for incoming call transcripts and caller labels.
 * Evaluates common KYC, OTP, UPI, courier, loan, and "digital arrest" scam signals in Hindi/Hinglish/English.
 */
object ScamMatcher {

    private data class Signal(
        val weight: Int,
        val reason: String,
        val keywords: List<String>
    )

    private val SIGNALS = listOf(
        Signal(
            weight = 45,
            reason = "OTP / PIN / CVV manga gaya",
            keywords = listOf("otp", "cvv", "atm pin", "upi pin", "mpin", "security code", "verification code", "one time password")
        ),
        Signal(
            weight = 40,
            reason = "Bank account / KYC / SIM block dhamki",
            keywords = listOf("kyc", "account block", "account freeze", "account suspend", "pan update", "aadhaar update", "sim block", "card block", "deactivated")
        ),
        Signal(
            weight = 45,
            reason = "Fake Police / CBI / Digital Arrest threat",
            keywords = listOf("digital arrest", "customs", "fedex", "narcotics", "police case", "arrest warrant", "cbi", "trai", "illegal parcel")
        ),
        Signal(
            weight = 40,
            reason = "Remote screen-share / APK install pressure",
            keywords = listOf("anydesk", "teamviewer", "quicksupport", "rustdesk", "screen share", "apk download", "install app")
        ),
        Signal(
            weight = 35,
            reason = "Lottery / Prize / Cashback / Refund trap",
            keywords = listOf("lottery", "lucky draw", "won prize", "kbc", "jackpot", "claim reward", "cashback claim", "refund link", "upi collect")
        ),
        Signal(
            weight = 25,
            reason = "Urgent payment / loan / utility disconnect",
            keywords = listOf("electricity disconnect", "bijli", "credit card limit", "instant loan", "pre-approved loan", "pay immediately", "abhi transfer")
        ),
        Signal(
            weight = 20,
            reason = "Telemarketer / Promo pattern",
            keywords = listOf("telemarketing", "insurance offer", "real estate", "trading tip", "stock tip", "crypto double")
        )
    )

    fun analyze(transcriptOrLabel: String): ScamAnalysisResult {
        val text = transcriptOrLabel.trim()
        if (text.isBlank()) {
            return ScamAnalysisResult(
                isSuspicious = false,
                score = 0,
                reasons = emptyList(),
                summary = "Koi scam signal nahi mila."
            )
        }
        val lower = text.lowercase()
        var score = 0
        val reasons = mutableListOf<String>()
        for (signal in SIGNALS) {
            if (signal.keywords.any { lower.contains(it) }) {
                score += signal.weight
                reasons.add(signal.reason)
            }
        }
        val bounded = score.coerceIn(0, 100)
        val suspicious = bounded >= 40
        val summary = when {
            bounded >= 70 -> "High Scam Risk ($bounded/100): ${reasons.joinToString("; ")}"
            suspicious -> "Suspicious Call ($bounded/100): ${reasons.joinToString("; ")}"
            reasons.isNotEmpty() -> "Low Caution ($bounded/100): ${reasons.joinToString("; ")}"
            else -> "Normal / Low Risk ($bounded/100)"
        }
        return ScamAnalysisResult(
            isSuspicious = suspicious,
            score = bounded,
            reasons = reasons,
            summary = summary
        )
    }
}
