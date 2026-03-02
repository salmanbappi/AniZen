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
    private val _isRunningFlow = MutableStateFlow(false)
    val isRunningFlow = _isRunningFlow.asStateFlow()

    val isRunning: Boolean
        get() = _isRunningFlow.value

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
        if (pending.isEmpty()) return false
        
        pending.forEach { 
            if (it.status == Download.State.PAUSED || it.status == Download.State.ERROR || it.status == Download.State.NOT_DOWNLOADED || it.status == Download.State.DOWNLOADING) {
                it.status = Download.State.QUEUE 
            }
        }
        _isRunningFlow.value = true
        launchDownloaderJob()
        return true
    }

    private var downloaderJob: Job? = null
    private val activeJobs = mutableMapOf<Download, Job>()

    @OptIn(FlowPreview::class)
    private fun launchDownloaderJob() {
        if (downloaderJob?.isActive == true) return
        downloaderJob = scope.launch {
            try {
                queueState.debounce(100).collectLatest { queue ->
                    // Cancel jobs no longer in queue or not downloading/queued
                    val activeDownloads = queue.filter { it.status == Download.State.DOWNLOADING }.toSet()
                    activeJobs.keys.filter { it !in activeDownloads }.forEach { download ->
                        activeJobs.remove(download)?.cancel()
                        notifier.dismissProgress(download)
                    }

                    val activeCount = queue.count { it.status == Download.State.DOWNLOADING }
                    val maxConcurrent = preferences.concurrentDownloads().get()
                    
                    if (activeCount < maxConcurrent) {
                        val pending = queue.filter { it.status == Download.State.QUEUE }
                            .sortedWith(compareBy({ it.anime.id }, { it.episode.episodeNumber }))
                        
                        pending.take(maxConcurrent - activeCount).forEach { download ->
                            activeJobs[download] = launch {
                                try {
                                    downloadEpisode(download)
                                } finally {
                                    activeJobs.remove(download)
                                }
                            }
                        }
                    }
                    
                    if (queue.all { it.status == Download.State.DOWNLOADED || it.status == Download.State.PAUSED || it.status == Download.State.ERROR }) {
                        stop()
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    logcat(LogPriority.ERROR, e) { "Downloader job failed" }
                }
            } finally {
                activeJobs.values.forEach { it.cancel() }
                activeJobs.clear()
                _isRunningFlow.value = false
            }
        }
    }

    fun stop(reason: String? = null) {
        _isRunningFlow.value = false
        downloaderJob?.cancel()
        downloaderJob = null
        queueState.value.filter { it.status == Download.State.DOWNLOADING || it.status == Download.State.QUEUE }
            .forEach { 
                it.status = Download.State.PAUSED 
                notifier.dismissProgress(it)
            }
        
        val hasPending = queueState.value.any { it.status == Download.State.PAUSED || it.status == Download.State.QUEUE }
        if (reason != null) notifier.onWarning(reason)
        else if (hasPending) notifier.onPaused()
        else {
            notifier.onComplete()
            notifier.dismissAll()
        }
        DownloadJob.stop(context)
    }

    fun pause() {
        _isRunningFlow.value = false
        downloaderJob?.cancel()
        downloaderJob = null
        queueState.value.filter { it.status == Download.State.DOWNLOADING || it.status == Download.State.QUEUE }
            .forEach { 
                it.status = Download.State.PAUSED 
                notifier.dismissProgress(it)
            }
        notifier.onPaused()
    }

    fun dismissAll() {
        notifier.dismissAll()
    }

    fun clearQueue() {
        downloaderJob?.cancel()
        downloaderJob = null
        _isRunningFlow.value = false
        val currentQueue = queueState.value
        _queueState.update {
            it.forEach { download -> 
                download.status = Download.State.NOT_DOWNLOADED
                download.clearProgress()
                notifier.dismissProgress(download)
            }
            store.clear()
            emptyList()
        }
        notifier.dismissProgress()
        notifier.dismissAll()
    }

    fun queueEpisodes(anime: Anime, episodes: List<Episode>, autoStart: Boolean, alt: Boolean = false, video: Video? = null) {
        val source = sourceManager.get(anime.source) as? HttpSource ?: return
        val downloads = episodes.map { Download(source, anime, it, alt, video) }
        addAllToQueue(downloads)
        if (autoStart || !DownloadJob.isRunning(context)) DownloadJob.start(context)
    }

    private suspend fun <T> retry(
        times: Int = 3,
        initialDelay: Long = 1000,
        maxDelay: Long = 5000,
        factor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        repeat(times - 1) {
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                return block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
            }
        }
        return block()
    }

    private suspend fun downloadEpisode(download: Download) {
        val animeDir = provider.getAnimeDir(download.anime.title, download.source)
        val episodeDirname = provider.getEpisodeDirName(download.episode.name, download.episode.scanlator)
        download.status = Download.State.DOWNLOADING
        
        notifier.onProgressChange(download)
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val video = retry {
                download.video ?: run {
                    val hosters = EpisodeLoader.getHosters(download.episode, download.anime, download.source as AnimeSource)
                    HosterLoader.getBestVideo(download.source as AnimeSource, hosters)
                } ?: throw Exception(context.stringResource(MR.strings.video_list_empty_error))
            }
            download.video = video

            if (download.changeDownloader) {
                val success = externalDownload(download, animeDir, episodeDirname)
                if (success) {
                    download.status = Download.State.DOWNLOADED
                    _queueState.update { it - download }
                    store.remove(download)
                    notifier.dismissProgress(download)
                    return
                } else {
                    throw Exception("Could not open external downloader")
                }
            }

            val tmpDir = animeDir.createDirectory(episodeDirname + TMP_DIR_SUFFIX)!!
            val filename = DiskUtil.buildValidFilename(download.episode.name)
            val url = video.videoUrl
            var isHls = video.type == VideoType.HLS || url.contains(".m3u8")
            var isDash = video.type == VideoType.DASH || url.contains(".mpd")
            if (url.contains(".mkv") || url.contains(".mp4") || url.contains("discoveryftp.net") || url.contains("cineplexbd.net") || url.contains("download.php")) {
                isHls = false; isDash = false
            }
            if (preferences.alwaysUseInternalDownloader().get()) { isHls = false; isDash = false }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
            notifier.dismissProgress(download)
        } catch (e: Exception) {
            if (e is CancellationException) {
                // Keep status as QUEUE if cancelled
                download.status = Download.State.QUEUE
                throw e
            }
            download.status = Download.State.ERROR
            notifier.onError(e.message, download.episode.name, download.anime.title, download.anime.id)
        }
    }

    private suspend fun internalDownload(download: Download, tmpDir: UniFile, filename: String): UniFile {
        val video = download.video!!
        val client = networkHelper.downloadClient
        
        // Recover thread count on resume to prevent progress bar desync
        var threadCount = download.activeThreads
        if (threadCount <= 0 || tmpDir.findFile("$filename.part0") != null) {
            var count = 0
            for (i in 0 until 64) {
                if (tmpDir.findFile("$filename.part$i") != null) count++ else break
            }
            threadCount = if (count > 0) count else calculateDynamicConcurrency()
            download.activeThreads = threadCount
        }

        var size = -1L
        var contentType = ""

        try {
            val headRes = retry { 
                client.newCall(Request.Builder().url(video.videoUrl).head().headers(video.headers ?: Headers.headersOf()).build()).await()
            }
            if (headRes.isSuccessful) {
                size = headRes.header("Content-Length")?.toLongOrNull() ?: -1L
                contentType = headRes.header("Content-Type") ?: ""
            }
            headRes.close()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "HEAD request failed for size detection" }
        }

        // If size is less than 1MB or invalid, fallback to GET Range to get the true size
        if (size <= 1024 * 1024) { 
            try {
                val getRes = retry {
                    client.newCall(
                        Request.Builder()
                            .url(video.videoUrl)
                            .headers(video.headers ?: Headers.headersOf())
                            .header("Range", "bytes=0-0")
                            .build()
                    ).await()
                }
                if (getRes.isSuccessful || getRes.code == 416 || getRes.code == 206) {
                    contentType = getRes.header("Content-Type") ?: contentType
                    val contentRange = getRes.header("Content-Range")
                    if (contentRange != null) {
                        size = contentRange.substringAfterLast("/").toLongOrNull() ?: size
                    } else if (getRes.code == 200) {
                        size = getRes.header("Content-Length")?.toLongOrNull() ?: size
                    }
                }
                getRes.close()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "GET Range request failed for size detection" }
            }
        }
        
        // If still invalid, try a normal GET just to read headers (GET Abort Strategy)
        if (size <= 1024 * 1024) {
            try {
                val getRes = retry {
                    client.newCall(
                        Request.Builder()
                            .url(video.videoUrl)
                            .headers(video.headers ?: Headers.headersOf())
                            .build()
                    ).await()
                }
                if (getRes.isSuccessful) {
                    contentType = getRes.header("Content-Type") ?: contentType
                    size = getRes.header("Content-Length")?.toLongOrNull() ?: size
                }
                getRes.close()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "GET request failed for size detection" }
            }
        }

        // 1DM Content-Type Validation: If it's a small file and it's HTML, it's a redirect/error page, not a video.
        if (size <= 5 * 1024 * 1024 && contentType.contains("text/html", ignoreCase = true)) {
            throw Exception("Download blocked: Server returned an HTML page instead of a video. This is usually due to anti-leech protection or an expired link.")
        }

        download.totalSize = size
        
        val videoFile = tmpDir.findFile("$filename.mkv") ?: tmpDir.createFile("$filename.mkv")!!
        var initialDownloadedBytes = 0L
        
        download.partProgress.clear()
        
        if (size > 0 && threadCount > 1) {
            val partSize = size / threadCount
            for (i in 0 until threadCount) {
                val partFile = tmpDir.findFile("$filename.part$i")
                val existing = partFile?.length() ?: 0L
                initialDownloadedBytes += existing
                val partTotalSize = if (i == threadCount - 1) size - (i * partSize) else partSize
                download.partProgress[i] = (existing.toDouble() / partTotalSize.coerceAtLeast(1L)).toFloat()
            }
        } else {
            initialDownloadedBytes = videoFile.length()
            download.partProgress[0] = if (size > 0) (initialDownloadedBytes.toFloat() / size) else 0f
        }

        val downloadedBytes = AtomicLong(initialDownloadedBytes)
        download.update(initialDownloadedBytes, size, false)

        coroutineScope {
            if (size > 0 && threadCount > 1) {
                val partSize = size / threadCount
                download.totalSegments = threadCount
                
                // Pre-calculate existing bytes to start global progress correctly
                var completedParts = 0
                for (i in 0 until threadCount) {
                    val partFile = tmpDir.findFile("$filename.part$i")
                    val existing = partFile?.length() ?: 0L
                    downloadedBytes.addAndGet(existing)
                    val partTotalSize = if (i == threadCount - 1) size - (i * partSize) else partSize
                    download.partProgress[i] = (existing.toDouble() / partTotalSize.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)
                    if (existing >= partTotalSize) completedParts++
                }
                download.downloadedSegments = completedParts

                (0 until threadCount).map { i ->
                    async {
                        val partFile = tmpDir.findFile("$filename.part$i") ?: tmpDir.createFile("$filename.part$i")!!
                        var existing = partFile.length()
                        val start = i * partSize + existing
                        val end = if (i == threadCount - 1) size - 1 else (i + 1) * partSize - 1
                        val partTotalSize = end - (i * partSize) + 1
                        
                        if (existing >= partTotalSize) return@async

                        retry(times = 5) {
                            val request = Request.Builder()
                                .url(video.videoUrl)
                                .headers(video.headers ?: Headers.headersOf())
                                .header("Range", "bytes=$start-$end")
                                .build()
                            
                            client.newCall(request).execute().use { res ->
                                if (!res.isSuccessful) {
                                    if (res.code == 416) {
                                        synchronized(download) { download.downloadedSegments++ }
                                        return@use
                                    }
                                    throw IOException("Part $i failed: ${res.code}")
                                }
                                val source = res.body?.source() ?: throw IOException("Empty Part")
                                
                                context.contentResolver.openFileDescriptor(partFile.uri, "wa")?.use { pfd ->
                                    FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                                        val buffer = ByteArray(128 * 1024)
                                        var bytesRead: Int
                                        while (source.read(buffer).also { bytesRead = it } != -1) {
                                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                            
                                            // Prevent over-downloading beyond the assigned part size
                                            val maxToWrite = Math.min(bytesRead.toLong(), partTotalSize - existing).toInt()
                                            if (maxToWrite <= 0) break
                                            
                                            channel.write(ByteBuffer.wrap(buffer, 0, maxToWrite))
                                            val total = downloadedBytes.addAndGet(maxToWrite.toLong())
                                            
                                            existing += maxToWrite.toLong()
                                            download.partProgress[i] = (existing.toDouble() / partTotalSize.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)

                                            download.update(total, size, false)
                                            throttleNotification(download)
                                            kotlinx.coroutines.yield()
                                            
                                            if (existing >= partTotalSize) break
                                        }
                                    }
                                }
                                synchronized(download) { download.downloadedSegments++ }
                            }
                        }
                    }
                }.awaitAll()
            } else {
                download.totalSegments = 1
                val existing = videoFile.length()
                if (size > 0 && existing >= size) {
                    download.downloadedSegments = 1
                    return@coroutineScope
                }
                
                downloadedBytes.set(existing)
                download.partProgress[0] = if (size > 0) (existing.toFloat() / size).coerceIn(0f, 1f) else 0f

                retry(times = 5) {
                    val request = Request.Builder()
                        .url(video.videoUrl)
                        .headers(video.headers ?: Headers.headersOf())
                        .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                        .build()
                        
                    client.newCall(request).execute().use { res ->
                        if (!res.isSuccessful) {
                            if (res.code == 416) {
                                download.downloadedSegments = 1
                                return@use
                            }
                            throw IOException("Failed: ${res.code}")
                        }
                        val source = res.body?.source() ?: return@use
                        
                        context.contentResolver.openFileDescriptor(videoFile.uri, "wa")?.use { pfd ->
                            FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                                val buffer = ByteArray(128 * 1024)
                                var bytesRead: Int
                                var localDownloaded = existing
                                while (source.read(buffer).also { bytesRead = it } != -1) {
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    
                                    val maxToWrite = if (size > 0) Math.min(bytesRead.toLong(), size - localDownloaded).toInt() else bytesRead
                                    if (maxToWrite <= 0 && size > 0) break
                                    
                                    channel.write(ByteBuffer.wrap(buffer, 0, maxToWrite))
                                    localDownloaded += maxToWrite.toLong()
                                    val total = downloadedBytes.addAndGet(maxToWrite.toLong())
                                    
                                    if (size > 0) download.partProgress[0] = (localDownloaded.toFloat() / size).coerceIn(0f, 1f)
                                    download.update(total, size, false)
                                    throttleNotification(download)
                                    kotlinx.coroutines.yield()
                                    
                                    if (size > 0 && localDownloaded >= size) break
                                }
                            }
                        }
                        download.downloadedSegments = 1
                    }
                }
            }
        }

        // Merge parts after coroutineScope finishes all threads
        if (size > 0 && threadCount > 1) {
            mergeParts(download, tmpDir, filename, threadCount, videoFile)
        }

        return videoFile
    }

    private suspend fun mergeParts(download: Download, dir: UniFile, filename: String, count: Int, outputFile: UniFile) {
        download.status = Download.State.MERGING
        download.progress = 0
        notifier.onProgressChange(download)
        
        context.contentResolver.openFileDescriptor(outputFile.uri, "w")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).channel.use { outChannel ->
                var currentPos = 0L
                val totalToMerge = (0 until count).sumOf { i -> dir.findFile("$filename.part$i")?.length() ?: 0L }
                var mergedSoFar = 0L
                
                for (i in 0 until count) {
                    val partFile = dir.findFile("$filename.part$i") ?: continue
                    context.contentResolver.openFileDescriptor(partFile.uri, "r")?.use { ppfd ->
                        java.io.FileInputStream(ppfd.fileDescriptor).channel.use { inChannel ->
                            val size = inChannel.size()
                            inChannel.transferTo(0, size, outChannel)
                            currentPos += size
                            mergedSoFar += size
                            outChannel.position(currentPos)
                            
                            download.progress = (100 * mergedSoFar / totalToMerge.coerceAtLeast(1L)).toInt()
                            notifier.onProgressChange(download)
                            kotlinx.coroutines.yield()
                        }
                    }
                    partFile.delete()
                }
            }
        }
    }

    private var lastNotifiedTime = 0L

    private fun throttleNotification(download: Download) {
        val now = System.currentTimeMillis()
        if (now - lastNotifiedTime > 1000) { // Global throttle for all active downloads
            lastNotifiedTime = now
            notifier.onProgressChange(download)
        }
    }

    private suspend fun nativeHlsDownload(download: Download, tmpDir: UniFile, filename: String): UniFile {
        val video = download.video!!
        val client = networkHelper.downloadClient
        val threadCount = calculateDynamicConcurrency()
        download.activeThreads = threadCount
        val playlistRes = retry {
            client.newCall(Request.Builder().url(video.videoUrl).headers(video.headers ?: Headers.headersOf()).build()).await()
        }
        val playlistBody = playlistRes.body?.string() ?: throw IOException("Empty HLS")
        val baseUrl = video.videoUrl.substringBeforeLast("/") + "/"
        val segments = playlistBody.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { if (it.startsWith("http")) it else baseUrl + it }
        download.totalSegments = segments.size
        
        download.segmentProgress.clear()
        val downloadedBytes = AtomicLong(0)
        var initialDownloadedSegments = 0
        
        // Initialize existing progress
        for (i in segments.indices) {
            val segFile = tmpDir.findFile("$i.seg")
            if (segFile != null && segFile.length() > 0) {
                download.segmentProgress[i] = true
                downloadedBytes.addAndGet(segFile.length())
                initialDownloadedSegments++
            } else {
                download.segmentProgress[i] = false
            }
        }
        download.downloadedSegments = initialDownloadedSegments

        coroutineScope {
            segments.mapIndexed { index, segUrl ->
                if (download.segmentProgress[index] == true) return@mapIndexed null
                
                async {
                    memorySemaphore.withPermit {
                        retry {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            client.newCall(Request.Builder().url(segUrl).headers(video.headers ?: Headers.headersOf()).build()).execute().use { res ->
                                if (!res.isSuccessful) throw IOException("Seg $index failed: ${res.code}")
                                val data = res.body?.bytes() ?: throw IOException("Empty segment")
                                val file = tmpDir.createFile("$index.seg")!!
                                context.contentResolver.openFileDescriptor(file.uri, "w")?.use { pfd ->
                                    FileOutputStream(pfd.fileDescriptor).use { it.write(data) }
                                }
                                download.segmentProgress[index] = true
                                val currentTotal = downloadedBytes.addAndGet(data.size.toLong())
                                synchronized(download) { download.downloadedSegments++ }
                                download.update(currentTotal, -1, false)
                                throttleNotification(download)
                                kotlinx.coroutines.yield()
                            }
                        }
                    }
                }
            }.filterNotNull().awaitAll()
        }
        
        // Merge segments
        download.status = Download.State.MERGING
        download.progress = 0
        notifier.onProgressChange(download)

        val videoFile = tmpDir.createFile("$filename.mkv")!!
        context.contentResolver.openFileDescriptor(videoFile.uri, "w")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).channel.use { outChannel ->
                var currentPos = 0L
                val totalToMerge = (0 until segments.size).sumOf { i -> tmpDir.findFile("$i.seg")?.length() ?: 0L }
                var mergedSoFar = 0L

                for (i in 0 until segments.size) {
                    val segFile = tmpDir.findFile("$i.seg") ?: continue
                    context.contentResolver.openFileDescriptor(segFile.uri, "r")?.use { spfd ->
                        java.io.FileInputStream(spfd.fileDescriptor).channel.use { inChannel ->
                            val size = inChannel.size()
                            inChannel.transferTo(0, size, outChannel)
                            currentPos += size
                            mergedSoFar += size
                            outChannel.position(currentPos)

                            download.progress = (100 * mergedSoFar / totalToMerge.coerceAtLeast(1L)).toInt()
                            notifier.onProgressChange(download)
                            kotlinx.coroutines.yield()
                        }
                    }
                    segFile.delete()
                }
            }
        }
        return videoFile
    }

    private fun isTor(video: Video) = video.videoUrl.startsWith("magnet") || video.videoUrl.endsWith(".torrent")

    private suspend fun torrentDownload(download: Download, tmpDir: UniFile, filename: String): UniFile {
        retry {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            TorrentServerService.start()
            TorrentServerService.wait(10)
        }
        val currentTorrent = retry {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            TorrentServerApi.addTorrent(download.video!!.videoUrl, download.video!!.quality, "", "", false)
        }
        val torrentUrl = TorrentServerUtils.getTorrentPlayLink(currentTorrent, 0)
        download.video!!.videoUrl = torrentUrl
        return internalDownload(download, tmpDir, filename)
    }

    private suspend fun ensureSuccessfulAnimeDownload(download: Download, animeDir: UniFile, tmpDir: UniFile, dirname: String) {
        // Wait a bit for file system to settle
        delay(500)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val downloadedVideo = tmpDir.listFiles().orEmpty().filterNot { it.getName()?.endsWith(".tmp") == true }
        if (downloadedVideo.isNotEmpty()) {
            tmpDir.renameTo(dirname)
            cache.addEpisode(dirname, animeDir, download.anime)
            download.status = Download.State.DOWNLOADED
            
            // Remove from queue since it's finished
            _queueState.update { it - download }
            store.remove(download)
        } else {
            // Check if it was already renamed (race condition)
            val alreadyRenamed = animeDir.findFile(dirname)
            if (alreadyRenamed != null && alreadyRenamed.isDirectory) {
                download.status = Download.State.DOWNLOADED
                _queueState.update { it - download }
                store.remove(download)
            } else {
                throw Exception("Unable to finalize download: No video file found in ${tmpDir.uri}")
            }
        }
    }

    private fun areAllDownloadsFinished() = queueState.value.none { it.status.value <= Download.State.DOWNLOADING.value }

    fun addAllToQueue(downloads: List<Download>) {
        _queueState.update {
            downloads.forEach { download -> download.status = Download.State.QUEUE }
            store.addAll(downloads)
            it + downloads
        }
    }

    private fun deleteTempFiles(download: Download) {
        try {
            val animeDir = provider.findAnimeDir(download.anime.title, download.source) ?: return
            val episodeDirname = provider.getEpisodeDirName(download.episode.name, download.episode.scanlator)
            animeDir.findFile(episodeDirname + TMP_DIR_SUFFIX)?.delete()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to delete temp files for ${download.episode.name}" }
        }
    }

    fun removeFromQueue(download: Download) {
        _queueState.update {
            store.remove(download)
            if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                download.status = Download.State.NOT_DOWNLOADED
            }
            deleteTempFiles(download)
            download.clearProgress()
            notifier.dismissProgress(download)
            it - download
        }
    }

    fun removeFromQueue(episodes: List<Episode>) {
        val episodeIds = episodes.map { it.id }
        _queueState.update { queue ->
            val downloads = queue.filter { it.episode.id in episodeIds }
            store.removeAll(downloads)
            downloads.forEach { 
                it.status = Download.State.NOT_DOWNLOADED 
                deleteTempFiles(it)
                it.clearProgress()
                notifier.dismissProgress(it)
            }
            queue - downloads.toSet()
        }
    }

    fun removeFromQueue(anime: Anime) {
        _queueState.update { queue ->
            val downloads = queue.filter { it.anime.id == anime.id }
            store.removeAll(downloads)
            downloads.forEach { 
                it.status = Download.State.NOT_DOWNLOADED 
                deleteTempFiles(it)
                it.clearProgress()
                notifier.dismissProgress(it)
            }
            queue - downloads.toSet()
        }
    }

    fun updateQueue(downloads: List<Download>) {
        if (queueState.value == downloads) return
        val wasRunning = isRunning
        pause()
        _queueState.update {
            it.forEach { download -> 
                download.status = Download.State.NOT_DOWNLOADED
                download.clearProgress()
            }
            store.clear()
            emptyList()
        }
        addAllToQueue(downloads)
        if (wasRunning) start()
    }

    private suspend fun externalDownload(download: Download, animeDir: UniFile, episodeDirname: String): Boolean {
        val video = download.video ?: return false
        val url = video.videoUrl
        val packageName = preferences.externalDownloaderSelection().get()
        val pm = context.packageManager
        
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val filename = DiskUtil.buildValidFilename(download.episode.name) + ".mp4"
            
            // Create the episode directory so external downloader can save inside it
            val episodeDir = animeDir.createDirectory(episodeDirname)
            val dirPath = episodeDir?.filePath ?: animeDir.filePath

            withUIContext {
                if (dirPath != null) {
                    context.copyToClipboard("Episode download location", dirPath)
                }
            }

            intent.setDataAndType(Uri.parse(url), "video/*")

            when {
                packageName.startsWith("idm.internet.download.manager") -> {
                    val headers = video.headers ?: (download.source as? HttpSource)?.headers
                    val bundle = Bundle()
                    headers?.let {
                        for (i in 0 until it.size) {
                            bundle.putString(it.name(i), it.value(i))
                        }
                    }

                    intent.apply {
                        putExtra("extra_filename", filename)
                        putExtra("extra_headers", bundle)
                        if (dirPath != null) {
                            putExtra("extra_path", dirPath)
                        }
                    }
                }
                packageName.startsWith("com.dv.adm") -> {
                    val headers = video.headers ?: (download.source as? HttpSource)?.headers
                    val bundle = Bundle()
                    headers?.let {
                        for (i in 0 until it.size) {
                            bundle.putString(it.name(i), it.value(i).replace("http", "h_ttp"))
                        }
                    }

                    intent.apply {
                        putExtra(
                            "com.dv.get.ACTION_LIST_ADD",
                            "${Uri.parse(url)}<info>$filename",
                        )
                        if (dirPath != null) {
                            putExtra("com.dv.get.ACTION_LIST_PATH", dirPath)
                        }
                        putExtra("android.media.intent.extra.HTTP_HEADERS", bundle)
                    }
                }
                else -> {
                    val headers = video.headers ?: (download.source as? HttpSource)?.headers
                    if (headers != null) {
                        val headersBundle = Bundle()
                        for (i in 0 until headers.size) {
                            headersBundle.putString(headers.name(i), headers.value(i))
                        }
                        intent.putExtra("android.media.intent.extra.HTTP_HEADERS", headersBundle)
                        
                        val headersArray = Array(headers.size) { i -> "${headers.name(i)}: ${headers.value(i)}" }
                        intent.putExtra("headers", headersArray)
                    }

                    intent.apply {
                        putExtra("title", "${download.anime.title} - ${download.episode.name}")
                        putExtra("filename", filename)
                        putExtra("extra_filename", filename)
                        if (dirPath != null) {
                            putExtra("extra_path", dirPath) // fallback 1DM
                            putExtra("com.dv.get.ext_dir", dirPath) // fallback ADM
                        }
                    }
                }
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            
            if (packageName.isNotBlank() && packageName != "None") {
                intent.setPackage(packageName)
                // Attempt to find the specific downloader activity to bypass the 'Open With' dialog
                val resolveInfo = pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                if (resolveInfo.isNotEmpty()) {
                    // Try to find an activity with 'Download' in its name, otherwise pick the first one
                    val bestMatch = resolveInfo.find { it.activityInfo.name.contains("Download", ignoreCase = true) } 
                                     ?: resolveInfo.first()
                    intent.component = ComponentName(bestMatch.activityInfo.packageName, bestMatch.activityInfo.name)
                }
            }
            
            context.startActivity(intent)
            
            // Explicitly remove from queue after successful handoff
            download.status = Download.State.DOWNLOADED
            _queueState.update { it - download }
            store.remove(download)
            notifier.dismissProgress(download)
            
            delay(1500) // Give external downloader time to register intent and prevent dropping multiple downloads
            return true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to launch external downloader: ${e.message}" }
            return false
        }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val EPISODES_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 500
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 500
    }
}

private const val MIN_DISK_SPACE = 200L * 1024 * 1024
