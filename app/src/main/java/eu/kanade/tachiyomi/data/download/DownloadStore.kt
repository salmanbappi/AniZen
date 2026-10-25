package eu.kanade.tachiyomi.data.download

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * This class is used to persist active downloads across application restarts.
 */
class DownloadStore(
    context: Context,
    private val sourceManager: SourceManager = Injekt.get(),
    private val json: Json = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val getEpisode: GetEpisode = Injekt.get(),
) {

    /**
     * Preference file where active downloads are stored.
     */
    private val preferences = context.getSharedPreferences("active_downloads", Context.MODE_PRIVATE)

    private val lastUpdateMap = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Counter used to keep the queue order.
     */
    private var counter = 0

    /**
     * Adds a list of downloads to the store.
     *
     * @param downloads the list of downloads to add.
     */
    fun addAll(downloads: List<Download>) {
        preferences.edit {
            downloads.forEach { putString(getKey(it), serialize(it)) }
        }
    }

    /**
     * Updates a single download in the store with internal throttling.
     * Prevents SQLiteDatabaseLockedException during high-concurrency downloads.
     */
    fun update(download: Download, force: Boolean = false) {
        val key = getKey(download)
        val now = System.currentTimeMillis()
        val lastUpdate = lastUpdateMap.getOrDefault(key, 0L)

        if (force || now - lastUpdate > 2000L) { // Flush to disk every 2 seconds
            preferences.edit {
                putString(key, serialize(download))
            }
            lastUpdateMap[key] = now
        }
    }

    /**
     * Removes a download from the store.
     *
     * @param download the download to remove.
     */
    fun remove(download: Download) {
        preferences.edit {
            remove(getKey(download))
        }
    }

    /**
     * Removes a list of downloads from the store.
     *
     * @param downloads the download to remove.
     */
    fun removeAll(downloads: List<Download>) {
        preferences.edit {
            downloads.forEach { remove(getKey(it)) }
        }
    }

    /**
     * Removes all the downloads from the store.
     */
    fun clear() {
        preferences.edit {
            clear()
        }
    }

    /**
     * Returns the preference's key for the given download.
     *
     * @param download the download.
     */
    private fun getKey(download: Download): String {
        return download.episode.id.toString()
    }

    /**
     * Returns the list of downloads to restore. It should be called in a background thread.
     */
    fun restore(): List<Download> {
        val objs = preferences.all
            .mapNotNull { it.value as? String }
            .mapNotNull { deserialize(it) }
            .sortedBy { it.order }

        val downloads = mutableListOf<Download>()
        if (objs.isNotEmpty()) {
            val cachedAnime = mutableMapOf<Long, Anime?>()
            for (obj in objs) {
                val anime = cachedAnime.getOrPut(obj.animeId) {
                    runBlocking { getAnime.await(obj.animeId) }
                } ?: continue
                val source = sourceManager.get(anime.source) as? HttpSource ?: continue
                val episode = runBlocking { getEpisode.await(obj.episodeId) } ?: continue
                val download = Download(source, anime, episode)

                // Restore metadata for 1DM-style resume
                download.totalSegments = obj.totalSegments
                download.downloadedSegments = obj.downloadedSegments
                obj.segmentProgress?.let { download.segmentProgress.putAll(it) }

                downloads.add(download)
            }
        }

        // Clear the store, downloads will be added again immediately.
        clear()
        return downloads
    }

    /**
     * Converts a download to a string.
     *
     * @param download the download to serialize.
     */
    private fun serialize(download: Download): String {
        val obj = AnimeDownloadObject(
            animeId = download.anime.id,
            episodeId = download.episode.id!!,
            order = counter++,
            totalSegments = download.totalSegments,
            downloadedSegments = download.downloadedSegments,
            segmentProgress = if (download.totalSegments > 0) download.segmentProgress.toMap() else null,
        )
        return json.encodeToString(obj)
    }

    /**
     * Restore a download from a string.
     *
     * @param string the download as string.
     */
    private fun deserialize(string: String): AnimeDownloadObject? {
        return try {
            json.decodeFromString<AnimeDownloadObject>(string)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Class used for download serialization
 *
 * @param animeId the id of the anime.
 * @param episodeId the id of the episode.
 * @param order the order of the download in the queue.
 */
@Serializable
private data class AnimeDownloadObject(
    val animeId: Long,
    val episodeId: Long,
    val order: Int,
    val totalSegments: Int = 0,
    val downloadedSegments: Int = 0,
    val segmentProgress: Map<Int, Boolean>? = null,
)
