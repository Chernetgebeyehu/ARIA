package com.cherinet.aria.ai

/**
 * SecurityAnalyzer: ARIA's built-in lie detector.
 *
 * This runs completely OFFLINE — no internet needed.
 * It's like a trained guard dog that knows what danger smells like.
 *
 * When a notification comes in, this checks:
 * - Does it use scam phrases? ("You won a prize!")
 * - Does it have suspicious links?
 * - Does it try to create panic? ("Act NOW or lose everything!")
 */
class SecurityAnalyzer {

    data class SecurityResult(
        val riskLevel: RiskLevel,
        val warnings: List<String>,
        val isSafe: Boolean
    )

    enum class RiskLevel { SAFE, LOW, MEDIUM, HIGH, CRITICAL }

    private val suspiciousKeywords = listOf(
        "verify your account", "confirm your identity",
        "click here immediately", "your account will be suspended",
        "act now", "limited time", "you have won",
        "congratulations you've been selected",
        "send your password", "social security number",
        "bank account details", "wire transfer",
        "nigerian prince", "inheritance fund",
        "urgent action required", "your account has been compromised",
        "reset your password immediately",
        "enter your credit card", "free gift card",
        "you are the lucky winner"
    )

    private val suspiciousDomainPatterns = listOf(
        "bit\\.ly", "tinyurl", "t\\.co",
        ".*login.*\\.(?!com|org|gov).*",
        ".*paypal.*\\.(?!com).*",
        ".*google.*\\.(?!com|co\\.).*",
        ".*apple.*\\.(?!com).*",
        ".*microsoft.*\\.(?!com).*",
        ".*amazon.*\\.(?!com|co\\.).*",
        "\\.ru$", "\\.cn$", "\\.tk$", "\\.xyz$"
    )

    private val urlRegex = Regex(
        """https?://[^\s<>"{}|\\^`\[\]]+""",
        RegexOption.IGNORE_CASE
    )

    fun analyzeText(text: String): SecurityResult {
        val warnings = mutableListOf<String>()
        val lowerText = text.lowercase()

        for (keyword in suspiciousKeywords) {
            if (lowerText.contains(keyword)) {
                warnings.add("🚨 Suspicious phrase detected: \"$keyword\"")
            }
        }

        val urls = urlRegex.findAll(text)
        for (urlMatch in urls) {
            val url = urlMatch.value.lowercase()
            warnings.addAll(analyzeUrl(url))
        }

        val urgencyWords = listOf("urgent", "immediately", "right now", "expires", "last chance", "hurry")
        val urgencyCount = urgencyWords.count { lowerText.contains(it) }
        if (urgencyCount >= 2) {
            warnings.add("⚠️ Multiple urgency tactics detected — common in scam messages")
        }

        val infoRequests = listOf("password", "ssn", "social security", "credit card", "bank account", "pin number", "routing number", "cvv")
        for (term in infoRequests) {
            if (lowerText.contains(term)) {
                warnings.add("🚨 Requests sensitive information: \"$term\"")
            }
        }

        val riskLevel = when {
            warnings.any { it.startsWith("🚨") } -> RiskLevel.HIGH
            warnings.size >= 3 -> RiskLevel.HIGH
            warnings.size >= 2 -> RiskLevel.MEDIUM
            warnings.size == 1 -> RiskLevel.LOW
            else -> RiskLevel.SAFE
        }

        return SecurityResult(riskLevel = riskLevel, warnings = warnings, isSafe = riskLevel == RiskLevel.SAFE)
    }

    private fun analyzeUrl(url: String): List<String> {
        val warnings = mutableListOf<String>()

        if (Regex("""https?://\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""").containsMatchIn(url)) {
            warnings.add("🚨 URL uses IP address instead of domain — likely phishing: $url")
        }

        for (pattern in suspiciousDomainPatterns) {
            if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                warnings.add("⚠️ Suspicious URL pattern detected: $url")
                break
            }
        }

        val domainPart = url.removePrefix("https://").removePrefix("http://").split("/")[0]
        if (domainPart.count { it == '.' } > 3) {
            warnings.add("⚠️ URL has many subdomains — possible spoofing: $url")
        }

        return warnings
    }

    fun getQuickVerdict(result: SecurityResult): String {
        return when (result.riskLevel) {
            RiskLevel.SAFE -> "✅ This appears safe. No threats detected."
            RiskLevel.LOW -> "⚠️ Minor concern detected. Proceed with caution."
            RiskLevel.MEDIUM -> "⚠️ Several warning signs found. Be careful!"
            RiskLevel.HIGH -> "🚨 HIGH RISK! Multiple danger signs detected. Do NOT interact with this content!"
            RiskLevel.CRITICAL -> "🚨 CRITICAL THREAT! This is almost certainly a scam or phishing attempt!"
        }
    }
}