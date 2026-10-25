package tachiyomi.domain.anime.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.model.Episode
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlin.time.toJavaDuration

@Execution(ExecutionMode.CONCURRENT)
class FetchIntervalTest {

    private val testTime = ZonedDateTime.parse("2020-01-01T00:00:00Z")
    private val testZoneId = ZoneOffset.UTC
    private var episode = Episode.create().copy(
        dateFetch = testTime.toEpochSecond() * 1000,
        dateUpload = testTime.toEpochSecond() * 1000,
    )

    private val getEpisodesByAnimeId = mockk<GetEpisodesByAnimeId>()
    private val fetchInterval = FetchInterval(getEpisodesByAnimeId)

    @Test
    fun `returns default interval of 7 days when not enough distinct days`() {
        val episodesWithUploadDate = (1..50).map {
            episodeWithTime(episode, 1.days)
        }
        fetchInterval.calculateInterval(episodesWithUploadDate, testZoneId) shouldBe 7

        val episodesWithoutUploadDate = episodesWithUploadDate.map {
            it.copy(dateUpload = 0L)
        }
        fetchInterval.calculateInterval(episodesWithoutUploadDate, testZoneId) shouldBe 7
    }

    @Test
    fun `returns interval based on more recent episodes`() {
        val oldEpisodes = (1..5).map {
            episodeWithTime(episode, (it * 7).days) // Would have interval of 7 days
        }
        val newEpisodes = (1..10).map {
            episodeWithTime(episode, oldEpisodes.lastUploadDate() + it.days)
        }

        val episodes = oldEpisodes + newEpisodes

        fetchInterval.calculateInterval(episodes, testZoneId) shouldBe 1
    }

    @Test
    fun `returns interval based on smaller subset of recent episodes if very few episodes`() {
        val oldEpisodes = (1..3).map {
            episodeWithTime(episode, (it * 7).days)
        }
        // Significant gap between episodes
        val newEpisodes = (1..3).map {
            episodeWithTime(episode, oldEpisodes.lastUploadDate() + 365.days + (it * 7).days)
        }

        val episodes = oldEpisodes + newEpisodes

        fetchInterval.calculateInterval(episodes, testZoneId) shouldBe 7
    }

    @Test
    fun `returns interval of 7 days when multiple episodes in 1 day`() {
        val episodes = (1..10).map {
            episodeWithTime(episode, 10.hours)
        }
        fetchInterval.calculateInterval(episodes, testZoneId) shouldBe 7
    }

    @Test
    fun `returns interval of 7 days when multiple episodes in 2 days`() {
        val episodes = (1..2).map {
            episodeWithTime(episode, 1.days)
        } + (1..5).map {
            episodeWithTime(episode, 2.days)
        }
        fetchInterval.calculateInterval(episodes, testZoneId) shouldBe 7
    }

    @Test
    fun `returns interval of 1 day when episodes are released every 1 day`() {
        val episodes = (1..20).map {
            episodeWithTime(episode, it.days)
        }
        fetchInterval.calculateInterval(episodes, testZoneId) shouldBe 1
    }

    @Test
    fun `returns interval of 1 day when delta is less than 1 day`() {
        val episodes = (1..20).map {
            episodeWithTime(episode, (15 * it).hours)
        }
        fetchInterval.calculateInterval(episodes, testZoneId) shouldBe 1
    }

    @Test
    fun `returns interval of 2 days when episodes are released every 2 days`() {
        val episodes = (1..20).map {
            episodeWithTime(episode, (2 * it).days)
        }
        fetchInterval.calculateInterval(episodes, testZoneId) shouldBe 2
    }

    @Test
    fun `returns interval with floored value when interval is decimal`() {
        val episodesWithUploadDate = (1..5).map {
            episodeWithTime(episode, (25 * it).hours)
        }
        fetchInterval.calculateInterval(episodesWithUploadDate, testZoneId) shouldBe 1

        val episodesWithoutUploadDate = episodesWithUploadDate.map {
            it.copy(dateUpload = 0L)
        }
        fetchInterval.calculateInterval(episodesWithoutUploadDate, testZoneId) shouldBe 1
    }

    @Test
    fun `returns interval of 1 day when episodes are released just below every 2 days`() {
        val episodes = (1..20).map {
            episodeWithTime(episode, (43 * it).hours)
        }
        fetchInterval.calculateInterval(episodes, testZoneId) shouldBe 1
    }

    @Test
    fun `calculateNextUpdate handles exponential backoff when missing many updates`() = runTest {
        // Mock anime that hasn't been updated in 100 days
        val interval = 1 // 1 day interval
        val lastUpdate = testTime.minusDays(100).toInstant().toEpochMilli()
        val anime = Anime.create().copy(lastUpdate = lastUpdate, nextUpdate = 0L, fetchInterval = interval)

        coEvery { getEpisodesByAnimeId.await(any(), any()) } returns emptyList()

        // window is today +/- 1 day
        val window = fetchInterval.getWindow(testTime)

        val update = fetchInterval.toAnimeUpdate(anime, testTime, window)

        // The interval in update should be increased exponentially
        update.fetchInterval shouldBe 16
    }

    private fun episodeWithTime(episode: Episode, duration: Duration): Episode {
        val newTime = testTime.plus(duration.toJavaDuration()).toEpochSecond() * 1000
        return episode.copy(dateFetch = newTime, dateUpload = newTime)
    }

    private fun List<Episode>.lastUploadDate() =
        last().dateUpload.toDuration(DurationUnit.MILLISECONDS)
}
