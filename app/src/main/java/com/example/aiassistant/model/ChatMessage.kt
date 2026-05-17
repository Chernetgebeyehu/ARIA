package com.example.aiassistant.model

data class ChatMessage(
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

// Simple in-memory storage — survives activity restarts
object ChatRepository {
    val messages = mutableListOf<ChatMessage>()
}