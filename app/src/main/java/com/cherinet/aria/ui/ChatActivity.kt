package com.cherinet.aria.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cherinet.aria.ai.SecurityAnalyzer
import com.cherinet.aria.automation.AppController
import com.cherinet.aria.automation.CommandParser
import com.cherinet.aria.automation.ContactsHelper
import com.cherinet.aria.model.ChatMessage
import com.cherinet.aria.model.ChatRepository
import com.cherinet.aria.services.NotificationReaderService
import com.cherinet.aria.services.ScreenReaderService
import com.cherinet.aria.services.WakeWordService
import com.cherinet.aria.voice.VoiceManager
import com.cherinet.aria.R
import com.cherinet.aria.ai.AiManager
import com.cherinet.aria.automation.ActionRouter
import com.cherinet.aria.databinding.ActivityChatBinding
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: ChatAdapter
    private lateinit var aiManager: AiManager
    private lateinit var securityAnalyzer: SecurityAnalyzer
    private lateinit var voiceManager: VoiceManager
    private lateinit var appController: AppController
    private lateinit var contactsHelper: ContactsHelper
    private lateinit var actionRouter: ActionRouter
    private var isProcessing = false

    // ============ LIFECYCLE ============

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        aiManager = AiManager.getInstance(this)
        securityAnalyzer = SecurityAnalyzer()
        appController = AppController(this)
        contactsHelper = ContactsHelper(this)
        actionRouter = ActionRouter(this, appController)

        // Phase 5: VoiceManager now has state machine built in
        voiceManager = VoiceManager(this)

        // Update UI whenever voice state changes
        voiceManager.stateMachine.onStateChanged = { _, newState ->
            runOnUiThread { updateVoiceStateUI(newState) }
        }

        voiceManager.initialize()
        setupVoiceCallbacks()
        setupRecyclerView()
        setupClickListeners()
        addWelcomeMessage()

        // If launched from wake word, start listening immediately
        if (intent.getBooleanExtra("start_listening", false)) {
            voiceManager.startListening()
        }
    }

    override fun onResume() {
        super.onResume()
        WakeWordService.isPaused = true
    }

    override fun onPause() {
        super.onPause()
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
            LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = adapter
        if (ChatRepository.messages.isNotEmpty()) {
            binding.rvMessages.scrollToPosition(ChatRepository.messages.size - 1)
        }
    }

    private fun setupClickListeners() {
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty() && !isProcessing) {
                sendMessage(text)
                binding.etMessage.text?.clear()
            }
        }

        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                binding.btnSend.performClick()
                true
            } else false
        }

        binding.btnVoice.setOnClickListener {
            when (voiceManager.stateMachine.current) {
                com.cherinet.aria.voice.VoiceState.IDLE -> {
                    if (ActivityCompat.checkSelfPermission(
                            this, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        voiceManager.startListening()
                    } else {
                        ActivityCompat.requestPermissions(
                            this, arrayOf(Manifest.permission.RECORD_AUDIO), 100
                        )
                    }
                }
                com.cherinet.aria.voice.VoiceState.LISTENING -> {
                    voiceManager.stopListening()
                }
                com.cherinet.aria.voice.VoiceState.SPEAKING -> {
                    // Tap mic while ARIA is speaking → interrupt
                    voiceManager.stopSpeaking()
                    voiceManager.startListening()
                }
                else -> { /* busy — ignore */ }
            }
        }

        binding.btnScreenScan.setOnClickListener {
            performScreenScan()
        }
    }

    private fun setupVoiceCallbacks() {
        voiceManager.onWakeWordDetected = {
            // Wake word fired — show feedback
            Toast.makeText(this, "👂 Listening...", Toast.LENGTH_SHORT).show()
        }

        voiceManager.onResult = { spokenText ->
            runOnUiThread {
                binding.etMessage.setText(spokenText)
                sendMessage(spokenText)
                binding.etMessage.text?.clear()
            }
        }

        voiceManager.onError = { error ->
            runOnUiThread {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                updateVoiceStateUI(com.cherinet.aria.voice.VoiceState.IDLE)
            }
        }

        voiceManager.onListeningStarted = {
            runOnUiThread {
                binding.btnVoice.setBackgroundResource(R.drawable.bg_message_user)
                binding.etMessage.hint = "🎤 Listening..."
            }
        }

        voiceManager.onListeningStopped = {
            runOnUiThread {
                binding.btnVoice.setBackgroundResource(R.drawable.bg_floating_button)
                binding.etMessage.hint = "Ask ARIA anything..."
            }
        }

        voiceManager.onSpeakingStarted = {
            // ARIA started speaking — update UI
        }

        voiceManager.onSpeakingFinished = {
            runOnUiThread {
                updateVoiceStateUI(com.cherinet.aria.voice.VoiceState.IDLE)
            }
        }

        voiceManager.onInterrupted = {
            runOnUiThread {
                Toast.makeText(this, "👂 Go ahead...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Update mic button and hint based on current voice state.
     * One function controls all UI state — no scattered updates.
     */
    private fun updateVoiceStateUI(state: com.cherinet.aria.voice.VoiceState) {
        val hint = when (state) {
            com.cherinet.aria.voice.VoiceState.IDLE       -> "Ask ARIA anything..."
            com.cherinet.aria.voice.VoiceState.DETECTING  -> "Wake word detected..."
            com.cherinet.aria.voice.VoiceState.LISTENING  -> "🎤 Listening..."
            com.cherinet.aria.voice.VoiceState.PROCESSING -> "⏳ Thinking..."
            com.cherinet.aria.voice.VoiceState.SPEAKING   -> "🔊 Speaking... (tap mic to interrupt)"
            com.cherinet.aria.voice.VoiceState.ERROR      -> "Try again..."
        }
        binding.etMessage.hint = hint

        val micEnabled = state == com.cherinet.aria.voice.VoiceState.IDLE ||
                state == com.cherinet.aria.voice.VoiceState.SPEAKING ||
                state == com.cherinet.aria.voice.VoiceState.LISTENING
        binding.btnVoice.isEnabled = micEnabled
        binding.btnVoice.alpha = if (micEnabled) 1.0f else 0.5f
    }

    private fun addWelcomeMessage() {
        if (ChatRepository.messages.isEmpty()) {
            adapter.addMessage(
                ChatMessage(
                    content = "👋 Hi! I'm ARIA, your AI assistant.\n\n" +
                            "I can help you with:\n" +
                            "• Answering questions\n" +
                            "• Detecting scams & phishing 🛡️\n" +
                            "• Opening apps & making calls\n" +
                            "• Setting alarms & timers ⏰\n" +
                            "• Reading your notifications 📬\n" +
                            "• Analyzing what's on your screen\n\n" +
                            "Try: \"Open YouTube\" • \"Call mom\" • \"Set alarm 7am\"\n" +
                            "Multi-commands: \"Open YouTube and set alarm for 8am\"",
                    isUser = false
                )
            )
            scrollToBottom()
        }
    }

    // ============ SEND MESSAGE ============

    private fun sendMessage(text: String) {
        if (isProcessing) return
        isProcessing = true

        adapter.addMessage(ChatMessage(content = text, isUser = true))
        scrollToBottom()
        adapter.addMessage(ChatMessage(content = "⏳ Thinking...", isUser = false))
        scrollToBottom()

        val lowerText = text.lowercase().trim()

        lifecycleScope.launch {
            val response = try {
                processUserInput(lowerText, text)
            } catch (e: Exception) {
                "⚠️ Something went wrong: ${e.localizedMessage}"
            }

            runOnUiThread {
                if (ChatRepository.messages.isNotEmpty()) {
                    ChatRepository.messages.removeAt(ChatRepository.messages.size - 1)
                    adapter.notifyItemRemoved(ChatRepository.messages.size)
                }
                adapter.addMessage(ChatMessage(content = response, isUser = false))
                scrollToBottom()
                isProcessing = false
                voiceManager.speak(response)
            }
        }
    }

    // ============ PROCESS INPUT ============

    private suspend fun processUserInput(lower: String, original: String): String {

        // ── 0. MULTI-COMMAND DETECTION (runs first, before everything else) ──
        // This is the Phase 4 addition.
        // If the user said multiple commands in one sentence,
        // split them and execute each one separately.
        //
        // Example: "Open YouTube and set alarm for 8am"
        // → splits into ["Open YouTube", "set alarm for 8am"]
        // → executes both independently
        // → returns combined result
        if (CommandParser.isMultiCommand(original)) {
            val tasks = CommandParser.split(original)
            val results = mutableListOf<String>()

            for (task in tasks) {
                val taskLower = task.lowercase().trim()
                val result = executeSingleCommand(taskLower, task)
                results.add(result)
            }

            return results.joinToString("\n\n")
        }

        // ── Single command: run normally ──
        return executeSingleCommand(lower, original)
    }

    /**
     * Execute exactly ONE command.
     * This is extracted from processUserInput so multi-command
     * can call it for each task separately.
     */
    private suspend fun executeSingleCommand(lower: String, original: String): String {

        // ── 1. DEBUG TOOLS ──
        if (lower == "debug" || lower.startsWith("debug ") || lower.startsWith("find app ")) {
            val query = when {
                lower == "debug" -> null
                lower.startsWith("find app ") -> lower.substring(9).trim()
                lower.startsWith("debug ") -> lower.substring(6).trim()
                else -> null
            }
            return findInstalledApps(query)
        }
        if (lower == "test youtube") return listAllYouTubeApps()
        if (lower == "aria history") return "AI remembers ${aiManager.getHistorySize()} turns."

        // ── 2. CALL ──
        if (lower.startsWith("call ") || lower.startsWith("phone ") || lower.startsWith("dial ")) {
            val name = original
                .replace(Regex("^(call |phone |dial )", RegexOption.IGNORE_CASE), "")
                .trim()
            return handleCallCommand(name)
        }

        // ── 3. OPEN APP ──
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.startsWith("start ")) {
            val appName = original
                .replace(Regex("^(open |launch |start )", RegexOption.IGNORE_CASE), "")
                .trim()
            return if (appController.openApp(appName)) "✅ Opening $appName!"
            else "❌ Couldn't find \"$appName\". Is it installed?"
        }

        // ── 4. SMS ──
        if (lower.startsWith("send message to ") || lower.startsWith("text ") ||
            lower.startsWith("sms ")) {
            val name = original
                .replace(Regex("^(send message to |text |sms )", RegexOption.IGNORE_CASE), "")
                .trim()
            return handleSmsCommand(name)
        }

        // ── 5. ALARM ──
        if (lower.contains("set alarm") || lower.contains("alarm for") ||
            lower.contains("wake me")) {
            return handleAlarm(lower)
        }

        // ── 6. TIMER ──
        if (lower.contains("set timer") || lower.contains("timer for")) {
            return handleTimer(lower)
        }

        // ── 7. WEATHER ──
        if (lower.contains("weather") || lower.contains("forecast") ||
            lower.contains("temperature") || lower.contains("rain")) {
            return handleWeather(original)
        }

        // ── 8. SCREEN ANALYSIS ──
        if (lower.contains("what's on my screen") || lower.contains("read my screen") ||
            lower.contains("read the screen") || lower.contains("what is on screen")) {
            return performScreenAnalysis("Describe what's on screen briefly.")
        }

        // ── 9. SAFETY CHECK ──
        if (lower.contains("is this safe") || lower.contains("is this a scam") ||
            lower.contains("check this") || lower.contains("is this legit") ||
            lower.contains("should i click")) {
            return performSafetyCheck()
        }

        // ── 10. NOTIFICATIONS ──
        if (lower.contains("notifications") || lower.contains("what did i miss") ||
            lower.contains("any messages")) {
            return readNotifications()
        }

        // ── 11. AI FALLBACK ──
        val result = actionRouter.handle(original, aiManager)
        return result.displayText
    }

    // ============ COMMAND HANDLERS ============

    private fun handleCallCommand(name: String): String {
        val contact = contactsHelper.findContact(name)
        return if (contact != null) {
            appController.makeCall(contact.phoneNumber)
            "📞 Calling ${contact.name}..."
        } else {
            "❌ Couldn't find \"$name\" in contacts.\n" +
                    "Try the exact name as saved in your contacts."
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
                .replace(Regex("what('s| is) the", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { "weather today" }
            val searchUrl = "https://www.google.com/search?q=" +
                    Uri.encode("weather $query")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            "🌤️ Searching weather for you!"
        } catch (e: Exception) {
            "⚠️ Could not open weather search."
        }
    }

    @RequiresApi(Build.VERSION_CODES.GINGERBREAD)
    private fun handleAlarm(lower: String): String {
        return try {
            val timeRegex = Regex("""(\d{1,2})\s*:?\s*(\d{0,2})\s*(am|pm)?""",
                RegexOption.IGNORE_CASE)
            val match = timeRegex.find(lower)
            var hour = 7; var minute = 0

            if (match != null) {
                hour = match.groupValues[1].toIntOrNull() ?: 7
                minute = match.groupValues[2].toIntOrNull() ?: 0
                val ampm = match.groupValues[3].lowercase()
                if (ampm == "pm" && hour != 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
            }

            val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "ARIA Alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }

            val clockPackages = listOf(
                "com.samsung.android.app.clockpack",
                "com.sec.android.app.clockpackage",
                "com.android.deskclock",
                "com.google.android.deskclock"
            )
            for (pkg in clockPackages) {
                try {
                    packageManager.getPackageInfo(pkg, 0)
                    alarmIntent.setPackage(pkg)
                    startActivity(alarmIntent)
                    return "⏰ Alarm set for ${hour}:${minute.toString().padStart(2, '0')}!"
                } catch (_: Exception) { continue }
            }
            startActivity(alarmIntent.apply { setPackage(null) })
            "⏰ Alarm set!"
        } catch (e: Exception) {
            "❌ Couldn't set alarm: ${e.localizedMessage}"
        }
    }

    private fun handleTimer(lower: String): String {
        return try {
            val minMatch = Regex("""(\d+)\s*minute""").find(lower)
            val secMatch = Regex("""(\d+)\s*second""").find(lower)
            val minutes = minMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val seconds = secMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val totalSeconds = (minutes * 60) + seconds

            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (totalSeconds > 0) putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, "ARIA Timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            startActivity(intent)
            "⏱️ Timer set for $minutes min $seconds sec!"
        } catch (e: Exception) {
            "❌ Couldn't set timer."
        }
    }

    private fun readNotifications(): String {
        val notifs = NotificationReaderService.recentNotifications
        return if (notifs.isEmpty()) "📭 No recent notifications."
        else "📬 Recent notifications:\n\n" + notifs.take(5).joinToString("\n─────────\n")
    }

    // ============ SCREEN / SECURITY ============

    private fun performScreenScan() {
        if (!ScreenReaderService.isServiceActive) {
            Toast.makeText(this, "Enable Accessibility Service first",
                Toast.LENGTH_LONG).show()
            return
        }
        sendMessage("Is this safe? Check my screen for threats.")
    }

    private suspend fun performSafetyCheck(): String {
        val screenContent = ScreenReaderService.getScreenContent()
        if (screenContent.isBlank()) {
            return "⚠️ Can't see your screen. Enable Accessibility Service in settings."
        }

        val localResult = securityAnalyzer.analyzeText(screenContent)
        val localVerdict = securityAnalyzer.getQuickVerdict(localResult)
        val warningText = if (localResult.warnings.isNotEmpty()) {
            "\n\n🔍 Findings:\n" + localResult.warnings.take(5).joinToString("\n")
        } else ""

        val aiAnalysis = aiManager.sendMessage(
            "Briefly analyze this for safety threats:",
            screenContent
        )

        return "$localVerdict$warningText\n\n🤖 AI:\n$aiAnalysis"
    }

    private suspend fun performScreenAnalysis(question: String): String {
        val screenContent = ScreenReaderService.getScreenContent()
        return if (screenContent.isNotBlank()) {
            aiManager.sendMessage(question, screenContent)
        } else {
            "⚠️ Can't read screen. Enable Accessibility Service."
        }
    }

    // ============ DEBUG HELPERS ============

    private fun findInstalledApps(searchQuery: String? = null): String {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val results = apps.filter { app ->
            val label = pm.getApplicationLabel(app).toString().lowercase()
            val pkg = app.packageName.lowercase()
            when {
                searchQuery == "all" -> true
                searchQuery != null -> label.contains(searchQuery) || pkg.contains(searchQuery)
                else -> listOf("youtube", "clock", "alarm", "camera", "chrome", "maps",
                    "gmail", "gallery", "whatsapp", "telegram").any {
                    label.contains(it) || pkg.contains(it)
                }
            }
        }.map { app ->
            "${pm.getApplicationLabel(app)} = ${app.packageName}"
        }

        return if (results.isEmpty())
            "No apps found${if (searchQuery != null) " matching '$searchQuery'" else ""}"
        else "${if (searchQuery == "all") "First 50 apps" else "Found"}:\n" +
                results.take(if (searchQuery == "all") 50 else results.size)
                    .joinToString("\n")
    }

    private fun listAllYouTubeApps(): String {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                val label = pm.getApplicationLabel(it).toString().lowercase()
                val pkg = it.packageName.lowercase()
                label.contains("you") || pkg.contains("you") ||
                        label.contains("tube") || pkg.contains("tube") ||
                        label.contains("video") || pkg.contains("video")
            }
        return if (apps.isEmpty()) "No YouTube-like apps found"
        else "Possible YouTube apps:\n" + apps.joinToString("\n") {
            "${pm.getApplicationLabel(it)} = ${it.packageName}"
        }
    }

    // ============ SCROLL ============

    private fun scrollToBottom() {
        if (ChatRepository.messages.isNotEmpty()) {
            binding.rvMessages.smoothScrollToPosition(ChatRepository.messages.size - 1)
        }
    }
}