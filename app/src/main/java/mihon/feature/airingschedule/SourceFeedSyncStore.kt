package mihon.feature.airingschedule

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Small rolling journal of source-feed observations.
 *
 * The journal is intentionally separate from preferences: it keeps the exact source episode
 * identity and both timestamps needed to recover a delay after the app was offline.
 */
class SourceFeedSyncStore(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    fun readRecent(now: LocalDate = LocalDate.now(ZoneId.systemDefault())): List<SourceFeedObservation> {
        val directory = directory()
        val observations = (0..1).flatMap { offset ->
            readDay(directory, now.minusDays(offset.toLong()))
        }
        prune(directory, now)
        return observations.distinctBy { it.eventId }
    }

    fun append(observations: List<SourceFeedObservation>, now: LocalDate = LocalDate.now(ZoneId.systemDefault())) {
        if (observations.isEmpty()) {
            prune(directory(), now)
            return
        }
        val directory = directory()
        val file = dayFile(directory, now)
        val merged = (readFile(file) + observations)
            .distinctBy { it.eventId }
            .sortedBy { it.sourceUploadAt }
        directory.mkdirs()
        val temporary = File(directory, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(merged))
        if (!temporary.renameTo(file)) {
            file.writeText(json.encodeToString(merged))
            temporary.delete()
        }
        prune(directory, now)
    }

    private fun directory() = File(context.filesDir, DIRECTORY_NAME)

    private fun readDay(directory: File, date: LocalDate): List<SourceFeedObservation> =
        readFile(dayFile(directory, date))

    private fun readFile(file: File): List<SourceFeedObservation> =
        runCatching {
            if (file.exists()) json.decodeFromString<List<SourceFeedObservation>>(file.readText()) else emptyList()
        }.getOrDefault(emptyList())

    private fun prune(directory: File, now: LocalDate) {
        directory.listFiles()
            ?.filter { it.name.endsWith(FILE_SUFFIX) }
            ?.filterNot { it.name == dayFile(directory, now).name || it.name == dayFile(directory, now.minusDays(1)).name }
            ?.forEach { it.delete() }
    }

    private fun dayFile(directory: File, date: LocalDate) =
        File(directory, "${date}$FILE_SUFFIX")

    companion object {
        private const val DIRECTORY_NAME = "source_feed_sync"
        private const val FILE_SUFFIX = ".json"
    }
}

@Serializable
data class SourceFeedObservation(
    val eventId: String,
    val sourceId: String,
    val episodeId: String,
    val episodeNumber: Float,
    val officialAirAt: Long,
    val sourceUploadAt: Long,
) {
    val delayMinutes: Long
        get() = (sourceUploadAt - officialAirAt) / 60L
}