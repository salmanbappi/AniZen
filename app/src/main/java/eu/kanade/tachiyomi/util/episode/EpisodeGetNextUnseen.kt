package eu.kanade.tachiyomi.util.episode

import eu.kanade.domain.episode.model.applyFilters
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.anime.EpisodeList
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.model.Episode

/**
 * Gets next unseen episode with filters and sorting applied
 */
fun List<Episode>.getNextUnseen(
    anime: Anime,
    downloadManager: DownloadManager,
    seasonName: String? = null,
    episodeToSeason: Map<Long, String> = emptyMap(),
): Episode? {
    return applyFilters(anime, downloadManager, false)
        .let { episodes ->
            if (seasonName != null) {
                episodes.filter { episodeToSeason[it.id] == seasonName }
            } else {
                episodes
            }
        }
        .let { episodes ->
            if (anime.sortDescending()) {
                episodes.findLast { !it.seen } ?: episodes.lastOrNull()
            } else {
                episodes.find { !it.seen } ?: episodes.firstOrNull()
            }
        }
}

/**
 * Gets next unseen episode with filters and sorting applied
 */
fun List<EpisodeList.Item>.getNextUnseen(
    anime: Anime,
    seasonName: String? = null,
    episodeToSeason: Map<Long, String> = emptyMap(),
): Episode? {
    return applyFilters(anime, false)
        .let { episodes ->
            if (seasonName != null) {
                episodes.filter { episodeToSeason[it.episode.id] == seasonName }
            } else {
                episodes
            }
        }
        .let { episodes ->
            if (anime.sortDescending()) {
                episodes.findLast { !it.episode.seen }?.episode ?: episodes.lastOrNull()?.episode
            } else {
                episodes.find { !it.episode.seen }?.episode ?: episodes.firstOrNull()?.episode
            }
        }
}
