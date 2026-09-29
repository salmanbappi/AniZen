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

        val entries = if (cached.isNullOrEmpty()) {
            val fromApi = try {
                api.fetchAnnouncements()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) {
                    "AnnouncementsRepository: Initial AniList fetch failed, trying MAL fallback"
                }
                emptyList()
            }
            fromApi.ifEmpty { fallbackApi.fetchAnnouncements() }
        } else {
            val fromApi = try {
                val newest = api.fetchNewestAnnouncements()
                val popularTop = api.fetchAnnouncements(maxPages = 1)
                (newest + popularTop).distinctBy { it.mediaId }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) {
                    "AnnouncementsRepository: Incremental AniList fetch failed, keeping cache"
                }
                emptyList()
            }

            if (fromApi.isNotEmpty()) {
                val updatedIds = fromApi.map { it.mediaId }.toSet()
                val remainingCached = cached.filter { it.mediaId !in updatedIds }
                pruneAired(fromApi + remainingCached)
            } else {
                cached
            }
        }

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

    private fun pruneAired(entries: List<AnnouncementEntry>): List<AnnouncementEntry> {
        val cutoff = System.currentTimeMillis() - TWO_DAYS_MILLIS
        return entries.filter { entry ->
            entry.exactReleaseDate == null || entry.exactReleaseDate >= cutoff
        }
    }

    private fun readCache(): List<AnnouncementEntry>? {
        val raw = preferences.cacheBlob().get()
        if (raw.isBlank()) return null
        return try {
            val parsed = json.decodeFromString<List<AnnouncementEntry>>(raw)
            pruneAired(parsed)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCache(entries: List<AnnouncementEntry>) {
        val pruned = pruneAired(entries)
        preferences.cacheBlob().set(json.encodeToString(pruned))
        preferences.lastFetchedAt().set(System.currentTimeMillis())
    }

    companion object {
        private const val TWO_DAYS_MILLIS = 2 * 24 * 60 * 60 * 1000L
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