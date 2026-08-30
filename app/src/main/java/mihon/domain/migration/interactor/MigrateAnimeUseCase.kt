package mihon.domain.migration.interactor

import eu.kanade.domain.episode.interactor.SyncEpisodesWithSource
import eu.kanade.domain.anime.interactor.UpdateAnime
import eu.kanade.domain.anime.model.hasCustomCover
import tachiyomi.domain.anime.model.toSAnime
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.toEpisodeUpdate
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.anime.model.AnimeUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import java.time.Instant
import java.util.Date
import kotlin.reflect.KProperty

class MigrateAnimeUseCase(
    private val sourcePreferences: SourcePreferences,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val updateAnime: UpdateAnime,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
    private val syncEpisodesWithSource: SyncEpisodesWithSource,
    private val updateEpisode: UpdateEpisode,
    private val getCategories: GetCategories,
    private val setAnimeCategories: SetAnimeCategories,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    private val coverCache: CoverCache,
    private val getHistory: GetHistory,
    private val upsertHistory: UpsertHistory,
) {
    private val enhancedServices: List<EnhancedTracker> by lazy { trackerManager.trackers.filterIsInstance<EnhancedTracker>() }

    suspend operator fun invoke(
        current: Anime,
        target: Anime,
        replace: Boolean,
        presetFlags: Set<MigrationFlag>? = null,
    ) {
        val targetSource = sourceManager.get(target.source) ?: return
        val currentSource = sourceManager.get(current.source)
        val flags = presetFlags ?: MigrationFlag.fromBit(sourcePreferences.migrationFlags().get())

        try {
            val episodes = targetSource.getAnimeEpisodeUpdate(
                anime = target.toSAnime(),
                episodes = emptyList(),
                fetchDetails = false,
                fetchEpisodes = true,
            ).episodes

            try {
                syncEpisodesWithSource.await(episodes, target, targetSource)
            } catch (_: Exception) {
                // Worst case, episodes won't be synced
            }

            // Update episodes seen, bookmark and dateFetch
            if (MigrationFlag.EPISODE in flags) {
                val prevAnimeEpisodes = getEpisodesByAnimeId.await(current.id)
                val animeEpisodes = getEpisodesByAnimeId.await(target.id)

                val maxLocalSeen = prevAnimeEpisodes
                    .filter { it.seen }
                    .maxOfOrNull { it.episodeNumber }
                val maxTrackSeen = getTracks.await(current.id)
                    .maxOfOrNull { it.lastEpisodeSeen }

                val maxEpisodeSeen: Double? = when {
                    maxLocalSeen != null && maxTrackSeen != null -> if (maxLocalSeen > maxTrackSeen) maxLocalSeen else maxTrackSeen
                    maxLocalSeen != null -> maxLocalSeen
                    maxTrackSeen != null -> maxTrackSeen
                    else -> null
                }





                val historyUpdates = mutableListOf<HistoryUpdate>()
                val prevHistoryList = getHistory.await(current.id)
                    .associateBy { it.episodeId }

                val updatedAnimeEpisodes = animeEpisodes.map { animeEpisode ->
                    var updatedEpisode = animeEpisode
                    if (updatedEpisode.isRecognizedNumber) {
                        val prevEpisode = prevAnimeEpisodes
                            .find { it.isRecognizedNumber && it.episodeNumber == updatedEpisode.episodeNumber }

                        if (prevEpisode != null) {
                            updatedEpisode = updatedEpisode.copy(
                                seen = prevEpisode.seen,
                                dateFetch = prevEpisode.dateFetch,
                                bookmark = prevEpisode.bookmark,
                            )
                            prevHistoryList[prevEpisode.id]?.let { prevHistory ->
                                historyUpdates += HistoryUpdate(
                                    animeEpisode.id,
                                    prevHistory.seenAt ?: return@let,
                                    prevHistory.watchDuration,
                                )
                            }
                        }
                        else if (maxEpisodeSeen != null && updatedEpisode.episodeNumber <= maxEpisodeSeen) {
                            updatedEpisode = updatedEpisode.copy(seen = true)
                        }
                    }

                    updatedEpisode
                }

                val episodeUpdates = updatedAnimeEpisodes.map { it.toEpisodeUpdate() }
                updateEpisode.awaitAll(episodeUpdates)
                upsertHistory.awaitAll(historyUpdates)
            }

            // Update categories
            if (MigrationFlag.CATEGORY in flags) {
                val categoryIds = getCategories.await(current.id).map { it.id }
                setAnimeCategories.await(target.id, categoryIds)
            }

            // Update track
            if (MigrationFlag.TRACK in flags) {
                getTracks.await(current.id).mapNotNull { track ->
                    val updatedTrack = track.copy(animeId = target.id)

                    val service = enhancedServices
                        .firstOrNull { it.isTrackFrom(updatedTrack, current, currentSource) }

                    if (service != null) {
                        service.migrateTrack(updatedTrack, target, targetSource)
                    } else {
                        updatedTrack
                    }
                }
                    .takeIf { it.isNotEmpty() }
                    ?.let { insertTrack.awaitAll(it) }
            }

            // Delete downloaded
            if (MigrationFlag.REMOVE_DOWNLOAD in flags && currentSource != null) {
                downloadManager.deleteAnime(current, currentSource)
            }

            // Update custom cover
            if (MigrationFlag.CUSTOM_COVER in flags && current.hasCustomCover()) {
                coverCache.setCustomCoverToCache(target, coverCache.getCustomCoverFile(current.id).inputStream())
            }

            val currentAnimeUpdate = AnimeUpdate(
                id = current.id,
                favorite = false,
                dateAdded = 0,
            )
                .takeIf { replace }
            val targetAnimeUpdate = AnimeUpdate(
                id = target.id,
                favorite = true,
                episodeFlags = current.episodeFlags
                    .takeIf { MigrationFlag.EXTRA in flags },
                viewerFlags = current.viewerFlags
                    .takeIf { MigrationFlag.EXTRA in flags },
                dateAdded = if (replace) current.dateAdded else Instant.now().toEpochMilli(),
            )

            updateAnime.awaitAll(listOfNotNull(currentAnimeUpdate, targetAnimeUpdate))
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
        }
    }
}
