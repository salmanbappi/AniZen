package eu.kanade.tachiyomi.ui.home

import android.content.Context
import android.util.Log
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.NavAction
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.history.interactor.RemoveHistory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

sealed interface ActionResult {
    @Serializable
    data object Success : ActionResult

    @Serializable
    data class Blocked(val reason: String) : ActionResult

    @Serializable
    data class Cooldown(val remainingMs: Long) : ActionResult
}

@Serializable
data class ActionTrace(
    val actionName: String,
    val tabId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val result: String, // Simplification for easier storage
)

class NavActionExecutor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val navigator: Navigator,
    private val uiPreferences: UiPreferences = Injekt.get(),
) {
    companion object {
        private val lastExecutionMap = mutableMapOf<String, Long>()
        private val actionHistory = mutableListOf<ActionTrace>()
        private const val MAX_HISTORY = 200

        fun getHistory(): List<ActionTrace> {
            val uiPreferences = Injekt.get<UiPreferences>()
            return try {
                Json.decodeFromString<List<ActionTrace>>(uiPreferences.navActionHistory().get())
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private fun logTrace(trace: ActionTrace) {
        if (!uiPreferences.adaptiveTelemetryEnabled().get()) return

        scope.launch {
            try {
                val currentHistory = getHistory().toMutableList()
                currentHistory.add(0, trace)
                if (currentHistory.size > MAX_HISTORY) currentHistory.removeAt(currentHistory.size - 1)

                val serialized = Json.encodeToString(currentHistory)
                uiPreferences.navActionHistory().set(serialized)
                Log.d("NavTelemetry", "Action: ${trace.actionName} | Tab: ${trace.tabId} | Persisted")
            } catch (e: Exception) {
                Log.e("NavTelemetry", "Failed to persist trace", e)
            }
        }
    }

    private fun checkCooldown(action: NavAction): ActionResult {
        val now = System.currentTimeMillis()
        val key = action.javaClass.simpleName
        val last = lastExecutionMap[key] ?: 0L
        val elapsed = now - last

        return if (elapsed < action.cooldownMs) {
            ActionResult.Cooldown(action.cooldownMs - elapsed)
        } else {
            lastExecutionMap[key] = now
            ActionResult.Success
        }
    }

    fun logClick(tabId: String) {
        if (!uiPreferences.adaptiveTelemetryEnabled().get()) return
        logTrace(
            ActionTrace(
                actionName = "TabClick",
                tabId = tabId,
                result = "Success",
            ),
        )
    }

    fun execute(action: NavAction, tabId: String? = null) {
        if (action is NavAction.Default) return

        val result = checkCooldown(action)
        logTrace(
            ActionTrace(
                actionName = action.javaClass.simpleName,
                tabId = tabId,
                result = result.javaClass.simpleName,
            ),
        )

        if (result is ActionResult.Cooldown) {
            context.toast("Please wait ${result.remainingMs / 1000 + 1}s")
            return
        }

        if (action.requiresConfirmation) {
            context.toast("Action requires confirmation (Safety Gate)")
            // return
        }

        when (action) {
            is NavAction.Default -> {}
            is NavAction.OpenExtensions -> {
                scope.launch {
                    HomeScreen.openTab(HomeScreen.HomeTab.Browse(toExtensions = true))
                }
            }
            is NavAction.OpenSettings -> {
                navigator.push(SettingsScreen())
            }
            is NavAction.OpenDownloads -> {
                navigator.push(DownloadQueueScreen)
            }
            is NavAction.ClearHistory -> {
                scope.launch {
                    val removeHistory = Injekt.get<RemoveHistory>()
                    removeHistory.awaitAll()
                    context.toast("History cleared")
                }
            }
            is NavAction.RefreshUpdates -> {
                context.toast("Refreshing updates...")
            }
            is NavAction.GlobalSearch -> {
                scope.launch {
                    HomeScreen.openTab(HomeScreen.HomeTab.Browse())
                }
            }
            is NavAction.CustomRoute -> {
                // Handle deep links
            }
        }
    }
}
