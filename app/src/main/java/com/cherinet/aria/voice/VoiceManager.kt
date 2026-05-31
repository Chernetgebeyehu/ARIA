package com.cherinet.aria.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID

/**
 * VoiceManager v2 — Full voice pipeline with state machine.
 *
 * What's new vs v1:
 * 1. State machine — voice always knows what it's doing
 * 2. Wake word receiver — listens for broadcasts from WakeWordService
 * 3. Interruption detection — user speaks while ARIA talks → ARIA stops
 * 4. Listening timeout — auto-stop if user doesn't speak for 8 seconds
 * 5. TTS callbacks — ARIA knows exactly when speech starts and ends
 * 6. Chime feedback — plays a sound when wake word detected
 */
class VoiceManager(
    private val context: Context,
    val stateMachine: VoiceStateMachine = VoiceStateMachine()
) {

    companion object {
        private const val TAG = "VoiceManager"
        private const val LISTENING_TIMEOUT_MS = 8000L
        const val WAKE_WORD_ACTION = "com.cherinet.aria.WAKE_WORD_DETECTED"
    }

    // ─── AUDIO COMPONENTS ─────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isTtsReady = false

    // ─── STATE ────────────────────────────────────────────────────────────────
    var isListening = false
        private set
    var isSpeaking = false
        private set

    // ─── CALLBACKS — set by ChatActivity ──────────────────────────────────────
    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningStarted: (() -> Unit)? = null
    var onListeningStopped: (() -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingFinished: (() -> Unit)? = null
    var onWakeWordDetected: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null

    // ─── INTERNAL ─────────────────────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var wakeWordReceiver: BroadcastReceiver? = null

    // ─── INIT ─────────────────────────────────────────────────────────────────

    fun initialize() {
        initTts()
        initSpeechRecognizer()
        registerWakeWordReceiver()
        Log.i(TAG, "VoiceManager initialized")
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(1.05f)
                isTtsReady = true

                // TTS callbacks — know exactly when ARIA starts/stops speaking
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        isSpeaking = true
                        mainHandler.post {
                            stateMachine.transition(VoiceState.SPEAKING)
                            onSpeakingStarted?.invoke()
                        }
                    }
                    override fun onDone(utteranceId: String) {
                        isSpeaking = false
                        mainHandler.post {
                            stateMachine.transition(VoiceState.IDLE)
                            onSpeakingFinished?.invoke()
                        }
                    }
                    @Deprecated("Deprecated in newer API")
                    override fun onError(utteranceId: String) {
                        isSpeaking = false
                        mainHandler.post {
                            stateMachine.reset()
                            onSpeakingFinished?.invoke()
                        }
                    }
                })
                Log.i(TAG, "TTS initialized")
            }
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "Speech recognition not available")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(createRecognitionListener())
        Log.i(TAG, "SpeechRecognizer initialized")
    }

    /**
     * Register receiver for wake word broadcasts from WakeWordService.
     *
     * WakeWordService sends a broadcast when it hears "Hey ARIA".
     * This receiver catches it and starts the listening phase.
     * Like a radio tuned to a specific frequency.
     */
    private fun registerWakeWordReceiver() {
        wakeWordReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WAKE_WORD_ACTION) {
                    Log.i(TAG, "Wake word broadcast received")
                    handleWakeWordDetected()
                }
            }
        }
        val filter = IntentFilter(WAKE_WORD_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(wakeWordReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                context,
                wakeWordReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    // ─── WAKE WORD HANDLING ───────────────────────────────────────────────────

    private fun handleWakeWordDetected() {
        mainHandler.post {
            onWakeWordDetected?.invoke()

            // If ARIA is speaking, interrupt it
            if (isSpeaking) {
                Log.i(TAG, "Interrupting TTS — wake word detected while speaking")
                stopSpeaking()
                onInterrupted?.invoke()
            }

            // Play chime to signal ARIA is listening
            playWakeChime()

            // Brief delay for chime, then start listening
            mainHandler.postDelayed({ startListening() }, 400)
        }
    }

    private fun playWakeChime() {
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_UP, 0.7f)
        } catch (e: Exception) {
            Log.d(TAG, "Chime failed: ${e.message}")
        }
    }

    // ─── STT ──────────────────────────────────────────────────────────────────

    fun startListening() {
        if (isListening) return

        // Recreate if needed
        if (speechRecognizer == null) initSpeechRecognizer()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            stateMachine.transition(VoiceState.LISTENING)
            onListeningStarted?.invoke()
            scheduleListeningTimeout()
            Log.i(TAG, "Listening started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}")
            onError?.invoke("Could not start voice recognition.")
            stateMachine.transition(VoiceState.ERROR)
            mainHandler.postDelayed({ stateMachine.reset() }, 2000)
        }
    }

    fun stopListening() {
        cancelTimeout()
        speechRecognizer?.stopListening()
        isListening = false
        onListeningStopped?.invoke()
    }

    private fun scheduleListeningTimeout() {
        cancelTimeout()
        timeoutRunnable = Runnable {
            if (isListening) {
                Log.w(TAG, "Listening timeout")
                stopListening()
                onError?.invoke("I didn't hear anything. Try again.")
                stateMachine.transition(VoiceState.ERROR)
                mainHandler.postDelayed({ stateMachine.reset() }, 2000)
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, LISTENING_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun createRecognitionListener() = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech detected")
            // User started speaking — cancel the timeout
            cancelTimeout()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Volume level changed.
            // If ARIA is speaking and user starts talking → interrupt
            if (isSpeaking && rmsdB > 1200f) {
                Log.i(TAG, "Interruption detected (RMS: $rmsdB)")
                stopSpeaking()
                onInterrupted?.invoke()
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
            isListening = false
            onListeningStopped?.invoke()
            cancelTimeout()
        }

        override fun onError(error: Int) {
            isListening = false
            cancelTimeout()
            onListeningStopped?.invoke()

            val msg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                SpeechRecognizer.ERROR_NETWORK -> "No internet for speech recognition"
                SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — please try again"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognizer busy"
                else -> "Voice recognition error ($error)"
            }

            Log.w(TAG, "STT error: $msg")

            // Recreate recognizer — it becomes invalid after error
            speechRecognizer?.destroy()
            speechRecognizer = null
            mainHandler.postDelayed({ initSpeechRecognizer() }, 500)

            onError?.invoke(msg)
            stateMachine.transition(VoiceState.ERROR)
            mainHandler.postDelayed({ stateMachine.reset() }, 3000)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            cancelTimeout()
            onListeningStopped?.invoke()

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""

            // Recreate for next session
            speechRecognizer?.destroy()
            speechRecognizer = null
            mainHandler.postDelayed({ initSpeechRecognizer() }, 200)

            if (text.isNotBlank()) {
                Log.i(TAG, "STT result: '$text'")
                stateMachine.transition(VoiceState.PROCESSING)
                onResult?.invoke(text)
            } else {
                onError?.invoke("Didn't catch that — please try again")
                stateMachine.transition(VoiceState.ERROR)
                mainHandler.postDelayed({ stateMachine.reset() }, 2000)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ─── TTS ──────────────────────────────────────────────────────────────────

    fun speak(text: String) {
        if (!isTtsReady) {
            Log.w(TAG, "TTS not ready")
            return
        }

        // Clean up text — TTS reads emoji as garbage otherwise
        val clean = text
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .replace("⚠️", "Warning.")
            .replace("🚨", "Alert!")
            .replace("✅", "Done.")
            .replace("❌", "Could not complete.")
            .replace("📞", "").replace("✉️", "").replace("⏰", "")
            .replace("⏱️", "").replace("🔍", "").replace("📬", "")
            .replace("\n", ". ")
            .replace("  ", " ")
            .trim()
            .take(500)

        if (clean.isBlank()) return

        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.i(TAG, "Speaking: '${clean.take(60)}...'")
    }

    fun stopSpeaking() {
        tts?.stop()
        isSpeaking = false
        onSpeakingFinished?.invoke()
        stateMachine.reset()
    }

    // ─── CLEANUP ──────────────────────────────────────────────────────────────

    fun shutdown() {
        cancelTimeout()
        try { wakeWordReceiver?.let { context.unregisterReceiver(it) } }
        catch (e: Exception) { /* already unregistered */ }

        speechRecognizer?.destroy()
        speechRecognizer = null

        tts?.stop()
        tts?.shutdown()
        tts = null

        isListening = false
        isSpeaking = false
        Log.i(TAG, "VoiceManager shut down")
    }
}