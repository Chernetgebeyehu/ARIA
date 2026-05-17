package com.example.aiassistant.service

import android.app.*
import android.content.Intent
import android.os.*
import android.speech.*
import androidx.annotation.RequiresApi
import com.example.aiassistant.ui.ChatActivity
import java.util.Locale

class WakeWordService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0

    companion object {
        var isRunning = false
            private set
        var isPaused = false

        private const val WAKE_WORD = "hey aria"
        private const val WAKE_WORD_2 = "hey area"
        private const val WAKE_WORD_3 = "aria"
        private const val WAKE_WORD_4 = "hey ria"

        private const val CHANNEL_ID = "wake_word_channel"

        // FIX: Use a different notification ID from FloatingButtonService (which uses 1001)
        private const val NOTIFICATION_ID = 1002
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.ECLAIR)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification())

        handler.postDelayed({
            if (isRunning) startWakeWordListener()
        }, 3000)

        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    @RequiresApi(Build.VERSION_CODES.FROYO)
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wake Word Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ARIA Active")
            .setContentText("Listening for wake word...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.FROYO)
    private fun startWakeWordListener() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        createRecognizer()
        scheduleListening(1500)
    }

    @RequiresApi(Build.VERSION_CODES.FROYO)
    private fun createRecognizer() {
        destroyRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(createListener())
    }

    @RequiresApi(Build.VERSION_CODES.FROYO)
    private fun scheduleListening(delayMs: Long) {
        handler.postDelayed({
            if (isRunning && !isPaused && !isListening) {
                beginListening()
            }
        }, delayMs)
    }

    @RequiresApi(Build.VERSION_CODES.FROYO)
    private fun beginListening() {
        if (!isRunning || isPaused || isListening) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            retryCount = 0
        } catch (e: Exception) {
            isListening = false
            retryCount++
            scheduleListening((2000L * retryCount).coerceAtMost(15000L))
        }
    }

    private fun createListener() = @RequiresApi(Build.VERSION_CODES.FROYO)
    object : RecognitionListener {

        override fun onResults(results: Bundle?) {
            isListening = false

            val matches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: return

            for (heard in matches) {
                val lower = heard.lowercase()
                if (lower.contains(WAKE_WORD) ||
                    lower.contains(WAKE_WORD_2) ||
                    lower.contains(WAKE_WORD_3) ||
                    lower.contains(WAKE_WORD_4)
                ) {
                    openChat()
                    scheduleListening(4000)
                    return
                }
            }

            scheduleListening(2000)
        }

        override fun onError(error: Int) {
            isListening = false
            scheduleListening(
                when (error) {
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 8000L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        createRecognizer()
                        4000L
                    }
                    else -> 3000L
                }
            )
        }

        override fun onEndOfSpeech() { isListening = false }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    @RequiresApi(Build.VERSION_CODES.FROYO)
    private fun destroyRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        isListening = false
    }

    private fun openChat() {
        val intent = Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }
}