package tachiyomi.domain.library.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.library.model.GroupLibraryMode
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibrarySort

class LibraryPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun displayMode() = preferenceStore.getObject(
        "pref_display_mode_library",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    fun sortingMode() = preferenceStore.getObject(
        "animelib_sorting_mode",
        LibrarySort.default,
        LibrarySort.Serializer::serialize,
        LibrarySort.Serializer::deserialize,
    )

    // Random Sort Seed

    fun randomSortSeed() = preferenceStore.getInt("library_random_anime_sort_seed", 0)

    fun portraitColumns() = preferenceStore.getInt("pref_animelib_columns_portrait_key", 0)

    fun landscapeColumns() = preferenceStore.getInt("pref_animelib_columns_landscape_key", 0)

    fun lastUpdatedTimestamp() = preferenceStore.getLong(Preference.appStateKey("library_update_last_timestamp"), 0L)
    fun autoUpdateInterval() = preferenceStore.getInt("pref_library_update_interval_key", 0)

    // KMK -->
    fun showUpdatingProgressBanner() = preferenceStore.getBoolean(
        Preference.appStateKey("pref_show_updating_progress_banner_key"),
        true,
    )

    fun showEmptyCategoriesSearch() = preferenceStore.getBoolean("pref_show_empty_categories_search", true)

    fun syncOnAdd() = preferenceStore.getBoolean("pref_sync_manga_on_add", false)
    // KMK <--

    fun coverRatios() = preferenceStore.getStringSet(
        Preference.appStateKey("pref_library_cover_ratios_key"),
        emptySet(),
    )

    fun coverColors() = preferenceStore.getStringSet(
        Preference.appStateKey("pref_library_cover_colors_key"),
        emptySet(),
    )
    // KMK <--

    val autoUpdateDeviceRestrictions: Preference<Set<String>> = preferenceStore.getStringSet(
        "library_update_restriction",
        setOf(
            DEVICE_ONLY_ON_WIFI,
        ),
    )
    val autoUpdateAnimeRestrictions: Preference<Set<String>> = preferenceStore.getStringSet(
        "library_update_manga_restriction",
        setOf(
            ANIME_HAS_UNSEEN,
            ANIME_NON_COMPLETED,
            ANIME_NON_SEEN,
            ANIME_OUTSIDE_RELEASE_PERIOD,
        ),
    )

    val autoUpdateMetadata: Preference<Boolean> = preferenceStore.getBoolean("auto_update_metadata", false)

    fun showContinueWatchingButton() = preferenceStore.getBoolean(
        "display_continue_reading_button",
        false,
    )

    // region Filter

    fun filterDownloaded() = preferenceStore.getEnum(
        "pref_filter_animelib_downloaded_v2",
        TriState.DISABLED,
    )

    fun filterUnseen() = preferenceStore.getEnum("pref_filter_animelib_unread_v2", TriState.DISABLED)

    fun filterStarted() = preferenceStore.getEnum(
        "pref_filter_animelib_started_v2",
        TriState.DISABLED,
    )

    fun filterBookmarked() = preferenceStore.getEnum(
        "pref_filter_animelib_bookmarked_v2",
        TriState.DISABLED,
    )

    // AM (FILLERMARK) -->
    fun filterFillermarkedAnime() =
        preferenceStore.getEnum("pref_filter_animelib_fillermarked_v2", TriState.DISABLED)
    // <-- AM (FILLERMARK)

    fun filterCompleted() = preferenceStore.getEnum(
        "pref_filter_animelib_completed_v2",
        TriState.DISABLED,
    )

    fun filterIntervalCustom() = preferenceStore.getEnum(
        "pref_filter_library_interval_custom",
        TriState.DISABLED,
    )

    // SY -->
    fun filterLewd() = preferenceStore.getEnum(
        "pref_filter_library_lewd_v2",
        TriState.DISABLED,
    )

    fun filterCategories() = preferenceStore.getBoolean("pref_filter_library_categories", false)

    // ANZ -->
    // Category include/exclude filter, ported from Anikku upstream.
    fun filterCategoriesInclude() = preferenceStore.getStringSet(FILTER_LIBRARY_CATEGORIES_INCLUDE_PREF_KEY, emptySet())

    fun filterCategoriesExclude() = preferenceStore.getStringSet(FILTER_LIBRARY_CATEGORIES_EXCLUDE_PREF_KEY, emptySet())
    // ANZ <--

    fun libraryReadDuplicateChapters() = preferenceStore.getBoolean("pref_library_mark_duplicate_chapters", false)

    // SY -->
    fun skipDupeEpisodes() = preferenceStore.getBoolean("pref_skip_dupe_episodes", false)
    // SY <--
    // SY <--

    fun hideMissingEpisodes() = preferenceStore.getBoolean("pref_hide_missing_episodes", false)

    fun filterTracking(id: Int) = preferenceStore.getEnum(
        "pref_filter_animelib_tracked_${id}_v2",
        TriState.DISABLED,
    )

    // endregion

    // Common badges

    fun downloadBadge() = preferenceStore.getBoolean("display_download_badge", false)

    // ANZ -->
    // Unseen badge on library covers, ported from Anikku upstream.
    // Preference exists so the sheet matches upstream; the renderer does not honor it yet.
    fun unreadBadge() = preferenceStore.getBoolean("display_unread_badge", true)
    // ANZ <--

    fun localBadge() = preferenceStore.getBoolean("display_local_badge", true)

    fun languageBadge() = preferenceStore.getBoolean("display_language_badge", false)

    fun showSourceIcon() = preferenceStore.getBoolean("display_source_icon", false)

    fun showLanguageIcon() = preferenceStore.getBoolean("display_language_icon", false)

    fun showEpisodeSummary() = preferenceStore.getBoolean("display_episode_summary", true)

    fun showEpisodeThumbnail() = preferenceStore.getBoolean("display_episode_thumbnail", true)

    fun newShowUpdatesCount() = preferenceStore.getBoolean("library_show_updates_count", true)

    fun newMangaUpdatesCount() = preferenceStore.getInt("library_unread_updates_count", 0)
    fun newUpdatesCount() = preferenceStore.getInt(Preference.appStateKey("library_unseen_updates_count"), 0)

    // endregion

    // region Category

    fun defaultCategory() = preferenceStore.getInt("default_anime_category", -1)

    fun lastUsedCategory() = preferenceStore.getInt(Preference.appStateKey("last_used_anime_category"), 0)

    fun categoryTabs() = preferenceStore.getBoolean("display_category_tabs", true)

    fun categoryNumberOfItems() = preferenceStore.getBoolean("display_number_of_items", false)

    fun categorizedDisplaySettings() = preferenceStore.getBoolean("categorized_display", false)

    // KMK -->
    fun showHiddenCategories() = preferenceStore.getBoolean("show_hidden_categories", false)
    // KMK <--

    fun updateCategories() = preferenceStore.getStringSet("animelib_update_categories", emptySet())

    fun updateCategoriesExclude() = preferenceStore.getStringSet(
        "animelib_update_categories_exclude",
        emptySet(),
    )

    // Mixture Item

    fun filterEpisodeBySeen() = preferenceStore.getLong(
        "default_episode_filter_by_seen",
        Anime.SHOW_ALL,
    )

    fun filterEpisodeByDownloaded() = preferenceStore.getLong(
        "default_episode_filter_by_downloaded",
        Anime.SHOW_ALL,
    )

    fun filterEpisodeByBookmarked() = preferenceStore.getLong(
        "default_episode_filter_by_bookmarked",
        Anime.SHOW_ALL,
    )

    // AM (FILLERMARK) -->
    fun filterEpisodeByFillermarked() =
        preferenceStore.getLong("default_episode_filter_by_fillermarked", Anime.SHOW_ALL)
    // <-- AM (FILLERMARK)

    // and upload date
    fun sortEpisodeBySourceOrNumber() = preferenceStore.getLong(
        "default_episode_sort_by_source_or_number",
        Anime.EPISODE_SORTING_SOURCE,
    )

    fun displayEpisodeByNameOrNumber() = preferenceStore.getLong(
        "default_chapter_display_by_name_or_number",
        Anime.EPISODE_DISPLAY_NAME,
    )

    fun seasonGroupingMode() = preferenceStore.getEnum(
        "default_chapter_group_by_season_v2",
        SeasonGrouping.Tabs,
    )

    @Deprecated("Use seasonGroupingMode")
    fun groupEpisodeBySeason() = preferenceStore.getBoolean(
        "default_chapter_group_by_season",
        true,
    )

    fun sortEpisodeByAscendingOrDescending() = preferenceStore.getLong(
        "default_chapter_sort_by_ascending_or_descending",
        Anime.EPISODE_SORT_DESC,
    )

    fun lastSelectedSeason(animeId: Long) = preferenceStore.getString("last_selected_season_$animeId", "")

    fun setEpisodeSettingsDefault(anime: Anime) {
        filterEpisodeBySeen().set(anime.unseenFilterRaw)
        filterEpisodeByDownloaded().set(anime.downloadedFilterRaw)
        filterEpisodeByBookmarked().set(anime.bookmarkedFilterRaw)
        // AM (FILLERMARK) -->
        filterEpisodeByFillermarked().set(anime.fillermarkedFilterRaw)
        // <-- AM (FILLERMARK)
        sortEpisodeBySourceOrNumber().set(anime.sorting)
        displayEpisodeByNameOrNumber().set(anime.displayMode)
        val seasonGroupRaw = anime.episodeFlags and Anime.EPISODE_SEASON_GROUP_MASK
        if (seasonGroupRaw != Anime.EPISODE_SEASON_GROUP_DEFAULT) {
            seasonGroupingMode().set(anime.seasonGroupingMode)
        }
        sortEpisodeByAscendingOrDescending().set(
            if (anime.sortDescending()) Anime.EPISODE_SORT_DESC else Anime.EPISODE_SORT_ASC,
        )
        showEpisodeSummary().set(anime.showSummaries())
        showEpisodeThumbnail().set(anime.showPreviews())
    }

    fun autoClearChapterCache() = preferenceStore.getBoolean("auto_clear_chapter_cache", false)

    // region Swipe Actions

    fun swipeEpisodeStartAction() = preferenceStore.getEnum(
        "pref_episode_swipe_end_action",
        EpisodeSwipeAction.ToggleBookmark,
    )

    fun swipeEpisodeEndAction() = preferenceStore.getEnum(
        "pref_episode_swipe_start_action",
        EpisodeSwipeAction.ToggleSeen,
    )

    // endregion

    enum class EpisodeSwipeAction {
        ToggleSeen,
        ToggleBookmark,

        // AM (FILLERMARK) -->
        ToggleFillermark,
        // <-- AM (FILLERMARK)

        Download,
        Disabled,
    }

    enum class SeasonGrouping {
        Disabled,
        Headers,
        Tabs,
    }

    // SY -->
    fun sortTagsForLibrary() = preferenceStore.getStringSet("sort_anime_tags_for_library", mutableSetOf())

    fun groupLibraryUpdateType() = preferenceStore.getEnum("group_anime_library_update_type", GroupLibraryMode.GLOBAL)

    fun groupLibraryBy() = preferenceStore.getInt("group_anime_library_by", LibraryGroup.BY_DEFAULT)
    // SY <--

    fun useHierarchicalSeasons() = preferenceStore.getBoolean("use_hierarchical_seasons", true)

    fun collapseFolders() = preferenceStore.getBoolean("collapse_library_folders", true)
    
    fun libraryFolders() = preferenceStore.getStringSet("library_folders_set", emptySet())
    
    fun animeFolderMap() = preferenceStore.getStringSet("anime_to_folder_map", emptySet())

    fun userAffinityMap() = preferenceStore.getString("user_affinity_map", "{}")
    fun lastAffinityUpdate() = preferenceStore.getLong("last_affinity_update", 0L)

    // AY -->
    val filterSeasonByDownload = preferenceStore.getLong("pref_filter_season_by_download_v2", Anime.SHOW_ALL)
    val filterSeasonByUnseen = preferenceStore.getLong("pref_filter_season_by_unseen_v2", Anime.SHOW_ALL)
    val filterSeasonByStarted = preferenceStore.getLong("pref_filter_season_by_started_v2", Anime.SHOW_ALL)
    val filterSeasonByCompleted = preferenceStore.getLong("pref_filter_season_by_completed_v2", Anime.SHOW_ALL)
    val filterSeasonByBookmarked = preferenceStore.getLong("pref_filter_season_by_bookmarked_v2", Anime.SHOW_ALL)
    val filterSeasonByFillermarked = preferenceStore.getLong("pref_filter_season_by_fillermarked_v2", Anime.SHOW_ALL)

    val sortSeasonBySourceOrNumber = preferenceStore.getLong("pref_sort_season_by_source_or_number_v2", Anime.SEASON_SORT_SEASON)
    val sortSeasonByAscendingOrDescending = preferenceStore.getLong("pref_sort_season_by_ascending_or_descending_v2", Anime.SEASON_SORT_ASC)

    val seasonDisplayGridMode = preferenceStore.getLong("pref_season_display_grid_mode_v2", 0L)
    val seasonDisplayGridSize = preferenceStore.getInt("pref_season_display_grid_size_v2", 0)

    val seasonDownloadOverlay = preferenceStore.getBoolean("pref_season_download_overlay_v2", false)
    val seasonUnseenOverlay = preferenceStore.getBoolean("pref_season_unseen_overlay_v2", true)
    val seasonLocalOverlay = preferenceStore.getBoolean("pref_season_local_overlay_v2", true)
    val seasonLangOverlay = preferenceStore.getBoolean("pref_season_lang_overlay_v2", false)
    val seasonContinueOverlay = preferenceStore.getBoolean("pref_season_continue_overlay_v2", true)

    val seasonDisplayMode = preferenceStore.getLong("pref_season_display_mode_v2", Anime.SEASON_DISPLAY_MODE_NUMBER)

    fun setSeasonSettingsDefault(anime: Anime) {
        filterSeasonByDownload.set(anime.seasonDownloadedFilterRaw)
        filterSeasonByUnseen.set(anime.seasonUnseenFilterRaw)
        filterSeasonByStarted.set(anime.seasonStartedFilterRaw)
        filterSeasonByCompleted.set(anime.seasonCompletedFilterRaw)
        filterSeasonByBookmarked.set(anime.seasonBookmarkedFilterRaw)
        filterSeasonByFillermarked.set(anime.seasonFillermarkedFilterRaw)
        sortSeasonBySourceOrNumber.set(anime.seasonSorting)
        sortSeasonByAscendingOrDescending.set(
            if (anime.seasonSortDescending()) Anime.SEASON_SORT_DESC else Anime.SEASON_SORT_ASC,
        )
        seasonDisplayGridMode.set(tachiyomi.domain.anime.model.SeasonDisplayMode.toLong(anime.seasonDisplayGridMode))
        seasonDisplayGridSize.set(anime.seasonDisplayGridSize)
        seasonDownloadOverlay.set(anime.seasonDownloadedOverlay)
        seasonUnseenOverlay.set(anime.seasonUnseenOverlay)
        seasonLocalOverlay.set(anime.seasonLocalOverlay)
        seasonLangOverlay.set(anime.seasonLangOverlay)
        seasonContinueOverlay.set(anime.seasonContinueOverlay)
        seasonDisplayMode.set(anime.seasonDisplayMode)
    }
    // <-- AY

    companion object {
        const val DEVICE_ONLY_ON_WIFI = "wifi"
        const val DEVICE_NETWORK_NOT_METERED = "network_not_metered"
        const val DEVICE_CHARGING = "ac"

        const val ANIME_NON_COMPLETED = "anime_ongoing"
        const val ANIME_HAS_UNSEEN = "anime_fully_seen"
        const val ANIME_NON_SEEN = "anime_started"
        const val ANIME_OUTSIDE_RELEASE_PERIOD = "anime_outside_release_period"

        // ANZ -->
        const val FILTER_LIBRARY_CATEGORIES_INCLUDE_PREF_KEY = "pref_filter_library_categories_include"
        const val FILTER_LIBRARY_CATEGORIES_EXCLUDE_PREF_KEY = "pref_filter_library_categories_exclude"
        // ANZ <--
    }
}
