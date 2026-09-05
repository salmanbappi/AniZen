package eu.kanade.tachiyomi.animesource.model

/**
 * The result of a combined details + episodes fetch from a source.
 *
 * @since extensions-lib 17
 * @param anime the updated anime details, may be the same instance if unchanged
 * @param episodes the available episodes of the anime
 */
class SAnimeEpisodeUpdate(
    val anime: SAnime,
    val episodes: List<SEpisode>,
)
