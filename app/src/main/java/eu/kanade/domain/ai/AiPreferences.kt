package eu.kanade.domain.ai

import tachiyomi.core.common.preference.PreferenceStore

class AiPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun enableAi() = preferenceStore.getBoolean("enable_ai", false)

    fun aiEngine() = preferenceStore.getString("ai_engine", "gemini")

    fun geminiApiKey() = preferenceStore.getString("gemini_api_key", "")

    fun geminiModel() = preferenceStore.getString("gemini_model", "gemini-flash-latest")

    fun groqApiKey() = preferenceStore.getString("groq_api_key", "")

    fun deepseekApiKey() = preferenceStore.getString("deepseek_api_key", "")

    fun deepseekModel() = preferenceStore.getString("deepseek_model", "deepseek-chat")

    fun opencodeApiKey() = preferenceStore.getString("opencode_api_key", "")

    fun opencodeModel() = preferenceStore.getString("opencode_model", "deepseek-v4-flash-free")

    fun literouterApiKey() = preferenceStore.getString("literouter_api_key", "")

    fun literouterModel() = preferenceStore.getString("literouter_model", "deepseek-v4-flash-free")

    fun tokenreplyApiKey() = preferenceStore.getString("tokenreply_api_key", "")

    fun tokenreplyModel() = preferenceStore.getString("tokenreply_model", "deepseek-v4-flash-free")

    fun openaiApiKey() = preferenceStore.getString("openai_api_key", "")

    fun openaiModel() = preferenceStore.getString("openai_model", "gpt-4o-mini")

    fun anthropicApiKey() = preferenceStore.getString("anthropic_api_key", "")

    fun anthropicModel() = preferenceStore.getString("anthropic_model", "claude-3-5-sonnet-20241022")

    fun openrouterApiKey() = preferenceStore.getString("openrouter_api_key", "")

    fun openrouterModel() = preferenceStore.getString("openrouter_model", "openai/gpt-4o-mini")

    fun togetherApiKey() = preferenceStore.getString("together_api_key", "")

    fun togetherModel() = preferenceStore.getString("together_model", "meta-llama/Llama-3.3-70B-Instruct-Turbo")

    fun groqModel() = preferenceStore.getString("groq_model", "llama-3.3-70b-versatile")

    // Assistant
    fun enableAiAssistant() = preferenceStore.getBoolean("enable_ai_assistant", true)

    fun aiAssistantLogs() = preferenceStore.getBoolean("ai_assistant_logs", true)

    fun aiAssistantLibrary() = preferenceStore.getBoolean("ai_assistant_library", true)

    fun aiAssistantEverythingMoe() = preferenceStore.getBoolean("ai_assistant_everything_moe", true)

    fun aiSystemPrompt() = preferenceStore.getString("ai_system_prompt", "")

    // Statistics
    fun enableAiStatistics() = preferenceStore.getBoolean("enable_ai_statistics", true)
    fun lastStatsAnalysis() = preferenceStore.getString("last_stats_analysis", "")

    // Profile
    fun profilePhotoUri() = preferenceStore.getString("profile_photo_uri", "")
    fun displayName() = preferenceStore.getString("display_name", "Anime Explorer")

    fun activeSessionId() = preferenceStore.getLong("active_ai_session_id", -1L)

    // Circuit Breaker
    fun lastAiRequestTime() = preferenceStore.getLong("last_ai_request_time", 0L)
    fun hourlyAiRequestCount() = preferenceStore.getInt("hourly_ai_request_count", 0)
    fun isCircuitBreakerTripped() = preferenceStore.getBoolean("ai_circuit_breaker_tripped", false)
    fun isRequestPending() = preferenceStore.getBoolean("ai_request_pending", false)
}
