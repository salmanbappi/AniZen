package eu.kanade.tachiyomi.animesource.model

/**
 * The result of a combined details + seasons fetch from a source.
 *
 * @since extensions-lib 17
 * @param anime the updated anime details, may be the same instance if unchanged
 * @param seasons the available seasons of the anime
 */
class SAnimeSeasonUpdate(
    val anime: SAnime,
    val seasons: List<SAnime>,
)
