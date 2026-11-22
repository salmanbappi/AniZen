package eu.kanade.tachiyomi.data.track.local

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.track.model.Track as DomainAnimeTrack

// ANZ -->
class LocalTracker(id: Long) : BaseTracker(id, "Local Metadata"), AnimeTracker { // ANZ

    override val isLoggedIn: Boolean = true
    override val isLoggedInFlow: Flow<Boolean> = flowOf(true)

    override suspend fun searchAnime(query: String): List<TrackSearch> = emptyList()

    override suspend fun refresh(track: Track): Track = track

    override suspend fun update(track: Track, didWatchEpisode: Boolean): Track {
        if (didWatchEpisode) {
            val episodes = Injekt.get<GetEpisodesByAnimeId>().await(track.anime_id)
            val totalEpisodes = episodes.size.toLong()

            // Use 0.01 epsilon to handle potential floating point precision issues
            val isFinishing = track.last_episode_seen >= (totalEpisodes - 0.01) && totalEpisodes > 0

            if (isFinishing) {
                track.status = COMPLETED
                track.total_episodes = totalEpisodes
                track.finished_watching_date = System.currentTimeMillis()
            } else {
                if (track.status != WATCHING) {
                    track.status = WATCHING
                    if (track.last_episode_seen <= 1.01) {
                        track.started_watching_date = System.currentTimeMillis()
                        track.finished_watching_date = 0L
                    }
                }
            }
        }
        return track
    }

    override suspend fun bind(track: Track, hasSeenEpisodes: Boolean): Track {
        if (track.status != COMPLETED) {
            track.status = if (hasSeenEpisodes) WATCHING else PLAN_TO_WATCH
        }
        return track
    }

    override fun getStatusListAnime(): List<Long> = listOf(
        WATCHING,
        COMPLETED,
        ON_HOLD,
        DROPPED,
        PLAN_TO_WATCH,
    )

    override fun getWatchingStatus(): Long = WATCHING

    override fun getRewatchingStatus(): Long = WATCHING

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): ImmutableList<String> = persistentListOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")

    override fun displayScore(track: DomainAnimeTrack): String = track.score.toInt().toString()

    override fun getStatusForAnime(status: Long): StringResource? {
        return when (status) {
            WATCHING -> MR.strings.watching
            COMPLETED -> MR.strings.completed
            ON_HOLD -> MR.strings.on_hold
            DROPPED -> MR.strings.dropped
            PLAN_TO_WATCH -> MR.strings.plan_to_watch
            else -> null
        }
    }

    override fun get10PointScore(track: DomainAnimeTrack): Double = track.score

    override fun getLogo(): Int = eu.kanade.tachiyomi.R.drawable.ic_glasses_24dp

    override fun getLogoColor(): Int = 0xFF000000.toInt()

    override suspend fun login(username: String, password: String) {
        // Always logged in
    }

    // Required by BaseTracker
    fun getStatusList(): List<Long> = getStatusListAnime()

    companion object {
        const val WATCHING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val DROPPED = 4L
        const val PLAN_TO_WATCH = 5L
    }
}
// ANZ <--
