package eu.kanade.tachiyomi.ui.anime.track

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import tachiyomi.domain.track.model.Track
import kotlin.math.roundToInt

data class TrackItem(val track: Track?, val tracker: Tracker)

fun Track.resolvedLastEpisodeSeen(trackerId: Long): Int {
    if (trackerId == TrackerManager.TRAKT) {
        val lastSeen = this.lastEpisodeSeen
        val lastSeenStr = runCatching {
            java.math.BigDecimal.valueOf(lastSeen).stripTrailingZeros().toPlainString()
        }.getOrNull()
        if (!lastSeenStr.isNullOrBlank() && lastSeenStr.contains('.')) {
            val parts = lastSeenStr.split('.', limit = 2)
            val fraction = parts.getOrNull(1).orEmpty()
            if (fraction.endsWith('1') && fraction.length >= 2) {
                return fraction.dropLast(1).toIntOrNull() ?: 0
            }
            return fraction.trimStart('0').toIntOrNull()
                ?: fraction.toIntOrNull()
                ?: lastSeen.roundToInt().coerceAtLeast(1)
        }
    }
    return this.lastEpisodeSeen.toInt()
}
