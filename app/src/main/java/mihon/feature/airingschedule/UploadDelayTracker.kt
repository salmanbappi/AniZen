package mihon.feature.airingschedule

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uy.kohesive.injekt.injectLazy

/**
 * Manages learned upload delay per source.
 * A "delay" is how many minutes after AniList's official air time a source typically
 * makes an episode available.  Negative values mean the source is early.
 *
 * Delays are stored as a JSON map: { "sourceId" : averageDelayMinutes }
 */
class UploadDelayTracker {

    private val prefs: SchedulePreferences by injectLazy()
    private val json: Json by injectLazy()

    /** Returns all stored delays (sourceId → delay in minutes). */
    fun getDelays(): Map<String, Long> {
        val raw = prefs.sourceUploadDelays().get()
        return try {
            val obj = json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.long }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Returns the stored delay for a single source, or null if unknown. */
    fun getDelay(sourceId: String): Long? = getDelays()[sourceId]

    /**
     * Records a new data point for a source.
     * [observedDelayMinutes] = time the episode appeared on the source minus AniList air time,
     * expressed in minutes.  Uses an exponential moving average (α=0.4) to smooth samples.
     */
    fun recordObservation(sourceId: String, observedDelayMinutes: Long) {
        val delays = getDelays().toMutableMap()
        val prev = delays[sourceId]
        val updated = if (prev == null) {
            observedDelayMinutes
        } else {
            ((0.6 * prev) + (0.4 * observedDelayMinutes)).toLong()
        }
        delays[sourceId] = updated
        saveDelays(delays)
        logcat(LogPriority.DEBUG) { "UploadDelayTracker: Source $sourceId delay updated to ${updated}min" }
    }

    /**
     * Batch-records observations for multiple (sourceId, delayMinutes) pairs in a single
     * read → update → write cycle, avoiding repeated disk I/O for each entry.
     */
    fun recordObservations(observations: List<Pair<String, Long>>) {
        if (observations.isEmpty()) return
        val delays = getDelays().toMutableMap()
        for ((sourceId, observedDelayMinutes) in observations) {
            val prev = delays[sourceId]
            delays[sourceId] = if (prev == null) {
                observedDelayMinutes
            } else {
                ((0.6 * prev) + (0.4 * observedDelayMinutes)).toLong()
            }
        }
        saveDelays(delays)
    }

    /**
     * Calculates the median delay per source across all valid observations in the rolling journal.
     * Enforces plausible simulcast delay bounds ([-60m, +360m]) and uses median estimation
     * so re-uploads, dubs, or corrupted timestamps cannot skew the result.
     */
    fun recordFeedObservations(
        observations: List<SourceFeedObservation>,
        intervalMinutes: Long = 0L,
    ): Boolean {
        if (observations.isEmpty()) return false
        val delays = calculateDelaysFromObservations(observations)
        if (delays.isEmpty()) return false
        val previous = getDelays()
        val updated = previous.toMutableMap().apply { putAll(delays) }
        if (previous == updated) return false
        saveDelays(updated)
        return true
    }

    /**
     * Extracts sourceId -> median delay (minutes) from a list of observations.
     */
    fun calculateDelaysFromObservations(
        observations: List<SourceFeedObservation>,
    ): Map<String, Long> {
        return observations
            .asSequence()
            .filter { it.delayMinutes in MIN_DELAY_MINUTES..MAX_DELAY_MINUTES }
            .groupBy { it.sourceId }
            .mapValues { (_, values) ->
                val delayList = values.map { it.delayMinutes }
                mihon.feature.airingschedule.util.UploadDelayResolver.computeMedian(delayList)
            }
    }

    /** Clears the stored delay for a source. */
    fun clearDelay(sourceId: String) {
        val delays = getDelays().toMutableMap()
        delays.remove(sourceId)
        saveDelays(delays)
    }

    /** Clears all stored delays. */
    fun clearAllDelays() {
        prefs.sourceUploadDelays().set("{}")
    }

    private fun saveDelays(delays: Map<String, Long>) {
        val obj = buildJsonObject {
            delays.forEach { (k, v) -> put(k, v) }
        }
        prefs.sourceUploadDelays().set(obj.toString())
    }

    companion object {
        const val MIN_DELAY_MINUTES = -60L
        const val MAX_DELAY_MINUTES = 360L // 6 hours

        /**
         * Converts a stored delay (minutes) to the expected airing timestamp.
         * [anilistAirAt] is the Unix epoch in seconds from AniList.
         * Returns the adjusted timestamp in seconds.
         */
        fun adjustedAirTime(anilistAirAt: Long, delayMinutes: Long): Long =
            mihon.feature.airingschedule.util.UploadDelayResolver.adjustedAirTime(anilistAirAt, delayMinutes)
    }
}
