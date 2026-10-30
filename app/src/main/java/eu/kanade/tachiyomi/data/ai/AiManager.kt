package eu.kanade.tachiyomi.data.ai

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.domain.ai.AiPreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.ai.everythingmoe.EverythingMoeScraper
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

private val PACKAGE_PATTERN = Regex("""(eu\.kanade|app\.anizen|mpv|ffmpeg|AndroidRuntime|libc|DEBUG|System\.err|XLog|FileUtils|ActivityThread|InputDispatcher)""")
private val PII_REDACTION_PATTERN = Regex("""(?:authorization|cookie|set-cookie):\s*[^\n\r]+|(?<=\?|&)[^=]+=[^&\s]*|(?:[a-zA-Z0-9+_.-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})|(?:auth|token|key|password|secret|sid|session)=[a-zA-Z0-9._-]+""", RegexOption.IGNORE_CASE)
private val TRACE_TRIGGER_PATTERN = Regex("""(FATAL EXCEPTION|Native crash|SIGSEGV|SIGABRT|mpv: error|Check failed)""", RegexOption.IGNORE_CASE)
private val NATIVE_PC_PATTERN = Regex("""#\d+ pc """)
private val DIAGNOSTICS_QUERY_PATTERN = Regex("""log|error|fail|video|load|setting|where|how|device|black|broke|froze|slow|crash|die|dead|bug|stuck|lag|hang|freeze""")
private val LIBRARY_QUERY_PATTERN = Regex("""library|anime|watch|collection|have|my|list|recommend""")
private val EXTENSION_QUERY_PATTERN = Regex("""extension|source|site|domain|mirror|everythingmoe|stream|1080p|dub|sub|quality|down|dead|alive|link|recommend|best|working|broken""")

class AiManager(
    private val context: Context,
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val aiPreferences: AiPreferences = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getLibraryAnime: tachiyomi.domain.anime.interactor.GetLibraryAnime = Injekt.get(),
    private val json: Json = Injekt.get(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val keyFailures = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val everythingMoeScraper: EverythingMoeScraper by lazy {
        EverythingMoeScraper(context, networkHelper, json)
    }

    companion object {
        // Circuit Breaker Config
        private val MAP_VERSION = 132
        private val REMOTE_KILL_SWITCH_URL = "https://raw.githubusercontent.com/salmanbappi/anikku-config/main/ai_kill_switch.json"
    }

    fun resetCircuitBreaker() {
        aiPreferences.isCircuitBreakerTripped().set(false)
        aiPreferences.isRequestPending().set(false)
    }

    suspend fun chatWithAssistant(query: String, history: List<ChatMessage>): String? {
        val result = StringBuilder()
        chatWithAssistantStream(query, history).collect { result.append(it) }
        return result.toString().ifBlank { null }
    }

    fun chatWithAssistantStream(query: String, history: List<ChatMessage>): Flow<String> = flow {
        if (!aiPreferences.enableAi().get() || !aiPreferences.enableAiAssistant().get()) return@flow

        if (isCircuitBreakerTripped()) {
            emit("Stability Alert: AI temporarily disabled due to detected app instability. [RESET_REQUIRED]")
            return@flow
        }
        if (isRemoteKillSwitchActive()) {
            emit("Service Maintenance: AI Assistant is currently offline.")
            return@flow
        }

        val engine = aiPreferences.aiEngine().get()
        val apiKey = when (engine) {
            "gemini" -> aiPreferences.geminiApiKey().get()
            "deepseek" -> aiPreferences.deepseekApiKey().get()
            "opencode" -> aiPreferences.opencodeApiKey().get()
            "literouter" -> aiPreferences.literouterApiKey().get()
            "tokenreply" -> aiPreferences.tokenreplyApiKey().get()
            "openai" -> aiPreferences.openaiApiKey().get()
            "anthropic" -> aiPreferences.anthropicApiKey().get()
            "openrouter" -> aiPreferences.openrouterApiKey().get()
            "together" -> aiPreferences.togetherApiKey().get()
            else -> aiPreferences.groqApiKey().get()
        }.ifBlank {
            emit("Please set an API Key in Settings > AI Integration")
            return@flow
        }

        val customPrompt = aiPreferences.aiSystemPrompt().get()
        val defaultSystemInstruction = """
            You are the 'AniZen System Assistant', a senior systems engineer.
            You have access to native diagnostic tools for logs, system maps, the user's anime library, and EverythingMoe extension intelligence.

            OPERATIONAL PROTOCOLS:
            1. FORMATTING: STRICTLY NO TABLES. Use bullet points or lists for structured data. NEVER output Markdown tables.
            2. SEMANTIC INTENT: Identify negative system states (e.g., "black screen", "crash", "stuck") and call get_system_diagnostics.
            3. GROUNDED NAVIGATION: Use get_app_navigation_guide. If a [STALENESS_WARNING] is present, inform the user that menu paths may have changed in their version.
            4. CRASH ANALYSIS: Prioritize "PINNED" blocks in logs as they contain the root cause of failures.
            5. LIBRARY AWARENESS: Use the [USER_LIBRARY_DATA] block to answer questions about the user's collection, recommendations, or statistics.
            6. EXTENSION & COMMUNITY INTELLIGENCE: Use [EXTENSION_COMMUNITY_INTELLIGENCE] (from EverythingMoe) for accurate site status, active mirrors, stream tags (1080p, dubs, soft-subs), and community reviews. Clearly differentiate extensions that are actually INSTALLED on the user's device versus external directory listings.
            7. PRIVACY: PII (Auth headers, Cookies, and URL params) is strictly redacted.
        """.trimIndent()

        val systemInstruction = if (customPrompt.isNotBlank()) customPrompt else defaultSystemInstruction

        val messages = history.toMutableList()
        messages.add(ChatMessage(role = "user", content = query))

        aiPreferences.isRequestPending().set(true)

        try {
            when (engine) {
                "gemini" -> callGeminiStream(messages, apiKey, systemInstruction, withTools = true).collect { emit(it) }
                "deepseek" -> callOpenAiCompatibleEndpointStream("https://api.deepseek.com/chat/completions", aiPreferences.deepseekModel().get().ifBlank { "deepseek-chat" }, messages, apiKey, systemInstruction, withTools = true).collect { emit(it) }
                "opencode" -> callOpenAiCompatibleEndpointStream("https://opencode.ai/zen/v1/chat/completions", aiPreferences.opencodeModel().get().ifBlank { "deepseek-v4-flash-free" }, messages, apiKey, systemInstruction, withTools = true).collect { emit(it) }
                "literouter" -> callOpenAiCompatibleEndpointStream("https://api.literouter.com/v1/chat/completions", aiPreferences.literouterModel().get().ifBlank { "deepseek-v4-flash-free" }, messages, apiKey, systemInstruction, withTools = true).collect { emit(it) }
                "tokenreply" -> callOpenAiCompatibleEndpointStream("https://api.tokenreply.com/v1/chat/completions", aiPreferences.tokenreplyModel().get().ifBlank { "deepseek-v4-flash-free" }, messages, apiKey, systemInstruction, withTools = true).collect { emit(it) }
                "openai" -> callOpenAiStream(messages, apiKey, systemInstruction, withTools = true).collect { emit(it) }
                "anthropic" -> callAnthropicStream(messages, apiKey, systemInstruction, withTools = true).collect { emit(it) }
                "openrouter" -> callOpenRouterStream(messages, apiKey, systemInstruction, withTools = true).collect { emit(it) }
                "together" -> callTogetherStream(messages, apiKey, systemInstruction, withTools = true).collect { emit(it) }
                else -> callGroqStream(messages, apiKey, systemInstruction, withTools = true).collect { emit(it) }
            }
        } finally {
            aiPreferences.isRequestPending().set(false)
            recordRequestSuccess()
        }
    }

    private suspend fun getLibrarySummary(): String {
        return try {
            val library = getLibraryAnime.await()
            if (library.isEmpty()) return "Library is empty."

            // Limit to top 50 items to save tokens and prevent blank responses
            library.take(50).joinToString("\n") { anime ->
                "- ${anime.anime.title} [Status: ${anime.anime.status}, Seen: ${anime.seenCount}]"
            }
        } catch (e: Exception) {
            "Failed to retrieve library summary."
        }
    }

    suspend fun getStatisticsAnalysis(statsSummary: String): String? {
        val result = StringBuilder()
        getStatisticsAnalysisStream(statsSummary).collect { result.append(it) }
        return result.toString().ifBlank { null }
    }

    fun getStatisticsAnalysisStream(statsSummary: String): Flow<String> = flow {
        if (!aiPreferences.enableAi().get() || !aiPreferences.enableAiStatistics().get()) return@flow

        if (isCircuitBreakerTripped()) return@flow

        val engine = aiPreferences.aiEngine().get()
        val apiKey = when (engine) {
            "gemini" -> aiPreferences.geminiApiKey().get()
            "deepseek" -> aiPreferences.deepseekApiKey().get()
            "opencode" -> aiPreferences.opencodeApiKey().get()
            "literouter" -> aiPreferences.literouterApiKey().get()
            "tokenreply" -> aiPreferences.tokenreplyApiKey().get()
            "openai" -> aiPreferences.openaiApiKey().get()
            "anthropic" -> aiPreferences.anthropicApiKey().get()
            "openrouter" -> aiPreferences.openrouterApiKey().get()
            "together" -> aiPreferences.togetherApiKey().get()
            else -> aiPreferences.groqApiKey().get()
        }.ifBlank { return@flow }

        val prompt = """
            Generate a 'System Behavioral Profile' based on the following data.

            DATA INPUT:
            $statsSummary

            REPORT STRUCTURE (STRICTLY NO TABLES):
            - **User Classification**: Technical archetype (e.g., 'High-Volume Archivist').
            - **Temporal Analysis**: Watch habit patterns.
            - **Source Integrity**: Distribution across extensions.
            - **Strategic Recommendations**: 3-5 anime titles based on data patterns.

            Constraint: Use bullet points. Do NOT use Markdown tables.
        """.trimIndent()

        aiPreferences.isRequestPending().set(true)
        try {
            when (engine) {
                "gemini" -> callGeminiStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "deepseek" -> callOpenAiCompatibleEndpointStream("https://api.deepseek.com/chat/completions", aiPreferences.deepseekModel().get().ifBlank { "deepseek-chat" }, listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "opencode" -> callOpenAiCompatibleEndpointStream("https://opencode.ai/zen/v1/chat/completions", aiPreferences.opencodeModel().get().ifBlank { "deepseek-v4-flash-free" }, listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "literouter" -> callOpenAiCompatibleEndpointStream("https://api.literouter.com/v1/chat/completions", aiPreferences.literouterModel().get().ifBlank { "deepseek-v4-flash-free" }, listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "tokenreply" -> callOpenAiCompatibleEndpointStream("https://api.tokenreply.com/v1/chat/completions", aiPreferences.tokenreplyModel().get().ifBlank { "deepseek-v4-flash-free" }, listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "openai" -> callOpenAiStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "anthropic" -> callAnthropicStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "openrouter" -> callOpenRouterStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                "together" -> callTogetherStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
                else -> callGroqStream(listOf(ChatMessage(role = "user", content = prompt)), apiKey, "You are a senior behavioral data analyst.").collect { emit(it) }
            }
        } finally {
            aiPreferences.isRequestPending().set(false)
            recordRequestSuccess()
        }
    }

    private fun isCircuitBreakerTripped(): Boolean {
        // If the app crashed during the last request, trip the breaker
        if (aiPreferences.isRequestPending().get()) {
            aiPreferences.isCircuitBreakerTripped().set(true)
            return true
        }
        return aiPreferences.isCircuitBreakerTripped().get()
    }

    private suspend fun isRemoteKillSwitchActive(): Boolean = withIOContext {
        try {
            val request = Request.Builder().url(REMOTE_KILL_SWITCH_URL).build()
            networkHelper.client.newCall(request).execute().use {
                if (it.isSuccessful) {
                    val body = it.body.string()
                    body.contains("\"disabled\": true")
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false // Default to enabled if network fails
        }
    }

    private fun recordRequestSuccess() {
        val count = aiPreferences.hourlyAiRequestCount().get()
        aiPreferences.hourlyAiRequestCount().set(count + 1)
        aiPreferences.lastAiRequestTime().set(System.currentTimeMillis())
    }

    private suspend fun getSanitizedLogs(): String = withIOContext {
        try {
            val logLines = mutableListOf<String>()

            // 1. Try Logcat with a hard timeout to prevent hanging
            try {
                val process = Runtime.getRuntime().exec("logcat -d -b main -t 500 *:W")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    logLines.add(line)
                }
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                process.destroy()
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Logcat retrieval timed out or failed: ${e.message}" }
            }

            // 2. Fallback to internal XLog files if logcat is restricted (Android 13+)
            if (logLines.size < 10) {
                val storageManager = Injekt.get<StorageManager>()
                val internalLogDir = File(context.cacheDir, "logs")
                val logDir = storageManager.getLogsDirectory()
                    ?: UniFile.fromFile(internalLogDir)

                val latestLog = logDir?.listFiles()
                    ?.filter { it.isFile && it.name?.endsWith(".log") == true }
                    ?.maxByOrNull { it.lastModified() }

                if (latestLog != null) {
                    try {
                        latestLog.openInputStream().bufferedReader().useLines { lines ->
                            logLines.addAll(lines.toList().takeLast(500))
                        }
                    } catch (e: Exception) {
                        logLines.add("Error reading internal log file: ${e.message}")
                    }
                }
            }

            if (logLines.isEmpty()) {
                return@withIOContext "Diagnostic engine active. No logs available for analysis in this environment."
            }

            val pinnedBlocks = mutableListOf<List<String>>()
            val currentBlock = mutableListOf<String>()

            var lastLine = ""
            var repeatCount = 0

            val sanitizedResult = mutableListOf<String>()
            for (line in logLines) {
                val sanitizedLine = line.replace(PII_REDACTION_PATTERN, "[REDACTED]")

                val isTraceLine = sanitizedLine.trimStart().startsWith("at ") ||
                    sanitizedLine.contains("Caused by:") ||
                    sanitizedLine.contains(NATIVE_PC_PATTERN)

                // Only trigger analysis on serious events
                if (sanitizedLine.contains(TRACE_TRIGGER_PATTERN) || (isTraceLine && currentBlock.isNotEmpty())) {
                    currentBlock.add(sanitizedLine)
                    if (currentBlock.size > 80) {
                        pinnedBlocks.add(currentBlock.toList())
                        currentBlock.clear()
                    }
                } else {
                    if (currentBlock.isNotEmpty()) {
                        pinnedBlocks.add(currentBlock.toList())
                        currentBlock.clear()
                    }

                    if (sanitizedLine.contains(PACKAGE_PATTERN)) {
                        if (sanitizedLine == lastLine) {
                            repeatCount++
                        } else {
                            if (repeatCount > 0) sanitizedResult.add("... [TRUNCATED] repeated $repeatCount times ...")
                            sanitizedResult.add(sanitizedLine)
                            lastLine = sanitizedLine
                            repeatCount = 0
                        }
                    }
                }
            }
            if (currentBlock.isNotEmpty()) pinnedBlocks.add(currentBlock.toList())
            if (repeatCount > 0) sanitizedResult.add("... [TRUNCATED] repeated $repeatCount times ...")

            val output = StringBuilder()
            if (pinnedBlocks.isNotEmpty()) {
                output.append("\n### CRITICAL SYSTEM EVENTS (PINNED):\n")
                pinnedBlocks.takeLast(2).forEach { output.append(it.joinToString("\n")).append("\n---\n") }
            }
            output.append("\n### SYSTEM LOG TAIL:\n")
            output.append(sanitizedResult.takeLast(100).joinToString("\n"))
            output.toString()
        } catch (e: Exception) {
            "Diagnostic retrieval failed: ${e.message}"
        }
    }

    private fun getAppMap(): String {
        val currentVersion = BuildConfig.VERSION_CODE
        val stalenessWarning = if (currentVersion != MAP_VERSION) {
            "[STALENESS_WARNING]: Navigation map version ($MAP_VERSION) differs from App Version ($currentVersion). Paths may be shifted.\n"
        } else {
            ""
        }

        return stalenessWarning + """
            - General: Settings > General
            - Appearance: Settings > Appearance (Theme, Monet, Dark Mode)
            - Library: Settings > Library (Update intervals, Columns)
            - Player: Settings > Player (Shaders/Anime4K, Orientation, Subtitles, External Player)
            - Downloads: Settings > Downloads (Threads, Cache)
            - Tracking: Settings > Tracking (Anilist, MAL)
            - Advanced: Settings > Advanced (Log viewer, Cache, Database)
            - Analytics: Settings > Advanced Analytics (AI Config)
        """.trimIndent()
    }

    private suspend fun getInstalledExtensions(): List<eu.kanade.tachiyomi.extension.model.Extension.Installed> {
        return try {
            if (!extensionManager.isInitialized.value) {
                withTimeoutOrNull(2000) {
                    extensionManager.isInitialized.first { it }
                }
            }
            extensionManager.installedExtensionsFlow.value
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun getExtensionStatusSummary(installed: List<eu.kanade.tachiyomi.extension.model.Extension.Installed>): String {
        if (installed.isEmpty()) {
            val sources = try {
                sourceManager.getOnlineSources()
            } catch (e: Exception) {
                emptyList()
            }
            return if (sources.isEmpty()) {
                "No extensions or sources currently installed in AniZen."
            } else {
                "Registered Sources in App:\n" + sources.joinToString("\n") { s ->
                    val bUrl = (s as? eu.kanade.tachiyomi.animesource.online.AnimeHttpSource)?.baseUrl ?: (s as? eu.kanade.tachiyomi.source.online.HttpSource)?.baseUrl ?: "N/A"
                    "- ${s.name} (Lang: ${s.lang}, BaseUrl: `$bUrl`)"
                }
            }
        }
        return installed.joinToString("\n") { ext ->
            val sourcesStr = ext.sources.joinToString(", ") { s ->
                val bUrl = (s as? eu.kanade.tachiyomi.animesource.online.AnimeHttpSource)?.baseUrl ?: (s as? eu.kanade.tachiyomi.source.online.HttpSource)?.baseUrl ?: ""
                if (bUrl.isNotBlank()) "${s.name} (`$bUrl`)" else s.name
            }
            "- **${ext.name}** (${ext.pkgName}) v${ext.versionName} [Sources: $sourcesStr, Obsolete: ${ext.isObsolete}, Update: ${ext.hasUpdate}]"
        }
    }

    private suspend fun buildToolContext(lastQuery: String): String {
        val toolContext = StringBuilder()
        val queryLower = lastQuery.lowercase()
        val installed = getInstalledExtensions()

        if (queryLower.contains(DIAGNOSTICS_QUERY_PATTERN)) {
            if (aiPreferences.aiAssistantLogs().get()) {
                toolContext.append("\n[DIAGNOSTICS_DATA]:\n${getSanitizedLogs()}\n")
            }
            toolContext.append("\n[NAVIGATION_MAP]:\n${getAppMap()}\n")
            toolContext.append("\n[EXTENSIONS_STATUS]:\n${getExtensionStatusSummary(installed)}\n")
            toolContext.append("\n[ENVIRONMENT]: ${getDeviceInfo()}\n")
        }

        if (queryLower.contains(LIBRARY_QUERY_PATTERN)) {
            if (aiPreferences.aiAssistantLibrary().get()) {
                toolContext.append("\n[USER_LIBRARY_DATA]:\n${getLibrarySummary()}\n")
            }
        }

        if (queryLower.contains(EXTENSION_QUERY_PATTERN) ||
            queryLower.contains(DIAGNOSTICS_QUERY_PATTERN)
        ) {
            if (aiPreferences.aiAssistantEverythingMoe().get()) {
                val intel = everythingMoeScraper.getIntelligenceContext(lastQuery, installed)
                if (intel.isNotBlank()) {
                    toolContext.append("\n[EXTENSION_COMMUNITY_INTELLIGENCE (EverythingMoe)]:\n$intel\n")
                }
            }
        }

        return toolContext.toString().trim()
    }

    private fun getDeviceInfo(): String = "Model: ${android.os.Build.MODEL}, SDK: ${android.os.Build.VERSION.SDK_INT}, App: AniZen"

    suspend fun getErrorCount(): Int = withIOContext {
        try {
            val logLines = mutableListOf<String>()
            val process = Runtime.getRuntime().exec("logcat -d -b main -t 200 *:E")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            while (true) {
                val line = reader.readLine() ?: break
                logLines.add(line)
            }

            // Fallback to internal files if logcat empty
            if (logLines.isEmpty()) {
                val storageManager = Injekt.get<StorageManager>()
                val internalLogDir = File(context.cacheDir, "logs")
                val logDir = storageManager.getLogsDirectory()
                    ?: UniFile.fromFile(internalLogDir)

                val latestLog = logDir?.listFiles()
                    ?.filter { it.isFile && it.name?.endsWith(".log") == true }
                    ?.maxByOrNull { it.lastModified() }

                if (latestLog != null) {
                    try {
                        latestLog.openInputStream().bufferedReader().useLines { lines ->
                            logLines.addAll(lines.toList().takeLast(200))
                        }
                    } catch (e: Exception) {
                        logLines.add("Error reading internal log file: ${e.message}")
                    }
                }
            }

            var count = 0
            // Only count CRITICAL failures that affect the user experience
            val criticalPatterns = listOf("FATAL EXCEPTION", "OutOfMemoryError", "Native crash", "SIGSEGV", "mpv: error", "Check failed")

            for (line in logLines) {
                if (criticalPatterns.any { line.contains(it, ignoreCase = true) }) {
                    count++
                }
            }
            count
        } catch (e: Exception) {
            0
        }
    }

    private suspend fun callGeminiStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false,
    ): Flow<String> = flow {
        val rawKeys = apiKey.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val healthyKeys = rawKeys.sortedBy { keyFailures[it] ?: 0L }

        val primaryModel = aiPreferences.geminiModel().get().ifBlank { "gemini-flash-latest" }
        val modelsToTry = if (primaryModel != "gemini-flash-lite-latest") {
            listOf(primaryModel, "gemini-flash-lite-latest")
        } else {
            listOf("gemini-flash-lite-latest")
        }

        val finalMessages = if (withTools) {
            val lastQuery = messages.last().content
            val toolContext = buildToolContext(lastQuery)
            messages.dropLast(1) + ChatMessage("user", messages.last().content + if (toolContext.isNotBlank()) "\n\n$toolContext" else "")
        } else {
            messages
        }

        val geminiContents = finalMessages.map { msg ->
            GeminiContent(parts = listOf(GeminiPart(text = msg.content)), role = if (msg.role == "user") "user" else "model")
        }
        val requestBody = GeminiRequest(
            contents = geminiContents,
            systemInstruction = systemInstruction?.let { GeminiContent(parts = listOf(GeminiPart(text = it))) },
            safetySettings = listOf(
                GeminiSafetySetting("HARM_CATEGORY_HARASSMENT", "BLOCK_NONE"),
                GeminiSafetySetting("HARM_CATEGORY_HATE_SPEECH", "BLOCK_NONE"),
                GeminiSafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_NONE"),
                GeminiSafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_NONE"),
            ),
        )

        var streamEmitted = false

        keyLoop@ for (key in healthyKeys) {
            for (model in modelsToTry) {
                try {
                    val request = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$key")
                        .header("Content-Type", "application/json")
                        .post(json.encodeToString(GeminiRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
                        .build()

                    val timedClient = networkHelper.client.newBuilder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    var emittedInAttempt = false
                    timedClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            keyFailures[key] = System.currentTimeMillis()
                            return@use
                        }
                        val source = response.body.source()
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data: ")) {
                                val data = line.substring(6).trim()
                                if (data == "[DONE]") break
                                try {
                                    val chunk = json.decodeFromString(GeminiResponse.serializer(), data)
                                    val text = chunk.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                                    if (text != null) {
                                        emit(text)
                                        emittedInAttempt = true
                                    }
                                } catch (e: Exception) {
                                    // Skip partial or invalid JSON
                                }
                            }
                        }
                    }
                    if (emittedInAttempt) {
                        streamEmitted = true
                        break@keyLoop
                    }
                } catch (e: Exception) {
                    keyFailures[key] = System.currentTimeMillis()
                }
            }
        }

        if (!streamEmitted) {
            val groqKey = aiPreferences.groqApiKey().get()
            if (groqKey.isNotBlank()) {
                callGroqStream(messages, groqKey, systemInstruction, withTools).collect { emit(it) }
            } else {
                emit("Gemini Exception: All Gemini keys/models failed. Please check your API keys.")
            }
        }
    }

    suspend fun fetchAvailableModels(engine: String): List<String> = withIOContext {
        val apiKey = when (engine) {
            "gemini" -> aiPreferences.geminiApiKey().get().split(",").firstOrNull()?.trim() ?: ""
            "deepseek" -> aiPreferences.deepseekApiKey().get()
            "opencode" -> aiPreferences.opencodeApiKey().get()
            "literouter" -> aiPreferences.literouterApiKey().get()
            "tokenreply" -> aiPreferences.tokenreplyApiKey().get()
            "openai" -> aiPreferences.openaiApiKey().get()
            "anthropic" -> aiPreferences.anthropicApiKey().get()
            "openrouter" -> aiPreferences.openrouterApiKey().get()
            "together" -> aiPreferences.togetherApiKey().get()
            else -> aiPreferences.groqApiKey().get()
        }

        if (apiKey.isBlank()) return@withIOContext getDefaultModelsForEngine(engine)

        try {
            when (engine) {
                "gemini" -> {
                    val request = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                        .build()
                    networkHelper.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withIOContext getDefaultModelsForEngine(engine)
                        val body = response.body.string()
                        val parsed = json.decodeFromString(GeminiModelsListResponse.serializer(), body)
                        val models = parsed.models.map { it.name.removePrefix("models/") }
                            .filter { it.contains("gemini") }
                        models.ifEmpty { getDefaultModelsForEngine(engine) }
                    }
                }
                "openai", "openrouter", "together", "groq", "deepseek", "opencode", "literouter", "tokenreply" -> {
                    val url = when (engine) {
                        "openai" -> "https://api.openai.com/v1/models"
                        "openrouter" -> "https://openrouter.ai/api/v1/models"
                        "together" -> "https://api.together.xyz/v1/models"
                        "groq" -> "https://api.groq.com/openai/v1/models"
                        "opencode" -> "https://opencode.ai/zen/v1/models"
                        "literouter" -> "https://api.literouter.com/v1/models"
                        "tokenreply" -> "https://api.tokenreply.com/v1/models"
                        else -> "https://api.deepseek.com/models"
                    }
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $apiKey")
                        .build()
                    networkHelper.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withIOContext getDefaultModelsForEngine(engine)
                        val body = response.body.string()
                        val parsed = json.decodeFromString(OpenAiModelsListResponse.serializer(), body)
                        val models = parsed.data.map { it.id }
                        models.ifEmpty { getDefaultModelsForEngine(engine) }
                    }
                }
                else -> getDefaultModelsForEngine(engine)
            }
        } catch (e: Exception) {
            getDefaultModelsForEngine(engine)
        }
    }

    private fun getDefaultModelsForEngine(engine: String): List<String> {
        return when (engine) {
            "gemini" -> listOf("gemini-pro-latest", "gemini-flash-latest", "gemini-flash-lite-latest")
            "openai" -> listOf("gpt-4o-mini", "gpt-4o", "o1-mini", "o1-preview")
            "anthropic" -> listOf("claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022", "claude-3-opus-20240229")
            "openrouter" -> listOf("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet", "deepseek/deepseek-r1")
            "together" -> listOf("meta-llama/Llama-3.3-70B-Instruct-Turbo", "deepseek-ai/DeepSeek-R1")
            "deepseek" -> listOf("deepseek-chat", "deepseek-reasoner")
            "opencode" -> listOf("deepseek-v4-flash-free", "deepseek-chat")
            "literouter" -> listOf("deepseek-v4-flash-free", "deepseek-chat")
            "tokenreply" -> listOf("deepseek-v4-flash-free", "deepseek-chat")
            else -> listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
        }
    }

    private suspend fun callOpenAiCompatibleEndpointStream(
        endpointUrl: String,
        modelName: String,
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false,
    ): Flow<String> = flow {
        val finalMessages = prepareFinalMessages(messages, systemInstruction, withTools)
        val requestBody = GroqRequest(messages = finalMessages, model = modelName, stream = true)
        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GroqRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()
        executeOpenAiFormatStream(request).collect { emit(it) }
    }

    private suspend fun callGroq(messages: List<ChatMessage>, apiKey: String, systemInstruction: String? = null): String? = withIOContext {
        val groqMessages = mutableListOf<GroqMessage>()
        if (systemInstruction != null) groqMessages.add(GroqMessage(role = "system", content = systemInstruction))
        messages.forEach { msg -> groqMessages.add(GroqMessage(role = if (msg.role == "user") "user" else "assistant", content = msg.content)) }
        val requestBody = GroqRequest(messages = groqMessages, model = "groq/compound-mini")
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GroqRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()
        try {
            val timedClient = networkHelper.client.newBuilder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            timedClient.newCall(request).execute().use {
                val bodyString = it.body.string()
                if (!it.isSuccessful) {
                    val errorMsg = "Groq Error ${it.code}: ${it.message}"
                    return@withIOContext if (bodyString.contains("rate_limit")) "$errorMsg (Rate limited)" else "$errorMsg\n$bodyString"
                }
                val groqResponse = try {
                    json.decodeFromString(GroqResponse.serializer(), bodyString)
                } catch (e: Exception) {
                    return@withIOContext "Failed to parse Groq response: ${e.message}\nRaw: $bodyString"
                }
                val answer = groqResponse.choices.firstOrNull()?.message?.content?.trim()
                if (answer.isNullOrBlank()) {
                    return@withIOContext "Groq returned a valid JSON but empty message content. Model info: 'groq/compound-mini'."
                }
                answer
            }
        } catch (e: Exception) {
            "Groq Connection Exception: ${e.message}"
        }
    }

    private suspend fun callGroqStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false,
    ): Flow<String> = flow {
        val finalMessages = if (withTools) {
            val lastQuery = messages.last().content
            val toolContext = buildToolContext(lastQuery)
            messages.dropLast(1) + ChatMessage("user", messages.last().content + if (toolContext.isNotBlank()) "\n\n$toolContext" else "")
        } else {
            messages
        }

        val groqMessages = mutableListOf<GroqMessage>()
        if (systemInstruction != null) groqMessages.add(GroqMessage(role = "system", content = systemInstruction))
        finalMessages.forEach { msg -> groqMessages.add(GroqMessage(role = if (msg.role == "user") "user" else "assistant", content = msg.content)) }

        val model = aiPreferences.groqModel().get().ifBlank { "llama-3.3-70b-versatile" }
        val requestBody = GroqRequest(messages = groqMessages, model = model, stream = true)
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GroqRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()

        try {
            val timedClient = networkHelper.client.newBuilder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            timedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit("Groq Error ${response.code}")
                    return@flow
                }
                val source = response.body.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        if (data == "[DONE]") break
                        try {
                            val chunk = json.decodeFromString(GroqStreamResponse.serializer(), data)
                            val text = chunk.choices.firstOrNull()?.delta?.content
                            if (text != null) emit(text)
                        } catch (e: Exception) {
                            // Skip partial or invalid JSON
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit("Groq Exception: ${e.message}")
        }
    }

    private suspend fun callOpenAiStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false,
    ): Flow<String> = flow {
        val finalMessages = prepareFinalMessages(messages, systemInstruction, withTools)
        val model = aiPreferences.openaiModel().get().ifBlank { "gpt-4o-mini" }
        val requestBody = GroqRequest(messages = finalMessages, model = model, stream = true)
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GroqRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()
        executeOpenAiFormatStream(request).collect { emit(it) }
    }

    private suspend fun callAnthropicStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false,
    ): Flow<String> = flow {
        val contextMessages = if (withTools) {
            val lastQuery = messages.last().content
            val toolContext = buildToolContext(lastQuery)
            messages.dropLast(1) + ChatMessage("user", messages.last().content + if (toolContext.isNotBlank()) "\n\n$toolContext" else "")
        } else {
            messages
        }
        val anthropicMessages = contextMessages.map { msg ->
            AnthropicMessage(role = if (msg.role == "user") "user" else "assistant", content = msg.content)
        }
        val model = aiPreferences.anthropicModel().get().ifBlank { "claude-3-5-sonnet-20241022" }
        val requestBody = AnthropicRequest(
            model = model,
            system = systemInstruction,
            messages = anthropicMessages,
            stream = true,
        )
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(AnthropicRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()

        try {
            val timedClient = networkHelper.client.newBuilder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            timedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit("Anthropic Error ${response.code}")
                    return@flow
                }
                val source = response.body.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        if (data == "[DONE]") break
                        try {
                            val chunk = json.decodeFromString(AnthropicStreamResponse.serializer(), data)
                            val text = chunk.delta?.text
                            if (text != null) emit(text)
                        } catch (e: Exception) {
                            // Skip partial JSON
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit("Anthropic Exception: ${e.message}")
        }
    }

    private suspend fun callOpenRouterStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false,
    ): Flow<String> = flow {
        val finalMessages = prepareFinalMessages(messages, systemInstruction, withTools)
        val model = aiPreferences.openrouterModel().get().ifBlank { "openai/gpt-4o-mini" }
        val requestBody = GroqRequest(messages = finalMessages, model = model, stream = true)
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/salmanbappi/AniZen")
            .header("X-Title", "AniZen")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GroqRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()
        executeOpenAiFormatStream(request).collect { emit(it) }
    }

    private suspend fun callTogetherStream(
        messages: List<ChatMessage>,
        apiKey: String,
        systemInstruction: String? = null,
        withTools: Boolean = false,
    ): Flow<String> = flow {
        val finalMessages = prepareFinalMessages(messages, systemInstruction, withTools)
        val model = aiPreferences.togetherModel().get().ifBlank { "meta-llama/Llama-3.3-70B-Instruct-Turbo" }
        val requestBody = GroqRequest(messages = finalMessages, model = model, stream = true)
        val request = Request.Builder()
            .url("https://api.together.xyz/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(GroqRequest.serializer(), requestBody).toRequestBody(jsonMediaType))
            .build()
        executeOpenAiFormatStream(request).collect { emit(it) }
    }

    private suspend fun prepareFinalMessages(
        messages: List<ChatMessage>,
        systemInstruction: String?,
        withTools: Boolean,
    ): List<GroqMessage> {
        val contextMessages = if (withTools) {
            val lastQuery = messages.last().content
            val toolContext = buildToolContext(lastQuery)
            messages.dropLast(1) + ChatMessage("user", messages.last().content + if (toolContext.isNotBlank()) "\n\n$toolContext" else "")
        } else {
            messages
        }

        val result = mutableListOf<GroqMessage>()
        if (systemInstruction != null) result.add(GroqMessage(role = "system", content = systemInstruction))
        contextMessages.forEach { msg -> result.add(GroqMessage(role = if (msg.role == "user") "user" else "assistant", content = msg.content)) }
        return result
    }

    private fun executeOpenAiFormatStream(request: Request): Flow<String> = flow {
        try {
            val timedClient = networkHelper.client.newBuilder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            timedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit("API Error ${response.code}")
                    return@flow
                }
                val source = response.body.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        if (data == "[DONE]") break
                        try {
                            val chunk = json.decodeFromString(GroqStreamResponse.serializer(), data)
                            val text = chunk.choices.firstOrNull()?.delta?.content
                            if (text != null) emit(text)
                        } catch (e: Exception) {
                            // Skip partial JSON
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit("API Exception: ${e.message}")
        }
    }

    @Serializable
    data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class GeminiRequest(
        val contents: List<GeminiContent>,
        @kotlinx.serialization.SerialName("system_instruction") val systemInstruction: GeminiContent? = null,
        val safetySettings: List<GeminiSafetySetting>? = null,
    )

    @Serializable
    private data class GeminiSafetySetting(
        val category: String,
        val threshold: String,
    )

    @Serializable
    private data class GeminiContent(val parts: List<GeminiPart>, val role: String? = null)

    @Serializable
    private data class GeminiPart(val text: String)

    @Serializable
    private data class GeminiResponse(val candidates: List<GeminiCandidate>)

    @Serializable
    private data class GeminiCandidate(val content: GeminiContent)

    @Serializable
    private data class GroqRequest(
        val messages: List<GroqMessage>,
        val model: String,
        val stream: Boolean = false,
    )

    @Serializable
    private data class GroqMessage(val role: String, val content: String)

    @Serializable
    private data class GroqResponse(val choices: List<GroqChoice>)

    @Serializable
    private data class GroqChoice(val message: GroqMessage)

    @Serializable
    private data class GroqStreamResponse(val choices: List<GroqStreamChoice>)

    @Serializable
    private data class GroqStreamChoice(val delta: GroqStreamDelta)

    @Serializable
    private data class GroqStreamDelta(val content: String? = null)

    @Serializable
    private data class AnthropicRequest(
        val model: String = "claude-3-5-sonnet-20241022",
        val max_tokens: Int = 1024,
        val system: String? = null,
        val messages: List<AnthropicMessage>,
        val stream: Boolean = true,
    )

    @Serializable
    private data class AnthropicMessage(val role: String, val content: String)

    @Serializable
    private data class AnthropicStreamResponse(
        val type: String? = null,
        val delta: AnthropicDelta? = null,
    )

    @Serializable
    private data class AnthropicDelta(val text: String? = null)

    @Serializable
    private data class GeminiModelsListResponse(val models: List<GeminiModelItem>)

    @Serializable
    private data class GeminiModelItem(val name: String)

    @Serializable
    private data class OpenAiModelsListResponse(val data: List<OpenAiModelItem>)

    @Serializable
    private data class OpenAiModelItem(val id: String)
}
