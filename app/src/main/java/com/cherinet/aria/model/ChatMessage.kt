package com.cherinet.aria.model

/**
 * ChatMessage: One single message in the conversation.
 *
 * Like a single text bubble — either from you or from ARIA.
 * isUser = true means YOU sent it.
 * isUser = false means ARIA sent it.
 *
 * ChatRepository is a shared storage box that holds all messages
 * while the app is open. When the app is closed, they disappear.
 * (We'll fix this with a real database in a later phase.)
 */
data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

object ChatRepository {
    val messages = mutableListOf<ChatMessage>()
}