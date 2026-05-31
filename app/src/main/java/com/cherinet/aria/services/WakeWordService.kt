package com.cherinet.aria.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.cherinet.aria.ui.ChatActivity
import com.cherinet.aria.voice.VoiceManager
import java.util.Locale

/**
 * WakeWordService v3 — Zero-gap always-on wake word detection.
 *
 * Key improvements over v2:
 * - Restarts IMMEDIATELY (300ms) after every result or error
 * - Watchdog: if deaf for 6+ seconds, force restart
 * - Handles all SpeechRecognizer failure modes
 * - Recognizer recreated on ERROR_CLIENT and ERROR_RECOGNIZER_BUSY
 * - Notification updates when wake word fires
 */
class WakeWordService : Service() {

    companion object {
        var isRunning = false
            private set
        var isPaused = false

        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "aria_wake_v3"
        private const val NOTIFICATION_ID = 1002
        private const val WATCHDOG_MS = 6000L

        private val WAKE_WORDS = listOf(
            "hey aria", "hey area", "aria",
            "hey ria", "okay aria", "ok aria",
            "hey arya", "hey areia"
        )
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var isListening = false
    @Volatile private var recognizerReady = false

    private val watchdog = object : Runnable {
        override fun run() {
            if (isRunning && !isPaused && !isListening) {
                Log.w(TAG, "Watchdog: not listening — forcing restart")
                restartListening(0)
            }
            if (isRunning) handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification("👂 Listening for \"Hey ARIA\""))
        handler.postDelayed({
            if (isRunning) {
                initRecognizer()
                startListeningCycle()
                handler.postDelayed(watchdog, WATCHDOG_MS)
            }
        }, 2000)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
    }

    private fun initRecognizer() {
        destroyRecognizer()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(createListener())
        recognizerReady = true
    }

    private fun destroyRecognizer() {
        try { speechRecognizer?.cancel(); speechRecognizer?.destroy() }
        catch (_: Exception) {}
        speechRecognizer = null
        recognizerReady = false
        isListening = false
    }

    private fun startListeningCycle() {
        if (!isRunning || isPaused) return
        if (!recognizerReady) initRecognizer()
        beginListening()
    }

    private fun beginListening() {
        if (!isRunning || isPaused || isListening || !recognizerReady) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
            restartListening(1000)
        }
    }

    private fun restartListening(delayMs: Long = 300) {
        isListening = false
        if (!isRunning || isPaused) return
        handler.postDelayed({
            if (isRunning && !isPaused && !isListening) startListeningCycle()
        }, delayMs)
    }

    private fun createListener() = object : RecognitionListener {

        override fun onResults(results: android.os.Bundle?) {
            isListening = false
            val matches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: run { restartListening(300); return }

            for (phrase in matches) {
                val lower = phrase.lowercase().trim()
                for (wakeWord in WAKE_WORDS) {
                    if (lower.contains(wakeWord)) {
                        Log.i(TAG, "✅ Wake word: '$phrase'")
                        onWakeWordDetected()
                        restartListening(3000)
                        return
                    }
                }
            }
            restartListening(300)
        }

        override fun onError(error: Int) {
            isListening = false
            val delay = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT  -> 300L
                SpeechRecognizer.ERROR_AUDIO           -> 1000L
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 5000L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    handler.postDelayed({
                        if (isRunning && !isPaused) { initRecognizer(); restartListening(500) }
                    }, 500)
                    return
                }
                else -> 1000L
            }
            restartListening(delay)
        }

        override fun onEndOfSpeech()                           { isListening = false }
        override fun onReadyForSpeech(p: android.os.Bundle?)   {}
        override fun onBeginningOfSpeech()                     {}
        override fun onRmsChanged(r: Float)                    {}
        override fun onBufferReceived(b: ByteArray?)           {}
        override fun onPartialResults(p: android.os.Bundle?)   {}
        override fun onEvent(t: Int, p: android.os.Bundle?)    {}
    }

    private fun onWakeWordDetected() {
        notify("🔊 Hey ARIA detected!")
        sendBroadcast(Intent(VoiceManager.WAKE_WORD_ACTION))
        startActivity(Intent(this, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("start_listening", true)
        })
        handler.postDelayed({ if (isRunning) notify("👂 Listening for \"Hey ARIA\"") }, 2000)
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "ARIA Wake Word",
            NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(msg: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ARIA")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notify(msg: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(msg))
    }
}