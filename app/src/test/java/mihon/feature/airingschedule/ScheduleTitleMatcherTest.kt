package mihon.feature.airingschedule

import mihon.feature.airingschedule.util.ScheduleTitleMatcher
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScheduleTitleMatcherTest {

    @Test
    fun `exact matching ignoring case and whitespace`() {
        assertTrue(ScheduleTitleMatcher.matches("Solo Leveling", "solo leveling"))
        assertTrue(ScheduleTitleMatcher.matches("  Bleach  ", "BLEACH"))
    }

    @Test
    fun `smart quotes and punctuation normalization`() {
        assertTrue(ScheduleTitleMatcher.matches("JoJo’s Bizarre Adventure", "JoJo's Bizarre Adventure"))
        assertTrue(ScheduleTitleMatcher.matches("“Oshi no Ko”", "\"Oshi no Ko\""))
        assertTrue(ScheduleTitleMatcher.matches("K-ON!", "K-On"))
        assertTrue(ScheduleTitleMatcher.matches("Fate/stay night: Unlimited Blade Works", "Fate/stay night - Unlimited Blade Works"))
        assertTrue(ScheduleTitleMatcher.matches("Mushoku Tensei: Isekai Ittara Honki Dasu", "Mushoku Tensei - Isekai Ittara Honki Dasu"))
        assertTrue(ScheduleTitleMatcher.matches("Spice & Wolf", "Spice and Wolf"))
        assertTrue(ScheduleTitleMatcher.matches("Tiger & Bunny", "Tiger and Bunny"))
        assertTrue(ScheduleTitleMatcher.matches("Panty & Stocking with Garterbelt", "Panty and Stocking with Garterbelt"))
    }

    @Test
    fun `media tag stripping`() {
        assertTrue(ScheduleTitleMatcher.matches("Sousou no Frieren (TV)", "Sousou no Frieren"))
        assertTrue(ScheduleTitleMatcher.matches("Chainsaw Man [TV]", "Chainsaw Man"))
        assertTrue(ScheduleTitleMatcher.matches("Cyberpunk: Edgerunners (ONA)", "Cyberpunk: Edgerunners"))
    }

    @Test
    fun `season notation variations match corresponding seasons`() {
        assertTrue(ScheduleTitleMatcher.matches("Jujutsu Kaisen 2nd Season", "Jujutsu Kaisen Season 2"))
        assertTrue(ScheduleTitleMatcher.matches("Jujutsu Kaisen S2", "Jujutsu Kaisen Season 2"))
        assertTrue(ScheduleTitleMatcher.matches("Jujutsu Kaisen S02", "Jujutsu Kaisen Season 2"))
        assertTrue(ScheduleTitleMatcher.matches("Mob Psycho 100 Season II", "Mob Psycho 100 Season 2"))
        assertTrue(ScheduleTitleMatcher.matches("Mob Psycho 100 II", "Mob Psycho 100 Season 2"))
        assertTrue(ScheduleTitleMatcher.matches("Mob Psycho 100 II", "Mob Psycho 100 2nd Season"))
        assertTrue(ScheduleTitleMatcher.matches("Mob Psycho 100 II", "Mob Psycho 100 S2"))
        assertTrue(ScheduleTitleMatcher.matches("Kingdom III", "Kingdom Season 3"))
        assertTrue(ScheduleTitleMatcher.matches("Overlord IV", "Overlord Season 4"))
        assertTrue(ScheduleTitleMatcher.matches("Date A Live V", "Date A Live Season 5"))
        assertTrue(ScheduleTitleMatcher.matches("Monogatari Series: Second Season", "Monogatari Series: 2nd Season"))
        assertTrue(ScheduleTitleMatcher.matches("Monogatari Series: Second Season", "Monogatari Series: Season 2"))
        assertTrue(ScheduleTitleMatcher.matches("Chihayafuru 3rd Season", "Chihayafuru Third Season"))
        assertTrue(ScheduleTitleMatcher.matches("Golden Kamuy 4th Season", "Golden Kamuy Fourth Season"))
        assertTrue(ScheduleTitleMatcher.matches("Bleach: Thousand-Year Blood War - The Separation - 2nd Cour", "Bleach: Thousand-Year Blood War - The Separation - Cour 2"))
        assertTrue(ScheduleTitleMatcher.matches("Bleach: Thousand-Year Blood War - The Separation - Cour II", "Bleach: Thousand-Year Blood War - The Separation - Cour 2"))
        assertTrue(ScheduleTitleMatcher.matches("Attack on Titan The Final Season 2nd Part", "Attack on Titan The Final Season Part 2"))
        assertTrue(ScheduleTitleMatcher.matches("Attack on Titan The Final Season Part II", "Attack on Titan The Final Season Part 2"))
    }

    @Test
    fun `different seasons do not collide`() {
        assertFalse(ScheduleTitleMatcher.matches("Jujutsu Kaisen Season 1", "Jujutsu Kaisen Season 2"))
        assertFalse(ScheduleTitleMatcher.matches("Jujutsu Kaisen S1", "Jujutsu Kaisen S2"))
        assertFalse(ScheduleTitleMatcher.matches("Mob Psycho 100 Season 1", "Mob Psycho 100 Season 2"))
        assertFalse(ScheduleTitleMatcher.matches("Mob Psycho 100", "Mob Psycho 100 II"))
        assertFalse(ScheduleTitleMatcher.matches("Mob Psycho 100 II", "Mob Psycho 100 III"))
        assertFalse(ScheduleTitleMatcher.matches("Attack on Titan Final Season Part 1", "Attack on Titan Final Season Part 2"))
        assertFalse(ScheduleTitleMatcher.matches("Attack on Titan Final Season Part I", "Attack on Titan Final Season Part II"))
    }

    @Test
    fun `unrelated titles do not collide`() {
        assertFalse(ScheduleTitleMatcher.matches("One Piece", "One Piece Film: Red"))
        assertFalse(ScheduleTitleMatcher.matches("Naruto", "Boruto"))
        assertFalse(ScheduleTitleMatcher.matches("Bleach", "Bleach: Thousand-Year Blood War"))
    }

    @Test
    fun `matchesAny checks multiple candidates from entry`() {
        val entry = AiringScheduleEntry(
            scheduleId = 1,
            airingAt = 1000L,
            episode = 1,
            mediaId = 123,
            titleUserPreferred = "Sousou no Frieren",
            titleEnglish = "Frieren: Beyond Journey's End",
            titleRomaji = "Sousou no Frieren",
            titleNative = "葬送のフリーレン",
        )
        val candidates = ScheduleTitleMatcher.candidateTitlesFromEntry(entry)

        assertTrue(ScheduleTitleMatcher.matchesAny("Frieren: Beyond Journey’s End", candidates))
        assertTrue(ScheduleTitleMatcher.matchesAny("Sousou no Frieren (TV)", candidates))
        assertTrue(ScheduleTitleMatcher.matchesAny("葬送のフリーレン", candidates))
        assertFalse(ScheduleTitleMatcher.matchesAny("Dungeon Meshi", candidates))
    }
}
