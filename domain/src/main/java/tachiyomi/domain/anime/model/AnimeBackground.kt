package tachiyomi.domain.anime.model

import androidx.compose.runtime.Immutable

/**
 * Contains the required data for the anime background/backdrop image loader.
 *
 * Unlike [AnimeCover] this always points at [Anime.backgroundUrl] and is never overridden by a
 * user's custom cover, so the details backdrop stays independent from the poster.
 */
@Immutable
data class AnimeBackground(
    val animeId: Long,
    val sourceId: Long,
    val isAnimeFavorite: Boolean,
    val url: String?,
    val lastModified: Long,
)

fun Anime.asAnimeBackground(): AnimeBackground {
    return AnimeBackground(
        animeId = id,
        sourceId = source,
        isAnimeFavorite = favorite,
        url = backgroundUrl,
        lastModified = backgroundLastModified,
    )
}
