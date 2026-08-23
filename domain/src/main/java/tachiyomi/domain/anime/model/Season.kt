package tachiyomi.domain.anime.model

import androidx.compose.runtime.Immutable

@Immutable
data class Season(
    val anime: Anime,
    val seasonNumber: Double,
    val isPrimary: Boolean = false,
)
