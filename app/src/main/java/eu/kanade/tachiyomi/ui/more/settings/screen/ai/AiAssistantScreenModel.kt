package eu.kanade.tachiyomi.ui.more.settings.screen.ai

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.tachiyomi.data.ai.AiManager
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.ai.model.ChatMessage
import tachiyomi.domain.ai.model.ChatSession
import tachiyomi.domain.ai.repository.ChatRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AiAssistantScreenModel(
    private val chatRepository: ChatRepository = Injekt.get(),
    private val aiPreferences: AiPreferences = Injekt.get(),
    private val aiManager: AiManager = Injekt.get(),
) : StateScreenModel<AiAssistantScreenModel.State>(State()) {

    val sessions: StateFlow<ImmutableList<ChatSession>> = combine(
        chatRepository.getSessions(),
        state.map { it.activeSessionId }.distinctUntilChanged(),
    ) { list, activeSessionId ->
        list.filter { it.messageCount > 0 || it.id == activeSessionId }
            .toImmutableList()
    }
        .stateIn(screenModelScope, SharingStarted.Lazily, persistentListOf())

    init {
        screenModelScope.launchIO {
            val prefSessionId = aiPreferences.activeSessionId().get()
            val allSessions = chatRepository.getSessions().firstOrNull().orEmpty()

            // Cleanup truly empty sessions on start (except pref active one)
            val emptySessionIds = allSessions
                .filter { it.messageCount == 0L && it.id != prefSessionId }
                .map { it.id }
            if (emptySessionIds.isNotEmpty()) {
                chatRepository.deleteSessions(emptySessionIds)
            }

            val initialSession = when {
                // 1. Try last active
                prefSessionId != -1L && allSessions.any { it.id == prefSessionId } -> {
                    allSessions.first { it.id == prefSessionId }
                }
                // 2. Try most recent with messages
                allSessions.any { it.messageCount > 0 } -> {
                    allSessions.first { it.messageCount > 0 }
                }
                // 3. Fallback to any session
                allSessions.isNotEmpty() -> {
                    allSessions.first()
                }
                // 4. Create new
                else -> null
            }

            if (initialSession != null) {
                switchSession(initialSession.id)
            } else {
                createNewSession()
            }
        }

        // Auto-create session ONLY if list becomes empty after filtering
        // and we are not already initializing
        screenModelScope.launchIO {
            sessions.collectLatest { list ->
                if (list.isEmpty() && state.value.activeSessionId != null) {
                    createNewSession()
                }
            }
        }
    }

    private var messageCollectionJob: kotlinx.coroutines.Job? = null

    private fun loadSession(sessionId: Long) {
        // Update ID immediately to prevent race conditions in sendMessage
        mutableState.update { it.copy(activeSessionId = sessionId) }

        messageCollectionJob?.cancel()
        messageCollectionJob = screenModelScope.launchIO {
            chatRepository.getMessagesBySessionId(sessionId).collectLatest { messages ->
                mutableState.update { it.copy(messages = messages.toImmutableList()) }
            }
        }
    }

    fun createNewSession() {
        screenModelScope.launchIO {
            // Find existing empty session to reuse instead of creating a new one
            // Check repo directly because sessions.value is filtered
            val existingEmpty = chatRepository.getSessions().firstOrNull()?.firstOrNull { it.messageCount == 0L }

            if (existingEmpty != null) {
                switchSession(existingEmpty.id)
                return@launchIO
            }

            val sessionId = chatRepository.insertSession("New Analytic Session")
            aiPreferences.activeSessionId().set(sessionId)
            loadSession(sessionId)
        }
    }

    fun switchSession(sessionId: Long) {
        aiPreferences.activeSessionId().set(sessionId)
        loadSession(sessionId)
    }

    fun deleteSession(sessionId: Long) {
        screenModelScope.launchIO {
            chatRepository.deleteSession(sessionId)
            if (state.value.activeSessionId == sessionId) {
                aiPreferences.activeSessionId().set(-1L)
                // The sessions collector will trigger createNewSession if empty
            }
        }
    }

    fun deleteSessions(sessionIds: List<Long>) {
        screenModelScope.launchIO {
            chatRepository.deleteSessions(sessionIds)
            if (state.value.activeSessionId in sessionIds) {
                aiPreferences.activeSessionId().set(-1L)
                // The sessions collector will trigger createNewSession if empty
            }
        }
    }

    fun toggleSessionPin(sessionId: Long, isPinned: Boolean) {
        screenModelScope.launchIO {
            chatRepository.updateSessionPinned(sessionId, !isPinned)
        }
    }

    fun sendMessage(query: String) {
        if (query.isBlank() || state.value.isLoading) return

        screenModelScope.launchIO {
            val sessionId = state.value.activeSessionId ?: return@launchIO

            // Check if session still exists
            if (chatRepository.getSessionById(sessionId) == null) {
                createNewSession()
                return@launchIO
            }

            // 1. Save User Message
            chatRepository.insertMessage(sessionId, "user", query)

            if (query.trim().equals("/reset", ignoreCase = true)) {
                aiManager.resetCircuitBreaker()
                chatRepository.insertMessage(sessionId, "model", "Neural stability reset. Circuit breaker cleared.")
                return@launchIO
            }

            // 2. Update Loading State
            mutableState.update { it.copy(isLoading = true, streamingMessage = "") }

            // 3. Call AI with streaming
            val history = state.value.messages.map { AiManager.ChatMessage(it.role, it.content) }
            val fullResponse = StringBuilder()

            try {
                aiManager.chatWithAssistantStream(query, history).collect { chunk ->
                    fullResponse.append(chunk)
                    mutableState.update { it.copy(streamingMessage = fullResponse.toString()) }
                }

                // 4. Save AI Response once complete
                val response = fullResponse.toString()
                if (response.isNotBlank()) {
                    chatRepository.insertMessage(sessionId, "model", response)

                    // 5. Update Session Title if it's the first message from human
                    val userMessages = state.value.messages.filter { it.role == "user" }
                    if (userMessages.size == 1) {
                        updateSessionTitle(sessionId, query)
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                chatRepository.insertMessage(sessionId, "model", "System error during uplink: ${e.message}")
            } finally {
                mutableState.update { it.copy(isLoading = false, streamingMessage = null) }
            }
        }
    }

    private suspend fun updateSessionTitle(sessionId: Long, query: String) {
        val title = if (query.length > 30) query.take(27) + "..." else query
        chatRepository.updateSessionTitle(sessionId, title)
    }

    fun toggleSessionSelection(sessionId: Long) {
        mutableState.update { state ->
            val newSelection = if (state.selectedSessionIds.contains(sessionId)) {
                state.selectedSessionIds - sessionId
            } else {
                state.selectedSessionIds + sessionId
            }
            state.copy(selectedSessionIds = newSelection)
        }
    }

    fun clearSessionSelection() {
        mutableState.update { it.copy(selectedSessionIds = emptySet()) }
    }

    fun clearAllSessions() {
        screenModelScope.launchIO {
            chatRepository.deleteAllSessions()
            aiPreferences.activeSessionId().set(-1L)
            createNewSession()
        }
    }

    data class State(
        val activeSessionId: Long? = null,
        val messages: ImmutableList<ChatMessage> = persistentListOf(),
        val isLoading: Boolean = false,
        val selectedSessionIds: Set<Long> = emptySet(),
        val streamingMessage: String? = null,
    )
}
