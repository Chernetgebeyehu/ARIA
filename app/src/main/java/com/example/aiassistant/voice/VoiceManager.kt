package com.example.aiassistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isTtsReady = false
    var isListening = false
        private set

    // Callbacks
    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningStarted: (() -> Unit)? = null
    var onListeningStopped: (() -> Unit)? = null

    fun initialize() {
        // Initialize Text-to-Speech
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.0f)
                isTtsReady = true
            }
        }

        // Initialize Speech Recognizer
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createListener())
        }
    }

    fun speak(text: String) {
        if (!isTtsReady) {
            initialize()
            return
        }

        // Clean text for better speech
        val cleanText = text
            .replace(Regex("[\\p{So}\\p{Cn}]"), "") // remove emoji
            .replace("⚠️", "Warning.")
            .replace("🚨", "Alert!")
            .replace("✅", "")
            .replace("❌", "No.")
            .replace("📞", "")
            .replace("✉️", "")
            .replace("🔍", "")
            .replace("🤖", "")
            .replace("\n", ". ")
            .replace("•", ",")
            .trim()
            .take(500)

        // Natural speaking speed
        tts?.setSpeechRate(0.95f)
        tts?.setPitch(1.05f)

        tts?.speak(
            cleanText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "aria_tts"
        )
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun startListening() {
        if (isListening) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                5000L
            )
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            onListeningStarted?.invoke()
        } catch (e: Exception) {
            onError?.invoke("Could not start voice recognition")
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        onListeningStopped?.invoke()
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            isListening = false
            onListeningStopped?.invoke()
        }

        override fun onError(error: Int) {
            isListening = false
            onListeningStopped?.invoke()
            val msg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                else -> "Voice recognition error"
            }
            onError?.invoke(msg)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            onListeningStopped?.invoke()
            val matches = results?.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION
            )
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                onResult?.invoke(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun shutdown() {
        tts?.shutdown()
        speechRecognizer?.destroy()
        tts = null
        speechRecognizer = null
    }
}