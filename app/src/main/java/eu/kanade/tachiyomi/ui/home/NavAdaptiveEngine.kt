package eu.kanade.tachiyomi.ui.home

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.NavConfig
import eu.kanade.domain.ui.model.NavConfigSerializer
import eu.kanade.domain.ui.model.NavPresets
import eu.kanade.tachiyomi.util.system.isOnline
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar

data class AdaptiveDecision(
    val reason: String,
    val priority: Int,
    val suggestedConfig: eu.kanade.domain.ui.model.NavConfig? = null,
    val onApply: (() -> Unit)? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

class NavAdaptiveEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val uiPreferences: UiPreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
) {
    private val _currentDecision = MutableStateFlow<AdaptiveDecision?>(null)
    val currentDecision = _currentDecision.asStateFlow()

    private var lastOnlineStatus: Boolean? = null

    companion object {
        private var lastShiftTimestamp = 0L
        private const val SHIFT_COOLDOWN = 3600000L // 1 hour stability lock
    }

    fun evaluateRules(force: Boolean = false) {
        if (!uiPreferences.adaptiveNavEnabled().get()) return

        val isOnline = context.isOnline()
        val isDownloadedOnly = basePreferences.downloadedOnly().get()
        val connectivityChanged = lastOnlineStatus != null && lastOnlineStatus != isOnline
        lastOnlineStatus = isOnline

        val now = System.currentTimeMillis()
        // Connectivity rules bypass global cooldown if state actually changed
        val skipCooldown = force || connectivityChanged

        if (!skipCooldown && now - lastShiftTimestamp < SHIFT_COOLDOWN) return

        // Rule 1: Connectivity (High Priority)
        if (uiPreferences.adaptiveConnectivityRule().get()) {
            if (!isOnline && !isDownloadedOnly) {
                _currentDecision.value = AdaptiveDecision(
                    reason = "Offline mode detected. Switch to Downloaded Only?",
                    priority = 10,
                    suggestedConfig = NavPresets.MINIMAL,
                    onApply = {
                        // Store current config before switching
                        val currentConfig = NavConfig(
                            visibleTabs = uiPreferences.bottomNavTabs().get().toImmutableList(),
                            hiddenTabs = uiPreferences.bottomNavHiddenTabs().get().toImmutableList(),
                            behaviorMap = uiPreferences.bottomNavBehaviors().get().toImmutableMap(),
                        )
                        uiPreferences.lastOnlineNavConfig().set(NavConfigSerializer.serialize(currentConfig))
                        basePreferences.downloadedOnly().set(true)
                    },
                )
                return
            } else if (isOnline && isDownloadedOnly) {
                // Rule 1b: Connectivity Restore (Suggestion Style)
                val lastConfigStr = uiPreferences.lastOnlineNavConfig().get()
                if (lastConfigStr.isNotBlank()) {
                    val savedConfig = NavConfigSerializer.deserialize(lastConfigStr) ?: NavPresets.DEFAULT
                    _currentDecision.value = AdaptiveDecision(
                        reason = "Back online! Restore full navigation?",
                        priority = 9,
                        suggestedConfig = savedConfig,
                        onApply = {
                            basePreferences.downloadedOnly().set(false)
                            uiPreferences.lastOnlineNavConfig().delete()
                        },
                    )
                    return
                }
            }
        }

        if (now - lastShiftTimestamp < SHIFT_COOLDOWN) return

        // Rule 2: Choice Fatigue (Lower Priority)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val startHour = uiPreferences.adaptiveTimeRuleStart().get()
        val endHour = uiPreferences.adaptiveTimeRuleEnd().get()

        val isLateNight = if (startHour <= endHour) {
            hour in startHour..endHour
        } else {
            hour >= startHour || hour <= endHour
        }

        if (uiPreferences.adaptiveTimeRule().get()) {
            if (isLateNight) {
                // Rule 2: Choice Fatigue (Suggest Minimal)
                _currentDecision.value = AdaptiveDecision(
                    reason = "Late night: suggesting minimal layout",
                    priority = 5,
                    suggestedConfig = NavPresets.MINIMAL,
                    onApply = {
                        // Store current config before switching
                        val currentConfig = NavConfig(
                            visibleTabs = uiPreferences.bottomNavTabs().get().toImmutableList(),
                            hiddenTabs = uiPreferences.bottomNavHiddenTabs().get().toImmutableList(),
                            behaviorMap = uiPreferences.bottomNavBehaviors().get().toImmutableMap(),
                        )
                        uiPreferences.lastOnlineNavConfig().set(NavConfigSerializer.serialize(currentConfig))
                    },
                )
                return
            } else if (!isLateNight) {
                // Rule 2b: Morning Restore
                val lastConfigStr = uiPreferences.lastOnlineNavConfig().get()
                if (lastConfigStr.isNotBlank()) {
                    val savedConfig = NavConfigSerializer.deserialize(lastConfigStr) ?: NavPresets.DEFAULT
                    _currentDecision.value = AdaptiveDecision(
                        reason = "Good morning! Restore your layout?",
                        priority = 4,
                        suggestedConfig = savedConfig,
                        onApply = {
                            uiPreferences.lastOnlineNavConfig().delete()
                        },
                    )
                    return
                }
            }
        }

        _currentDecision.value = null
    }

    fun applyDecision(decision: AdaptiveDecision) {
        decision.onApply?.invoke()
        decision.suggestedConfig?.let {
            uiPreferences.updateNavConfig(it)
        }
        lastShiftTimestamp = System.currentTimeMillis()
        _currentDecision.value = null
    }

    fun dismissDecision() {
        _currentDecision.value = null
        // Mark this timestamp to prevent immediate re-suggestion
        lastShiftTimestamp = System.currentTimeMillis()
    }
}
