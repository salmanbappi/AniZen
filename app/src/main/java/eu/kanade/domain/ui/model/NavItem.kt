package eu.kanade.domain.ui.model

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallToAction
import androidx.compose.material.icons.outlined.DateRange
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.home.FeedTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import kotlinx.collections.immutable.*
import mihon.feature.airingschedule.AiringScheduleTab
import tachiyomi.i18n.MR

sealed interface NavAction {
    val requiresConfirmation: Boolean get() = false
    val cooldownMs: Long get() = 500L
    val isDangerous: Boolean get() = false

    data object Default : NavAction
    
    data object OpenExtensions : NavAction {
        override val cooldownMs = 1000L
    }
    
    data object OpenSettings : NavAction
    
    data object ClearHistory : NavAction {
        override val requiresConfirmation = true
        override val isDangerous = true
        override val cooldownMs = 5000L
    }
    
    data object RefreshUpdates : NavAction {
        override val cooldownMs = 10000L
    }
    
    data object OpenDownloads : NavAction
    
    data object GlobalSearch : NavAction
    
    data class CustomRoute(val route: String) : NavAction
    
    companion object {
        val ALL = listOf(
            Default, OpenExtensions, OpenSettings, ClearHistory, 
            RefreshUpdates, OpenDownloads, GlobalSearch
        )
    }
}

data class NavBehavior(
    val onLongClick: NavAction = NavAction.Default,
    val onDoubleTap: NavAction = NavAction.Default,
)

data class NavConfig(
    val version: Int = NavConfig.CURRENT_VERSION,
    val visibleTabs: ImmutableList<String>,
    val hiddenTabs: ImmutableList<String>,
    val behaviorMap: ImmutableMap<String, NavBehavior> = persistentMapOf()
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

data class NavLayoutPack(
    val id: String,
    val name: String,
    val description: String,
    val config: NavConfig,
    val author: String = "AniZen"
)

object NavCommunityRegistry {
    val OFFICIAL_PACKS = listOf(
        NavLayoutPack(
            id = "minimal",
            name = "The Minimalist",
            description = "For users who want zero distractions. Just Library and More.",
            config = NavPresets.MINIMAL
        ),
        NavLayoutPack(
            id = "power",
            name = "The Power User",
            description = "Access everything instantly. Feed, Updates, and History on the front line.",
            config = NavPresets.POWER
        ),
        NavLayoutPack(
            id = "classic",
            name = "Classic Tachi",
            description = "The familiar layout you know and love.",
            config = NavPresets.DEFAULT
        )
    )
}

object NavConfigSerializer {
    fun serialize(config: NavConfig): String {
        val base = "v${config.version}|${config.visibleTabs.joinToString(",")}|${config.hiddenTabs.joinToString(",")}"
        val behaviors = config.behaviorMap.entries.joinToString(";") { (id, b) ->
            "$id:${b.onLongClick.javaClass.simpleName},${b.onDoubleTap.javaClass.simpleName}"
        }
        val data = "$base|$behaviors"
        return android.util.Base64.encodeToString(data.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
    }

    fun deserialize(input: String): NavConfig? {
        return try {
            val decoded = if (input.contains("|")) input else {
                String(android.util.Base64.decode(input, android.util.Base64.URL_SAFE))
            }
            val parts = decoded.split("|")
            if (parts.size < 3) return null
            val version = parts[0].removePrefix("v").toInt()
            val visible = parts[1].split(",").filter { it.isNotBlank() }
            val hidden = parts[2].split(",").filter { it.isNotBlank() }
            
            val allItems = NavItem.entries.map { it.id }.toSet()
            if (visible.any { it !in allItems } || hidden.any { it !in allItems }) return null
            
            val behaviorMap = mutableMapOf<String, NavBehavior>()
            if (parts.size >= 4) {
                parts[3].split(";").filter { it.isNotBlank() }.forEach { entry ->
                    val entryParts = entry.split(":")
                    if (entryParts.size == 2) {
                        val tabId = entryParts[0]
                        val actions = entryParts[1].split(",")
                        if (actions.size == 2) {
                            behaviorMap[tabId] = NavBehavior(
                                onLongClick = parseAction(actions[0]),
                                onDoubleTap = parseAction(actions[1])
                            )
                        }
                    }
                }
            }
            
            val config = NavConfig(version, visible.toImmutableList(), hidden.toImmutableList(), behaviorMap.toImmutableMap())
            NavMigrator.migrate(config)
        } catch (e: Exception) {
            null
        }
    }

    fun parseAction(name: String): NavAction {
        return when (name) {
            "OpenExtensions" -> NavAction.OpenExtensions
            "OpenSettings" -> NavAction.OpenSettings
            "ClearHistory" -> NavAction.ClearHistory
            "RefreshUpdates" -> NavAction.RefreshUpdates
            "OpenDownloads" -> NavAction.OpenDownloads
            "GlobalSearch" -> NavAction.GlobalSearch
            else -> NavAction.Default
        }
    }
}

object NavMigrator {
    fun migrate(config: NavConfig): NavConfig {
        var current = config
        if (current.version < 2) {
            current = current.copy(version = 2, behaviorMap = persistentMapOf())
        }
        return NavConfigValidator.validate(current)
    }
}

object NavConfigValidator {
    const val MAX_BOTTOM_TABS = 6
    val REQUIRED_TABS = setOf(NavItem.LIBRARY.id, NavItem.MORE.id)
    private val ALL_STANDARD_TABS = setOf(
        NavItem.LIBRARY.id,
        NavItem.FEED.id,
        NavItem.UPDATES.id,
        NavItem.HISTORY.id,
        NavItem.BROWSE.id,
        NavItem.MORE.id,
    )

    fun validate(config: NavConfig): NavConfig {
        var visible = config.visibleTabs.distinct().filter { NavItem.fromId(it) != null }.toMutableList()
        var hidden = config.hiddenTabs.distinct().filter { NavItem.fromId(it) != null }.toMutableList()

        // The schedule is opened from the Updates app bar instead of being a nav
        // tab; drop stale references so stored configs self-clean on write.
        visible.remove(NavItem.SCHEDULE.id)
        hidden.remove(NavItem.SCHEDULE.id)

        hidden.removeAll(visible.toSet())

        REQUIRED_TABS.forEach { id ->
            if (!visible.contains(id) && !hidden.contains(id)) {
                if (visible.size < MAX_BOTTOM_TABS) visible.add(id) else hidden.add(id)
            }
        }
        
        // Prevent missing tabs from completely vanishing from the UI
        ALL_STANDARD_TABS.forEach { id ->
            if (!visible.contains(id) && !hidden.contains(id)) {
                hidden.add(id)
            }
        }

        if (hidden.isNotEmpty() && !visible.contains(NavItem.MORE.id)) {
            hidden.remove(NavItem.MORE.id)
            if (visible.size >= MAX_BOTTOM_TABS) {
                val toMove = visible.lastOrNull { !REQUIRED_TABS.contains(it) } ?: visible.last()
                visible.remove(toMove)
                hidden.add(0, toMove)
            }
            visible.add(NavItem.MORE.id)
        }

        while (visible.size > MAX_BOTTOM_TABS) {
            val toMove = visible.lastOrNull { !REQUIRED_TABS.contains(it) } ?: visible.last()
            visible.remove(toMove)
            if (!hidden.contains(toMove)) hidden.add(0, toMove)
        }

        return NavConfig(
            version = NavConfig.CURRENT_VERSION, 
            visibleTabs = visible.toImmutableList(), 
            hiddenTabs = hidden.toImmutableList(),
            behaviorMap = config.behaviorMap
        )
    }
}

object NavPresets {
    val DEFAULT = NavConfig(
        visibleTabs = persistentListOf(NavItem.LIBRARY.id, NavItem.UPDATES.id, NavItem.HISTORY.id, NavItem.BROWSE.id, NavItem.MORE.id),
        hiddenTabs = persistentListOf(NavItem.FEED.id)
    )

    val MINIMAL = NavConfig(
        visibleTabs = persistentListOf(NavItem.LIBRARY.id, NavItem.MORE.id),
        hiddenTabs = persistentListOf(NavItem.FEED.id, NavItem.UPDATES.id, NavItem.HISTORY.id, NavItem.BROWSE.id)
    )

    val POWER = NavConfig(
        visibleTabs = persistentListOf(NavItem.LIBRARY.id, NavItem.FEED.id, NavItem.UPDATES.id, NavItem.BROWSE.id, NavItem.MORE.id),
        hiddenTabs = persistentListOf(NavItem.HISTORY.id)
    )
}

enum class NavItem(
    val id: String,
    val titleRes: StringResource,
    val tab: Tab,
    val iconRes: Int,
    val staticIconRes: Int,
    val iconVector: ImageVector? = null,
    val behavior: NavBehavior = NavBehavior()
) {
    LIBRARY("library", MR.strings.label_library, LibraryTab, R.drawable.anim_library_enter, R.drawable.ic_animelibrary_outline_24dp),
    FEED("feed", MR.strings.feed, FeedTab, R.drawable.ic_dynamic_feed_24dp, R.drawable.ic_dynamic_feed_24dp),
    UPDATES("updates", MR.strings.label_recent_updates, UpdatesTab, R.drawable.anim_updates_enter, R.drawable.ic_updates_outline_24dp),
    HISTORY("history", MR.strings.history, HistoryTab, R.drawable.anim_history_enter, R.drawable.ic_progress_clock_24dp),
    SCHEDULE(
        "schedule",
        MR.strings.label_schedule_short,
        AiringScheduleTab,
        R.drawable.ic_progress_clock_24dp,
        R.drawable.ic_progress_clock_24dp,
        Icons.Outlined.DateRange,
    ),
    BROWSE("browse", MR.strings.browse, BrowseTab, R.drawable.anim_browse_enter, R.drawable.ic_browse_filled_24dp),
    MORE("more", MR.strings.label_more, MoreTab, R.drawable.anim_more_enter, R.drawable.ic_overflow_24dp);

    companion object {
        fun fromId(id: String): NavItem? = entries.find { it.id == id }

        val defaultTabs = NavPresets.DEFAULT.visibleTabs
    }
}
