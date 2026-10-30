package eu.kanade.tachiyomi.data.track.myanimelist

import android.graphics.Color
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.ImportStatusFilter
import eu.kanade.tachiyomi.data.track.ImportableEntry
import eu.kanade.tachiyomi.data.track.ImportableTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALOAuth
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import tachiyomi.domain.track.model.Track as DomainAnimeTrack

class MyAnimeList(id: Long) :
    BaseTracker(
        id,
        "MyAnimeList",
    ),
    AnimeTracker,
    DeletableTracker,
    ImportableTracker {

    companion object {
        const val READING = 1L
        const val WATCHING = 11L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val DROPPED = 4L
        const val PLAN_TO_READ = 6L
        const val PLAN_TO_WATCH = 16L
        const val REREADING = 7L
        const val REWATCHING = 17L

        private const val SEARCH_ID_PREFIX = "id:"
        private const val SEARCH_LIST_PREFIX = "my:"

        private val SCORE_LIST = IntRange(0, 10)
            .map(Int::toString)
            .toImmutableList()
    }

    private val json: Json by injectLazy()

    private val interceptor by lazy { MyAnimeListInterceptor(this) }
    private val api by lazy { MyAnimeListApi(id, client, interceptor) }

    override val supportsReadingDates: Boolean = true

    override fun getLogo() = R.drawable.ic_tracker_mal

    override fun getLogoColor() = Color.rgb(46, 81, 162)

    override fun getStatusListAnime(): List<Long> {
        return listOf(WATCHING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_WATCH, REWATCHING)
    }

    override fun getStatusForAnime(status: Long): StringResource? = when (status) {
        WATCHING -> MR.strings.watching
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        DROPPED -> MR.strings.dropped
        PLAN_TO_WATCH -> MR.strings.plan_to_watch
        REWATCHING -> MR.strings.repeating_anime
        else -> null
    }

    override fun getWatchingStatus(): Long = WATCHING

    override fun getRewatchingStatus(): Long = REWATCHING

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): ImmutableList<String> = SCORE_LIST

    override fun indexToScore(index: Int): Double {
        return index.toDouble()
    }

    override fun displayScore(track: DomainAnimeTrack): String {
        return track.score.toInt().toString()
    }

    private suspend fun add(track: Track): Track {
        track.status = WATCHING
        track.score = 0.0
        return api.updateItem(track)
    }

    override suspend fun update(track: Track, didWatchEpisode: Boolean): Track {
        if (track.status != COMPLETED) {
            if (didWatchEpisode) {
                if (track.last_episode_seen.toLong() == track.total_episodes && track.total_episodes > 0) {
                    track.status = COMPLETED
                    track.finished_watching_date = System.currentTimeMillis()
                } else if (track.status != REWATCHING) {
                    track.status = WATCHING
                    if (track.last_episode_seen == 1.0) {
                        track.started_watching_date = System.currentTimeMillis()
                    }
                }
            }
        }

        return api.updateItem(track)
    }

    override suspend fun delete(track: DomainAnimeTrack) {
        api.deleteAnimeItem(track)
    }

    override suspend fun bind(track: Track, hasSeenEpisodes: Boolean): Track {
        val remoteTrack = api.findListItem(track)
        return if (remoteTrack != null) {
            track.copyPersonalFrom(remoteTrack)
            track.remote_id = remoteTrack.remote_id

            if (track.status != COMPLETED) {
                val isRewatching = track.status == REWATCHING
                track.status = if (!isRewatching && hasSeenEpisodes) WATCHING else track.status
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
        if (query.startsWith(SEARCH_ID_PREFIX)) {
            query.substringAfter(SEARCH_ID_PREFIX).toIntOrNull()?.let { id ->
                return listOf(api.getAnimeDetails(id))
            }
        }

        if (query.startsWith(SEARCH_LIST_PREFIX)) {
            query.substringAfter(SEARCH_LIST_PREFIX).let { title ->
                return api.findListItemsAnime(title)
            }
        }

        return api.searchAnime(query)
    }

    override suspend fun refresh(track: Track): Track {
        return api.findListItem(track) ?: add(track)
    }

    suspend fun getUserAnimeList(): List<eu.kanade.tachiyomi.data.track.myanimelist.dto.MALUserAnimeListItem> {
        return api.getUserAnimeList()
    }

    override suspend fun login(username: String, password: String) = login(password)

    suspend fun login(authCode: String) {
        try {
            val oauth = api.getAccessToken(authCode)
            interceptor.setAuth(oauth)
            val username = api.getCurrentUser()
            saveCredentials(username, oauth.accessToken)
        } catch (e: Throwable) {
            logout()
        }
    }

    override fun logout() {
        super.logout()
        trackPreferences.trackToken(this).delete()
        interceptor.setAuth(null)
    }

    override suspend fun getAnimeMetadata(track: DomainAnimeTrack): eu.kanade.tachiyomi.data.track.model.TrackAnimeMetadata? {
        return try {
            val malTrack = api.getAnimeDetails(track.remoteId.toInt())
            eu.kanade.tachiyomi.data.track.model.TrackAnimeMetadata(
                remoteId = malTrack.remote_id,
                title = malTrack.title,
                thumbnailUrl = malTrack.cover_url,
                description = malTrack.summary,
                author = null,
                artist = null,
                genres = null,
            )
        } catch (e: Exception) {
            null
        }
    }

    fun getIfAuthExpired(): Boolean {
        return trackPreferences.trackAuthExpired(this).get()
    }

    fun setAuthExpired() {
        trackPreferences.trackAuthExpired(this).set(true)
    }

    fun saveOAuth(oAuth: MALOAuth?) {
        trackPreferences.trackToken(this).set(json.encodeToString(oAuth))
    }

    fun loadOAuth(): MALOAuth? {
        return try {
            json.decodeFromString<MALOAuth>(trackPreferences.trackToken(this).get())
        } catch (e: Exception) {
            null
        }
    }

    override fun getNoticeStringRes(): StringResource {
        return MR.strings.myanimelist_import_notice
    }

    override suspend fun getImportableList(): List<ImportableEntry> {
        return getUserAnimeList().map { item ->
            val mappedStatusFilter = when (item.listStatus.status) {
                "watching" -> ImportStatusFilter.WATCHING
                "plan_to_watch" -> ImportStatusFilter.PLAN_TO_WATCH
                "completed" -> ImportStatusFilter.COMPLETED
                "on_hold" -> ImportStatusFilter.ON_HOLD
                else -> null
            }
            ImportableEntry(
                remoteId = item.node.id,
                title = item.node.title,
                coverUrl = item.node.covers?.large ?: item.node.covers?.medium ?: "",
                totalEpisodes = item.node.numEpisodes,
                episodesSeen = item.listStatus.numEpisodesWatched.toInt(),
                score = item.listStatus.score.toDouble(),
                status = item.toMALUserAnime().toTrack().status,
                statusFilter = mappedStatusFilter,
                startDate = item.listStatus.startDate?.let { parseDate(it) } ?: 0L,
                finishDate = item.listStatus.finishDate?.let { parseDate(it) } ?: 0L,
                trackingUrl = "https://myanimelist.net/anime/${item.node.id}",
            )
        }
    }

    private fun parseDate(isoDate: String): Long {
        if (isoDate.isBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(isoDate)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
