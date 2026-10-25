package tachiyomi.domain.ai.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.ai.model.ChatMessage
import tachiyomi.domain.ai.model.ChatSession

interface ChatRepository {
    fun getSessions(): Flow<List<ChatSession>>
    suspend fun getSessionById(id: Long): ChatSession?
    fun getMessagesBySessionId(sessionId: Long): Flow<List<ChatMessage>>
    suspend fun insertSession(title: String): Long
    suspend fun updateSessionTitle(id: Long, title: String)
    suspend fun updateSessionLastMessageAt(id: Long, lastMessageAt: Long)
    suspend fun updateSessionPinned(id: Long, isPinned: Boolean)
    suspend fun insertMessage(sessionId: Long, role: String, content: String)
    suspend fun deleteSession(id: Long)
    suspend fun deleteSessions(ids: List<Long>)
    suspend fun deleteAllSessions()
}
