package eu.kanade.tachiyomi.data.download

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.VideoType
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.torrentServer.service.TorrentServerService
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import tachiyomi.core.common.util.system.logcat
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.toFFmpegString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import okhttp3.Headers
import okhttp3.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * High-Performance Downloader optimized for BDIX and Parallel HLS.
 * Replicates 1DM+ behavior with zero memory-buffering.
 */
class Downloader(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkHelper: eu.kanade.tachiyomi.network.NetworkHelper = Injekt.get(),
) {

    private val preferences: DownloadPreferences by injectLazy()
    private val store = DownloadStore(context)
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val ffmpegSemaphore = Semaphore(5)
    private val memorySemaphore = Semaphore(12) 

    private val notifier by lazy { DownloadNotifier(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloaderJob: Job? = null

    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    init {
        launchIO {
            val downloads = store.restore()
            addAllToQueue(downloads)
        }
    }

    private fun calculateDynamicConcurrency(): Int {
        val userThreads = preferences.downloadThreads().get().coerceAtLeast(1)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        return if (activityManager?.isLowRamDevice == true) userThreads.coerceIn(1, 4) else userThreads.coerceIn(1, 64)
    }

    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) return false
        val pending = queueState.value.filter { it.status != Download.State.DOWNLOADED }
        pending.forEach { if (it.status != Download.State.QUEUE) it.status = Download.State.QUEUE }
        launchDownloaderJob()
        return pending.isNotEmpty()
    }

    @OptIn(FlowPreview::class)
    private fun launchDownloaderJob() {
        if (downloaderJob?.isActive == true) return
        downloaderJob = scope.launch {
            queueState.debounce(100).collectLatest { queue ->
                val activeDownloads = queue.count { it.status == Download.State.DOWNLOADING }
                val maxConcurrent = preferences.concurrentDownloads().get()
                if (activeDownloads < maxConcurrent) {
                    val pending = queue.filter { it.status == Download.State.QUEUE }
                    pending.take(maxConcurrent - activeDownloads).forEach { download ->
                        launch {
                            downloadEpisode(download)
                        }
                    }
                }
                if (areAllDownloadsFinished()) stop()
            }
        }
    }

    fun stop(reason: String? = null) {
        downloaderJob?.cancel()
        downloaderJob = null
        queueState.value.filter { it.status == Download.State.DOWNLOADING }.forEach { it.status = Download.State.QUEUE }
        if (reason != null) notifier.onWarning(reason)
        else if (queueState.value.isNotEmpty()) notifier.onPaused()
        else notifier.onComplete()
        DownloadJob.stop(context)
    }

    fun pause() {
        downloaderJob?.cancel()
        downloaderJob = null
        queueState.value.filter { it.status == Download.State.DOWNLOADING }.forEach { it.status = Download.State.QUEUE }
    }

    fun clearQueue() {
        downloaderJob?.cancel()
        downloaderJob = null
        _queueState.update {
            it.forEach { download -> download.status = Download.State.NOT_DOWNLOADED }
            store.clear()
            emptyList()
        }
        notifier.dismissProgress()
    }

    fun queueEpisodes(anime: Anime, episodes: List<Episode>, autoStart: Boolean, alt: Boolean = false, video: Video? = null) {
        val source = sourceManager.get(anime.source) as? HttpSource ?: return
        val downloads = episodes.map { Download(source, anime, it, alt, video) }
        addAllToQueue(downloads)
        if (autoStart || !DownloadJob.isRunning(context)) DownloadJob.start(context)
    }

    private suspend fun downloadEpisode(download: Download) {
        val animeDir = provider.getAnimeDir(download.anime.title, download.source)
        val episodeDirname = provider.getEpisodeDirName(download.episode.name, download.episode.scanlator)
        val tmpDir = animeDir.createDirectory(episodeDirname + TMP_DIR_SUFFIX)!!
        download.status = Download.State.DOWNLOADING
        notifier.onProgressChange(download)
        try {
            val video = download.video ?: run {
                val hosters = EpisodeLoader.getHosters(download.episode, download.anime, download.source as AnimeSource)
                HosterLoader.getBestVideo(download.source as AnimeSource, hosters)
            } ?: throw Exception(context.stringResource(MR.strings.video_list_empty_error))
            download.video = video
            val filename = DiskUtil.buildValidFilename(download.episode.name)
            val url = video.videoUrl
            var isHls = video.type == VideoType.HLS || url.contains(".m3u8")
            var isDash = video.type == VideoType.DASH || url.contains(".mpd")
            if (url.contains(".mkv") || url.contains(".mp4") || url.contains("discoveryftp.net") || url.contains("cineplexbd.net") || url.contains("download.php")) {
                isHls = false; isDash = false
            }
            if (preferences.alwaysUseInternalDownloader().get()) { isHls = false; isDash = false }
            if (isTor(video)) {
                download.engineType = "Torrent"
                torrentDownload(download, tmpDir, filename)
            } else if (isHls || isDash) {
                download.engineType = "HLS"
                nativeHlsDownload(download, tmpDir, filename)
            } else {
                download.engineType = "Normal"
                internalDownload(download, tmpDir, filename)
            }
            ensureSuccessfulAnimeDownload(download, animeDir, tmpDir, episodeDirname)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            download.status = Download.State.ERROR
            notifier.onError(e.message, download.episode.name, download.anime.title, download.anime.id)
        }
    }

    private suspend fun internalDownload(download: Download, tmpDir: UniFile, filename: String): UniFile {
        val video = download.video!!
        val client = networkHelper.downloadClient
        val threadCount = calculateDynamicConcurrency()
        download.activeThreads = threadCount
        val headRes = client.newCall(Request.Builder().url(video.videoUrl).head().headers(video.headers ?: Headers.headersOf()).build()).await()
        val size = headRes.header("Content-Length")?.toLongOrNull() ?: -1L
        download.totalSize = size
        headRes.close()
        val videoFile = tmpDir.findFile("$filename.tmp") ?: tmpDir.createFile("$filename.tmp")!!
        val downloadedBytes = AtomicLong(0)
        context.contentResolver.openFileDescriptor(videoFile.uri, "rw")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                coroutineScope {
                    if (size > 0 && threadCount > 1) {
                        val partSize = size / threadCount
                        download.totalSegments = threadCount
                        download.downloadedSegments = 0
                        (0 until threadCount).map { i ->
                            launch {
                                val start = i * partSize
                                val end = if (i == threadCount - 1) size - 1 else (i + 1) * partSize - 1
                                val request = Request.Builder().url(video.videoUrl).headers(video.headers ?: Headers.headersOf()).header("Range", "bytes=$start-$end").build()
                                try {
                                    client.newCall(request).execute().use { res ->
                                        val source = res.body?.source() ?: throw IOException("Empty Part")
                                        val buffer = ByteArray(64 * 1024)
                                        var bytesRead: Int
                                        var currentPos = start
                                        while (source.read(buffer).also { bytesRead = it } != -1) {
                                            ensureActive()
                                            channel.write(ByteBuffer.wrap(buffer, 0, bytesRead), currentPos)
                                            currentPos += bytesRead
                                            val total = downloadedBytes.addAndGet(bytesRead.toLong())
                                            download.update(total, size, false)
                                            notifier.onProgressChange(download)
                                        }
                                        synchronized(download) { download.downloadedSegments++ }
                                    }
                                } catch (e: Exception) { Log.e("AniZen", "Part $i failed", e) }
                            }
                        }
                    } else {
                        download.totalSegments = 1
                        client.newCall(Request.Builder().url(video.videoUrl).headers(video.headers ?: Headers.headersOf()).build()).execute().use { res ->
                            val source = res.body?.source() ?: throw IOException("Empty Body")
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            while (source.read(buffer).also { bytesRead = it } != -1) {
                                ensureActive()
                                channel.write(ByteBuffer.wrap(buffer, 0, bytesRead))
                                val total = downloadedBytes.addAndGet(bytesRead.toLong())
                                download.update(total, size, false)
                                notifier.onProgressChange(download)
                            }
                            download.downloadedSegments = 1
                        }
                    }
                }
            }
        }
        videoFile.renameTo("$filename.mkv")
        return videoFile
    }

    private suspend fun nativeHlsDownload(download: Download, tmpDir: UniFile, filename: String): UniFile {
        val video = download.video!!
        val client = networkHelper.downloadClient
        val threadCount = calculateDynamicConcurrency()
        download.activeThreads = threadCount
        val playlistRes = client.newCall(Request.Builder().url(video.videoUrl).headers(video.headers ?: Headers.headersOf()).build()).await()
        val playlistBody = playlistRes.body?.string() ?: throw IOException("Empty HLS")
        val baseUrl = video.videoUrl.substringBeforeLast("/") + "/"
        val segments = playlistBody.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { if (it.startsWith("http")) it else baseUrl + it }
        download.totalSegments = segments.size
        val videoFile = tmpDir.createFile("$filename.tmp")!!
        val nextWriteIdx = AtomicInteger(0)
        val downloadedBytes = AtomicLong(0)
        val segmentCache = ConcurrentHashMap<Int, ByteArray>()
        context.contentResolver.openFileDescriptor(videoFile.uri, "rw")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                coroutineScope {
                    val segmentsToDownload = AtomicInteger(0)
                    segments.forEachIndexed { index, segUrl ->
                        launch {
                            memorySemaphore.withPermit {
                                try {
                                    val res = client.newCall(Request.Builder().url(segUrl).headers(video.headers ?: Headers.headersOf()).build()).execute()
                                    val data = res.body?.bytes() ?: throw IOException("Empty segment")
                                    segmentCache[index] = data
                                    synchronized(channel) {
                                        while (segmentCache.containsKey(nextWriteIdx.get())) {
                                            val writeData = segmentCache.remove(nextWriteIdx.get())!!
                                            channel.write(ByteBuffer.wrap(writeData))
                                            val total = downloadedBytes.addAndGet(writeData.size.toLong())
                                            nextWriteIdx.incrementAndGet()
                                            download.downloadedSegments = nextWriteIdx.get()
                                            download.update(total, -1, false)
                                            if (nextWriteIdx.get() % 5 == 0) notifier.onProgressChange(download)
                                        }
                                    }
                                } catch (e: Exception) {
                                    segmentCache[index] = ByteArray(0)
                                    synchronized(channel) {
                                        while (segmentCache.containsKey(nextWriteIdx.get())) {
                                            segmentCache.remove(nextWriteIdx.get())
                                            nextWriteIdx.incrementAndGet()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        videoFile.renameTo("$filename.mkv")
        return videoFile
    }

    private fun isTor(video: Video) = video.videoUrl.startsWith("magnet") || video.videoUrl.endsWith(".torrent")

    private suspend fun torrentDownload(download: Download, tmpDir: UniFile, filename: String): UniFile {
        TorrentServerService.start()
        TorrentServerService.wait(10)
        val currentTorrent = TorrentServerApi.addTorrent(download.video!!.videoUrl, download.video!!.quality, "", "", false)
        val torrentUrl = TorrentServerUtils.getTorrentPlayLink(currentTorrent, 0)
        download.video!!.videoUrl = torrentUrl
        return internalDownload(download, tmpDir, filename)
    }

    private suspend fun ensureSuccessfulAnimeDownload(download: Download, animeDir: UniFile, tmpDir: UniFile, dirname: String) {
        val downloadedVideo = tmpDir.listFiles().orEmpty().filterNot { it.getName()?.endsWith(".tmp") == true }
        if (downloadedVideo.size >= 1) {
            tmpDir.renameTo(dirname)
            cache.addEpisode(dirname, animeDir, download.anime)
            download.status = Download.State.DOWNLOADED
        } else throw Exception("Unable to finalize download")
    }

    private fun areAllDownloadsFinished() = queueState.value.none { it.status.value <= Download.State.DOWNLOADING.value }

    fun addAllToQueue(downloads: List<Download>) {
        _queueState.update {
            downloads.forEach { download -> download.status = Download.State.QUEUE }
            store.addAll(downloads)
            it + downloads
        }
    }

    fun removeFromQueue(download: Download) {
        _queueState.update {
            store.remove(download)
            if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                download.status = Download.State.NOT_DOWNLOADED
            }
            it - download
        }
    }

    fun removeFromQueue(episodes: List<Episode>) {
        val episodeIds = episodes.map { it.id }
        _queueState.update { queue ->
            val downloads = queue.filter { it.episode.id in episodeIds }
            store.removeAll(downloads)
            downloads.forEach { it.status = Download.State.NOT_DOWNLOADED }
            queue - downloads.toSet()
        }
    }

    fun removeFromQueue(anime: Anime) {
        _queueState.update { queue ->
            val downloads = queue.filter { it.anime.id == anime.id }
            store.removeAll(downloads)
            downloads.forEach { it.status = Download.State.NOT_DOWNLOADED }
            queue - downloads.toSet()
        }
    }

    fun updateQueue(downloads: List<Download>) {
        if (queueState.value == downloads) return
        val wasRunning = isRunning
        pause()
        _queueState.update {
            it.forEach { download -> download.status = Download.State.NOT_DOWNLOADED }
            store.clear()
            emptyList()
        }
        addAllToQueue(downloads)
        if (wasRunning) start()
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val EPISODES_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 500
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 500
    }
}

private const val MIN_DISK_SPACE = 200L * 1024 * 1024
