package com.example.aiassistant.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AiManager private constructor(
    private val context: Context
) {

    companion object {
        @Volatile
        private var instance: AiManager? = null

        fun getInstance(context: Context): AiManager =
            instance ?: synchronized(this) {
                instance ?: AiManager(context.applicationContext)
                    .also { instance = it }
            }

        private const val MAX_HISTORY = 10

        // gemini-2.5-flash: current free tier model (2.0-flash retired March 2026)
        private const val GEMINI_MODEL = "gemini-2.5-flash"
        private const val GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "$GEMINI_MODEL:generateContent"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Conversation history (Gemini format)
    private val history = mutableListOf<JsonObject>()

    private val systemPrompt = """
You are ARIA, a smart and friendly Android AI assistant.

CRITICAL RULES - NEVER break these:
1. ALWAYS respond with raw JSON only. No exceptions.
2. NEVER wrap your response in markdown, code blocks, or backticks.
3. NEVER write ```json or ``` anywhere in your response.
4. Your ENTIRE response must be a single JSON object and nothing else.

When the user asks you to do a phone action, respond ONLY with this exact JSON format:
{"type":"action","actions":[{"tool":"open_app","value":"youtube"}]}

Available tools: open_app, search, call, sms, alarm

For everything else (questions, conversation, help), respond ONLY with this exact JSON format:
{"type":"chat","text":"your response here"}

WRONG - never do this:
```json
{"type":"chat","text":"hello"}
```

CORRECT - always do this:
{"type":"chat","text":"hello"}
""".trimIndent()

    // ========= API KEY =========
    private fun getApiKey(): String {
        return context.getSharedPreferences(
            "aria_prefs",
            Context.MODE_PRIVATE
        ).getString("api_key", "") ?: ""
    }

    private fun isGeminiKey(key: String) = key.startsWith("AIza")
    private fun isOpenRouterKey(key: String) = key.startsWith("sk-or-")

    // ========= ENTRY POINT =========
    suspend fun sendMessage(
        message: String,
        screen: String? = null
    ): String = withContext(Dispatchers.IO) {

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext buildJsonChat(
                "No API key set. Please enter your Gemini API key in the main screen."
            )
        }

        val finalMessage =
            if (screen != null)
                "$message\n\nScreen:\n${screen.take(1200)}"
            else message

        // Retry up to 2 times on 429 (rate limit), with delay
        var lastError = ""
        repeat(3) { attempt ->
            try {
                val result = when {
                    isGeminiKey(apiKey) -> callGemini(apiKey, finalMessage)
                    isOpenRouterKey(apiKey) -> callOpenRouter(apiKey, finalMessage)
                    else -> return@withContext buildJsonChat(
                        "Unknown API key format. Please use a Gemini key (starts with AIza) " +
                                "from aistudio.google.com/app/apikey"
                    )
                }
                // If result is a 429 and we still have retries left, wait and retry
                if (result.contains("TOO_MANY_REQUESTS") || result.contains("429")) {
                    lastError = result
                    if (attempt < 2) delay(3000L * (attempt + 1))
                } else {
                    return@withContext result
                }
            } catch (e: Exception) {
                lastError = buildJsonChat("Error: ${e.localizedMessage ?: "unknown"}")
                if (attempt < 2) delay(2000L)
            }
        }
        return@withContext lastError.ifBlank {
            buildJsonChat("Something went wrong. Please try again.")
        }
    }

    // ========= GEMINI =========
    private fun callGemini(apiKey: String, message: String): String {

        val contents = JsonArray()
        history.forEach { contents.add(it) }

        val userContent = JsonObject().apply {
            addProperty("role", "user")
            add("parts", JsonArray().apply {
                add(JsonObject().apply { addProperty("text", message) })
            })
        }
        contents.add(userContent)

        val systemInstruction = JsonObject().apply {
            add("parts", JsonArray().apply {
                add(JsonObject().apply { addProperty("text", systemPrompt) })
            })
        }

        val body = JsonObject().apply {
            add("systemInstruction", systemInstruction)
            add("contents", contents)
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.7)
                addProperty("maxOutputTokens", 400)
            })
        }

        val request = Request.Builder()
            .url("$GEMINI_URL?key=$apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val raw = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            // Return raw for retry detection on 429
            if (response.code == 429) return raw

            val errorMsg = try {
                gson.fromJson(raw, JsonObject::class.java)
                    ?.getAsJsonObject("error")
                    ?.get("message")?.asString
                    ?: "Error ${response.code}"
            } catch (e: Exception) { "Error ${response.code}" }

            val friendly = when (response.code) {
                400 -> "Invalid request. Make sure your API key is correct."
                403 -> "API key rejected. Get a fresh free key at: aistudio.google.com/app/apikey"
                else -> errorMsg
            }
            return buildJsonChat(friendly)
        }

        return try {
            val json = gson.fromJson(raw, JsonObject::class.java)
            val reply = json
                .getAsJsonArray("candidates")
                .get(0).asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts")
                .get(0).asJsonObject
                .get("text").asString
                .trim()

            val cleanReply = stripMarkdown(reply)

            addToHistory("user", message)
            addToHistory("model", cleanReply)
            cleanReply

        } catch (e: Exception) {
            buildJsonChat("Could not read Gemini response. Please try again.")
        }
    }

    // ========= OPENROUTER (fallback) =========
    private fun callOpenRouter(apiKey: String, message: String): String {

        val messages = JsonArray()
        messages.add(JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemPrompt)
        })
        history.forEach { messages.add(it) }
        messages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", message)
        })

        val body = JsonObject().apply {
            addProperty("model", "google/gemma-3-4b-it:free")
            add("messages", messages)
            addProperty("temperature", 0.7)
            addProperty("max_tokens", 400)
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://aria.app")
            .addHeader("X-Title", "ARIA")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val raw = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            if (response.code == 429) return raw
            return buildJsonChat("OpenRouter error ${response.code}. Try a Gemini key instead.")
        }

        return try {
            val json = gson.fromJson(raw, JsonObject::class.java)
            val reply = json.getAsJsonArray("choices")
                .get(0).asJsonObject
                .getAsJsonObject("message")
                .get("content").asString

            addToHistory("user", message)
            addToHistory("assistant", reply)
            reply
        } catch (e: Exception) {
            buildJsonChat("Parse error. Try again.")
        }
    }

    // ========= HELPERS =========

    // Strips markdown code fences Gemini sometimes adds despite instructions
    private fun stripMarkdown(raw: String): String {
        var result = raw.trim()
        // Remove ```json ... ``` or ``` ... ```
        if (result.startsWith("```")) {
            result = result
                .removePrefix("```json")
                .removePrefix("```")
            val endFence = result.lastIndexOf("```")
            if (endFence >= 0) {
                result = result.substring(0, endFence)
            }
            result = result.trim()
        }
        return result
    }

    private fun buildJsonChat(text: String): String {
        // Escape quotes in text to avoid JSON breaking
        val safe = text.replace("\"", "'")
        return """{"type":"chat","text":"$safe"}"""
    }

    private fun addToHistory(role: String, content: String) {
        val geminiRole = if (role == "assistant") "model" else role
        val obj = JsonObject().apply {
            addProperty("role", geminiRole)
            add("parts", JsonArray().apply {
                add(JsonObject().apply { addProperty("text", content) })
            })
        }
        history.add(obj)
        while (history.size > MAX_HISTORY * 2) {
            history.removeAt(0)
        }
    }

    fun clearHistory() = history.clear()

    fun warmUp() { /* safe no-op */ }
}