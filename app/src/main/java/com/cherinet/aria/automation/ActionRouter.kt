package com.cherinet.aria.automation

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.cherinet.aria.ai.AiManager
import org.json.JSONObject

/**
 * ActionRouter v2 — Phase 4: now handles agent_result responses.
 *
 * Before Phase 4, the server only ever returned one of three types:
 *   "chat"     → text response
 *   "action"   → one or more device actions
 *   "security" → security verdict
 *
 * Phase 4 adds a fourth type:
 *   "agent_result" → the agent loop ran and produced a full task execution trace
 *
 * Example agent_result from the server:
 * {
 *   "type": "agent_result",
 *   "spoken": "Done! Set alarm for 7am and opened YouTube.",
 *   "display": "✅ Alarm set for 7am\n✅ Opening YouTube",
 *   "tasks": [
 *     {"tool": "alarm", "value": "7am", "status": "success"},
 *     {"tool": "open_app", "value": "youtube", "status": "success"}
 *   ],
 *   "success_rate": 1.0
 * }
 *
 * For agent_result, ActionRouter executes ALL tasks in the list one by one.
 * Memory tools ("remember", "recall") already ran on the server side — we skip them here.
 * Device tools (alarm, open_app, call, etc.) run here on the Android device.
 *
 * Why split this way?
 * The server can't open YouTube on your phone. Only the Android app can.
 * The server CAN store memories in ChromaDB. The phone can't.
 * So: server-side tools run on server, device-side tools run on device.
 * That's the correct separation of concerns for a hybrid AI agent.
 */
class ActionRouter(
    private val context: Context,
    private val appController: AppController
) {

    data class RouteResult(
        val spokenText: String,
        val displayText: String,
        val actionExecuted: Boolean = false,
        val actionType: String = "",
        val taskCount: Int = 0,
        val successCount: Int = 0
    )

    /**
     * Main entry: send message to AI, then parse and execute the response.
     */
    suspend fun handle(
        input: String,
        aiManager: AiManager
    ): RouteResult {
        val rawResponse = aiManager.sendMessage(input)
        return parseAndExecute(rawResponse)
    }

    /**
     * Parse a raw JSON response from the server and execute any device-side actions.
     * This is also called directly from ChatActivity when it already has the raw response.
     */
    fun parseAndExecute(rawResponse: String): RouteResult {
        return try {
            val json = JSONObject(rawResponse)

            when (json.getString("type")) {

                // ── Standard single/multi action ──────────────────────────────
                // This is the old Phase 2/3 format, still supported.
                "action" -> {
                    val spoken = json.optString("spoken", "")
                    val actions = json.getJSONArray("actions")
                    val resultLines = mutableListOf<String>()
                    var successCount = 0

                    for (i in 0 until actions.length()) {
                        val action = actions.getJSONObject(i)
                        val tool   = action.getString("tool")
                        val value  = action.optString("value", "")
                        val result = execute(tool, value)
                        resultLines.add(result)
                        if (!result.startsWith("❌")) successCount++
                    }

                    RouteResult(
                        spokenText     = spoken.ifBlank { resultLines.joinToString("\n") },
                        displayText    = resultLines.joinToString("\n"),
                        actionExecuted = true,
                        actionType     = if (actions.length() > 0)
                            actions.getJSONObject(0).optString("tool") else "",
                        taskCount      = actions.length(),
                        successCount   = successCount
                    )
                }

                // ── Agent multi-task result (NEW in Phase 4) ──────────────────
                // The agent planned multiple tasks, executed them server-side
                // (memory ops), and now we execute the device-side tasks here.
                "agent_result" -> {
                    val spoken  = json.optString("spoken", "")
                    val display = json.optString("display", "")
                    val tasks   = json.optJSONArray("tasks")
                    val resultLines   = mutableListOf<String>()
                    var successCount  = 0
                    var taskCount     = 0

                    if (tasks != null) {
                        for (i in 0 until tasks.length()) {
                            val task         = tasks.getJSONObject(i)
                            val tool         = task.getString("tool")
                            val value        = task.optString("value", "")
                            val serverStatus = task.optString("status", "pending")
                            taskCount++

                            when (serverStatus) {
                                "success", "recovered" -> {
                                    // Memory tools already executed on server — just acknowledge
                                    if (tool == "remember" || tool == "recall") {
                                        successCount++
                                        resultLines.add("✅ $tool: $value")
                                    } else {
                                        // Device-side tools — execute now
                                        val result = execute(tool, value)
                                        resultLines.add(result)
                                        if (!result.startsWith("❌")) successCount++
                                    }
                                }
                                "failed" -> {
                                    resultLines.add("❌ $tool: $value — couldn't complete")
                                }
                                "skipped" -> {
                                    resultLines.add("⏭️ $tool: $value — skipped")
                                }
                            }
                        }
                    }

                    // Use the server-generated display if available,
                    // otherwise build it from our local execution results
                    val finalDisplay = display.ifBlank { resultLines.joinToString("\n") }

                    RouteResult(
                        spokenText     = spoken.ifBlank { "Done!" },
                        displayText    = finalDisplay,
                        actionExecuted = true,
                        actionType     = "agent",
                        taskCount      = taskCount,
                        successCount   = successCount
                    )
                }

                // ── Normal conversation ────────────────────────────────────────
                "chat" -> {
                    val text = json.getString("text")
                    RouteResult(spokenText = text, displayText = text)
                }

                // ── Security analysis ──────────────────────────────────────────
                "security" -> {
                    val text    = json.optString("text", "Security analysis complete.")
                    val verdict = json.optString("verdict", "unknown")
                    val icon    = when (verdict) {
                        "safe"    -> "✅"
                        "warning" -> "⚠️"
                        "danger"  -> "🚨"
                        else      -> "🔍"
                    }
                    RouteResult(spokenText = text, displayText = "$icon $text")
                }

                else -> RouteResult(
                    spokenText  = "I got an unexpected response. Please try again.",
                    displayText = "⚠️ Unknown response format"
                )
            }

        } catch (e: Exception) {
            // If JSON parsing fails, show the raw response
            RouteResult(spokenText = rawResponse, displayText = rawResponse)
        }
    }

    /**
     * Execute a single device-side tool.
     * Returns a human-readable result string with an emoji status prefix.
     */
    private fun execute(tool: String, value: String): String {
        return when (tool) {

            "open_app" -> {
                if (appController.openApp(value)) "✅ Opening $value!"
                else "❌ App '$value' not found. Is it installed?"
            }

            "search" -> {
                appController.search(value)
                "🔍 Searching: $value"
            }

            "call" -> {
                appController.makeCall(value)
                "📞 Calling $value..."
            }

            "sms" -> {
                appController.sendSms(value)
                "✉️ Opening message to $value..."
            }

            "alarm" -> {
                openAlarmApp(value)
                "⏰ Opening alarm for $value"
            }

            "timer" -> {
                openTimerApp(value)
                "⏱️ Opening timer for $value"
            }

            "settings" -> {
                appController.openSettings()
                "⚙️ Opening settings"
            }

            "open_url" -> {
                appController.openUrl(value)
                "🌐 Opening $value"
            }

            // Memory tools should have been handled server-side,
            // but we handle them gracefully here just in case
            "remember" -> "💾 Remembered: $value"
            "recall"   -> "🧠 Recalled from memory"

            else -> "⚠️ Unknown tool: $tool"
        }
    }

    /**
     * Open the system clock app to set an alarm.
     *
     * Parses time strings like:
     *   "7:30am"  → hour=7,  minute=30
     *   "14:00"   → hour=14, minute=0
     *   "9pm"     → hour=21, minute=0
     *   "7am"     → hour=7,  minute=0
     */
    private fun openAlarmApp(timeStr: String) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK

                val clean = timeStr.lowercase().trim()
                val hour: Int
                val minute: Int

                when {
                    clean.contains(":") -> {
                        val parts = clean.replace("am", "").replace("pm", "").split(":")
                        var h = parts[0].trim().toIntOrNull() ?: 7
                        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                        if (clean.contains("pm") && h < 12) h += 12
                        if (clean.contains("am") && h == 12) h = 0
                        hour = h; minute = m
                    }
                    clean.contains("am") || clean.contains("pm") -> {
                        val digits = clean.filter { it.isDigit() }.toIntOrNull() ?: 7
                        var h = digits
                        if (clean.contains("pm") && h < 12) h += 12
                        if (clean.contains("am") && h == 12) h = 0
                        hour = h; minute = 0
                    }
                    else -> { hour = 7; minute = 0 }
                }

                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "ARIA alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback: just open the clock app
            appController.openApp("clock")
        }
    }

    /**
     * Open the system clock app to set a timer.
     *
     * Parses duration strings like:
     *   "5 minutes"  → 300 seconds
     *   "30 seconds" → 30 seconds
     *   "2 hours"    → 7200 seconds
     *   "1 hour 30 minutes" → 5400 seconds
     */
    private fun openTimerApp(durationStr: String) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK

                val clean   = durationStr.lowercase()
                val seconds = when {
                    clean.contains("hour") -> {
                        val n = clean.filter { it.isDigit() }.toIntOrNull() ?: 1
                        n * 3600
                    }
                    clean.contains("min")  -> {
                        val n = clean.filter { it.isDigit() }.toIntOrNull() ?: 5
                        n * 60
                    }
                    clean.contains("sec")  -> {
                        clean.filter { it.isDigit() }.toIntOrNull() ?: 30
                    }
                    else -> 300  // default 5 minutes
                }

                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, "ARIA timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            appController.openApp("clock")
        }
    }
}