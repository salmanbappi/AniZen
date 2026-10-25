package tachiyomi.domain.anime.interactor

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.track.interactor.GetTracks

class CalculateUserAffinity(
    private val getLibraryAnime: GetLibraryAnime,
    private val getTracks: GetTracks,
    private val preferences: LibraryPreferences,
) {

    suspend fun await(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - preferences.lastAffinityUpdate().get() < UPDATE_INTERVAL) return

        val library = getLibraryAnime.await()
        if (library.isEmpty()) return

        val tracks = getTracks.await()
        val trackMap = tracks.groupBy { it.animeId }

        val affinityMap = mutableMapOf<String, Float>()

        library.forEach { libAnime ->
            val anime = libAnime.anime
            val animeTracks = trackMap[anime.id] ?: emptyList()

            // Core Formula: (score - 5) * (seen / total)
            // Use 5.0 as neutral score if no tracks available
            val score = animeTracks.map { it.score }.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 5.0
            val progress = if (libAnime.totalEpisodes > 0) {
                libAnime.seenCount.toFloat() / libAnime.totalEpisodes
            } else if (libAnime.hasStarted) {
                0.5f
            } else {
                0.0f
            }

            val weight = (score.toFloat() - 5f) * progress

            // Features: Genres
            anime.genre?.forEach { tag ->
                val normalizedTag = tag.trim().lowercase()
                affinityMap[normalizedTag] = (affinityMap[normalizedTag] ?: 0f) + weight
            }

            // Features: Author (Studio)
            anime.author?.split(",")?.forEach { author ->
                val normalizedAuthor = "studio:${author.trim().lowercase()}"
                affinityMap[normalizedAuthor] = (affinityMap[normalizedAuthor] ?: 0f) + (weight * 1.5f) // Studio is high signal
            }
        }

        preferences.userAffinityMap().set(Json.encodeToString(affinityMap))
        preferences.lastAffinityUpdate().set(now)
    }

    companion object {
        private const val UPDATE_INTERVAL = 24 * 60 * 60 * 1000L // 24 hours
    }
}
