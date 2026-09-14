package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.ai.AiManager
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsAiScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_ai

    @Composable
    override fun getPreferences(): List<Preference> {
        val aiPreferences = remember { Injekt.get<AiPreferences>() }
        val navigator = LocalNavigator.currentOrThrow
        val enableAi by aiPreferences.enableAi().collectAsState()

        return if (enableAi) {
            listOf(
                getMainGroup(aiPreferences, navigator),
                getAssistantGroup(aiPreferences),
                getStatisticsGroup(aiPreferences),
            )
        } else {
            listOf(
                getMainGroup(aiPreferences, navigator),
            )
        }
    }

    @Composable
    private fun getMainGroup(aiPreferences: AiPreferences, navigator: cafe.adriel.voyager.navigator.Navigator): Preference.PreferenceGroup {
        val aiManager = remember { Injekt.get<AiManager>() }
        val enableAiPref = aiPreferences.enableAi()
        val enableAi by enableAiPref.collectAsState()
        val aiEngine by aiPreferences.aiEngine().collectAsState()

        val geminiKey by aiPreferences.geminiApiKey().collectAsState()
        val deepseekKey by aiPreferences.deepseekApiKey().collectAsState()
        val opencodeKey by aiPreferences.opencodeApiKey().collectAsState()
        val literouterKey by aiPreferences.literouterApiKey().collectAsState()
        val tokenreplyKey by aiPreferences.tokenreplyApiKey().collectAsState()
        val openaiKey by aiPreferences.openaiApiKey().collectAsState()
        val anthropicKey by aiPreferences.anthropicApiKey().collectAsState()
        val openrouterKey by aiPreferences.openrouterApiKey().collectAsState()
        val togetherKey by aiPreferences.togetherApiKey().collectAsState()
        val groqKey by aiPreferences.groqApiKey().collectAsState()

        val activeKey = when (aiEngine) {
            "gemini" -> geminiKey
            "deepseek" -> deepseekKey
            "opencode" -> opencodeKey
            "literouter" -> literouterKey
            "tokenreply" -> tokenreplyKey
            "openai" -> openaiKey
            "anthropic" -> anthropicKey
            "openrouter" -> openrouterKey
            "together" -> togetherKey
            else -> groqKey
        }

        val availableModels by produceState(
            initialValue = emptyList<String>(),
            key1 = aiEngine,
            key2 = activeKey,
        ) {
            value = aiManager.fetchAvailableModels(aiEngine)
        }

        val currentModelPref = when (aiEngine) {
            "gemini" -> aiPreferences.geminiModel()
            "deepseek" -> aiPreferences.deepseekModel()
            "opencode" -> aiPreferences.opencodeModel()
            "literouter" -> aiPreferences.literouterModel()
            "tokenreply" -> aiPreferences.tokenreplyModel()
            "openai" -> aiPreferences.openaiModel()
            "anthropic" -> aiPreferences.anthropicModel()
            "openrouter" -> aiPreferences.openrouterModel()
            "together" -> aiPreferences.togetherModel()
            else -> aiPreferences.groqModel()
        }

        val modelEntries = remember(availableModels) {
            if (availableModels.isNotEmpty()) {
                availableModels.associateWith { it }.toPersistentMap()
            } else {
                persistentMapOf("loading" to "Fetching accessible models...")
            }
        }

        val activeApiKeyPref = when (aiEngine) {
            "gemini" -> aiPreferences.geminiApiKey()
            "deepseek" -> aiPreferences.deepseekApiKey()
            "opencode" -> aiPreferences.opencodeApiKey()
            "literouter" -> aiPreferences.literouterApiKey()
            "tokenreply" -> aiPreferences.tokenreplyApiKey()
            "openai" -> aiPreferences.openaiApiKey()
            "anthropic" -> aiPreferences.anthropicApiKey()
            "openrouter" -> aiPreferences.openrouterApiKey()
            "together" -> aiPreferences.togetherApiKey()
            else -> aiPreferences.groqApiKey()
        }

        val activeApiKeyTitle = when (aiEngine) {
            "gemini" -> stringResource(MR.strings.pref_ai_gemini_api_key)
            "deepseek" -> "DeepSeek API Key"
            "opencode" -> "OpenCode Zen API Key"
            "literouter" -> "LiteRouter API Key"
            "tokenreply" -> "TokenReply API Key"
            "openai" -> "OpenAI API Key"
            "anthropic" -> "Anthropic API Key"
            "openrouter" -> "OpenRouter API Key"
            "together" -> "Together AI API Key"
            else -> "Groq API Key"
        }

        val activeApiKeySubtitle = when (aiEngine) {
            "gemini" -> "Supports multiple comma-separated keys for health rotation"
            "deepseek" -> "Used for official DeepSeek API"
            "opencode" -> "Used for OpenCode Zen endpoint"
            "literouter" -> "Used for LiteRouter endpoint"
            "tokenreply" -> "Used for TokenReply endpoint"
            "openai" -> "Used for GPT-4o / o-series models"
            "anthropic" -> "Used for Claude 3.5 Sonnet / Haiku models"
            "openrouter" -> "Unified access to open & closed models"
            "together" -> "High-performance inference for open-weights models"
            else -> "Used for high-speed inference"
        }

        return Preference.PreferenceGroup(
            title = "AI Configuration",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    pref = enableAiPref,
                    title = "Enable AI Features",
                    subtitle = "Allows smart search, summaries, and diagnostic help",
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = aiPreferences.aiEngine(),
                    title = "AI Model Provider",
                    subtitle = "Choose the active AI service provider",
                    entries = persistentMapOf(
                        "gemini" to "Google Gemini",
                        "deepseek" to "DeepSeek V4 (Official)",
                        "opencode" to "OpenCode Zen",
                        "literouter" to "LiteRouter",
                        "tokenreply" to "TokenReply",
                        "openai" to "OpenAI (ChatGPT)",
                        "anthropic" to "Anthropic (Claude)",
                        "openrouter" to "OpenRouter",
                        "together" to "Together AI",
                        "groq" to "Groq",
                    ),
                    enabled = enableAi,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = activeApiKeyPref,
                    title = activeApiKeyTitle,
                    subtitle = activeApiKeySubtitle,
                    enabled = enableAi,
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = currentModelPref,
                    title = "Accessible Models",
                    subtitle = if (activeKey.isNotBlank()) "Dynamically fetched based on API key permissions" else "Enter API key above to load accessible models",
                    entries = modelEntries,
                    enabled = enableAi,
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = currentModelPref,
                    title = "Custom Model Override",
                    subtitle = "Enter custom model tag if not listed in accessible models",
                    enabled = enableAi && aiEngine != "gemini",
                ),
            ),
        )
    }

    @Composable
    private fun getAssistantGroup(aiPreferences: AiPreferences): Preference.PreferenceGroup {
        val enableAi by aiPreferences.enableAi().collectAsState()

        return Preference.PreferenceGroup(
            title = "Diagnostic Assistant",
            enabled = enableAi,
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    pref = aiPreferences.enableAiAssistant(),
                    title = "Enable Assistant",
                    subtitle = "Enables conversational diagnostics",
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = aiPreferences.aiAssistantLogs(),
                    title = "Ingest Error Logs",
                    subtitle = "Allows the assistant to analyze stack traces",
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = aiPreferences.aiAssistantLibrary(),
                    title = "Ingest Library Context",
                    subtitle = "Allows the assistant to analyze your collection",
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = aiPreferences.aiAssistantEverythingMoe(),
                    title = "Extension Community Intelligence",
                    subtitle = "Allows the assistant to use EverythingMoe for mirrors, features, and site health",
                ),
                Preference.PreferenceItem.MultiLineEditTextPreference(
                    pref = aiPreferences.aiSystemPrompt(),
                    title = "Custom System Prompt",
                    subtitle = "Override the default behavioral instructions",
                    canBeBlank = true,
                ),
            ),
        )
    }

    @Composable
    private fun getStatisticsGroup(aiPreferences: AiPreferences): Preference.PreferenceGroup {
        val enableAi by aiPreferences.enableAi().collectAsState()

        return Preference.PreferenceGroup(
            title = "Advanced Analytics",
            enabled = enableAi,
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    pref = aiPreferences.enableAiStatistics(),
                    title = "Data Summarization",
                    subtitle = "Generates technical summaries in the Statistics module",
                ),
            ),
        )
    }
}
