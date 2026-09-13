package eu.kanade.tachiyomi.ui.player.utils

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences

class VideoComparator(
    private val playerPreferences: PlayerPreferences,
    private val audioPreferences: AudioPreferences,
) : Comparator<Video> {

    val preferredQuality = playerPreferences.preferredQuality().get()
    val preferredAudio = audioPreferences.preferredAudioLanguages().get()

    override fun compare(v1: Video, v2: Video): Int {

        // 1. Check for preferred quality
        val v1QualityMatch = v1.quality.contains(preferredQuality)
        val v2QualityMatch = v2.quality.contains(preferredQuality)
        if (v1QualityMatch && !v2QualityMatch) return -1
        if (!v1QualityMatch && v2QualityMatch) return 1

        // 2. Check for preferred audio language
        if (preferredAudio.isNotBlank()) {
            val v1AudioMatch = v1.quality.contains(preferredAudio, ignoreCase = true)
            val v2AudioMatch = v2.quality.contains(preferredAudio, ignoreCase = true)
            if (v1AudioMatch && !v2AudioMatch) return -1
            if (!v1AudioMatch && v2AudioMatch) return 1
        }

        // 3. Fallback to natural order (as provided by extension)
        return 0
    }
}
