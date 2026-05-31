package com.cherinet.aria.voice

/**
 * VoiceStateMachine: Traffic controller for ARIA's voice system.
 *
 * At any moment ARIA's voice is in exactly ONE state.
 * The state machine makes sure transitions are valid.
 *
 * Example valid flow:
 *   IDLE → DETECTING (wake word heard)
 *   DETECTING → LISTENING (chime played, ready)
 *   LISTENING → PROCESSING (user finished speaking)
 *   PROCESSING → SPEAKING (AI responded)
 *   SPEAKING → IDLE (ARIA finished talking)
 *
 * Invalid transitions get rejected silently.
 * This prevents bugs like ARIA trying to listen while speaking.
 */

enum class VoiceState {
    IDLE,        // Waiting for wake word. Near-zero battery usage.
    DETECTING,   // Wake word heard. Playing chime. Setting up STT.
    LISTENING,   // Recording user's command.
    PROCESSING,  // Sent to server. Waiting for AI response.
    SPEAKING,    // TTS playing ARIA's response.
    ERROR        // Something went wrong. Will return to IDLE.
}

// Which transitions are allowed from each state
private val VALID_TRANSITIONS = mapOf(
    VoiceState.IDLE       to setOf(VoiceState.DETECTING, VoiceState.LISTENING, VoiceState.ERROR),
    VoiceState.DETECTING  to setOf(VoiceState.LISTENING, VoiceState.IDLE, VoiceState.ERROR),
    VoiceState.LISTENING  to setOf(VoiceState.PROCESSING, VoiceState.IDLE, VoiceState.ERROR),
    VoiceState.PROCESSING to setOf(VoiceState.SPEAKING, VoiceState.IDLE, VoiceState.ERROR),
    VoiceState.SPEAKING   to setOf(VoiceState.IDLE, VoiceState.DETECTING, VoiceState.ERROR),
    VoiceState.ERROR      to setOf(VoiceState.IDLE)
)

class VoiceStateMachine {

    @Volatile
    var current: VoiceState = VoiceState.IDLE
        private set

    // Called every time state changes — UI listens to this
    var onStateChanged: ((from: VoiceState, to: VoiceState) -> Unit)? = null

    @Synchronized
    fun transition(to: VoiceState): Boolean {
        val allowed = VALID_TRANSITIONS[current] ?: emptySet()
        return if (to in allowed) {
            val from = current
            current = to
            onStateChanged?.invoke(from, to)
            true
        } else {
            android.util.Log.w("VoiceState", "Rejected: $current → $to")
            false
        }
    }

    // Force back to IDLE — used for emergency reset
    @Synchronized
    fun reset() {
        val from = current
        current = VoiceState.IDLE
        if (from != VoiceState.IDLE) {
            onStateChanged?.invoke(from, VoiceState.IDLE)
        }
    }

    fun isIdle()       = current == VoiceState.IDLE
    fun isListening()  = current == VoiceState.LISTENING
    fun isSpeaking()   = current == VoiceState.SPEAKING
    fun isProcessing() = current == VoiceState.PROCESSING
    fun isBusy()       = current != VoiceState.IDLE && current != VoiceState.ERROR
}