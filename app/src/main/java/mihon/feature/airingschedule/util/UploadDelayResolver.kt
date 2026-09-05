package mihon.feature.airingschedule.util

import mihon.feature.airingschedule.AiringScheduleEntry

/**
 * Centralized resolver for calculating expected source upload delays and adjusted air times.
 *
 * Enforces a strict, deterministic priority order:
 * 1. Manual Custom Delay (user setting override)
 * 2. Matched Library Source (Pinned priority)
 * 3. Matched Library Source (Favorite priority)
 * 4. Matched Library Source (Direct match)
 * 5. Pinned Sources in priority order
 * 6. Favorite Sources in priority order
 * 7. Median of all learned delays for configured sources
 * 8. null (fallback to official unadjusted air time)
 */
object UploadDelayResolver {

    /**
     * Resolves the effective upload delay in minutes for an [entry].
     */
    fun resolveDelay(
        entry: AiringScheduleEntry,
        delays: Map<String, Long>,
        manualDelayMinutes: Long? = null,
        librarySourcesByTitle: Map<String, Set<String>> = emptyMap(),
        pinnedSources: Set<String> = emptySet(),
        favoriteSources: Set<String> = emptySet(),
    ): Long? {
        manualDelayMinutes?.let { return it }
        if (delays.isEmpty()) return null

        val configuredSources = pinnedSources + favoriteSources
        val matchedSources = if (configuredSources.isNotEmpty() && librarySourcesByTitle.isNotEmpty()) {
            matchedSourcesFor(entry, configuredSources, librarySourcesByTitle)
        } else {
            emptySet()
        }

        // 1. Matched library source in pinned priority order
        for (pinned in pinnedSources) {
            if (pinned in matchedSources && delays.containsKey(pinned)) {
                return delays[pinned]
            }
        }

        // 2. Matched library source in favorite priority order
        for (fav in favoriteSources) {
            if (fav in matchedSources && delays.containsKey(fav)) {
                return delays[fav]
            }
        }

        // 3. First matched library source with any learned delay
        val firstMatched = matchedSources.firstNotNullOfOrNull { delays[it] }
        if (firstMatched != null) return firstMatched

        // 4. Pinned sources in priority order
        for (pinned in pinnedSources) {
            if (delays.containsKey(pinned)) {
                return delays[pinned]
            }
        }

        // 5. Favorite sources in priority order
        for (fav in favoriteSources) {
            if (delays.containsKey(fav)) {
                return delays[fav]
            }
        }

        // 6. Median of learned delays across configured sources (immune to outliers)
        val relevantDelays = configuredSources.mapNotNull { delays[it] }
        if (relevantDelays.isNotEmpty()) {
            return computeMedian(relevantDelays)
        }

        return null
    }

    /**
     * Returns the adjusted air time in Unix epoch seconds.
     * If [delayMinutes] is null, returns [entry.airingAt] unchanged.
     */
    fun adjustedAirTime(entry: AiringScheduleEntry, delayMinutes: Long?): Long {
        return if (delayMinutes != null) entry.airingAt + (delayMinutes * 60) else entry.airingAt
    }

    /**
     * Returns the adjusted air time in Unix epoch seconds given raw [airingAt] in seconds.
     */
    fun adjustedAirTime(airingAt: Long, delayMinutes: Long?): Long {
        return if (delayMinutes != null) airingAt + (delayMinutes * 60) else airingAt
    }

    /**
     * Finds configured sources that carry this anime based on library title matching.
     */
    fun matchedSourcesFor(
        entry: AiringScheduleEntry,
        configuredSources: Set<String>,
        librarySourcesByTitle: Map<String, Set<String>>,
    ): Set<String> {
        val titleCandidates = ScheduleTitleMatcher.candidateTitlesFromEntry(entry)
        val candidateKeys = titleCandidates.flatMap { ScheduleTitleMatcher.normalizedKeys(it) }
        val candidateSources = candidateKeys.flatMap { librarySourcesByTitle[it].orEmpty() }.toSet()
        return candidateSources.intersect(configuredSources)
    }

    /**
     * Calculates the median of a list of delay numbers.
     */
    fun computeMedian(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val size = sorted.size
        return if (size % 2 == 1) {
            sorted[size / 2]
        } else {
            (sorted[size / 2 - 1] + sorted[size / 2]) / 2L
        }
    }
}
