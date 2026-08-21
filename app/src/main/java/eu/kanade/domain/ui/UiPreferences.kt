package eu.kanade.domain.ui

import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.NavBehavior
import eu.kanade.domain.ui.model.NavConfig
import eu.kanade.domain.ui.model.NavConfigSerializer
import eu.kanade.domain.ui.model.NavConfigValidator
import eu.kanade.domain.ui.model.NavItem
import eu.kanade.domain.ui.model.NavPresets
import eu.kanade.domain.ui.model.NavLabelVisibility
import eu.kanade.domain.ui.model.NavStyle
import eu.kanade.domain.ui.model.PanoramaMode
import eu.kanade.domain.ui.model.StartScreen
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class UiPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun themeMode() = preferenceStore.getEnum("pref_theme_mode_key", ThemeMode.SYSTEM)

    fun appTheme() = preferenceStore.getEnum(
        "pref_app_theme",
        if (DeviceUtil.isDynamicColorAvailable) {
            AppTheme.MONET
        } else {
            AppTheme.DEFAULT
        },
    )

    fun colorTheme() = preferenceStore.getInt("pref_color_theme", 0)

    fun themeDarkAmoled() = preferenceStore.getBoolean("pref_theme_dark_amoled_key", false)

    fun relativeTime() = preferenceStore.getBoolean("relative_time_v2", true)

    fun dateFormat() = preferenceStore.getString("app_date_format", "")

    fun tabletUiMode() = preferenceStore.getEnum("tablet_ui_mode", TabletUiMode.AUTOMATIC)

    fun startScreen() = preferenceStore.getEnum("start_screen", StartScreen.LIBRARY)

    // Keep the default bottom bar compact while making the schedule available from More.
    fun navStyle() = preferenceStore.getEnum("bottom_rail_nav_style", NavStyle.MOVE_SCHEDULE_TO_MORE)

    fun bottomNavTabs() = preferenceStore.getObject(
        "bottom_nav_tabs_v2",
        NavItem.defaultTabs,
        { it.joinToString(",") },
        { it.split(",").filter { id -> id.isNotBlank() } },
    )

    fun bottomNavHiddenTabs() = preferenceStore.getObject(
        "bottom_nav_hidden_tabs",
        NavPresets.DEFAULT.hiddenTabs,
        { it.joinToString(",") },
        { it.split(",").filter { id -> id.isNotBlank() } },
    )

    fun bottomNavBehaviors() = preferenceStore.getObject(
        "bottom_nav_behaviors_v1",
        persistentMapOf<String, NavBehavior>(),
        { map -> 
            map.entries.joinToString(";") { (id, b) -> 
                "$id:${b.onLongClick.javaClass.simpleName},${b.onDoubleTap.javaClass.simpleName}" 
            } 
        },
        { str ->
            val map = mutableMapOf<String, NavBehavior>()
            str.split(";").filter { it.isNotBlank() }.forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val tabId = parts[0]
                    val actions = parts[1].split(",")
                    if (actions.size == 2) {
                        map[tabId] = NavBehavior(
                            onLongClick = NavConfigSerializer.parseAction(actions[0]),
                            onDoubleTap = NavConfigSerializer.parseAction(actions[1])
                        )
                    }
                }
            }
            map.toImmutableMap()
        }
    )

    fun bottomNavConfigVersion() = preferenceStore.getInt("bottom_nav_config_version", 0)

    fun navLabelVisibility() = preferenceStore.getEnum("bottom_nav_label_visibility", NavLabelVisibility.ALWAYS)

    fun hideBottomBarOnScroll() = preferenceStore.getBoolean("bottom_nav_hide_on_scroll", false)

    fun hideTabsCompletely() = preferenceStore.getBoolean("hide_tabs_completely", false)

    // Adaptive Navigation Toggles
    fun adaptiveNavEnabled() = preferenceStore.getBoolean("adaptive_nav_enabled", false)
    fun adaptiveConnectivityRule() = preferenceStore.getBoolean("adaptive_rule_connectivity", true)
    fun adaptiveTimeRule() = preferenceStore.getBoolean("adaptive_rule_time", true)
    fun adaptiveTimeRuleStart() = preferenceStore.getInt("adaptive_rule_time_start", 1) // 1 AM
    fun adaptiveTimeRuleEnd() = preferenceStore.getInt("adaptive_rule_time_end", 5) // 5 AM
    fun adaptiveTelemetryEnabled() = preferenceStore.getBoolean("adaptive_telemetry_enabled", false)

    fun navActionHistory() = preferenceStore.getString("nav_action_history_v1", "[]")

    fun lastOnlineNavConfig() = preferenceStore.getString("last_online_nav_config", "")

    fun updateNavConfig(config: NavConfig) {
        val lastVisible = bottomNavTabs().get()
        val lastHidden = bottomNavHiddenTabs().get()
        val lastBehaviors = bottomNavBehaviors().get()
        
        try {
            val validated = NavConfigValidator.validate(config)
            bottomNavTabs().set(validated.visibleTabs)
            bottomNavHiddenTabs().set(validated.hiddenTabs)
            bottomNavBehaviors().set(validated.behaviorMap)
            bottomNavConfigVersion().set(NavConfig.CURRENT_VERSION)
        } catch (e: Exception) {
            // Rollback to Last Known Good (LKG)
            bottomNavTabs().set(lastVisible)
            bottomNavHiddenTabs().set(lastHidden)
            bottomNavBehaviors().set(lastBehaviors)
            throw e
        }
    }

    fun migrateNavStyle() {
        val navStylePref = navStyle()
        if (navStylePref.isSet()) {
            val style = navStylePref.get()
            val visible = mutableListOf(NavItem.LIBRARY.id)
            val hidden = mutableListOf<String>()

            when (style) {
                NavStyle.MOVE_UPDATES_TO_MORE -> {
                    hidden.add(NavItem.UPDATES.id)
                    visible.addAll(listOf(NavItem.HISTORY.id, NavItem.BROWSE.id))
                }
                NavStyle.MOVE_HISTORY_TO_MORE -> {
                    hidden.add(NavItem.HISTORY.id)
                    visible.addAll(listOf(NavItem.UPDATES.id, NavItem.BROWSE.id))
                }
                NavStyle.MOVE_SCHEDULE_TO_MORE -> {
                    visible.addAll(listOf(NavItem.UPDATES.id, NavItem.HISTORY.id, NavItem.BROWSE.id))
                }
                NavStyle.MOVE_BROWSE_TO_MORE -> {
                    hidden.add(NavItem.BROWSE.id)
                    visible.addAll(listOf(NavItem.UPDATES.id, NavItem.HISTORY.id))
                }
                NavStyle.SHOW_ALL -> {
                    visible.addAll(listOf(NavItem.UPDATES.id, NavItem.HISTORY.id, NavItem.BROWSE.id))
                }
            }
            visible.add(NavItem.MORE.id)

            updateNavConfig(NavConfig(visibleTabs = visible.toImmutableList(), hiddenTabs = hidden.toImmutableList()))
            navStylePref.delete()
        }
    }

    fun showFeedInBrowse() = preferenceStore.getBoolean("show_feed_in_browse", false)

    // SY -->
    fun bottomBarLabels() = preferenceStore.getBoolean("pref_show_bottom_bar_labels", true)

    fun dynamicAnimeTheme() = preferenceStore.getBoolean("pref_dynamic_manga_theme", true)

    fun dynamicPlayerTheme() = preferenceStore.getBoolean("pref_dynamic_player_theme", true)

    fun autoExpandAnimeDescription() = preferenceStore.getBoolean("pref_auto_expand_anime_description", false)

    fun panoramaCover() = preferenceStore.getBoolean("pref_panorama_cover", false)

    fun libraryPanoramaMode() = getPanoramaMode("pref_library_panorama_mode", "pref_library_panorama")

    fun browsePanoramaMode() = getPanoramaMode("pref_browse_panorama_mode", "pref_browse_panorama")

    fun feedPanoramaMode() = getPanoramaMode("pref_feed_panorama_mode", "pref_feed_panorama")

    fun updatesPanoramaMode() = getPanoramaMode("pref_updates_panorama_mode", "pref_updates_panorama")

    fun historyPanoramaMode() = getPanoramaMode("pref_history_panorama_mode", "pref_history_panorama")

    private fun getPanoramaMode(key: String, oldKey: String): tachiyomi.core.common.preference.Preference<PanoramaMode> {
        val pref = preferenceStore.getEnum(key, PanoramaMode.FOLLOW_GLOBAL)
        if (!pref.isSet()) {
            val oldPref = preferenceStore.getBoolean(oldKey)
            if (oldPref.isSet()) {
                val newValue = if (oldPref.get()) PanoramaMode.FORCE_ON else PanoramaMode.FORCE_OFF
                pref.set(newValue)
                oldPref.delete()
            }
        }
        return pref
    }

    fun containerStyles() = preferenceStore.getStringSet(
        "pref_ui_container_styles",
        setOf(
            ContainerStyle.LIBRARY,
            ContainerStyle.UPDATES,
            ContainerStyle.HISTORY,
            ContainerStyle.SETTINGS,
            ContainerStyle.BROWSE,
        ),
    )

    fun animatedTransitions() = preferenceStore.getBoolean("pref_animated_transitions_key", true)

    fun preloadLibraryColor() = preferenceStore.getBoolean("preload_library_color", true)

    fun hazeEnabled() = preferenceStore.getBoolean("pref_haze_enabled", false)

    fun animeDetailsFabOnLeft() = preferenceStore.getBoolean("anime_details_fab_on_left", false)
    // SY <--

    companion object {
        fun dateFormat(format: String): DateTimeFormatter = when (format) {
            "" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            else -> DateTimeFormatter.ofPattern(format, Locale.getDefault())
        }
    }
}

object ContainerStyle {
    const val LIBRARY = "library"
    const val UPDATES = "updates"
    const val HISTORY = "history"
    const val DETAILS = "details"
    const val SETTINGS = "settings"
    const val BROWSE = "browse"
}
