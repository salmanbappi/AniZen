package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkHelperTest {

    private val context = mockk<Context>()
    private val preferences = mockk<NetworkPreferences>()
    private val networkHelper = NetworkHelper(context, preferences, isDebugBuild = false)

    @Test
    fun `calculateExponentialBackoff respects maxDelay`() {
        val maxDelay = 5000L
        val attempt = 10 // 1000 * 2^10 = 1024000, which is > 5000
        val delay = networkHelper.calculateExponentialBackoff(attempt, maxDelay = maxDelay)
        assertTrue(delay <= maxDelay, "Delay $delay should be less than or equal to maxDelay $maxDelay")
    }

    @Test
    fun `calculateChunks correctly divides total size`() {
        val totalSize = 1000L
        val threadCount = 4
        val chunks = networkHelper.calculateChunks(totalSize, threadCount)

        // Should have 4 chunks
        assertTrue(chunks.size == 4)

        // Check ranges: 0-249, 250-499, 500-749, 750-999
        assertTrue(chunks[0].start == 0L && chunks[0].end == 249L)
        assertTrue(chunks[1].start == 250L && chunks[1].end == 499L)
        assertTrue(chunks[2].start == 500L && chunks[2].end == 749L)
        assertTrue(chunks[3].start == 750L && chunks[3].end == 999L)
    }

    @Test
    fun `calculateChunks handles non-divisible sizes`() {
        val totalSize = 10L
        val threadCount = 3
        val chunks = networkHelper.calculateChunks(totalSize, threadCount)

        // Should have 3 chunks: 0-2, 3-5, 6-9
        assertTrue(chunks[0].start == 0L && chunks[0].end == 2L)
        assertTrue(chunks[1].start == 3L && chunks[1].end == 5L)
        assertTrue(chunks[2].start == 6L && chunks[2].end == 9L)
    }
}
