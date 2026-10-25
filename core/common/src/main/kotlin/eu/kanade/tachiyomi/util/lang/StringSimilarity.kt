package eu.kanade.tachiyomi.util.lang

import kotlin.math.max

object StringSimilarity {

    private val STOP_WORDS = setOf(
        "the", "a", "an", "my", "no", "in", "of", "to", "for", "with", "on", "at", "by", "from",
        "season", "2nd", "3rd", "4th", "5th", "s1", "s2", "s3", "s4", "s5", "tv", "ova", "ona", "movie",
    )

    // Pre-compiled scalpel regex: replaces special chars with space to preserve tokens
    private val SCALPEL_REGEX = Regex("[^a-z0-9\\s]")
    private val WHITESPACE_REGEX = Regex("\\s+")

    private val FRANCHISE_SUFFIXES = Regex("(?i)\\s(season|part|cour|vol|volume|tv|ova|ona|movie|special|specials|extra|extras|s\\d+|v\\d+|\\d+st|\\d+nd|\\d+rd|\\d+th|\\(\\d{4}\\)|\\[\\d{4}\\]).*")
    private val YEAR_REGEX = Regex("\\s?\\(\\d{4}\\)|\\s?\\[\\d{4}\\]")

    /**
     * Extracts the "Root" title by stripping season, part, and media format indicators.
     * Useful for finding sequels, prequels, and related entries.
     */
    fun getRootTitle(title: String): String {
        val root = title.replace(YEAR_REGEX, "")
            .replace(FRANCHISE_SUFFIXES, "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

        // Fallback: If stripping everything left us with too little, return first three words
        return if (root.length < 3 || root.split(" ").size < 1) {
            title.split(" ").take(3).joinToString(" ").lowercase().replace(SCALPEL_REGEX, " ").trim()
        } else {
            root.lowercase()
        }
    }

    /**
     * Cleans a title for better search results.
     */
    fun cleanTitle(title: String): String {
        return title.replace(YEAR_REGEX, "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .lowercase()
    }

    /**
     * Extracts significant keywords. Replaces special characters with spaces
     * to ensure "K-On!" becomes "k on" for better search engine tokenization.
     */
    fun getSearchKeywords(title: String): String {
        return title.lowercase()
            .replace(SCALPEL_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() && it !in STOP_WORDS }
            .take(5) // Increased from 3 to 5 for better specificity
            .joinToString(" ")
    }

    /**
     * Token Sort Ratio: Order-independent Levenshtein.
     * Uses pre-compiled regex for efficiency.
     */
    fun tokenSortRatio(s1: String, s2: String): Double {
        val t1 = s1.lowercase().replace(SCALPEL_REGEX, " ").split(" ").filter { it.isNotBlank() }.sorted().joinToString("")
        val t2 = s2.lowercase().replace(SCALPEL_REGEX, " ").split(" ").filter { it.isNotBlank() }.sorted().joinToString("")
        return if (t1.isEmpty() || t2.isEmpty()) 0.0 else levenshteinSimilarity(t1, t2)
    }

    fun diceCoefficient(s1: String, s2: String): Double {
        val str1 = s1.lowercase().replace(Regex("[^a-z0-9]"), "")
        val str2 = s2.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (str1 == str2) return 1.0
        if (str1.length < 2 || str2.length < 2) return 0.0
        val s1Bigrams = str1.windowed(2)
        val s2Bigrams = str2.windowed(2).toMutableList()
        var matches = 0
        for (bigram in s1Bigrams) {
            if (s2Bigrams.remove(bigram)) matches++
        }
        return (2.0 * matches) / (s1Bigrams.size + str2.length - 1)
    }

    /**
     * Optimized Levenshtein Distance (Two-Row Algorithm).
     * Space: O(min(N, M)) instead of O(N * M).
     */
    fun levenshteinSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val len1 = s1.length
        val len2 = s2.length
        if (len1 == 0 || len2 == 0) return 0.0

        // Optimization: Ensure we allocate the smaller array
        val (short, long) = if (len1 < len2) s1 to s2 else s2 to s1
        val minLen = short.length
        val maxLen = long.length

        var previousRow = IntArray(minLen + 1) { it }
        var currentRow = IntArray(minLen + 1)

        for (i in 1..maxLen) {
            currentRow[0] = i
            for (j in 1..minLen) {
                val cost = if (long[i - 1] == short[j - 1]) 0 else 1
                currentRow[j] = minOf(
                    currentRow[j - 1] + 1, // Insertion
                    previousRow[j] + 1, // Deletion
                    previousRow[j - 1] + cost, // Substitution
                )
            }
            // Swap arrays to reuse memory
            for (j in 0..minLen) {
                previousRow[j] = currentRow[j]
            }
        }

        val distance = previousRow[minLen]
        return 1.0 - (distance.toDouble() / max(len1, len2))
    }

    /**
     * Smoothly interpolates weights based on confidence.
     * Prevents ranking cliffs by using a linear shift between thresholds.
     */
    fun adaptiveScore(titleSim: Double, genreOverlap: Double): Double {
        // Transition window: Below 0.4 we trust tags (80%), above 0.8 we trust title (80%)
        val minThreshold = 0.4
        val maxThreshold = 0.8

        // Calculate interpolation factor (alpha) between 0 and 1
        val alpha = ((titleSim - minThreshold) / (maxThreshold - minThreshold)).coerceIn(0.0, 1.0)

        // titleWeight shifts from 0.2 to 0.8
        val titleWeight = 0.2 + (alpha * 0.6)
        val genreWeight = 1.0 - titleWeight

        return (titleSim * titleWeight) + (genreOverlap * genreWeight)
    }
}
