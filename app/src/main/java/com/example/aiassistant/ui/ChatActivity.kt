package com.example.aiassistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.aiassistant.R
import com.example.aiassistant.ai.AiManager
import com.example.aiassistant.ai.SecurityAnalyzer
import com.example.aiassistant.automation.ActionRouter
import com.example.aiassistant.automation.AppController
import com.example.aiassistant.automation.ContactsHelper
import com.example.aiassistant.databinding.ActivityChatBinding
import com.example.aiassistant.model.ChatMessage
import com.example.aiassistant.model.ChatRepository
import com.example.aiassistant.service.NotificationReaderService
import com.example.aiassistant.service.ScreenReaderService
import com.example.aiassistant.service.WakeWordService
import com.example.aiassistant.voice.VoiceManager
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatAdapter
    private lateinit var aiManager: AiManager
    private lateinit var securityAnalyzer: SecurityAnalyzer
    private lateinit var voiceManager: VoiceManager
    private lateinit var appController: AppController
    private lateinit var contactsHelper: ContactsHelper
    private var isProcessing = false

    // ============ LIFECYCLE ============

    @RequiresApi(Build.VERSION_CODES.GINGERBREAD)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        aiManager = AiManager.getInstance(this)
        securityAnalyzer = SecurityAnalyzer()
        voiceManager = VoiceManager(this)
        appController = AppController(this)
        contactsHelper = ContactsHelper(this)

        voiceManager.initialize()
        setupVoiceCallbacks()
        setupRecyclerView()
        setupClickListeners()
        addWelcomeMessage()

        // Warm up AI in background
        lifecycleScope.launch {
            aiManager.warmUp()
        }
    }

    override fun onResume() {
        super.onResume()
        // Pause wake word while user is chatting
        WakeWordService.isPaused = true
    }

    override fun onPause() {
        super.onPause()
        // Resume wake word when user leaves chat
        WakeWordService.isPaused = false
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.shutdown()
    }

    // ============ SETUP ============

    private fun setupRecyclerView() {
        while (ChatRepository.messages.size > 50) {
            ChatRepository.messages.removeAt(0)
        }
        adapter = ChatAdapter(ChatRepository.messages)
        binding.rvMessages.layoutManager =
            LinearLayoutManager(this).apply {
                stackFromEnd = true
            }
        binding.rvMessages.adapter = adapter
        if (ChatRepository.messages.isNotEmpty()) {
            binding.rvMessages.scrollToPosition(
                ChatRepository.messages.size - 1
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.GINGERBREAD)
    private fun setupClickListeners() {
        binding.btnSend.setOnClickListener {
            val text =
                binding.etMessage.text.toString().trim()
            if (text.isNotEmpty() && !isProcessing) {
                sendMessage(text)
                binding.etMessage.text?.clear()
            }
        }

        binding.etMessage.setOnEditorActionListener {
                _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                binding.btnSend.performClick()
                true
            } else false
        }

        binding.btnVoice.setOnClickListener {
            if (voiceManager.isListening) {
                voiceManager.stopListening()
            } else {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    voiceManager.startListening()
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            Manifest.permission.RECORD_AUDIO
                        ),
                        100
                    )
                }
            }
        }

        binding.btnScreenScan.setOnClickListener {
            performScreenScan()
        }
    }

    @RequiresApi(Build.VERSION_CODES.GINGERBREAD)
    private fun setupVoiceCallbacks() {
        voiceManager.onResult = { spokenText ->
            runOnUiThread {
                binding.etMessage.setText(spokenText)
                sendMessage(spokenText)
                binding.etMessage.text?.clear()
            }
        }

        voiceManager.onError = { error ->
            runOnUiThread {
                Toast.makeText(
                    this, error, Toast.LENGTH_SHORT
                ).show()
            }
        }

        voiceManager.onListeningStarted = {
            runOnUiThread {
                binding.btnVoice.setBackgroundResource(
                    R.drawable.bg_message_user
                )
                binding.etMessage.hint = "🎤 Listening..."
            }
        }

        voiceManager.onListeningStopped = {
            runOnUiThread {
                binding.btnVoice.setBackgroundResource(
                    R.drawable.bg_floating_button
                )
                binding.etMessage.hint =
                    "Ask ARIA anything..."
            }
        }
    }

    private fun addWelcomeMessage() {
        if (ChatRepository.messages.isEmpty()) {
            // FIX: Removed the stray '0' that caused a compile error
            adapter.addMessage(
                ChatMessage(
                    content =
                        "👋 Hi! I'm ARIA, your AI assistant.\n\n" +
                                "I can help you with:\n" +
                                "• Answering questions\n" +
                                "• Detecting scams & phishing 🛡️\n" +
                                "• Opening apps & making calls\n" +
                                "• Setting alarms & timers ⏰\n" +
                                "• Reading your notifications 📬\n" +
                                "• Analyzing what's on your screen\n\n" +
                                "Try: \"debug\" to find app packages\n" +
                                "Or: \"Open YouTube\" • " +
                                "\"Set alarm for 7am\"",
                    isUser = false
                )
            )
            scrollToBottom()
        }
    }

    // ============ SEND MESSAGE ============

    @RequiresApi(Build.VERSION_CODES.GINGERBREAD)
    private fun sendMessage(text: String) {
        if (isProcessing) return
        isProcessing = true

        adapter.addMessage(
            ChatMessage(content = text, isUser = true)
        )
        scrollToBottom()

        adapter.addMessage(
            ChatMessage(
                content = "⏳ Thinking...",
                isUser = false
            )
        )
        scrollToBottom()

        val lowerText = text.lowercase().trim()

        lifecycleScope.launch {
            val response = try {
                processUserInput(lowerText, text)
            } catch (_: Exception) {
                "⚠️ Something went wrong. Try again."
            }

            runOnUiThread {
                if (ChatRepository.messages.isNotEmpty()) {
                    ChatRepository.messages.removeAt(
                        ChatRepository.messages.size - 1
                    )
                    adapter.notifyItemRemoved(
                        ChatRepository.messages.size
                    )
                }
                adapter.addMessage(
                    ChatMessage(
                        content = response,
                        isUser = false
                    )
                )
                scrollToBottom()
                isProcessing = false
                voiceManager.speak(response)
            }
        }
    }

    // ============ PROCESS INPUT ============

    @RequiresApi(Build.VERSION_CODES.GINGERBREAD)
    private suspend fun processUserInput(
        lower: String,
        original: String
    ): String {

        // 1. DEBUG — must be first
        if (lower.contains("debug") || lower.contains("find app")) {
            val searchQuery = when {
                lower == "debug all" -> "all"
                lower.startsWith("find app ") -> lower.substring(9).trim()
                lower.startsWith("debug ") -> lower.substring(6).trim()
                else -> null
            }
            return findInstalledApps(searchQuery)
        }

        if (lower == "test youtube") {
            return listAllYouTubeApps()
        }

        // 2. CALL
        if (lower.startsWith("call ")) {
            val name = original.substring(5).trim()
            return handleCallCommand(name)
        }

        // 3. OPEN APP
        if (lower.startsWith("open ")) {
            val appName = original.substring(5).trim()
            return if (appController.openApp(appName)) {
                "✅ Opening $appName!"
            } else {
                "❌ Couldn't find \"$appName\". " +
                        "Make sure it's installed."
            }
        }

        // 4. SEND MESSAGE / TEXT
        if (lower.startsWith("send message to ") ||
            lower.startsWith("text ")
        ) {
            val name = original.replace(
                Regex(
                    "^(send message to |text )",
                    RegexOption.IGNORE_CASE
                ), ""
            ).trim()
            return handleSmsCommand(name)
        }

        // 5. SETTINGS
        if (lower.contains("open settings") ||
            lower.contains("phone settings")
        ) {
            appController.openSettings()
            return "✅ Opening phone settings."
        }

        // 6. WEATHER
        if (lower.contains("weather") ||
            lower.contains("temperature") ||
            lower.contains("rain") ||
            lower.contains("sunny") ||
            lower.contains("forecast")
        ) {
            return handleWeather(original)
        }

        // 7. SCREEN ANALYSIS
        if (lower.contains("what's on my screen") ||
            lower.contains("what is on my screen") ||
            lower.contains("read my screen") ||
            lower.contains("read the screen")
        ) {
            return performScreenAnalysis(
                "Describe what's on the screen briefly."
            )
        }

        // 8. SAFETY CHECK
        if (lower.contains("is this safe") ||
            lower.contains("is it safe") ||
            lower.contains("check this") ||
            lower.contains("is this a scam") ||
            lower.contains("is this legit") ||
            lower.contains("should i click")
        ) {
            return performSafetyCheck()
        }

        // 9. ALARM
        if (lower.contains("set alarm") ||
            lower.contains("wake me up") ||
            lower.contains("remind me")
        ) {
            return handleAlarm(lower)
        }

        // 10. TIMER
        if (lower.contains("set timer") ||
            lower.contains("timer for")
        ) {
            return handleTimer(lower)
        }

        // 11. NOTIFICATIONS
        if (lower.contains("any notifications") ||
            lower.contains("what did i miss") ||
            lower.contains("read notifications") ||
            lower.contains("check notifications")
        ) {
            return readNotifications()
        }

        // 12. DEFAULT — Ask AI, then route actions
        // FIX: Pass the original user message to ActionRouter, not a raw AI response
        val router = ActionRouter(this, appController)
        return router.handle(original, aiManager)
    }

    // ============ COMMAND HANDLERS ============

    private fun handleCallCommand(name: String): String {
        val contact = contactsHelper.findContact(name)
        return if (contact != null) {
            appController.makeCall(contact.phoneNumber)
            "📞 Calling ${contact.name}..."
        } else {
            "❌ Couldn't find \"$name\" in contacts."
        }
    }

    private fun handleSmsCommand(name: String): String {
        val contact = contactsHelper.findContact(name)
        return if (contact != null) {
            appController.sendSms(contact.phoneNumber)
            "✉️ Opening message to ${contact.name}..."
        } else {
            "❌ Couldn't find \"$name\" in contacts."
        }
    }

    private fun handleWeather(original: String): String {
        return try {
            val query = original
                .replace("weather", "")
                .replace("what is the", "")
                .replace("what's the", "")
                .trim()
                .ifBlank { "weather today" }

            val searchUrl =
                "https://www.google.com/search?q=" +
                        android.net.Uri.encode(
                            "weather $query"
                        )
            val intent = Intent(
                Intent.ACTION_VIEW,
                android.net.Uri.parse(searchUrl)
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            "🌤️ Opening weather for you!"
        } catch (_: Exception) {
            "⚠️ Could not open weather."
        }
    }

    @RequiresApi(Build.VERSION_CODES.GINGERBREAD)
    private fun handleAlarm(lower: String): String {
        return try {
            val hourRegex = Regex(
                """(\d{1,2})\s*:?\s*(\d{0,2})\s*(am|pm)?""",
                RegexOption.IGNORE_CASE
            )
            val match = hourRegex.find(lower)

            var hour = 7
            var minute = 0

            if (match != null) {
                hour = match.groupValues[1].toIntOrNull() ?: 7
                minute = match.groupValues[2].toIntOrNull() ?: 0
                val ampm = match.groupValues[3].lowercase()

                if (ampm == "pm" && hour != 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
            }

            val alarmIntent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "ARIA Alarm")
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
            }

            val clockPackages = listOf(
                "com.samsung.android.app.clockpack",
                "com.sec.android.app.clockpackage",
                "com.samsung.android.clock",
                "com.android.deskclock",
                "com.google.android.deskclock"
            )

            for (pkg in clockPackages) {
                try {
                    packageManager.getPackageInfo(pkg, 0)
                    alarmIntent.setPackage(pkg)
                    startActivity(alarmIntent)
                    return "⏰ Alarm set for ${hour}:${minute.toString().padStart(2, '0')}!"
                } catch (e: Exception) {
                    continue
                }
            }

            try {
                alarmIntent.setPackage(null)
                startActivity(alarmIntent)
                return "⏰ Opening alarm app!"
            } catch (e: Exception) {
                return if (appController.openApp("clock")) {
                    "⏰ Please set alarm manually - opened Clock"
                } else {
                    "❌ No clock app found"
                }
            }

        } catch (e: Exception) {
            "❌ Error setting alarm: ${e.localizedMessage}"
        }
    }

    private fun handleTimer(lower: String): String {
        return try {
            val intent = Intent(
                android.provider.AlarmClock.ACTION_SET_TIMER
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK

                val minRegex =
                    Regex("""(\d+)\s*minute""")
                val secRegex =
                    Regex("""(\d+)\s*second""")
                val minMatch = minRegex.find(lower)
                val secMatch = secRegex.find(lower)

                val minutes = minMatch?.groupValues
                    ?.get(1)?.toIntOrNull() ?: 0
                val seconds = secMatch?.groupValues
                    ?.get(1)?.toIntOrNull() ?: 0
                val totalSeconds =
                    (minutes * 60) + seconds

                if (totalSeconds > 0) {
                    putExtra(
                        android.provider.AlarmClock
                            .EXTRA_LENGTH,
                        totalSeconds
                    )
                }
                putExtra(
                    android.provider.AlarmClock
                        .EXTRA_MESSAGE,
                    "ARIA Timer"
                )
                putExtra(
                    android.provider.AlarmClock
                        .EXTRA_SKIP_UI,
                    true
                )
            }
            startActivity(intent)
            "⏱️ Timer set!"
        } catch (_: Exception) {
            "❌ Could not set timer."
        }
    }

    private fun readNotifications(): String {
        val notifs =
            NotificationReaderService.recentNotifications
        return if (notifs.isEmpty()) {
            "📭 No recent notifications."
        } else {
            "📬 Recent notifications:\n\n" +
                    notifs.take(5)
                        .joinToString("\n─────────\n")
        }
    }

    // ============ DEBUG ============

    private fun findInstalledApps(searchQuery: String? = null): String {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val results = mutableListOf<String>()

        for (app in apps) {
            val label = pm.getApplicationLabel(app).toString()
            val labelLower = label.lowercase()
            val packageLower = app.packageName.lowercase()

            val matches = when {
                searchQuery == "all" -> true
                searchQuery != null -> {
                    labelLower.contains(searchQuery.lowercase()) ||
                            packageLower.contains(searchQuery.lowercase())
                }
                else -> {
                    labelLower.contains("youtube") ||
                            labelLower.contains("you tube") ||
                            packageLower.contains("youtube") ||
                            packageLower.contains("yt") ||
                            labelLower.contains("clock") ||
                            labelLower.contains("alarm") ||
                            labelLower.contains("time") ||
                            labelLower.contains("camera") ||
                            labelLower.contains("gallery") ||
                            labelLower.contains("chrome") ||
                            labelLower.contains("maps") ||
                            labelLower.contains("gmail")
                }
            }

            if (matches) {
                results.add("$label = ${app.packageName}")
            }
        }

        val displayResults = if (searchQuery == "all") {
            results.take(50)
        } else {
            results
        }

        return if (displayResults.isEmpty()) {
            "No apps found" + if (searchQuery != null && searchQuery != "all") " matching '$searchQuery'" else ""
        } else {
            val header = if (searchQuery == "all") "First 50 apps:\n" else "Found:\n"
            header + displayResults.joinToString("\n")
        }
    }

    // ============ SCREEN ============

    @RequiresApi(Build.VERSION_CODES.GINGERBREAD)
    private fun performScreenScan() {
        if (!ScreenReaderService.isServiceActive) {
            Toast.makeText(
                this,
                "Enable Accessibility Service first",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        sendMessage(
            "Is this safe? Check my screen for threats."
        )
    }

    private suspend fun performSafetyCheck(): String {
        val screenContent =
            ScreenReaderService.getScreenContent()

        if (screenContent.isBlank()) {
            return "⚠️ Can't see your screen. " +
                    "Enable Accessibility Service."
        }

        val localResult =
            securityAnalyzer.analyzeText(screenContent)
        val localVerdict =
            securityAnalyzer.getQuickVerdict(localResult)

        val warningText =
            if (localResult.warnings.isNotEmpty()) {
                "\n\n🔍 Findings:\n" +
                        localResult.warnings
                            .take(5)
                            .joinToString("\n")
            } else ""

        val aiAnalysis = aiManager.sendMessage(
            "Analyze for safety threats. Be concise.",
            screenContent
        )

        return "$localVerdict$warningText" +
                "\n\n🤖 AI Analysis:\n$aiAnalysis"
    }

    private suspend fun performScreenAnalysis(
        question: String
    ): String {
        val screenContent =
            ScreenReaderService.getScreenContent()
        return if (screenContent.isNotBlank()) {
            aiManager.sendMessage(question, screenContent)
        } else {
            "⚠️ Can't read screen. " +
                    "Enable Accessibility Service."
        }
    }

    private fun scrollToBottom() {
        if (ChatRepository.messages.isNotEmpty()) {
            binding.rvMessages.smoothScrollToPosition(
                ChatRepository.messages.size - 1
            )
        }
    }

    private fun listAllYouTubeApps(): String {
        val pm = packageManager
        val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val youtubeApps = allApps.filter {
            val label = pm.getApplicationLabel(it).toString().lowercase()
            val pkg = it.packageName.lowercase()
            label.contains("you") || pkg.contains("you") ||
                    label.contains("tube") || pkg.contains("tube") ||
                    label.contains("video") || pkg.contains("video")
        }
        return if (youtubeApps.isEmpty()) {
            "No YouTube-like apps found"
        } else {
            "Possible YouTube apps:\n" + youtubeApps.joinToString("\n") { app ->
                "${pm.getApplicationLabel(app)} = ${app.packageName}"
            }
        }
    }
}