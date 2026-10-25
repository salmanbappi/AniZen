package eu.kanade.tachiyomi.ui.home

import android.content.Context
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.NavConfig
import eu.kanade.domain.ui.model.NavItem
import eu.kanade.domain.ui.model.NavPresets
import kotlinx.collections.immutable.toImmutableList
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class BrainStrategy {
    CLASSIC, // Long-term balanced habits
    TRENDING, // Short-term (24h) focus
    FOCUS, // Only the absolute #1 most used extra tab
}

object NavLearningBrain {

    fun recommendLayout(context: Context, strategy: BrainStrategy = BrainStrategy.CLASSIC): NavConfig {
        val allHistory = NavActionExecutor.getHistory()
        if (allHistory.isEmpty()) return NavPresets.DEFAULT

        val now = System.currentTimeMillis()
        val dayMs = 24 * 60 * 60 * 1000L

        // Filter history based on strategy
        val history = when (strategy) {
            BrainStrategy.TRENDING -> allHistory.filter { (now - it.timestamp) <= dayMs }
            else -> allHistory
        }

        if (history.isEmpty() && strategy == BrainStrategy.TRENDING) {
            // Fallback to classic if no recent history
            return recommendLayout(context, BrainStrategy.CLASSIC)
        }

        // Calculate scores for each tab
        val scores = mutableMapOf<String, Float>()
        history.forEach { trace ->
            val tabId = trace.tabId ?: return@forEach
            val ageMs = (now - trace.timestamp).coerceAtLeast(0L)
            val ageDays = ageMs.toFloat() / dayMs

            // Recency weighting
            var weight = (1.0f / (1.0f + ageDays)).coerceAtLeast(0.1f)

            // Boost very recent actions for Trending
            if (strategy == BrainStrategy.TRENDING && ageMs <= dayMs) {
                weight *= 5.0f // Heavy focus on the last 24 hours
            }

            scores[tabId] = (scores[tabId] ?: 0f) + weight
        }

        // Required tabs
        val visible = mutableListOf(NavItem.LIBRARY.id)
        val hidden = mutableListOf<String>()

        val candidateIds = listOf(NavItem.FEED.id, NavItem.UPDATES.id, NavItem.HISTORY.id, NavItem.BROWSE.id)
        val rankedCandidates = candidateIds.sortedByDescending { scores[it] ?: 0f }

        // Apply strategy constraints
        val limit = when (strategy) {
            BrainStrategy.FOCUS -> 1
            else -> 3
        }

        visible.addAll(rankedCandidates.take(limit))
        visible.add(NavItem.MORE.id)

        val allIds = NavItem.entries.map { it.id }.toSet()
        hidden.addAll(allIds.filter { it !in visible })

        return NavConfig(
            visibleTabs = visible.distinct().toImmutableList(),
            hiddenTabs = hidden.distinct().toImmutableList(),
            behaviorMap = Injekt.get<UiPreferences>().bottomNavBehaviors().get(),
        )
    }

    fun hasEnoughData(context: Context): Boolean {
        return NavActionExecutor.getHistory().size >= 10
    }

    fun hasTrendingData(): Boolean {
        val now = System.currentTimeMillis()
        val dayMs = 24 * 60 * 60 * 1000L
        return NavActionExecutor.getHistory().any { (now - it.timestamp) <= dayMs }
    }
}
