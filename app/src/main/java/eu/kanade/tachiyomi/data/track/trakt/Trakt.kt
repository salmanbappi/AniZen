package eu.kanade.tachiyomi.data.track.trakt

import android.app.Application
import android.graphics.Color
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.model.Credit
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.ImportableEntry
import eu.kanade.tachiyomi.data.track.ImportableTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktIds
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktMovie
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktOAuth
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktShow
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktSyncMovie
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.roundToInt
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * Trakt.tv tracker implementation (anime / shows / movies).
 */
class Trakt(
    id: Long,
) : BaseTracker(id, "Trakt"), AnimeTracker, DeletableTracker, ImportableTracker {

    companion object {
        const val WATCHING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val DROPPED = 4L
        const val PLAN_TO_WATCH = 5L

        // Replace these with your app credentials (user provided values are filled here)
        const val CLIENT_ID = "b11cdc911e0a7b3c211d1afe4f3487f0e145a905d3369c0a42d5b5d2326e93c7"
        private const val CLIENT_SECRET = "c3677fd800bdfe3e51ba93b77c159487f08c13fc9bda388d52e67992e6f89dbd"
        const val REDIRECT_URI = "anizen://trakt-auth"
        const val SCOPES = "public"
    }

    private val json: Json by injectLazy()

    // Interceptor and API built with injected OKHttp client
    private val interceptor by lazy { TraktInterceptor(this, null, CLIENT_ID) }
    private val api by lazy { TraktApi(client, interceptor) }

    // In-memory current oauth
    private var oauth: TraktOAuth? = null

    override val name: String = "Trakt"
    override val id: Long = 201L
    override val supportsReadingDates: Boolean = true

    override fun getLogo() = R.drawable.ic_tracker_trakt
    override fun getLogoColor() = Color.rgb(255, 69, 0)

    override fun getStatusListAnime(): List<Long> {
        return listOf(WATCHING, PLAN_TO_WATCH, COMPLETED, ON_HOLD, DROPPED)
    }

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

    override fun getWatchingStatus(): Long = WATCHING
    override fun getRewatchingStatus(): Long = 0L
    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): ImmutableList<String> {
        return persistentListOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")
    }

    override fun get10PointScore(track: DomainTrack): Double {
        return track.score
    }

    override fun indexToScore(index: Int): Double = index.toDouble()

    override fun displayScore(track: DomainTrack): String = track.score.toString()

    init {
        // Restore persisted token (if any) and set auth on interceptor so api calls use it.
        restoreToken()?.let { saved ->
            oauth = saved
            interceptor.setAuth(saved.access_token)
        }
    }

    fun saveToken(oauth: TraktOAuth?) {
        if (oauth == null) {
            trackPreferences.trackToken(this).delete()
        } else {
            trackPreferences.trackToken(this).set(json.encodeToString(oauth))
        }
    }

    fun restoreToken(): TraktOAuth? {
        return try {
            val raw = trackPreferences.trackToken(this).get()
            if (raw.isBlank()) return null
            json.decodeFromString<TraktOAuth>(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureTotalEpisodes(track: Track) {
        if (track.total_episodes > 0 || track.remote_id == 0L) return
        if (isMovieTrack(track)) {
            // Some older movie entries might have been stored without explicitly setting episode count.
            track.total_episodes = 1L
            return
        }

        try {
            val (existingSeason, _) = resolveSeasonEpisode(track.last_episode_seen)
            val total = if (existingSeason != null) {
                api.getShowSeasons(track.remote_id).firstOrNull { it.first == existingSeason }?.second?.toLong() ?: 0L
            } else {
                api.getShowEpisodeCount(track.remote_id)
            }
            if (total > 0) {
                track.total_episodes = total
            }
        } catch (_: Exception) {
            // Network/parse errors shouldn't block the rest of the sync flow.
        }
    }

    private fun isMovieTrack(track: Track): Boolean {
        if (track.total_episodes == 1L) return true
        val url = track.tracking_url
        return url.contains("/movies/", ignoreCase = true)
    }

    override suspend fun fetchCastByTitle(remoteId: Long, mediaType: String): List<Credit>? {
        return try {
            val tmdbId = api.getTmdbId(remoteId, mediaType) ?: return null
            val tmdbTracker = Injekt.get<TrackerManager>().tmdb
            tmdbTracker.fetchCastByTitle(tmdbId, mediaType)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun searchAnime(query: String): List<TrackSearch> {
        return api.search(query).mapNotNull { result ->
            when (result.type) {
                "show" -> result.show?.toTrackSearch()
                "movie" -> result.movie?.toTrackSearch()
                else -> null
            }
        }
    }

    private fun idsFromRemoteId(remoteId: Long): TraktIds {
        return TraktIds(trakt = remoteId, slug = "", imdb = null, tmdb = null)
    }

    override suspend fun update(track: Track, didWatchEpisode: Boolean): Track {
        if (track.remote_id == 0L) return track
        ensureTotalEpisodes(track)
        applyLocalStatus(track, didWatchEpisode)
        return if (isMovieTrack(track)) {
            updateMovieTrack(track)
        } else {
            updateShowTrack(track)
        }
    }

    override suspend fun delete(track: DomainTrack) {
        // Best-effort removal using the domain model fields.
        try {
            val rid = track.remoteId
            if (rid == 0L) return
            // Try both removals; one will be a no-op server-side if not applicable.
            try {
                api.removeShowHistory(rid)
            } catch (_: Exception) {}
            try {
                api.removeMovieHistory(rid)
            } catch (_: Exception) {}
        } catch (_: Exception) {
            // ignore failures for best-effort removal
        }
    }

    suspend fun getSeasons(remoteId: Long): List<Pair<Int, Int>> = withIOContext {
        api.getShowSeasons(remoteId)
    }

    override suspend fun setRemoteLastEpisodeSeen(track: Track, episodeNumber: Int) {
        val (seasonParam, _) = resolveSeasonEpisode(track.last_episode_seen)
        val currentEpisode = if (seasonParam != null) {
            resolveSeasonEpisode(track.last_episode_seen).second
        } else {
            track.last_episode_seen.toInt()
        }
        if (currentEpisode == 0 &&
            currentEpisode < episodeNumber &&
            track.status != getRewatchingStatus()
        ) {
            track.status = getWatchingStatus()
        }

        if (seasonParam != null) {
            track.last_episode_seen = encodeSeasonEpisode(seasonParam, episodeNumber)
        } else {
            track.last_episode_seen = episodeNumber.toDouble()
        }

        val finalEpisode = if (seasonParam != null) episodeNumber else track.last_episode_seen.toLong().toInt()
        if (track.total_episodes != 0L && finalEpisode.toLong() == track.total_episodes) {
            track.status = getCompletionStatus()
            track.finished_watching_date = System.currentTimeMillis()
        }

        withIOContext {
            try {
                update(track, didWatchEpisode = true)
                track.toDomainTrack(idRequired = false)?.let {
                    Injekt.get<InsertTrack>().await(it)
                }
            } catch (e: Throwable) {
                withUIContext { Injekt.get<Application>().toast(e.message) }
                throw e
            }
        }
    }

    override suspend fun bind(track: Track, hasSeenEpisodes: Boolean): Track {
        // Try to find the item in the user's watched/collection. If found, copy progress into the track.
        try {
            val remoteId = track.remote_id
            if (remoteId == 0L) return update(track, didWatchEpisode = hasSeenEpisodes)
            ensureTotalEpisodes(track)
            val traktId = remoteId
            val items = if (track.total_episodes == 1L) {
                api.getUserMovies()
            } else {
                api.getUserShows()
            }
            val found = items.firstOrNull { it.traktId == traktId }
            if (found != null) {
                track.library_id = traktId
                val (existingSeason, _) = resolveSeasonEpisode(track.last_episode_seen)
                if (existingSeason != null) {
                    val currentEp = resolveSeasonEpisode(track.last_episode_seen).second
                    track.last_episode_seen = encodeSeasonEpisode(existingSeason, maxOf(currentEp, found.progress))
                } else {
                    track.last_episode_seen = found.progress.toDouble()
                }
                return track
            }
        } catch (_: Exception) {
            // ignore and fallback to update
        }
        return update(track, didWatchEpisode = hasSeenEpisodes)
    }

    override suspend fun refresh(track: Track): Track {
        try {
            val remoteId = track.remote_id
            if (remoteId == 0L) return track
            ensureTotalEpisodes(track)
            val traktId = remoteId
            val items = if (track.total_episodes == 1L) {
                api.getUserMovies()
            } else {
                api.getUserShows()
            }
            val found = items.firstOrNull { it.traktId == traktId }
            if (found != null) {
                val (existingSeason, _) = resolveSeasonEpisode(track.last_episode_seen)
                if (existingSeason != null) {
                    val currentEp = resolveSeasonEpisode(track.last_episode_seen).second
                    track.last_episode_seen = encodeSeasonEpisode(existingSeason, maxOf(currentEp, found.progress))
                } else {
                    track.last_episode_seen = found.progress.toDouble()
                }
            }
        } catch (_: Exception) {
            // ignore errors, return track as-is
        }
        return track
    }

    // OAuth login helpers:
    // The app's TrackLoginActivity should provide the authorization code to this login(code) method.
    override suspend fun login(username: String, password: String) = login(password)

    fun login(code: String) {
        try {
            val token = try {
                api.loginOAuth(code, CLIENT_ID, CLIENT_SECRET, REDIRECT_URI)
            } catch (_: Exception) {
                null
            }
            if (token == null) {
                throw Exception("Failed to get token from Trakt")
            }
            oauth = token
            interceptor.setAuth(token.access_token)
            saveToken(token)

            // fetch username and save as credentials (password stores access token per BaseTracker convention)
            val username = try {
                api.getCurrentUser() ?: ""
            } catch (_: Exception) {
                ""
            }
            saveCredentials(username, token.access_token)
        } catch (e: Throwable) {
            logout()
            throw e
        }
    }

    /**
     * Blocking refresh used by the interceptor when executing synchronous requests.
     * Returns true if the token was refreshed successfully.
     */
    fun refreshAuthBlocking(): Boolean {
        return try {
            val saved = restoreToken() ?: return false
            val refreshed = api.refreshOAuth(saved.refresh_token, CLIENT_ID, CLIENT_SECRET) ?: return false
            oauth = refreshed
            interceptor.setAuth(refreshed.access_token)
            saveToken(refreshed)
            // Try to update stored username
            try {
                val username = api.getCurrentUser() ?: ""
                saveCredentials(username, refreshed.access_token)
            } catch (_: Exception) {
                // ignore
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun logout() {
        // Clear persisted tokens and interceptor
        oauth = null
        saveToken(null)
        interceptor.setAuth(null)
        super.logout()
    }

    private fun TraktShow.toTrackSearch(): TrackSearch =
        createTrackSearch(ids.trakt, title, overview, images?.poster, ids.slug, isMovie = false)

    private fun TraktMovie.toTrackSearch(): TrackSearch =
        createTrackSearch(ids.trakt, title, overview, images?.poster, ids.slug, isMovie = true)

    private fun createTrackSearch(
        remoteId: Long,
        title: String,
        overview: String?,
        posterEl: JsonElement?,
        slug: String,
        isMovie: Boolean,
    ): TrackSearch {
        val path = if (isMovie) "movies" else "shows"
        val slugOrId = slug.takeIf { it.isNotBlank() } ?: remoteId.toString()
        return TrackSearch.create(this@Trakt.id).apply {
            this.remote_id = remoteId
            this.title = title
            this.summary = overview ?: ""
            this.cover_url = extractPosterUrl(posterEl)
            this.total_episodes = if (isMovie) 1L else 0L
            this.tracking_url = "https://trakt.tv/$path/$slugOrId"
        }
    }

    private fun extractPosterUrl(posterEl: JsonElement?): String {
        val raw = when (posterEl) {
            null -> null
            is JsonObject -> posterEl["full"]?.jsonPrimitive?.contentOrNull
                ?: posterEl["medium"]?.jsonPrimitive?.contentOrNull
                ?: posterEl["thumb"]?.jsonPrimitive?.contentOrNull
                ?: posterEl.entries.firstOrNull()?.value?.let { extractPosterUrl(it) }
            is JsonArray -> posterEl.firstOrNull()?.jsonPrimitive?.contentOrNull
            else -> posterEl.jsonPrimitive.contentOrNull
        }?.trim().takeUnless { it.isNullOrBlank() } ?: return ""

        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> raw
            else -> "https://$raw"
        }
    }

    private fun applyLocalStatus(track: Track, didWatchEpisode: Boolean) {
        if (!didWatchEpisode || track.status == COMPLETED) return
        val currentEpisode = resolveSeasonEpisode(track.last_episode_seen).second
        track.status = if (track.total_episodes > 0 && currentEpisode.toLong() == track.total_episodes) {
            COMPLETED
        } else {
            WATCHING
        }
    }

    private fun updateMovieTrack(track: Track): Track {
        val ids = idsFromRemoteId(track.remote_id)
        runCatching {
            if (track.last_episode_seen.toLong() >= 1L) {
                val alreadyWatched = runCatching {
                    api.getUserMovies().any { it.traktId == ids.trakt }
                }.getOrDefault(false)
                if (!alreadyWatched) {
                    val syncMovie = TraktSyncMovie(ids = ids, watched = true)
                    api.updateMovieWatched(syncMovie)
                }
            }
            syncRating(ids.trakt, track.score, isMovie = true)
        }
        return track
    }

    private fun updateShowTrack(track: Track): Track {
        val traktId = track.remote_id
        val (seasonParam, episodeParam) = resolveSeasonEpisode(track.last_episode_seen)
        runCatching {
            api.updateShowEpisodeProgress(traktId, seasonParam, episodeParam)
        }
        syncRating(traktId, track.score, isMovie = false)
        return track
    }

    private fun encodeSeasonEpisode(season: Int, episode: Int): Double {
        val formattedFraction = String.format(java.util.Locale.US, "%04d1", episode)
        return "$season.$formattedFraction".toDoubleOrNull() ?: (season.toDouble())
    }

    private fun resolveSeasonEpisode(lastSeen: Double): Pair<Int?, Int> {
        val lastSeenStr = runCatching {
            java.math.BigDecimal.valueOf(lastSeen).stripTrailingZeros().toPlainString()
        }.getOrNull()
        if (!lastSeenStr.isNullOrBlank() && lastSeenStr.contains('.')) {
            val parts = lastSeenStr.split('.', limit = 2)
            val season = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val fraction = parts.getOrNull(1).orEmpty()
            if (fraction.endsWith('1') && fraction.length >= 2) {
                val episode = fraction.dropLast(1).toIntOrNull() ?: 0
                return season to episode
            } else {
                val episode = fraction.trimStart('0').toIntOrNull()
                    ?: fraction.toIntOrNull()
                    ?: lastSeen.roundToInt().coerceAtLeast(1)
                return season to episode
            }
        }
        return null to lastSeen.roundToInt().coerceAtLeast(1)
    }

    private fun syncRating(traktId: Long, score: Double, isMovie: Boolean) {
        if (score <= 0.0) return
        val rating = score.toInt().coerceIn(1, 10)
        runCatching {
            if (isMovie) {
                api.sendRatings(movieRatings = listOf(traktId to rating))
            } else {
                api.sendRatings(showRatings = listOf(traktId to rating))
            }
        }
    }

    override fun getNoticeStringRes(): StringResource {
        return MR.strings.trakt_import_notice
    }

    override suspend fun getImportableList(): List<ImportableEntry> {
        return withIOContext {
            api.getImportableList()
        }
    }
}
