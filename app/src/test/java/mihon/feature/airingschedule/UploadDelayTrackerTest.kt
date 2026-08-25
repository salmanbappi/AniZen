package mihon.feature.airingschedule

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UploadDelayTrackerTest {

    private val tracker = UploadDelayTracker()

    @Test
    fun `calculateDelaysFromObservations computes median and rejects outliers`() {
        val observations = listOf(
            SourceFeedObservation(
                eventId = "1",
                sourceId = "src1",
                episodeId = "ep1",
                episodeNumber = 1f,
                officialAirAt = 10000L,
                sourceUploadAt = 10000L + (45 * 60), // +45 min
            ),
            SourceFeedObservation(
                eventId = "2",
                sourceId = "src1",
                episodeId = "ep2",
                episodeNumber = 2f,
                officialAirAt = 20000L,
                sourceUploadAt = 20000L + (50 * 60), // +50 min
            ),
            SourceFeedObservation(
                eventId = "3",
                sourceId = "src1",
                episodeId = "ep3",
                episodeNumber = 3f,
                officialAirAt = 30000L,
                sourceUploadAt = 30000L + (48 * 60), // +48 min
            ),
            SourceFeedObservation(
                eventId = "4",
                sourceId = "src1",
                episodeId = "ep4_dub",
                episodeNumber = 4f,
                officialAirAt = 40000L,
                sourceUploadAt = 40000L + (720 * 60), // +720 min (outlier, should be filtered by MAX_DELAY_MINUTES)
            ),
            SourceFeedObservation(
                eventId = "5",
                sourceId = "src1",
                episodeId = "ep5_corrupt",
                episodeNumber = 5f,
                officialAirAt = 50000L,
                sourceUploadAt = 50000L - (100 * 60), // -100 min (corrupt date truncate, should be filtered by MIN_DELAY_MINUTES)
            ),
        )

        val result = tracker.calculateDelaysFromObservations(observations)
        assertEquals(1, result.size)
        // Valid samples are 45, 48, 50. Median is 48.
        assertEquals(48L, result["src1"])
    }

    @Test
    fun `calculateDelaysFromObservations handles multiple sources independently`() {
        val observations = listOf(
            SourceFeedObservation(
                eventId = "1",
                sourceId = "fast_source",
                episodeId = "ep1",
                episodeNumber = 1f,
                officialAirAt = 10000L,
                sourceUploadAt = 10000L + (25 * 60), // +25 min
            ),
            SourceFeedObservation(
                eventId = "2",
                sourceId = "slow_source",
                episodeId = "ep1",
                episodeNumber = 1f,
                officialAirAt = 10000L,
                sourceUploadAt = 10000L + (90 * 60), // +90 min
            ),
        )

        val result = tracker.calculateDelaysFromObservations(observations)
        assertEquals(25L, result["fast_source"])
        assertEquals(90L, result["slow_source"])
    }

    @Test
    fun `calculateDelaysFromObservations returns empty map when observations are empty`() {
        val result = tracker.calculateDelaysFromObservations(emptyList())
        assertTrue(result.isEmpty())
    }
}
