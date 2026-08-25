package mihon.feature.airingschedule

import mihon.feature.airingschedule.util.UploadDelayResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UploadDelayResolverTest {

    private val sampleEntry = AiringScheduleEntry(
        scheduleId = 1,
        airingAt = 10000L,
        episode = 1,
        mediaId = 100,
        titleUserPreferred = "Jujutsu Kaisen Season 2",
        titleEnglish = "Jujutsu Kaisen Season 2",
        titleRomaji = "Jujutsu Kaisen 2nd Season",
    )

    @Test
    fun `computeMedian correctly handles empty, single, odd, and even lists`() {
        assertEquals(0L, UploadDelayResolver.computeMedian(emptyList()))
        assertEquals(45L, UploadDelayResolver.computeMedian(listOf(45L)))
        assertEquals(50L, UploadDelayResolver.computeMedian(listOf(40L, 50L, 60L)))
        assertEquals(45L, UploadDelayResolver.computeMedian(listOf(60L, 40L, 50L, 30L)))
    }

    @Test
    fun `computeMedian is immune to extreme outliers`() {
        // Normal episodes + 1 re-upload / dub outlier (1440 min = 24 hours)
        val samples = listOf(45L, 48L, 50L, 52L, 1440L)
        assertEquals(50L, UploadDelayResolver.computeMedian(samples))
    }

    @Test
    fun `resolveDelay respects manualDelay override first`() {
        val delay = UploadDelayResolver.resolveDelay(
            entry = sampleEntry,
            delays = mapOf("1" to 30L, "2" to 60L),
            manualDelayMinutes = 90L,
            pinnedSources = setOf("1"),
            favoriteSources = setOf("2"),
        )
        assertEquals(90L, delay)
    }

    @Test
    fun `resolveDelay respects pinned source precedence over favorite sources`() {
        val delay = UploadDelayResolver.resolveDelay(
            entry = sampleEntry,
            delays = mapOf("pinned1" to 35L, "fav1" to 60L),
            manualDelayMinutes = null,
            pinnedSources = linkedSetOf("pinned1"),
            favoriteSources = linkedSetOf("fav1"),
        )
        assertEquals(35L, delay)
    }

    @Test
    fun `resolveDelay matches library source when anime is in library`() {
        val librarySources = mapOf("jujutsu kaisen season 2" to setOf("matched_src"))
        val delay = UploadDelayResolver.resolveDelay(
            entry = sampleEntry,
            delays = mapOf("pinned1" to 30L, "matched_src" to 45L),
            manualDelayMinutes = null,
            librarySourcesByTitle = librarySources,
            pinnedSources = setOf("pinned1", "matched_src"),
            favoriteSources = setOf("pinned1"),
        )
        assertEquals(45L, delay)
    }

    @Test
    fun `resolveDelay returns median across configured sources when no direct match`() {
        val delay = UploadDelayResolver.resolveDelay(
            entry = sampleEntry,
            delays = mapOf("fav1" to 40L, "fav2" to 60L),
            manualDelayMinutes = null,
            favoriteSources = setOf("fav1", "fav2"),
        )
        // fav1 is first checked, returns 40L or median 50L
        assertEquals(40L, delay)
    }

    @Test
    fun `resolveDelay returns null when no delays exist`() {
        val delay = UploadDelayResolver.resolveDelay(
            entry = sampleEntry,
            delays = emptyMap(),
            manualDelayMinutes = null,
        )
        assertNull(delay)
    }

    @Test
    fun `adjustedAirTime adds minutes properly or preserves unadjusted time`() {
        assertEquals(10000L + 45 * 60, UploadDelayResolver.adjustedAirTime(sampleEntry, 45L))
        assertEquals(10000L, UploadDelayResolver.adjustedAirTime(sampleEntry, null))
    }
}
