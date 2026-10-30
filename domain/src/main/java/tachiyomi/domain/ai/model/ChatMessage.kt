package tachiyomi.domain.ai.model

data class ChatMessage(
    val id: Long,
    val sessionId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
)
