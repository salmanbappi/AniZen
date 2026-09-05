package eu.kanade.domain.episode.model

import eu.kanade.domain.anime.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.anime.EpisodeList
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.applyFilter
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.episode.service.getEpisodeSort
import tachiyomi.source.localanime.isLocal

/**
 * Applies the view filters to the list of episodes obtained from the database.
 * @return an observable of the list of episodes filtered and sorted.
 */
fun List<Episode>.applyFilters(anime: Anime, downloadManager: DownloadManager, skipDupeEpisodes: Boolean): List<Episode> {
    val isLocalAnime = anime.isLocal()
    val unseenFilter = anime.unseenFilter
    val downloadedFilter = anime.downloadedFilter
    val bookmarkedFilter = anime.bookmarkedFilter
    // AM (FILLERMARK) -->
    val fillermarkedFilter = anime.fillermarkedFilter
    // <-- AM (FILLERMARK)

    val episodes = if (skipDupeEpisodes) {
        groupBy { if (it.isRecognizedNumber) it.episodeNumber to it.scanlator else it.id }
            .map { (_, episodes) ->
                episodes.maxWithOrNull(
                    compareBy<Episode> { it.bookmark }
                        .thenBy { it.seen }
                        .thenBy { it.lastSecondSeen }
                        .thenBy { it.dateFetch }
                )!!
            }
    } else {
        this
    }

    return episodes.filter { episode -> applyFilter(unseenFilter) { !episode.seen } }
        .filter { episode -> applyFilter(bookmarkedFilter) { episode.bookmark } }
        // AM (FILLERMARK) -->
        .filter { episode -> applyFilter(fillermarkedFilter) { episode.fillermark } }
        // <-- AM (FILLERMARK)
        .filter { episode ->
            applyFilter(downloadedFilter) {
                val downloaded = downloadManager.isEpisodeDownloaded(
                    episode.name,
                    episode.scanlator,
                    // SY -->
                    anime.ogTitle,
                    // SY <--
                    anime.source,
                    episodeNumber = episode.episodeNumber,
                )
                downloaded || isLocalAnime
            }
        }
        .sortedWith(getEpisodeSort(anime))
}

/**
 * Applies the view filters to the list of episodes obtained from the database.
 * @return an observable of the list of episodes filtered and sorted.
 */
fun List<EpisodeList.Item>.applyFilters(anime: Anime, skipDupeEpisodes: Boolean): Sequence<EpisodeList.Item> {
    val isLocalAnime = anime.isLocal()
    val unseenFilter = anime.unseenFilter
    val downloadedFilter = anime.downloadedFilter
    val bookmarkedFilter = anime.bookmarkedFilter
    // AM (FILLERMARK) -->
    val fillermarkedFilter = anime.fillermarkedFilter
    // <-- AM (FILLERMARK)

    val episodes = if (skipDupeEpisodes) {
        groupBy { if (it.episode.isRecognizedNumber) it.episode.episodeNumber to it.episode.scanlator else it.episode.id }
            .map { (_, episodes) ->
                episodes.maxWithOrNull(
                    compareBy<EpisodeList.Item> { it.episode.bookmark }
                        .thenBy { it.episode.seen }
                        .thenBy { it.episode.lastSecondSeen }
                        .thenBy { it.episode.dateFetch }
                )!!
            }
    } else {
        this
    }

    return episodes.asSequence()
        .filter { (episode) -> applyFilter(unseenFilter) { !episode.seen } }
        .filter { (episode) -> applyFilter(bookmarkedFilter) { episode.bookmark } }
        // AM (FILLERMARK) -->
        .filter { (episode) -> applyFilter(fillermarkedFilter) { episode.fillermark } }
        // <-- AM (FILLERMARK)
        .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalAnime } }
        .sortedWith { (episode1), (episode2) -> getEpisodeSort(anime).invoke(episode1, episode2) }
}
