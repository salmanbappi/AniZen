// ANZ -->
package eu.kanade.tachiyomi.data.anilist

import kotlinx.serialization.Serializable

@Serializable
data class AniListMedia(
    val id: Int,
    val title: AniListTitle? = null,
    val bannerImage: String? = null,
    val coverImage: AniListCoverImage? = null,
    val description: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val averageScore: Int? = null,
    val genres: List<String>? = null,
    val episodes: Int? = null,
    val status: String? = null,
    val format: String? = null,
    val trailer: AniListTrailer? = null,
) {
    val displayTitle: String
        get() = title?.english?.takeIf { it.isNotBlank() }
            ?: title?.userPreferred?.takeIf { it.isNotBlank() }
            ?: title?.romaji?.takeIf { it.isNotBlank() }
            ?: title?.native?.takeIf { it.isNotBlank() }
            ?: "Anime #$id"

    val displayCover: String
        get() = coverImage?.extraLarge ?: coverImage?.large ?: coverImage?.medium.orEmpty()
}

@Serializable
data class AniListTitle(
    val userPreferred: String? = null,
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
)

@Serializable
data class AniListCoverImage(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
    val color: String? = null,
)

@Serializable
data class AniListTrailer(
    val id: String? = null,
    val site: String? = null,
)
// ANZ <--
