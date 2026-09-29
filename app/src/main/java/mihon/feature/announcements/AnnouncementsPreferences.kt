package mihon.feature.announcements

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class AnnouncementsPreferences(private val preferenceStore: PreferenceStore) {
    fun cacheBlob() = preferenceStore.getString("announcements_cache_v3", "")

    fun lastFetchedAt() = preferenceStore.getLong("announcements_last_fetched_v3", 0L)

    fun categoryFilter() = preferenceStore.getString("announcements_category_filter", "")

    fun watchlistMediaIds() =
        preferenceStore.getStringSet("announcement_watchlist_media_ids", emptySet())

    fun notifiedReleaseDateMediaIds() =
        preferenceStore.getStringSet("announcement_notified_release_date_ids", emptySet())

    fun sort() = preferenceStore.getEnum(
        "announcements_sort",
        AnnouncementSort.AIRING_SOON,
    )

    fun yearFilter() = preferenceStore.getString("announcements_year_filter", "")

    fun includeAdult() = preferenceStore.getBoolean("announcements_include_adult", false)

    fun autoRefresh() = preferenceStore.getEnum(
        "announcements_auto_refresh",
        AnnouncementAutoRefresh.OFF,
    )
}