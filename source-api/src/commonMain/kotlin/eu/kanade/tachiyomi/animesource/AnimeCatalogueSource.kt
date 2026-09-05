package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import rx.Observable
import tachiyomi.core.common.util.lang.awaitSingle

interface AnimeCatalogueSource : AnimeSource {

    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    override val lang: String

    /**
     * Whether the source has support for latest updates.
     */
    val supportsLatest: Boolean

    /**
     * Get a page with a list of anime.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     */
    @Suppress("DEPRECATION")
    suspend fun getPopularAnime(page: Int): AnimesPage {
        return fetchPopularAnime(page).awaitSingle()
    }

    /**
     * Get a page with a list of anime.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    @Suppress("DEPRECATION")
    suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return fetchSearchAnime(page, query, filters).awaitSingle()
    }

    /**
     * Get a page with a list of latest anime updates.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     */
    @Suppress("DEPRECATION")
    suspend fun getLatestUpdates(page: Int): AnimesPage {
        return fetchLatestUpdates(page).awaitSingle()
    }

    /**
     * Returns the list of filters for the source.
     */
    fun getFilterList(): AnimeFilterList

    // Should be replaced as soon as Anime Extension reach 1.5
    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPopularAnime"),
    )
    fun fetchPopularAnime(page: Int): Observable<AnimesPage>

    // Should be replaced as soon as Anime Extension reach 1.5
    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getSearchAnime"),
    )
    fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage>

    // Should be replaced as soon as Anime Extension reach 1.5
    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getLatestUpdates"),
    )
    fun fetchLatestUpdates(page: Int): Observable<AnimesPage>

    // KMK -->

    /**
     * Whether parsing related animes in anime page or extension provide custom related animes request.
     * @default false
     * @since komikku/extensions-lib 1.6
     */
    val supportsRelatedAnimes: Boolean get() = false

    /**
     * Whether the source supports related anime (Aniyomi naming).
     */
    override val supportsRelatedAnime: Boolean get() = supportsRelatedAnimes

    override suspend fun getRelatedAnimeList(
        anime: eu.kanade.tachiyomi.animesource.model.SAnime,
    ): List<eu.kanade.tachiyomi.animesource.model.AnimeRelation> = emptyList()

    /**
     * Get all the available related animes for an anime.
     * Normally it's not needed to override this method.
     *
     * @since komikku/extensions-lib 1.6
     * @param anime the current anime to get related animes.
     * @return a list of <keyword, related animes>
     * @throws UnsupportedOperationException if a source doesn't support related animes.
     */
    override suspend fun getRelatedAnimeList(
        anime: eu.kanade.tachiyomi.animesource.model.SAnime,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedAnime: Pair<String, List<eu.kanade.tachiyomi.animesource.model.SAnime>>, completed: Boolean) -> Unit,
    ) {
        if (supportsRelatedAnimes) {
            getRelatedAnimeListByExtension(anime, pushResults)
        }
    }

    /**
     * Get related animes provided by extension
     *
     * @return a list of <keyword, related animes>
     * @since komikku/extensions-lib 1.6
     */
    suspend fun getRelatedAnimeListByExtension(
        anime: eu.kanade.tachiyomi.animesource.model.SAnime,
        pushResults: suspend (relatedAnime: Pair<String, List<eu.kanade.tachiyomi.animesource.model.SAnime>>, completed: Boolean) -> Unit,
    ) {
        try {
            val related = fetchRelatedAnimeList(anime)
            if (related.isNotEmpty()) {
                pushResults(Pair("", related), false)
            }
        } catch (e: Throwable) {
            // Ignored
        }
    }

    /**
     * Fetch related animes for an anime from source/site.
     *
     * @since komikku/extensions-lib 1.6
     * @param anime the current anime to get related animes.
     * @return the related animes for the current anime.
     * @throws UnsupportedOperationException if a source doesn't support related animes.
     */
    suspend fun fetchRelatedAnimeList(anime: eu.kanade.tachiyomi.animesource.model.SAnime): List<eu.kanade.tachiyomi.animesource.model.SAnime> = throw UnsupportedOperationException("Unsupported!")
    // KMK <--
}
