package eu.kanade.tachiyomi.util.episode

import tachiyomi.domain.episode.model.Episode

object EpisodeSeasonUtils {
    // Added |_ to delimiters to support patterns like _s1e1_
    private val seasonRegex = Regex("""(?i)(?:^|\b|\s|\[|_)(?:s|season\s*)(\d+)(?:\s|e|x|\||-|\.|\b|\]|_|$)""")

    /**
     * Extracts season number from episode name.
     * Returns "Season X" if found.
     */
    fun getSeasonName(episode: Episode): String? {
        val name = episode.name
        val match = seasonRegex.find(name)
        return if (match != null) {
            val seasonNumber = match.groupValues[1].toIntOrNull()
            if (seasonNumber != null) "Season $seasonNumber" else null
        } else {
            null
        }
    }

    /**
     * Checks if the episode is from Season 0.
     * Also detects 0.x numbering (e.g., 0.1, 0.2) used by many extensions for specials.
     */
    fun isSeasonZero(episode: Episode): Boolean {
        val hasSeasonZeroName = getSeasonName(episode) == "Season 0"
        val hasSeasonZeroNumber = episode.episodeNumber >= 0 && episode.episodeNumber < 1.0
        return hasSeasonZeroName || hasSeasonZeroNumber
    }

    /**
     * Comparator to sort section names naturally.
     * Priority: Seasons > Specials > Extras.
     */
    val SeasonComparator = Comparator<String> { s1, s2 ->
        fun getPriority(s: String): Int {
            return when {
                s.startsWith("Season", ignoreCase = true) -> 0
                s.contains("Special", ignoreCase = true) -> 1
                s.contains("Extra", ignoreCase = true) -> 2
                else -> 3
            }
        }

        val p1 = getPriority(s1)
        val p2 = getPriority(s2)

        if (p1 != p2) return@Comparator p1.compareTo(p2)

        val n1 = s1.filter { it.isDigit() }.toIntOrNull() ?: 0
        val n2 = s2.filter { it.isDigit() }.toIntOrNull() ?: 0
        if (n1 != n2) {
            n1.compareTo(n2)
        } else {
            s1.compareTo(s2)
        }
    }
}
