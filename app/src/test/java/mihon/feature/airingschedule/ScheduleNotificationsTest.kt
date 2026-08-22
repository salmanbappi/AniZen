package mihon.feature.airingschedule

import mihon.feature.airingschedule.notification.ScheduleNotifications
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduleNotificationsTest {

    @Test
    fun `alarmKey generates unique string per media and episode`() {
        val key1 = ScheduleNotifications.alarmKey(101, 1)
        val key2 = ScheduleNotifications.alarmKey(101, 2)
        val key3 = ScheduleNotifications.alarmKey(102, 1)

        assertEquals("101:1", key1)
        assertEquals("101:2", key2)
        assertEquals("102:1", key3)
        assertNotEquals(key1, key2)
        assertNotEquals(key1, key3)
    }

    @Test
    fun `requestCode produces consistent hash codes`() {
        val code1 = ScheduleNotifications.requestCode(101, 1)
        val code2 = ScheduleNotifications.requestCode(101, 1)
        val code3 = ScheduleNotifications.requestCode(101, 2)

        assertEquals(code1, code2)
        assertNotEquals(code1, code3)
    }

    @Test
    fun `notificationId produces safe positive non-colliding IDs`() {
        val notifId1 = ScheduleNotifications.notificationId(101, 1)
        val notifId2 = ScheduleNotifications.notificationId(101, 2)
        val notifId3 = ScheduleNotifications.notificationId(999999, 12)

        assertTrue(notifId1 > 0)
        assertTrue(notifId2 > 0)
        assertTrue(notifId3 > 0)
        assertNotEquals(notifId1, notifId2)
    }

    @Test
    fun `AiringScheduleEntry hasAired correctly reflects past vs future time`() {
        val pastEntry = AiringScheduleEntry(
            scheduleId = 1,
            airingAt = (System.currentTimeMillis() / 1000L) - 3600L,
            episode = 1,
            mediaId = 10,
            titleUserPreferred = "Past Anime",
        )
        val futureEntry = AiringScheduleEntry(
            scheduleId = 2,
            airingAt = (System.currentTimeMillis() / 1000L) + 3600L,
            episode = 2,
            mediaId = 10,
            titleUserPreferred = "Future Anime",
        )

        assertTrue(pastEntry.hasAired())
        assertTrue(!futureEntry.hasAired())
    }

    @Test
    fun `AiringScheduleEntry displayTitle falls back properly`() {
        val entryWithAll = AiringScheduleEntry(
            scheduleId = 1,
            airingAt = 1000L,
            episode = 1,
            mediaId = 10,
            titleUserPreferred = "Default Title",
            titleEnglish = "English Title",
            titleRomaji = "Romaji Title",
            titleNative = "Native Title",
        )

        assertEquals("English Title", entryWithAll.displayTitle(SchedulePreferences.TitleLanguage.ENGLISH))
        assertEquals("Romaji Title", entryWithAll.displayTitle(SchedulePreferences.TitleLanguage.ROMAJI))
        assertEquals("Native Title", entryWithAll.displayTitle(SchedulePreferences.TitleLanguage.NATIVE))
        assertEquals("Default Title", entryWithAll.displayTitle(SchedulePreferences.TitleLanguage.USER_PREFERRED))

        val entryWithFallback = AiringScheduleEntry(
            scheduleId = 2,
            airingAt = 1000L,
            episode = 1,
            mediaId = 11,
            titleUserPreferred = "Only Default",
            titleEnglish = null,
            titleRomaji = null,
            titleNative = null,
        )

        assertEquals("Only Default", entryWithFallback.displayTitle(SchedulePreferences.TitleLanguage.ENGLISH))
        assertEquals("Only Default", entryWithFallback.displayTitle(SchedulePreferences.TitleLanguage.ROMAJI))
        assertEquals("Only Default", entryWithFallback.displayTitle(SchedulePreferences.TitleLanguage.NATIVE))
    }
}
