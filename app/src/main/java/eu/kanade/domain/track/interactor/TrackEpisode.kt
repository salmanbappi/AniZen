package eu.kanade.domain.track.interactor

import android.content.Context
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.local.LocalTracker
import eu.kanade.tachiyomi.util.system.isOnline
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack

class TrackEpisode(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
    // ANZ -->
    private val trackPreferences: TrackPreferences,
    // ANZ <--
    private val getAnime: GetAnime,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
) {

    suspend fun await(context: Context, animeId: Long, episodeNumber: Double, setupJobOnFailure: Boolean = true) {
        withNonCancellableContext {
            val tracks = getTracks.await(animeId)
            if (tracks.isEmpty()) {
                // ANZ -->
                if (trackPreferences.autoTrackWhenWatching().get()) {
                    val anime = getAnime.await(animeId) ?: return@withNonCancellableContext
                    val episodes = getEpisodesByAnimeId.await(animeId)
                    val localTrack = eu.kanade.tachiyomi.data.database.models.Track.create(TrackerManager.LOCAL).apply {
                        this.anime_id = animeId
                        this.title = anime.title
                        this.last_episode_seen = episodeNumber
                        this.total_episodes = episodes.size.toLong()
                        this.status = if (episodeNumber >= episodes.size && episodes.isNotEmpty()) {
                            LocalTracker.COMPLETED
                        } else {
                            LocalTracker.WATCHING
                        }
                    }.toDomainTrack(idRequired = false)!!
                    insertTrack.await(localTrack)
                }
                // ANZ <--
                return@withNonCancellableContext
            }

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (service == null || !service.isLoggedIn || episodeNumber <= track.lastEpisodeSeen) {
                    return@mapNotNull null
                }

                async {
                    runCatching {
                        if (context.isOnline()) {
                            val refreshedTrack = service.animeService.refresh(track.toDbTrack())
                                .toDomainTrack(idRequired = true)!!
                                .copy(lastEpisodeSeen = episodeNumber)
                            val finalDbTrack = service.animeService.update(refreshedTrack.toDbTrack(), true)
                            insertTrack.await(finalDbTrack.toDomainTrack(idRequired = true)!!)
                            delayedTrackingStore.remove(track.id)
                        } else {
                            delayedTrackingStore.add(track.id, episodeNumber)
                            if (setupJobOnFailure) {
                                DelayedTrackingUpdateJob.setupTask(context)
                            }
                        }
                    }
                }
            }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { logcat(LogPriority.INFO, it) }
        }
    }

    // ANZ -->
    suspend fun trackStatus(context: Context, animeId: Long, status: Long) { // ANZ
        withNonCancellableContext {
            val tracks = getTracks.await(animeId)
            if (tracks.isEmpty()) {
                if (trackPreferences.autoTrackWhenWatching().get()) {
                    val anime = getAnime.await(animeId) ?: return@withNonCancellableContext
                    val episodes = getEpisodesByAnimeId.await(animeId)
                    val localTrack = eu.kanade.tachiyomi.data.database.models.Track.create(TrackerManager.LOCAL).apply {
                        this.anime_id = animeId
                        this.title = anime.title
                        this.last_episode_seen = 0.0
                        this.total_episodes = episodes.size.toLong()
                        this.status = status
                    }.toDomainTrack(idRequired = false)!!
                    insertTrack.await(localTrack)
                }
                return@withNonCancellableContext
            }

            tracks.forEach { track ->
                val service = trackerManager.get(track.trackerId) ?: return@forEach
                if (!service.isLoggedIn) return@forEach

                val trackerStatus = when (status) {
                    LocalTracker.WATCHING -> service.animeService.getWatchingStatus()
                    LocalTracker.COMPLETED -> service.animeService.getCompletionStatus()
                    // Fallback to local status if not specialized in interface
                    else -> status
                }

                if (track.status == trackerStatus) return@forEach

                runCatching {
                    val updatedTrack = track.copy(status = trackerStatus)
                    val finalTrack = if (status == LocalTracker.WATCHING) {
                        updatedTrack.copy(finishDate = 0L)
                    } else {
                        updatedTrack
                    }

                    val dbTrack = service.animeService.update(finalTrack.toDbTrack(), false)
                    insertTrack.await(dbTrack.toDomainTrack(idRequired = true)!!)
                }
            }
        }
    }
    // ANZ <--
}
