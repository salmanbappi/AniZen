package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.awaitSingle
import rx.Observable

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc.
 */
interface AnimeSource {

    /**
     * ID for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Fetches updated information for an anime.
     *
     * Depending on the provided flags or source availability, this may include
     * updated anime metadata, available episodes, or both.
     *
     * If a value is not requested, the existing provided value can be returned as-is.
     * The host app may apply any returned updates regardless of the flags,
     * so care should be taken to only return accurate and intentional changes.
     *
     * @since extensions-lib 17
     * @param anime The anime to fetch updates for.
     * @param episodes Existing episodes of the anime.
     * @param fetchDetails Whether to fetch updated anime details.
     * @param fetchEpisodes Whether to fetch available episodes.
     */
    @Suppress("DEPRECATION")
    suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate {
        // Bridges the combined API to the legacy methods so extensions built against
        // older lib versions keep working without any branching in the host app.
        val updatedAnime = if (fetchDetails) getAnimeDetails(anime) else anime
        val newEpisodes = if (fetchEpisodes) getEpisodeList(anime) else episodes
        return SAnimeEpisodeUpdate(updatedAnime, newEpisodes)
    }

    /**
     * Fetches updated information for an anime.
     *
     * Depending on the provided flags or source availability, this may include
     * updated anime metadata, available seasons, or both.
     *
     * If a value is not requested, the existing provided value can be returned as-is.
     * The host app may apply any returned updates regardless of the flags,
     * so care should be taken to only return accurate and intentional changes.
     *
     * @since extensions-lib 17
     * @param anime The anime to fetch updates for.
     * @param seasons Existing seasons of the anime.
     * @param fetchDetails Whether to fetch updated anime details.
     * @param fetchSeasons Whether to fetch available seasons.
     */
    @Suppress("DEPRECATION")
    suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate {
        val updatedAnime = if (fetchDetails) getAnimeDetails(anime) else anime
        val newSeasons = if (fetchSeasons) getSeasonList(anime) else seasons
        return SAnimeSeasonUpdate(updatedAnime, newSeasons)
    }

    /**
     * Get the updated details for a anime.
     *
     * @since extensions-lib 1.5
     * @param anime the anime to update.
     * @return the updated anime.
     */
    @Deprecated(
        "Use the combined suspend API instead",
        ReplaceWith("getAnimeEpisodeUpdate"),
    )
    @Suppress("DEPRECATION")
    suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return fetchAnimeDetails(anime).awaitSingle()
    }

    /**
     * Get all the available episodes for a anime.
     *
     * @since extensions-lib 1.5
     * @param anime the anime to update.
     * @return the episodes for the anime.
     */
    @Deprecated(
        "Use the combined suspend API instead",
        ReplaceWith("getAnimeEpisodeUpdate"),
    )
    @Suppress("DEPRECATION")
    suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return fetchEpisodeList(anime).awaitSingle()
    }

    /**
     * Get all the available seasons for an anime
     *
     * @since extensions-lib 16
     * @param anime the anime to fetch seasons for.
     * @return the anime list for the anime.
     */
    @Deprecated(
        "Use the combined suspend API instead",
        ReplaceWith("getAnimeSeasonUpdate"),
    )
    suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    /**
     * Get the list of hoster for an episode. The first hoster in the list should
     * be the preferred hoster.
     *
     * @since extensions-lib 16
     * @param anime the anime.
     * @param episode the episode.
     * @return the hosters for the episode.
     */
    suspend fun getHosterList(anime: SAnime, episode: SEpisode): List<Hoster> = getHosterList(episode)

    @Deprecated("Use the version with anime instead", ReplaceWith("getHosterList(anime, episode)"))
    suspend fun getHosterList(episode: SEpisode): List<Hoster> = throw IllegalStateException("Not used")

    /**
     * Get the list of videos for a hoster.
     *
     * @since extensions-lib 16
     * @param hoster the hoster.
     * @return the videos for the hoster.
     */
    suspend fun getVideoList(hoster: Hoster): List<Video> = throw IllegalStateException("Not used")

    /**
     * Get the list of videos a episode has. Videos should be returned
     * in the expected order; the index is ignored.
     *
     * @since extensions-lib 1.5
     * @param episode the episode.
     * @return the videos for the episode.
     */
    @Suppress("DEPRECATION")
    suspend fun getVideoList(episode: SEpisode): List<Video> {
        return fetchVideoList(episode).awaitSingle()
    }

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getAnimeDetails"),
    )
    fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getEpisodeList"),
    )
    fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getVideoList"),
    )
    fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        throw IllegalStateException("Not used")

    // Lib 17 -->
    val supportsRelatedAnime: Boolean
        get() = false

    suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> = emptyList()
    // Lib 17 <--

    // KMK -->
    /**
     * Get all the available related animes for a anime.
     *
     * @since komikku/extensions-lib 1.6
     * @param anime the current anime to get related animes.
     * @return a list of <keyword, related animes>
     */
    suspend fun getRelatedAnimeList(
        anime: SAnime,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedAnime: Pair<String, List<SAnime>>, completed: Boolean) -> Unit,
    ): Unit = getRelatedMangaList(anime, exceptionHandler, pushResults)
    suspend fun getRelatedMangaList(
        anime: SAnime,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedAnime: Pair<String, List<SAnime>>, completed: Boolean) -> Unit,
    ): Unit = throw UnsupportedOperationException()
    // KMK <--
}
