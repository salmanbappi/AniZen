package eu.kanade.domain.source.service

import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.library.model.LibraryDisplayMode

import eu.kanade.tachiyomi.extension.model.ContentWarning

enum class ContentWarningLevel {
    SAFE,
    SAFE_AND_MIXED,
    ALL,
    ;

    fun allowsDiscovery(warning: ContentWarning): Boolean {
        return when (this) {
            SAFE -> warning == ContentWarning.SAFE || warning == ContentWarning.UNSPECIFIED
            SAFE_AND_MIXED -> warning != ContentWarning.NSFW
            ALL -> true
        }
    }

    fun allowsInstalled(warning: ContentWarning): Boolean {
        return when (this) {
            SAFE -> warning == ContentWarning.SAFE || warning == ContentWarning.UNSPECIFIED
            SAFE_AND_MIXED -> true
            ALL -> true
        }
    }
}

class SourcePreferences(
    private val preferenceStore: PreferenceStore,
) {

    // Common options

    fun sourceDisplayMode() = preferenceStore.getObject(
        "pref_display_mode_catalogue",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    fun enabledLanguages() = preferenceStore.getStringSet(
        "source_languages",
        LocaleHelper.getDefaultEnabledLanguages(),
    )

    fun showNsfwSource() = preferenceStore.getBoolean("show_nsfw_source", true)

    fun contentWarningLevel() = preferenceStore.getEnum(
        "pref_content_warning_level",
        if (showNsfwSource().get()) ContentWarningLevel.ALL else ContentWarningLevel.SAFE_AND_MIXED,
    )

    fun migrationSortingMode() = preferenceStore.getEnum(
        "pref_migration_sorting",
        SetMigrateSorting.Mode.ALPHABETICAL,
    )

    fun migrationSortingDirection() = preferenceStore.getEnum(
        "pref_migration_direction",
        SetMigrateSorting.Direction.ASCENDING,
    )

    // KMK -->
    fun migrationSources() = preferenceStore.getStringSet("pref_migration_sources", emptySet())

    fun migrationFlags() = preferenceStore.getInt("migration_flags", Int.MAX_VALUE)

    fun migrationDeepSearchMode() = preferenceStore.getBoolean("migration_deep_search", false)

    fun migrationPrioritizeByChapters() = preferenceStore.getBoolean("migration_prioritize_by_chapters", true)

    fun migrationHideUnmatched() = preferenceStore.getBoolean("migration_hide_unmatched", false)

    fun migrationHideWithoutUpdates() = preferenceStore.getBoolean("migration_hide_without_updates", false)

    fun migrationSmartSearchSingleEntry() = preferenceStore.getBoolean("migration_smart_search_single_entry", false)
    // KMK <--

    fun trustedExtensions() = preferenceStore.getStringSet(
        Preference.appStateKey("trusted_extensions"),
        emptySet(),
    )

    fun globalSearchFilterState() = preferenceStore.getBoolean(
        Preference.appStateKey("has_filters_toggle_state"),
        false,
    )

    // Mixture Sources

    fun disabledSources() = preferenceStore.getStringSet("hidden_anime_catalogues", emptySet())

    fun pinnedSources() = preferenceStore.getStringSet("pinned_anime_catalogues", emptySet())

    fun lastUsedSource() = preferenceStore.getLong(
        Preference.appStateKey("last_anime_catalogue_source"),
        -1,
    )

    fun animeExtensionUpdatesCount() = preferenceStore.getInt("animeext_updates_count", 0)

    fun hideInAnimeLibraryItems() = preferenceStore.getBoolean(
        "browse_hide_in_anime_library_items",
        false,
    )

    fun hideLatest() = preferenceStore.getBoolean("browse_hide_latest", false)

    fun autoSearch() = preferenceStore.getBoolean("pref_auto_search", true)

    // SY -->

    // fun enableSourceBlacklist() = preferenceStore.getBoolean("eh_enable_source_blacklist", true)

    // fun sourcesTabCategories() = preferenceStore.getStringSet("sources_tab_categories", mutableSetOf())

    // fun sourcesTabCategoriesFilter() = preferenceStore.getBoolean("sources_tab_categories_filter", false)

    // fun sourcesTabSourcesInCategories() = preferenceStore.getStringSet("sources_tab_source_categories", mutableSetOf())

    fun dataSaver() = preferenceStore.getEnum("data_saver", DataSaver.NONE)

    fun dataSaverIgnoreJpeg() = preferenceStore.getBoolean("ignore_jpeg", false)

    fun dataSaverIgnoreGif() = preferenceStore.getBoolean("ignore_gif", true)

    fun dataSaverImageQuality() = preferenceStore.getInt("data_saver_image_quality", 80)

    fun dataSaverImageFormatJpeg() = preferenceStore.getBoolean(
        "data_saver_image_format_jpeg",
        false,
    )

    fun dataSaverServer() = preferenceStore.getString("data_saver_server", "")

    fun dataSaverColorBW() = preferenceStore.getBoolean("data_saver_color_bw", false)

    fun dataSaverExcludedSources() = preferenceStore.getStringSet("data_saver_excluded", emptySet())

    fun dataSaverDownloader() = preferenceStore.getBoolean("data_saver_downloader", true)

    // Related Anime Suggestions
    fun relatedAnimeExpand() = preferenceStore.getBoolean("pref_expand_related_mangas", true)
    fun relatedAnimeShowSource() = preferenceStore.getBoolean("pref_source_related_mangas", true)
    fun relatedAnimeShowSmart() = preferenceStore.getBoolean("pref_smart_related_mangas", false)
    fun relatedAnimeShowHome() = preferenceStore.getBoolean("pref_show_home_on_related_mangas", false)
    fun relatedAnimeInOverflow() = preferenceStore.getBoolean("put_related_mangas_in_overflow", false)
    fun relatedAnimeDisplayMode() = preferenceStore.getObject(
        "pref_related_mangas_display_mode",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    enum class DataSaver {
        NONE,
        BANDWIDTH_HERO,
        WSRV_NL,
        RESMUSH_IT,
    }
    // SY <--
}
