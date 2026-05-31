package com.cherinet.aria.ai

/**
 * OpenRouterModels: A menu of AI brains ARIA can use.
 *
 * Think of this like choosing between different assistants:
 * - Fast assistant: quick but not the deepest thinker
 * - Smart assistant: slower but much better at complex things
 * - Free assistant: no cost, but limited usage
 *
 * OpenRouter is like a switchboard that connects you to
 * many different AI companies from one single API.
 */
object OpenRouterModels {

    // ═══ FREE MODELS ═══
    const val GEMMA_3_4B = "google/gemma-3-4b-it:free"
    const val LLAMA_3_8B = "meta-llama/llama-3-8b-instruct:free"
    const val PHI_3_MINI = "microsoft/phi-3-mini-128k-instruct:free"

    // ═══ PAID MODELS ═══
    const val GEMMA_3_27B = "google/gemma-3-27b-it"
    const val MISTRAL_7B = "mistralai/mistral-7b-instruct"
    const val CLAUDE_HAIKU = "anthropic/claude-3-haiku"

    // ═══ DEFAULTS ═══
    const val DEFAULT = GEMMA_3_4B
    const val SMART = CLAUDE_HAIKU
    const val FAST = LLAMA_3_8B

    val ALL_MODELS = listOf(
        ModelInfo(GEMMA_3_4B, "Gemma 3 4B (Free)", "Fast, free, good for commands"),
        ModelInfo(LLAMA_3_8B, "Llama 3 8B (Free)", "Good reasoning, free tier"),
        ModelInfo(PHI_3_MINI, "Phi-3 Mini (Free)", "Small but smart, free"),
        ModelInfo(GEMMA_3_27B, "Gemma 3 27B", "Smarter, costs tokens"),
        ModelInfo(MISTRAL_7B, "Mistral 7B", "Great quality/speed balance"),
        ModelInfo(CLAUDE_HAIKU, "Claude Haiku", "Best quality, costs tokens")
    )

    data class ModelInfo(
        val id: String,
        val displayName: String,
        val description: String
    )
}