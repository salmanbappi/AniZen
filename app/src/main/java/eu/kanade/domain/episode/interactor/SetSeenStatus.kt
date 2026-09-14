package eu.kanade.domain.episode.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.repository.AnimeRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.episode.model.EpisodeUpdate
import tachiyomi.domain.episode.repository.EpisodeRepository
import tachiyomi.domain.history.interactor.LogActivity
import tachiyomi.domain.history.model.ActivityLog

class SetSeenStatus(
    private val downloadPreferences: DownloadPreferences,
    private val deleteDownload: DeleteDownload,
    private val animeRepository: AnimeRepository,
    private val episodeRepository: EpisodeRepository,
    private val logActivity: LogActivity,
) {

    private val mapper = { episode: Episode, seen: Boolean ->
        EpisodeUpdate(
            seen = seen,
            lastSecondSeen = if (!seen) 0 else null,
            id = episode.id,
        )
    }

    suspend fun await(seen: Boolean, vararg episodes: Episode): Result = withNonCancellableContext {
        val episodesToUpdate = episodes.filter {
            when (seen) {
                true -> !it.seen
                false -> it.seen || it.lastSecondSeen > 0
            }
        }
        if (episodesToUpdate.isEmpty()) {
            return@withNonCancellableContext Result.NoEpisodes
        }

        try {
            episodeRepository.updateAll(
                episodesToUpdate.map { mapper(it, seen) },
            )

            // Log engagement for manual actions
            if (seen) {
                episodesToUpdate.firstOrNull()?.let { ep ->
                    val anime = animeRepository.getAnimeById(ep.animeId)
                    val allEpisodes = episodeRepository.getEpisodeByAnimeId(anime.id)
                    val isFinished = allEpisodes.all { it.seen || episodesToUpdate.any { updated -> updated.id == it.id } }

                    if (isFinished) {
                        logActivity.await(anime.source, ActivityLog.TYPE_COMPLETE, animeId = anime.id)
                    } else {
                        logActivity.await(anime.source, ActivityLog.TYPE_PLAY, animeId = anime.id)
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }

        if (seen && downloadPreferences.removeAfterMarkedAsSeen().get()) {
            episodesToUpdate
                .groupBy { it.animeId }
                .forEach { (animeId, episodes) ->
                    deleteDownload.awaitAll(
                        anime = animeRepository.getAnimeById(animeId),
                        episodes = episodes.toTypedArray(),
                    )
                }
        }

        Result.Success
    }

    suspend fun await(animeId: Long, seen: Boolean): Result = withNonCancellableContext {
        await(
            seen = seen,
            episodes = episodeRepository
                .getEpisodeByAnimeId(animeId)
                .toTypedArray(),
        )
    }

    suspend fun await(anime: Anime, seen: Boolean) =
        await(anime.id, seen)

    sealed interface Result {
        data object Success : Result
        data object NoEpisodes : Result
        data class InternalError(val error: Throwable) : Result
    }
}
