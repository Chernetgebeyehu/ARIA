package com.cherinet.aria.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
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

class AiManager private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: AiManager? = null
        fun getInstance(context: Context): AiManager =
            instance ?: synchronized(this) {
                instance ?: AiManager(context.applicationContext).also { instance = it }
            }

        private const val TAG = "AiManager"
        private const val MAX_HISTORY = 10
        private const val SERVER_URL = "https://aria-backend-production-c3d6.up.railway.app"
        private const val SERVER_TOKEN = "aria_Cherinet_2025_secure"
        private const val DEFAULT_USER_ID = "aria_user_default"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val history = mutableListOf<JsonObject>()

    var selectedProvider: String = "gemini"
    var selectedOpenRouterModel: String = OpenRouterModels.DEFAULT

    var lastAgentUsed: Boolean = false; private set
    var lastAgentTasks: Int = 0; private set
    var lastSuccessRate: Double = 1.0; private set
    var lastMemoriesRetrieved: Int = 0; private set
    var lastUsedOffline: Boolean = false; private set

    // ─── INTERNET CHECK ───────────────────────────────────────────────────────
    fun isInternetAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }

    // ─── MAIN ENTRY POINT ─────────────────────────────────────────────────────
    suspend fun sendMessage(
        message: String,
        screen: String? = null,
        storeMemory: Boolean = true
    ): String = withContext(Dispatchers.IO) {

        // Layer 1: Cloud server (if online)
        if (isInternetAvailable()) {
            var lastError = ""
            repeat(3) { attempt ->
                try {
                    val result = callServer(message, screen, storeMemory)
                    if (result != null) {
                        lastUsedOffline = false
                        return@withContext result
                    }
                    lastError = buildJsonChat("Server unavailable.")
                    if (attempt < 2) delay(2000L)
                } catch (e: Exception) {
                    lastError = buildJsonChat("Connection error: ${e.localizedMessage ?: "unknown"}")
                    if (attempt < 2) delay(2000L)
                }
            }
            Log.w(TAG, "Server failed — using rule engine")
        }

        // Layer 2: Rule engine (offline fallback)
        lastUsedOffline = true
        return@withContext RuleEngine.match(message).response
    }

    // ─── CALL SERVER ──────────────────────────────────────────────────────────
    private fun callServer(message: String, screen: String?, storeMemory: Boolean): String? {
        val historyArray = JsonArray()
        history.forEach { historyArray.add(it) }

        val body = JsonObject().apply {
            addProperty("message", message)
            screen?.let { addProperty("screen_context", it.take(1200)) }
            addProperty("model", selectedProvider)
            addProperty("openrouter_model", selectedOpenRouterModel)
            add("history", historyArray)
            addProperty("token", SERVER_TOKEN)
            addProperty("user_id", DEFAULT_USER_ID)
            addProperty("store_memory", storeMemory)
            addProperty("use_agent", true)
        }

        val request = Request.Builder()
            .url("$SERVER_URL/chat")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val raw = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                return buildJsonChat(when (response.code) {
                    401 -> "Authentication failed."
                    422 -> "Request format error."
                    429 -> "Rate limit exceeded. Please wait."
                    500 -> null.toString()
                    else -> "Server error ${response.code}."
                })
            }

            val json = gson.fromJson(raw, JsonObject::class.java)
            lastAgentUsed   = json.get("agent_used")?.asBoolean ?: false
            lastAgentTasks  = json.getAsJsonArray("agent_tasks")?.size() ?: 0
            lastSuccessRate = json.get("success_rate")?.asDouble ?: 1.0
            lastMemoriesRetrieved = json.get("memories_retrieved")?.asInt ?: 0

            val aiResponse = json.get("response")?.asString ?: return null
            addToHistory("user", message)
            addToHistory("model", aiResponse)
            aiResponse

        } catch (e: Exception) { null }
    }

    // ─── MEMORY ───────────────────────────────────────────────────────────────
    suspend fun storeMemory(content: String, memoryType: String = "general"): Boolean =
        withContext(Dispatchers.IO) {
            if (!isInternetAvailable()) return@withContext false
            try {
                val body = JsonObject().apply {
                    addProperty("content", content)
                    addProperty("memory_type", memoryType)
                    addProperty("user_id", DEFAULT_USER_ID)
                    addProperty("token", SERVER_TOKEN)
                }
                val req = Request.Builder().url("$SERVER_URL/memory/store")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().isSuccessful
            } catch (e: Exception) { false }
        }

    suspend fun getMemories(): List<Map<String, String>> = withContext(Dispatchers.IO) {
        if (!isInternetAvailable()) return@withContext emptyList()
        try {
            val req = Request.Builder()
                .url("$SERVER_URL/memory/$DEFAULT_USER_ID?token=$SERVER_TOKEN")
                .get().build()
            val raw = client.newCall(req).execute().body?.string().orEmpty()
            val json = gson.fromJson(raw, JsonObject::class.java)
            val arr = json.getAsJsonArray("memories") ?: return@withContext emptyList()
            arr.map { el ->
                val m = el.asJsonObject
                mapOf(
                    "content" to (m.get("content")?.asString ?: ""),
                    "type" to (m.get("type")?.asString ?: "general"),
                    "date" to (m.get("date")?.asString ?: "")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun clearMemories(): Boolean = withContext(Dispatchers.IO) {
        if (!isInternetAvailable()) return@withContext false
        try {
            val req = Request.Builder()
                .url("$SERVER_URL/memory/$DEFAULT_USER_ID?token=$SERVER_TOKEN")
                .delete().build()
            client.newCall(req).execute().isSuccessful
        } catch (e: Exception) { false }
    }

    // ─── ENCRYPTED STORAGE ────────────────────────────────────────────────────
    private fun getEncryptedPrefs() = try {
        EncryptedSharedPreferences.create(
            "aria_secure_prefs", "aria_master_key", context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("aria_prefs", Context.MODE_PRIVATE)
    }

    fun getApiKey(): String = getEncryptedPrefs().getString("server_url", SERVER_URL) ?: SERVER_URL
    fun saveApiKey(url: String) = getEncryptedPrefs().edit().putString("server_url", url).apply()
    fun clearApiKey() = getEncryptedPrefs().edit().remove("server_url").apply()
    fun isApiKeySet() = true

    // ─── HELPERS ──────────────────────────────────────────────────────────────
    private fun buildJsonChat(text: String): String {
        val safe = text.replace("\"", "'")
        return """{"type":"chat","text":"$safe"}"""
    }

    private fun addToHistory(role: String, content: String) {
        val obj = JsonObject().apply {
            addProperty("role", role)
            add("parts", JsonArray().apply {
                add(JsonObject().apply { addProperty("text", content) })
            })
        }
        history.add(obj)
        while (history.size > MAX_HISTORY * 2) history.removeAt(0)
    }

    fun clearHistory() = history.clear()
    fun getHistorySize() = history.size / 2
    fun warmUp() {}
    var selectedOpenRouterModel_compat: String
        get() = selectedOpenRouterModel
        set(v) { selectedOpenRouterModel = v }
}