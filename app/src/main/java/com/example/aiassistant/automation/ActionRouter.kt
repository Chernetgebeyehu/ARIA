package com.example.aiassistant.automation

import android.content.Context
import com.example.aiassistant.ai.AiManager
import org.json.JSONObject

class ActionRouter(
    private val context: Context,
    private val appController: AppController
) {

    suspend fun handle(
        input: String,
        aiManager: AiManager
    ): String {

        val response = aiManager.sendMessage(input)

        return try {
            val json = JSONObject(response)

            when (json.getString("type")) {

                "chat" -> {
                    json.getString("text")
                }

                "action" -> {
                    val actions = json.getJSONArray("actions")

                    var resultLog = ""

                    for (i in 0 until actions.length()) {
                        val action = actions.getJSONObject(i)

                        val tool = action.getString("tool")
                        val value = action.getString("value")

                        resultLog += execute(tool, value) + "\n"
                    }

                    resultLog.trim()
                }

                else -> "⚠️ Unknown response format"
            }

        } catch (e: Exception) {
            // fallback if AI returns normal text
            response
        }
    }

    private fun execute(tool: String, value: String): String {

        return when (tool) {

            "open_app" -> {
                if (appController.openApp(value)) {
                    "Opened $value"
                } else {
                    "Could not open $value"
                }
            }

            "search" -> {
                appController.openUrl(
                    "https://www.google.com/search?q=$value"
                )
                "Searching $value"
            }

            "call" -> {
                appController.makeCall(value)
                "Calling $value"
            }

            "sms" -> {
                appController.sendSms(value)
                "Opening SMS for $value"
            }

            "alarm" -> {
                appController.openApp("clock")
                "Setting alarm via clock app"
            }

            else -> "Unknown tool: $tool"
        }
    }
}