package eu.kanade.tachiyomi.data.download.model

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class Download(
    val source: HttpSource,
    val anime: Anime,
    val episode: Episode,
    val changeDownloader: Boolean = false,
    var video: Video? = null,
    var selectedAudioTracks: List<Track> = emptyList(),
    var selectedSubtitleTracks: List<Track> = emptyList(),
) : ProgressListener {

    @Transient
    private val _statusFlow = MutableStateFlow(State.NOT_DOWNLOADED)

    @Transient
    val statusFlow = _statusFlow.asStateFlow()
    var status: State = State.NOT_DOWNLOADED
        set(value) {
            if (field == value) return
            field = value
            _statusFlow.update { value }
        }

    @Transient
    private val progressStateFlow = MutableStateFlow(0)

    @Transient
    val progressFlow = progressStateFlow.asStateFlow()
    var progress: Int = 0
        set(value) {
            // PRO-LEVEL: Prevent StateFlow flood by only updating on actual integer changes
            if (field == value) return
            field = value
            progressStateFlow.update { value }
        }

    // Rich Notification Fields
    @Transient var speed: String = ""

    @Transient var eta: String = ""
    var totalSize: Long = -1L
    var totalDuration: Long = 0L

    @Transient var downloadedSize: String = ""

    @Transient var downloadedSegments: Int = 0
    var totalSegments: Int = 0

    @Transient var activeThreads: Int = 0
    var engineType: String = ""

    // "HLS", "DASH", or "Normal"
    @Transient var interruptedState: State? = null

    // 1DM-style granular progress
    @Transient val partProgress = ConcurrentHashMap<Int, Float>()

    @Transient val segmentProgress = ConcurrentHashMap<Int, Boolean>()

    @Transient var lastNotifiedTime: Long = 0L

    // PERFORMANCE: Atomic accumulators for lock-free metric updates
    private val totalBytesAccumulator = AtomicLong(0)
    private var lastUpdateTime: Long = System.currentTimeMillis()
    private val lastBytesRead = AtomicLong(0)
    private val speedSamples = java.util.concurrent.CopyOnWriteArrayList<Double>()

    /**
     * Updates the status of the download
     */
    override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
        if (contentLength > 0) {
            totalSize = contentLength
        }
        totalBytesAccumulator.set(bytesRead)

        val newProgress = when {
            totalSize > 0 -> (100 * bytesRead / totalSize).toInt()
            totalSegments > 0 -> (100 * downloadedSegments / totalSegments).toInt()
            else -> -1
        }

        calculateSpeed(bytesRead)

        if (progress != newProgress) progress = newProgress
    }

    /**
     * Updates only the speed of the download
     */
    fun updateSpeed(bytesRead: Long) {
        totalBytesAccumulator.set(bytesRead)
        calculateSpeed(bytesRead)
    }

    fun clearProgress() {
        progress = 0
        speed = ""
        eta = ""
        downloadedSize = ""
        downloadedSegments = 0
        totalSegments = 0
        activeThreads = 0
        partProgress.clear()
        segmentProgress.clear()
        lastUpdateTime = System.currentTimeMillis()
        lastBytesRead.set(0)
        totalBytesAccumulator.set(0)
        speedSamples.clear()
    }

    private fun calculateSpeed(bytesRead: Long) {
        val now = System.currentTimeMillis()
        val timeDiff = (now - lastUpdateTime) / 1000.0

        // SMOOTHING: Only calculate metrics every 500ms to avoid CPU thrashing
        if (timeDiff >= 0.5) {
            val bytesDiff = bytesRead - lastBytesRead.get()
            val currentSpeed = bytesDiff / timeDiff

            // Lock-free sampling using CopyOnWriteArrayList and Atomic updates
            speedSamples.add(currentSpeed)
            if (speedSamples.size > 5) speedSamples.removeAt(0)
            val smoothSpeed = speedSamples.average()

            speed = when {
                smoothSpeed > 1024 * 1024 -> "%.2f MB/s".format(smoothSpeed / (1024 * 1024))
                smoothSpeed > 1024 -> "%.1f KB/s".format(smoothSpeed / 1024)
                else -> "${smoothSpeed.toLong()} B/s"
            }

            // Update Downloaded Size String
            downloadedSize = formatSize(bytesRead)
            if (totalSize > 0) {
                downloadedSize += " / " + formatSize(totalSize)
            }

            // Calculate ETA
            if (totalSize > 0 && smoothSpeed > 0) {
                val remainingBytes = totalSize - bytesRead
                val remainingSeconds = (remainingBytes / smoothSpeed).toLong()
                eta = formatRemainingTime(remainingSeconds)
            } else {
                eta = ""
            }

            lastUpdateTime = now
            lastBytesRead.set(bytesRead)
        }
    }

    private fun formatRemainingTime(seconds: Long): String {
        return when {
            seconds >= 3600 -> "%dh %dm %ds".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
            seconds >= 60 -> "%dm %ds".format(seconds / 60, seconds % 60)
            else -> "%ds".format(seconds)
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> "%.1f KB".format(bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    enum class State(val value: Int) {
        NOT_DOWNLOADED(0),
        QUEUE(1),
        DOWNLOADING(2),
        DOWNLOADED(3),
        ERROR(4),
        PAUSED(5),
        MERGING(6),
        DECRYPTING(7),
        FINALIZING(8),
    }

    companion object {
        suspend fun fromEpisodeId(
            episodeId: Long,
            getEpisode: GetEpisode = Injekt.get(),
            getAnime: GetAnime = Injekt.get(),
            sourceManager: SourceManager = Injekt.get(),
        ): Download? {
            val episode = getEpisode.await(episodeId) ?: return null
            val anime = getAnime.await(episode.animeId) ?: return null
            val source = sourceManager.get(anime.source) as? HttpSource ?: return null

            return Download(source, anime, episode)
        }
    }
}
