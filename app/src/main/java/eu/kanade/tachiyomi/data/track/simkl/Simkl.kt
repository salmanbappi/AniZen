package eu.kanade.tachiyomi.data.track.simkl

import android.graphics.Color
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.ImportStatusFilter
import eu.kanade.tachiyomi.data.track.ImportableEntry
import eu.kanade.tachiyomi.data.track.ImportableTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.simkl.SimklApi.Companion.POSTERS_URL
import eu.kanade.tachiyomi.data.track.simkl.dto.SimklOAuth
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import tachiyomi.domain.track.model.Track as DomainAnimeTrack

class Simkl(id: Long) : BaseTracker(id, "Simkl"), AnimeTracker, ImportableTracker {

    companion object {
        const val WATCHING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val NOT_INTERESTING = 4L
        const val PLAN_TO_WATCH = 5L

        private val SCORE_LIST = IntRange(0, 10)
            .map(Int::toString)
            .toImmutableList()
    }

    private val json: Json by injectLazy()

    private val interceptor by lazy { SimklInterceptor(this) }

    private val api by lazy { SimklApi(client, interceptor) }

    override fun getScoreList(): ImmutableList<String> = SCORE_LIST

    override fun displayScore(track: DomainAnimeTrack): String {
        return track.score.toInt().toString()
    }

    private suspend fun add(track: Track): Track {
        return api.addLibAnime(track)
    }

    override suspend fun update(track: Track, didWatchEpisode: Boolean): Track {
        if (track.status != COMPLETED) {
            if (didWatchEpisode) {
                if (track.last_episode_seen.toLong() == track.total_episodes && track.total_episodes > 0) {
                    track.status = COMPLETED
                } else {
                    track.status = WATCHING
                }
            }
        }

        return api.updateLibAnime(track)
    }

    override suspend fun bind(track: Track, hasSeenEpisodes: Boolean): Track {
        val remoteTrack = api.findLibAnime(track)
        return if (remoteTrack != null) {
            track.copyPersonalFrom(remoteTrack)
            track.library_id = remoteTrack.library_id

            if (track.status != COMPLETED) {
                track.status = if (hasSeenEpisodes) WATCHING else track.status
            }

            update(track)
        } else {
            // Set default fields if it's not found in the list
            track.status = if (hasSeenEpisodes) WATCHING else PLAN_TO_WATCH
            track.score = 0.0
            add(track)
        }
    }

    override suspend fun searchAnime(query: String): List<TrackSearch> {
        return api.searchAnime(query, "anime") +
            api.searchAnime(query, "tv") +
            api.searchAnime(query, "movie")
    }

    override suspend fun refresh(track: Track): Track {
        api.findLibAnime(track)?.let { remoteTrack ->
            track.copyPersonalFrom(remoteTrack)
            track.total_episodes = remoteTrack.total_episodes
        }
        return track
    }

    override fun getLogo() = R.drawable.ic_tracker_simkl

    override fun getLogoColor() = Color.rgb(0, 0, 0)

    override fun getStatusListAnime(): List<Long> {
        return listOf(WATCHING, COMPLETED, ON_HOLD, NOT_INTERESTING, PLAN_TO_WATCH)
    }

    override fun getStatusForAnime(status: Long): StringResource? = when (status) {
        WATCHING -> MR.strings.watching
        PLAN_TO_WATCH -> MR.strings.plan_to_watch
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        NOT_INTERESTING -> MR.strings.not_interesting
        else -> null
    }

    override fun getWatchingStatus(): Long = WATCHING

    override fun getRewatchingStatus(): Long = 0

    override fun getCompletionStatus(): Long = COMPLETED

    override suspend fun login(username: String, password: String) = login(password)

    suspend fun login(code: String) {
        try {
            val oauth = api.accessToken(code)
            interceptor.newAuth(oauth)
            val user = api.getCurrentUser()
            saveCredentials(user.toString(), oauth.accessToken)
        } catch (e: Throwable) {
            logout()
        }
    }

    fun saveToken(oauth: SimklOAuth?) {
        trackPreferences.trackToken(this).set(json.encodeToString(oauth))
    }

    fun restoreToken(): SimklOAuth? {
        return try {
            json.decodeFromString<SimklOAuth>(trackPreferences.trackToken(this).get())
        } catch (e: Exception) {
            null
        }
    }

    override fun logout() {
        super.logout()
        trackPreferences.trackToken(this).delete()
        interceptor.newAuth(null)
    }

    override fun getNoticeStringRes(): StringResource {
        return MR.strings.simkl_import_notice
    }

    override suspend fun getImportableList(): List<ImportableEntry> {
        val syncResult = api.getAllItems()
        val allItems = (syncResult.anime.orEmpty() + syncResult.tv.orEmpty() + syncResult.shows.orEmpty() + syncResult.movies.orEmpty())
            .distinctBy { (it.show?.ids?.simkl ?: it.movie?.ids?.simkl) }

        return allItems.mapNotNull { item ->
            val resultData = item.show ?: item.movie ?: return@mapNotNull null
            val isMovie = item.movie != null
            val statusStr = item.status ?: "watching"
            val mappedStatusFilter = when (statusStr) {
                "watching" -> ImportStatusFilter.WATCHING
                "plantowatch" -> ImportStatusFilter.PLAN_TO_WATCH
                "completed" -> ImportStatusFilter.COMPLETED
                "hold" -> ImportStatusFilter.ON_HOLD
                else -> null
            }
            val cover = resultData.poster?.let { "$POSTERS_URL${it}_m.webp" } ?: ""
            val totalEps = if (isMovie) 1L else (item.totalEpisodesCount ?: 0L)
            val epsSeen = if (isMovie) {
                if (statusStr == "completed") 1 else 0
            } else {
                item.watchedEpisodesCount?.toInt() ?: 0
            }

            ImportableEntry(
                remoteId = resultData.ids.simkl,
                title = resultData.title,
                coverUrl = cover,
                totalEpisodes = totalEps,
                episodesSeen = epsSeen,
                score = item.userRating?.toDouble() ?: 0.0,
                status = toTrackStatus(statusStr),
                statusFilter = mappedStatusFilter,
                startDate = 0L,
                finishDate = 0L,
                trackingUrl = "https://simkl.com/${if (isMovie) "movies" else "anime"}/${resultData.ids.simkl}",
            )
        }
    }
}
