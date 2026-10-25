package tachiyomi.domain.ai.model

data class ChatSession(
    val id: Long,
    val title: String,
    val lastMessageAt: Long,
    val isPinned: Boolean,
    val messageCount: Long = 0,
)
