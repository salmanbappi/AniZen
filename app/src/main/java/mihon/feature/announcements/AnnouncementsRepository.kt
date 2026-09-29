package mihon.feature.announcements

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnnouncementsRepository(
    private val api: AnnouncementsApi = AnnouncementsApi(),
    private val fallbackApi: AnnouncementsFallbackApi = AnnouncementsFallbackApi(),
    private val preferences: AnnouncementsPreferences = Injekt.get(),
    private val json: Json = Injekt.get(),
) {
    sealed interface Result {
        data class Success(val entries: List<AnnouncementEntry>, val fromCache: Boolean) : Result
        data class Failure(val cachedEntries: List<AnnouncementEntry>) : Result
    }

    suspend fun getAnnouncements(forceRefresh: Boolean = false): Result = withIOContext {
        val cached = readCache()
        val cacheAge = System.currentTimeMillis() - preferences.lastFetchedAt().get()
        val refreshAfter = preferences.autoRefresh().get().durationMillis()

        if (!forceRefresh && cached != null && (refreshAfter == null || cacheAge < refreshAfter)) {
            return@withIOContext Result.Success(cached, fromCache = true)
        }

        val fromApi = try {
            api.fetchAnnouncements()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "AnnouncementsRepository: AniList fetch failed, trying MAL fallback"
            }
            emptyList()
        }

        val entries = fromApi.ifEmpty { fallbackApi.fetchAnnouncements() }

        if (entries.isNotEmpty()) {
            writeCache(entries)
            checkWatchlistForConfirmedDates(entries)
            return@withIOContext Result.Success(entries, fromCache = false)
        }

        return@withIOContext Result.Failure(cached ?: emptyList())
    }

    fun getCached(): List<AnnouncementEntry>? = readCache()

    fun findCached(mediaId: Int): AnnouncementEntry? =
        readCache()?.firstOrNull { it.mediaId == mediaId }

    fun toggleWatchlist(mediaId: Int) {
        val key = mediaId.toString()
        val current = preferences.watchlistMediaIds().get()
        preferences.watchlistMediaIds().set(if (key in current) current - key else current + key)
    }

    fun isWatchlisted(mediaId: Int): Boolean =
        mediaId.toString() in preferences.watchlistMediaIds().get()

    private fun readCache(): List<AnnouncementEntry>? {
        val raw = preferences.cacheBlob().get()
        if (raw.isBlank()) return null
        return try {
            json.decodeFromString<List<AnnouncementEntry>>(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCache(entries: List<AnnouncementEntry>) {
        preferences.cacheBlob().set(json.encodeToString(entries))
        preferences.lastFetchedAt().set(System.currentTimeMillis())
    }

    private fun checkWatchlistForConfirmedDates(entries: List<AnnouncementEntry>) {
        val watchlist = preferences.watchlistMediaIds().get()
        if (watchlist.isEmpty()) return

        val alreadyNotified = preferences.notifiedReleaseDateMediaIds().get()
        val newlyConfirmed = entries.filter { entry ->
            entry.mediaId.toString() in watchlist &&
                entry.exactReleaseDate != null &&
                entry.mediaId.toString() !in alreadyNotified
        }
        if (newlyConfirmed.isEmpty()) return

        newlyConfirmed.forEach { AnnouncementNotifications.notifyReleaseDateConfirmed(it) }
        preferences.notifiedReleaseDateMediaIds().set(
            alreadyNotified + newlyConfirmed.map { it.mediaId.toString() },
        )
    }

}