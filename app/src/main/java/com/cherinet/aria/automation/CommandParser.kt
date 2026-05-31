package com.cherinet.aria.automation

/**
 * CommandParser: ARIA's sentence splitter.
 *
 * When you say "Open YouTube and set alarm for 8am and call mom",
 * this class breaks it into 3 separate tasks:
 *   Task 1: "Open YouTube"
 *   Task 2: "Set alarm for 8am"
 *   Task 3: "Call mom"
 *
 * Each task then gets handled independently by ChatActivity.
 * This is how real voice assistants work —
 * Siri and Google Assistant both parse compound commands
 * into individual tasks before executing them.
 *
 * The key insight: split BEFORE executing, not during.
 */
object CommandParser {

    // Words that connect multiple commands in one sentence
    private val SEPARATORS = listOf(
        " and then ",
        " and also ",
        " then ",
        " also ",
        " and "
    )

    // Keywords that indicate the START of a new distinct command
    // If a segment starts with one of these, it's its own task
    private val COMMAND_STARTERS = listOf(
        "open ", "launch ", "start ",
        "call ", "phone ", "dial ",
        "set alarm", "alarm for", "wake me",
        "set timer", "timer for",
        "send message", "text ", "sms ",
        "search for", "search ", "google ",
        "play ", "pause ", "stop ",
        "turn on", "turn off",
        "open settings", "settings"
    )

    /**
     * Check if a message contains multiple commands.
     *
     * Examples that return true:
     * - "Open YouTube and set alarm for 8am"
     * - "Call mom and then open WhatsApp"
     * - "Set alarm for 7am and also search weather"
     *
     * Examples that return false (single command):
     * - "Open YouTube"
     * - "What is the capital of Ethiopia?"
     * - "Call mom"
     */
    fun isMultiCommand(input: String): Boolean {
        val lower = input.lowercase()
        for (sep in SEPARATORS) {
            if (lower.contains(sep)) {
                val parts = splitBySeparator(lower, sep)
                // Only count as multi-command if at least 2 parts
                // look like real commands
                val commandParts = parts.filter { looksLikeCommand(it.trim()) }
                if (commandParts.size >= 2) return true
            }
        }
        return false
    }

    /**
     * Split a multi-command message into individual task strings.
     *
     * Input:  "Open YouTube and set alarm for 8am and call mom"
     * Output: ["Open YouTube", "set alarm for 8am", "call mom"]
     *
     * We try separators from most specific to least specific
     * so "and then" is matched before plain "and".
     */
    fun split(input: String): List<String> {
        val lower = input.lowercase()

        for (sep in SEPARATORS) {
            if (lower.contains(sep)) {
                val parts = lower.split(sep).map { it.trim() }.filter { it.isNotBlank() }
                if (parts.size >= 2 && parts.all { looksLikeCommand(it) || it.length > 2 }) {
                    // Reconstruct with original casing where possible
                    return parts.map { part -> reconstructCase(part, input) }
                }
            }
        }

        return listOf(input)
    }

    /**
     * Does this string look like an actionable command
     * rather than just a continuation of a sentence?
     *
     * "Open YouTube" → true (starts with "open")
     * "Set alarm for 8am" → true (starts with "set alarm")
     * "The one I told you about" → false (continuation phrase)
     */
    private fun looksLikeCommand(text: String): Boolean {
        val lower = text.lowercase().trim()
        return COMMAND_STARTERS.any { lower.startsWith(it) }
    }

    /**
     * Split by separator and preserve case.
     */
    private fun splitBySeparator(lower: String, sep: String): List<String> {
        return lower.split(sep).map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * Try to reconstruct original casing from the lowercased part.
     * If we can't find it, just capitalize first letter.
     */
    private fun reconstructCase(lowPart: String, original: String): String {
        val idx = original.lowercase().indexOf(lowPart)
        return if (idx >= 0) {
            original.substring(idx, minOf(idx + lowPart.length, original.length))
        } else {
            lowPart.replaceFirstChar { it.uppercase() }
        }
    }
}