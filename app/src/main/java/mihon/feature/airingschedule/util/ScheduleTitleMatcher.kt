package mihon.feature.airingschedule.util

import mihon.feature.airingschedule.AiringScheduleEntry

/**
 * Utility for robust title matching between library anime and schedule entries.
 * Normalizes punctuation, quotes, media tags, and season indicators while strictly
 * preventing false-positive collisions across different seasons or distinct titles.
 */
object ScheduleTitleMatcher {

    private val SMART_SINGLE_QUOTES = Regex("[’‘`´]")
    private val SMART_DOUBLE_QUOTES = Regex("[“”„«»]")
    private val PARENTHETICAL_TAGS = Regex("(?i)\\s*[\\[\\(](tv|ona|ova|special|specials|movie|web)[\\]\\)]")
    private val SYMBOL_PUNCTUATION = Regex("[^\\p{L}\\p{N}\\s]")
    private val MULTI_WHITESPACE = Regex("\\s+")

    // Season variations mapping to canonical "season X" / "part X"
    private val ORDINAL_SEASON = Regex("(?i)\\b(\\d+)(?:st|nd|rd|th)\\s+season\\b")
    private val WORD_ORDINAL_SEASON = Regex("(?i)\\b(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\\s+season\\b")
    private val SHORT_SEASON = Regex("(?i)\\bs0*(\\d+)\\b")
    private val ROMAN_SEASON = Regex("(?i)\\bseason\\s+(i{1,3}|iv|v|vi{0,3}|ix|x)\\b")
    private val ORDINAL_PART = Regex("(?i)\\b(\\d+)(?:st|nd|rd|th)\\s+part\\b")
    private val WORD_ORDINAL_PART = Regex("(?i)\\b(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\\s+part\\b")
    private val SHORT_PART = Regex("(?i)\\bpart\\s+0*(\\d+)\\b")
    private val ROMAN_PART = Regex("(?i)\\bpart\\s+(i{1,3}|iv|v|vi{0,3}|ix|x)\\b")
    private val ORDINAL_COUR = Regex("(?i)\\b(\\d+)(?:st|nd|rd|th)\\s+cour\\b")
    private val WORD_ORDINAL_COUR = Regex("(?i)\\b(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\\s+cour\\b")
    private val SHORT_COUR = Regex("(?i)\\bcour\\s+0*(\\d+)\\b")
    private val ROMAN_COUR = Regex("(?i)\\bcour\\s+(i{1,3}|iv|v|vi{0,3}|ix|x)\\b")
    private val TRAILING_ROMAN = Regex("(?i)\\b(ii|iii|iv|v|vi{0,3}|ix|x)\\s*$")

    /**
     * Converts Roman numerals (I - X) to standard Arabic integer string.
     */
    private fun romanToArabic(roman: String): String = when (roman.lowercase()) {
        "i" -> "1"
        "ii" -> "2"
        "iii" -> "3"
        "iv" -> "4"
        "v" -> "5"
        "vi" -> "6"
        "vii" -> "7"
        "viii" -> "8"
        "ix" -> "9"
        "x" -> "10"
        else -> roman
    }

    /**
     * Converts English word ordinals to standard Arabic integer string.
     */
    private fun wordOrdinalToNumber(word: String): String = when (word.lowercase()) {
        "first" -> "1"
        "second" -> "2"
        "third" -> "3"
        "fourth" -> "4"
        "fifth" -> "5"
        "sixth" -> "6"
        "seventh" -> "7"
        "eighth" -> "8"
        "ninth" -> "9"
        "tenth" -> "10"
        else -> word
    }

    /**
     * Extracts a list of non-blank title variants from an AiringScheduleEntry.
     */
    fun candidateTitlesFromEntry(entry: AiringScheduleEntry): List<String> = listOfNotNull(
        entry.titleUserPreferred.takeIf { it.isNotBlank() },
        entry.titleEnglish?.takeIf { it.isNotBlank() },
        entry.titleRomaji?.takeIf { it.isNotBlank() },
        entry.titleNative?.takeIf { it.isNotBlank() },
    )

    /**
     * Produces normalized variants of a title for robust matching.
     * All returned keys are lowercase, stripped of noise tags, and normalized for whitespace/punctuation.
     */
    fun normalizedKeys(title: String?): Set<String> {
        if (title.isNullOrBlank()) return emptySet()

        val keys = mutableSetOf<String>()

        // 1. Basic clean (lowercase, replace smart quotes, trim)
        val base = title.trim().lowercase()
            .replace(SMART_SINGLE_QUOTES, "'")
            .replace(SMART_DOUBLE_QUOTES, "\"")

        keys.add(base)
        if (base.contains('&')) {
            keys.add(base.replace("&", " and "))
        }

        // 2. Strip non-informative media tags like (TV), [OVA], etc.
        val strippedTags = base.replace(PARENTHETICAL_TAGS, "").trim()
        if (strippedTags.isNotEmpty()) {
            keys.add(strippedTags)
            if (strippedTags.contains('&')) {
                keys.add(strippedTags.replace("&", " and "))
            }
        }

        // 3. Normalize season / part / cour notations:
        // "2nd season" / "second season" -> "season 2"
        // "s2" -> "season 2"
        // "season ii" -> "season 2"
        // "part 2" / "2nd part" / "part ii" -> "part 2"
        // "cour 2" / "2nd cour" / "cour ii" -> "cour 2"
        val baseVariants = listOf(strippedTags) + (if (strippedTags.contains('&')) listOf(strippedTags.replace("&", " and ")) else emptyList())
        for (variant in baseVariants) {
            val normalizedSeason = variant
                .replace(ORDINAL_SEASON) { match -> "season ${match.groupValues[1]}" }
                .replace(WORD_ORDINAL_SEASON) { match -> "season ${wordOrdinalToNumber(match.groupValues[1])}" }
                .replace(SHORT_SEASON) { match -> "season ${match.groupValues[1]}" }
                .replace(ROMAN_SEASON) { match -> "season ${romanToArabic(match.groupValues[1])}" }
                .replace(ORDINAL_PART) { match -> "part ${match.groupValues[1]}" }
                .replace(WORD_ORDINAL_PART) { match -> "part ${wordOrdinalToNumber(match.groupValues[1])}" }
                .replace(SHORT_PART) { match -> "part ${match.groupValues[1]}" }
                .replace(ROMAN_PART) { match -> "part ${romanToArabic(match.groupValues[1])}" }
                .replace(ORDINAL_COUR) { match -> "cour ${match.groupValues[1]}" }
                .replace(WORD_ORDINAL_COUR) { match -> "cour ${wordOrdinalToNumber(match.groupValues[1])}" }
                .replace(SHORT_COUR) { match -> "cour ${match.groupValues[1]}" }
                .replace(ROMAN_COUR) { match -> "cour ${romanToArabic(match.groupValues[1])}" }
                .trim()

            if (normalizedSeason.isNotEmpty()) {
                keys.add(normalizedSeason)
            }
        }

        // 4. Handle trailing Roman numerals (e.g. "Mob Psycho 100 II" -> "season 2", "2")
        val romanMatch = TRAILING_ROMAN.find(strippedTags)
        if (romanMatch != null) {
            val num = romanToArabic(romanMatch.groupValues[1])
            val seasonKey = strippedTags.replace(TRAILING_ROMAN, "season $num").trim()
            val numKey = strippedTags.replace(TRAILING_ROMAN, num).trim()
            if (seasonKey.isNotEmpty()) keys.add(seasonKey)
            if (numKey.isNotEmpty()) keys.add(numKey)
        }

        // 5. Punctuation-stripped alphanumeric representation for all keys
        val derivedKeys = keys.toList()
        for (k in derivedKeys) {
            val alphaNumeric = k.replace(SYMBOL_PUNCTUATION, " ").replace(MULTI_WHITESPACE, " ").trim()
            if (alphaNumeric.isNotEmpty()) {
                keys.add(alphaNumeric)
            }
        }

        return keys
    }

    /**
     * Checks whether two titles match based on normalized representations.
     */
    fun matches(title1: String?, title2: String?): Boolean {
        if (title1.isNullOrBlank() || title2.isNullOrBlank()) return false
        if (title1.equals(title2, ignoreCase = true)) return true

        val keys1 = normalizedKeys(title1)
        val keys2 = normalizedKeys(title2)

        return keys1.any { it in keys2 }
    }

    /**
     * Checks if a target title matches any candidate titles from an entry or list.
     */
    fun matchesAny(targetTitle: String?, candidateTitles: Collection<String?>): Boolean {
        if (targetTitle.isNullOrBlank()) return false
        val targetKeys = normalizedKeys(targetTitle)
        if (targetKeys.isEmpty()) return false

        for (candidate in candidateTitles) {
            if (candidate.isNullOrBlank()) continue
            if (targetTitle.equals(candidate, ignoreCase = true)) return true
            val candidateKeys = normalizedKeys(candidate)
            if (targetKeys.any { it in candidateKeys }) {
                return true
            }
        }
        return false
    }

    /**
     * Finds the first AiringScheduleEntry that matches the given anime title.
     */
    fun findMatchingEntry(
        animeTitle: String?,
        entries: Collection<AiringScheduleEntry>,
    ): AiringScheduleEntry? {
        if (animeTitle.isNullOrBlank()) return null
        val targetKeys = normalizedKeys(animeTitle)
        if (targetKeys.isEmpty()) return null

        return entries.firstOrNull { entry ->
            val candidates = candidateTitlesFromEntry(entry)
            candidates.any { candidate ->
                if (animeTitle.equals(candidate, ignoreCase = true)) true
                else normalizedKeys(candidate).any { it in targetKeys }
            }
        }
    }
}
