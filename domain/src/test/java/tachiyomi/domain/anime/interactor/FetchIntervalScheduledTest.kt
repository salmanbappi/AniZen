package tachiyomi.domain.anime.interactor

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import java.time.ZonedDateTime

class FetchIntervalScheduledTest {

    private val getEpisodesByAnimeId = mockk<GetEpisodesByAnimeId>()
    private val fetchInterval = FetchInterval(getEpisodesByAnimeId)

    @Test
    fun `calculateNextUpdate handles scheduled updates correctly`() {
        // Monday 10:30 AM
        val dayOfWeek = 1 // Monday
        val hour = 10
        val minute = 30

        // Encode using the new logic: -(10000 + d * 2000 + h * 60 + m)
        val interval = -(10000 + dayOfWeek * 2000 + hour * 60 + minute)

        val anime = Anime.create().copy(fetchInterval = interval)

        // Test time: Sunday 9:00 AM
        val dateTime = ZonedDateTime.parse("2023-10-22T09:00:00Z") // 2023-10-22 is Sunday

        val nextUpdate = fetchInterval.toAnimeUpdate(anime, dateTime, Pair(0, 0))

        val nextUpdateDateTime = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(nextUpdate.nextUpdate!!),
            java.time.ZoneId.of("UTC"),
        )

        nextUpdateDateTime.dayOfWeek.value shouldBe 1 // Monday
        nextUpdateDateTime.hour shouldBe 10
        nextUpdateDateTime.minute shouldBe 30
    }

    @Test
    fun `calculateNextUpdate handles late night scheduled updates correctly`() {
        // Sunday 11:45 PM
        val dayOfWeek = 7 // Sunday
        val hour = 23
        val minute = 45

        val interval = -(10000 + dayOfWeek * 2000 + hour * 60 + minute)

        val anime = Anime.create().copy(fetchInterval = interval)

        // Test time: Monday 1:00 AM
        val dateTime = ZonedDateTime.parse("2023-10-23T01:00:00Z") // 2023-10-23 is Monday

        val nextUpdate = fetchInterval.toAnimeUpdate(anime, dateTime, Pair(0, 0))

        val nextUpdateDateTime = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(nextUpdate.nextUpdate!!),
            java.time.ZoneId.of("UTC"),
        )

        nextUpdateDateTime.dayOfWeek.value shouldBe 7 // Sunday
        nextUpdateDateTime.hour shouldBe 23
        nextUpdateDateTime.minute shouldBe 45
        // It should be the next Sunday (Oct 29)
        nextUpdateDateTime.dayOfMonth shouldBe 29
    }
}
