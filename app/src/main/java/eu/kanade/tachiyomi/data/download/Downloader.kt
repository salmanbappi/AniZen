package eu.kanade.tachiyomi.data.download

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.system.Os
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import com.arthenica.ffmpegkit.StatisticsCallback
import com.hippo.unifile.UniFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ensureActive
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.VideoType
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.torrentServer.service.TorrentServerService
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.network.await
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
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

/**
 * This class is the one in charge of downloading episodes.
 *
 * Its queue contains the list of episodes to download. In order to download them, the downloader
 * subscription must be running and the list of episodes must be sent to them by [downloaderJob].
 *
 * The queue manipulation must be done in one thread (currently the main thread) to avoid unexpected
 * behavior, but it's safe to read it from multiple threads.
 */
class Downloader(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val sourceManager: SourceManager = Injekt.get<SourceManager>(),
    private val networkHelper: eu.kanade.tachiyomi.network.NetworkHelper = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>(),
) {

    private fun calculateDynamicConcurrency(): Int {
        val userThreads = preferences.downloadThreads().get()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        
        // Rationale: High thread counts (30+) can cause out-of-memory errors on older devices.
        // We dynamically cap the threads based on the device's memory profile if the user 
        // doesn't force a high limit.
        return when {
            activityManager?.isLowRamDevice == true -> userThreads.coerceIn(1, 8)
            Runtime.getRuntime().maxMemory() < 256 * 1024 * 1024 -> userThreads.coerceIn(1, 12)
            else -> userThreads.coerceAtLeast(12)
        }
    }

    /**
     * Store for controlling downloader.
     */
    private val store = DownloadStore(context)

    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val ffmpegSemaphore = Semaphore(10)

    // Rationale: Tracks "memory slots" to prevent OOM when using high thread counts.
    // Each slot represents a segment currently held in RAM before being written to disk.
    private val memorySemaphore = Semaphore(80) 

    /**
     * Notifier for the downloader state and progress.
     */
    private val notifier by lazy { DownloadNotifier(context) }

    /**
     * Coroutine scope used for download job scheduling
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Job object for download queue management
     */
    private var downloaderJob: Job? = null

    /**
     * Preference for user's choice of external downloader
     */
    private val preferences: DownloadPreferences by injectLazy()

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    init {
        scope.launch {
            val episodes = async { store.restore() }
            addAllToQueue(episodes.await())
        }
    }

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != Download.State.DOWNLOADED }
        pending.forEach { if (it.status != Download.State.QUEUE) it.status = Download.State.QUEUE }

        launchDownloaderJob()

        return pending.isNotEmpty()
    }

    /**
     * Stops the downloader.
     */
    fun stop(reason: String? = null) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        DownloadJob.stop(context)
    }

    /**
     * Pauses the downloader
     */
    fun pause() {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.QUEUE }
    }

    /**
     * Removes everything from the queue.
     */
    fun clearQueue() {
        cancelDownloaderJob()

        internalClearQueue()
        notifier.dismissProgress()
    }

    /**
     * Prepares the jobs to start downloading.
     */
    @OptIn(FlowPreview::class)
    private fun launchDownloaderJob() {
        if (isRunning) return

        downloaderJob = scope.launch {
            val activeDownloadsFlow = queueState
                .debounce(50) // Reduced for faster startup
                .transformLatest { queue ->
                    val activeDownloads = queue.asSequence()
                        .filter {
                            it.status.value <= Download.State.DOWNLOADING.value
                        } // Ignore completed downloads, leave them in the queue
                        .take(preferences.concurrentDownloads().get()) // Concurrent download from user setting
                        .toList()
                    emit(activeDownloads)

                    if (areAllDownloadsFinished()) stop()
                }.distinctUntilChanged()

            // Use supervisorScope to cancel child jobs when the downloader job is cancelled
            supervisorScope {
                val downloadJobs = mutableMapOf<Download, Job>()

                activeDownloadsFlow.collectLatest { activeDownloads ->
                    val downloadJobsToStop = downloadJobs.filter { it.key !in activeDownloads }
                    downloadJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        downloadJobs.remove(download)
                    }

                    val downloadsToStart = activeDownloads.filter { it !in downloadJobs }
                    downloadsToStart.forEach { download ->
                        downloadJobs[download] = launchDownloadJob(download)
                    }
                }
            }
        }
    }

    /**
     * Launch the job responsible for download a single video
     */
    private fun CoroutineScope.launchDownloadJob(download: Download) = launchIO {
        // This try-catch manages the job cancellation
        try {
            download.speed = "Connecting..."
            downloadEpisode(download)

            // Remove successful download from queue
            if (download.status == Download.State.DOWNLOADED) {
                removeFromQueue(download)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                // Silent cancellation
            } else {
                notifier.onError(e.message)
                logcat(LogPriority.ERROR, throwable = e) { "Download failed" }
            }
        }
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun cancelDownloaderJob() {
        downloaderJob?.cancel()
        downloaderJob = null
    }

    /**
     * Creates a download object for every episode and adds them to the downloads queue.
     *
     * @param anime the anime of the episodes to download.
     * @param episodes the list of episodes to download.
     * @param autoStart whether to start the downloader after enqueing the episodes.
     */
    fun queueEpisodes(
        anime: Anime,
        episodes: List<Episode>,
        autoStart: Boolean,
        changeDownloader: Boolean = false,
        video: Video? = null,
    ) {
        if (episodes.isEmpty()) return

        val source = sourceManager.get(anime.source) as? HttpSource ?: return
        val wasEmpty = queueState.value.isEmpty()

        val episodesToQueue = episodes.asSequence()
            // Filter out those already downloaded.
            .filter { provider.findEpisodeDir(it.name, it.scanlator, anime.title, source) == null }
            // Add episodes to queue from the start.
            .sortedByDescending { it.sourceOrder }
            // Filter out those already enqueued.
            .filter { episode -> queueState.value.none { it.episode.id == episode.id } }
            // Create a download for each one.
            .map { Download(source, anime, it, changeDownloader, video) }
            .toList()

        if (episodesToQueue.isNotEmpty()) {
            addAllToQueue(episodesToQueue)

            // Start downloader if needed
            if (autoStart && (wasEmpty || !isRunning)) {
                val queuedDownloads =
                    queueState.value.count { it: Download -> it.source !is UnmeteredSource }
                val maxDownloadsFromSource = queueState.value
                    .groupBy { it.source }
                    .filterKeys { it !is UnmeteredSource }
                    .maxOfOrNull { it.value.size }
                    ?: 0
                // TODO: show warnings in stable
                if (
                    queuedDownloads > DOWNLOADS_QUEUED_WARNING_THRESHOLD ||
                    maxDownloadsFromSource > EPISODES_PER_SOURCE_QUEUE_WARNING_THRESHOLD
                ) {
                    notifier.onWarning(
                        context.stringResource(MR.strings.download_queue_size_warning),
                        WARNING_NOTIF_TIMEOUT_MS,
                        NotificationHandler.openUrl(
                            context,
                            LibraryUpdateNotifier.HELP_WARNING_URL,
                        ),
                    )
                }
                DownloadJob.start(context)
            }
        }
    }

    /**
     * Download the video associated with download object
     *
     * @param download the episode to be downloaded.
     */
    private suspend fun downloadEpisode(download: Download) {
        // This try catch manages errors during download
        try {
            // Immediate UI feedback
            download.status = Download.State.DOWNLOADING
            notifier.onProgressChange(download)

            val animeDir = provider.getAnimeDir(download.anime.title, download.source)

            // val availSpace = DiskUtil.getAvailableStorageSpace(animeDir)
            // if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            //    throw Exception(context.stringResource(MR.strings.download_insufficient_space))
            // }

            val episodeDirname = provider.getEpisodeDirName(download.episode.name, download.episode.scanlator)
            val tmpDir = animeDir.createDirectory(episodeDirname + TMP_DIR_SUFFIX)!!

            if (download.video == null) {
                // Pull video from network and add them to download object
                try {
                    val hosters = EpisodeLoader.getHosters(download.episode, download.anime, download.source)
                    val fetchedVideo = HosterLoader.getBestVideo(download.source, hosters)!!

                    download.video = fetchedVideo
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, throwable = e) { "Failed to get best video" }
                    throw Exception(context.stringResource(MR.strings.video_list_empty_error))
                }
            }

            getOrDownloadVideoFile(download, tmpDir)

            ensureSuccessfulAnimeDownload(download, animeDir, tmpDir, episodeDirname)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            download.status = Download.State.ERROR
            notifier.onError(e.message, download.episode.name, download.anime.title, download.anime.id)
        } finally {
            notifier.dismissProgress()
        }
    }

    /**
     * Gets the video file if already downloaded, otherwise downloads it
     *
     * @param download the download of the video.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadVideoFile(
        download: Download,
        tmpDir: UniFile,
    ): Video {
        val video = download.video!!

        video.status = Video.State.LOAD_VIDEO

        var progressJob: Job? = null

        // Get filename from download info
        val filename = DiskUtil.buildValidFilename(download.episode.name)

        // Delete temp file if it exists
        tmpDir.findFile("$filename.tmp")?.delete()

        // Try to find the video file
        val videoFile = tmpDir.listFiles()?.firstOrNull { it.name!!.startsWith("$filename.mkv") }

        try {
            // If the video is already downloaded, do nothing. Otherwise download from network
            val file = when {
                videoFile != null -> videoFile
                else -> {
                    notifier.onProgressChange(download)

                    download.status = Download.State.DOWNLOADING
                    download.progress = 0

                    // If videoFile is not existing then download it
                    if (preferences.useExternalDownloader().get() == download.changeDownloader) {
                        progressJob = scope.launch {
                            while (download.status == Download.State.DOWNLOADING) {
                                delay(50)
                                notifier.onProgressChange(download)
                            }
                        }

                        downloadVideo(download, tmpDir, filename)
                    } else {
                        val betterFileName = DiskUtil.buildValidFilename(
                            "${download.anime.title} - ${download.episode.name}",
                        )
                        downloadVideoExternal(download, tmpDir, betterFileName)
                    }
                }
            }

            video.videoUrl = file.uri.path ?: ""
            download.progress = 100
            video.status = Video.State.READY
            progressJob?.cancel()
        } catch (e: Throwable) {
            progressJob?.cancel()
            if (e is CancellationException) throw e
            video.status = Video.State.ERROR
            notifier.onError(e.message, download.episode.name, download.anime.title, download.anime.id)

            logcat(LogPriority.ERROR, throwable = e) { "Failed to download video file" }

            throw e
        }

        return video
    }

    /**
     * Define a retry routine in order to accommodate some errors that can be raised
     *
     * @param download the download reference
     * @param tmpDir the directory where placing the file
     * @param filename the name to give to download file
     */
    private suspend fun downloadVideo(
        download: Download,
        tmpDir: UniFile,
        filename: String,
    ): UniFile {
        var file: UniFile? = null

        val downloadScope = CoroutineScope(coroutineContext)
        for (tries in 1..3) {
            if (downloadScope.isActive) {
                file = try {
                    val video = download.video!!
                    
                    // 1DM+ Style Intelligence: Peek at the first few bytes if unsure
                    val url = video.videoUrl
                    val path = url.substringBefore("?")
                    val extension = path.substringAfterLast(".", "").lowercase()
                    
                    val hasVideoExtension = url.contains(".mp4", ignoreCase = true) ||
                                            url.contains(".mkv", ignoreCase = true) ||
                                            url.contains(".avi", ignoreCase = true) ||
                                            url.contains(".mov", ignoreCase = true) ||
                                            url.contains(".ts", ignoreCase = true)
                    
                    val isBDIX = url.contains("discoveryftp.net", ignoreCase = true) ||
                                 url.contains("cineplexbd.net", ignoreCase = true) ||
                                 url.contains("ftpbd.net", ignoreCase = true) ||
                                 url.contains("sam-bd.com", ignoreCase = true) ||
                                 url.contains("download.php", ignoreCase = true)
                                      
                    var isHls = (video.type == VideoType.HLS || extension == "m3u8" || url.contains(".m3u8", ignoreCase = true)) && !hasVideoExtension && !isBDIX
                    var isDash = (video.type == VideoType.DASH || extension == "mpd" || url.contains(".mpd", ignoreCase = true)) && !hasVideoExtension && !isBDIX
                    
                    if (!hasVideoExtension && !isBDIX && !preferences.alwaysUseInternalDownloader().get()) {
                        try {
                            val client = networkHelper.client
                            val headers = video.headers ?: download.source.headers
                            val sniffReq = Request.Builder().url(url).header("Range", "bytes=0-511").headers(headers).build()
                            val response = client.newCall(sniffReq).execute()
                            
                            val contentType = response.header("Content-Type")?.lowercase() ?: ""
                            val bodySource = response.body?.source()
                            val peekText = bodySource?.peek()?.readUtf8(10) ?: ""
                            response.close()
                            
                            if (contentType.contains("mpegurl") || peekText.contains("#EXTM3U")) {
                                isHls = true
                            } else if (contentType.contains("dash+xml") || peekText.contains("<MPD")) {
                                isDash = true
                            } else if (contentType.startsWith("video/") || contentType.contains("octet-stream")) {
                                isHls = false
                                isDash = false
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.DEBUG) { "Sniffing failed: ${e.message}" }
                        }
                    }

                    if (preferences.alwaysUseInternalDownloader().get()) {
                        isHls = false
                        isDash = false
                    }

                    android.util.Log.d("AniZen", "Download Detection [SMART]: url=$url, isHls=$isHls, isDash=$isDash")
                    
                    if (isTor(video)) {
                        download.engineType = "Torrent"
                        torrentDownload(download, tmpDir, filename)
                    } else if (isHls || isDash) {
                        download.engineType = "HLS"
                        // TRY NATIVE HLS FIRST (1DM+ Style)
                        try {
                            nativeHlsDownload(download, tmpDir, filename)
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, throwable = e) { "Native HLS failed, falling back to FFmpeg" }
                            ffmpegSemaphore.withPermit {
                                ffmpegDownload(download, tmpDir, filename)
                            }
                        }
                    } else {
                        download.engineType = "Normal"
                        internalDownload(download, tmpDir, filename)
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    notifier.onError(
                        e.message + ", retrying..",
                        download.episode.name,
                        download.anime.title,
                        download.anime.id,
                    )
                    delay(2 * 1000L)
                    null
                }
            }
            // If download has been completed successfully we break from retry loop
            if (file != null) break
        }

        return if (downloadScope.isActive) {
            file ?: throw Exception("Downloaded file not found")
        } else {
            throw Exception("Download has been stopped")
        }
    }

    private suspend fun nativeHlsDownload(
        download: Download,
        tmpDir: UniFile,
        filename: String,
    ): UniFile {
        val video = download.video!!
        val client = networkHelper.downloadClient
        val headers = (video.headers ?: download.source.headers).toMutableList()
        
        // 1DM+ Secrets: Mimic a real browser environment
        if (headers.none { it.first.equals("User-Agent", ignoreCase = true) }) {
            headers.add("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }
        
        // Remove X-Requested-With if it causes issues, or set to browser
        headers.removeAll { it.first.equals("X-Requested-With", ignoreCase = true) }
        
        // Dynamic Referer/Origin: Critical for mirrors like stormshade84.live
        val videoUri = Uri.parse(video.videoUrl)
        val hostOrigin = "${videoUri.scheme}://${videoUri.host}"
        
        if (headers.none { it.first.equals("Referer", ignoreCase = true) }) {
            headers.add("Referer" to "$hostOrigin/")
        }
        if (headers.none { it.first.equals("Origin", ignoreCase = true) }) {
            headers.add("Origin" to hostOrigin)
        }

        val headerBuilder = okhttp3.Headers.Builder()
        headers.forEach { headerBuilder.add(it.first, it.second) }
        val headerMap = headerBuilder.build()

        // 1. Fetch Playlist (Safe Handshake with Retry)
        var playlistContent: String? = null
        for (i in 1..3) {
            try {
                val request = Request.Builder().url(video.videoUrl).headers(headerMap).build()
                playlistContent = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Failed to fetch playlist: ${response.code}")
                    response.body?.string() ?: throw IOException("Empty playlist")
                }
                break
            } catch (e: Exception) {
                if (i == 3) throw e
                delay(1000L * i)
            }
        }

        // 2. Parse segments
        val baseUrl = video.videoUrl.substringBeforeLast("/") + "/"
        val segments = playlistContent!!.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { if (it.startsWith("http")) it else baseUrl + it }

        if (segments.isEmpty()) throw IOException("No segments found in playlist")

        val videoFile = tmpDir.findFile("$filename.tmp") ?: tmpDir.createFile("$filename.tmp")!!
        val downloadedSegments = mutableMapOf<Int, ByteArray>()
        var nextWriteIndex = 0
        var totalDownloaded = 0L
        val totalSegments = segments.size
        
        // Init rich notification fields
        download.totalSegments = totalSegments
        download.downloadedSegments = 0

        // 3. Parallel segment downloading with Sequential Writing (High Performance)
        // Rationale: We use a Semaphore to saturate bandwidth while the OOM prevention loop 
        // ensures that we don't buffer too many segments in memory before they are 
        // sequentially written to the file channel.
        val concurrency = calculateDynamicConcurrency()
        val semaphore = Semaphore(concurrency)
        var failedSegments = 0
        
        context.contentResolver.openFileDescriptor(videoFile.uri, "rw")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                coroutineScope {
                    segments.forEachIndexed { index, segmentUrl ->
                                launch(Dispatchers.IO) {
                                    var attempt = 0
                                    var success = false
                                    while (attempt < 5 && !success) {
                                        try {
                                            // Rationale: We acquire a permit before downloading to bound RAM usage.
                                            // The permit is released automatically after the segment is written to disk.
                                            memorySemaphore.withPermit {
                                                val segRequest = Request.Builder().url(segmentUrl).headers(headerMap).build()
                                                client.newCall(segRequest).execute().use { response ->
                                                    if (!response.isSuccessful) {
                                                        if (response.code == 403) throw IOException("Segment 403: Forbidden")
                                                        throw IOException("Segment failed: ${response.code}")
                                                    }
                                                    val data = response.body?.bytes() ?: throw IOException("Empty segment")
                                                    
                                                    synchronized(downloadedSegments) {
                                                        downloadedSegments[index] = data
                                                    }
                                                    
                                                    // Sequential merge logic
                                                    synchronized(channel) {
                                                        while (downloadedSegments.containsKey(nextWriteIndex)) {
                                                            val segmentData = downloadedSegments.remove(nextWriteIndex)!!
                                                            channel.write(ByteBuffer.wrap(segmentData))
                                                            nextWriteIndex++
                                                            
                                                            totalDownloaded++
                                                            // Update Rich Notification
                                                            download.downloadedSegments = nextWriteIndex
                                                            // Simulate speed update
                                                            download.update(channel.size(), (totalSegments * 1024 * 1024).toLong(), false) 
                                                            notifier.onProgressChange(download)
                                                        }
                                                    }
                                                    success = true
                                                }
                                            }
                                        } catch (e: Exception) {
                                            if (e is CancellationException) throw e
                                            attempt++
                                            if (attempt >= 5) {
                                                logcat(LogPriority.WARN) { "Failed to download segment $index after 5 attempts: ${e.message}" }
                                                synchronized(downloadedSegments) {
                                                    // Add empty byte array to skip this segment but keep sequence
                                                    downloadedSegments[index] = ByteArray(0)
                                                    failedSegments++
                                                }
                                                // Trigger write if this was the next index
                                                synchronized(channel) {
                                                    while (downloadedSegments.containsKey(nextWriteIndex)) {
                                                        val segmentData = downloadedSegments.remove(nextWriteIndex)!!
                                                        if (segmentData.isNotEmpty()) channel.write(ByteBuffer.wrap(segmentData))
                                                        nextWriteIndex++
                                                    }
                                                }
                                            } else {
                                                delay(1000L * attempt)
                                            }
                                        }
                                    }
                                }
                    }
                }
                // Force flush to disk to ensure size check is accurate
                channel.force(true)
            }
            pfd.fileDescriptor.sync()
        }

        // 4. Final Verification (Loosened for resilience)
        val file = tmpDir.findFile("$filename.tmp")?.apply {
            // Only fail if more than 20% of segments are missing
            if (failedSegments > (totalSegments * 0.2)) {
                this.delete()
                throw Exception("Download Failed: Too many segments ($failedSegments/$totalSegments) missing.")
            }
            
            renameTo("$filename.mkv")
        }
        return file ?: throw Exception("Downloaded file not found")
    }

    private fun isTor(video: Video): Boolean {
        return (video.videoUrl.startsWith("magnet") || video.videoUrl.endsWith(".torrent"))
    }

    private suspend fun internalDownload(
        download: Download,
        tmpDir: UniFile,
        filename: String,
    ): UniFile {
        val video = download.video!!
        val videoFile = tmpDir.findFile("$filename.tmp") ?: tmpDir.createFile("$filename.tmp")!!
        
        // 1DM+ Secret: Use a specialized client with zero timeouts for handshake
        val client = networkHelper.downloadClient
        val headers = video.headers ?: download.source.headers
        
        val threadCount = preferences.downloadThreads().get().coerceAtLeast(8)
        val segmentSize = 16 * 1024 * 1024L // 16MB segments for high-speed stability
        val totalDownloaded = java.util.concurrent.atomic.AtomicLong(0)
        val progressMap = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        
        val sizeDeferred = kotlinx.coroutines.CompletableDeferred<Long>()

        context.contentResolver.openFileDescriptor(videoFile.uri, "rw")?.use { pfd ->
            java.io.FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                coroutineScope {
                    // Start Leader: Determination of size and immediate data pull
                    launch(Dispatchers.IO) {
                        try {
                            val request = Request.Builder()
                                .url(video.videoUrl)
                                .headers(headers)
                                .header("Range", "bytes=0-")
                                .build()

                            client.newCall(request).execute().use { response ->
                                if (!response.isSuccessful && response.code != 206) {
                                    sizeDeferred.completeExceptionally(IOException("HTTP ${response.code}"))
                                    return@use
                                }

                                val size = response.header("Content-Range")?.substringAfterLast("/")?.toLongOrNull() 
                                    ?: response.header("Content-Length")?.toLongOrNull() 
                                    ?: -1L
                                
                                if (size <= 0) {
                                    sizeDeferred.completeExceptionally(IOException("Invalid Size"))
                                    return@use
                                }
                                
                                sizeDeferred.complete(size)

                                // Process Leader Segment immediately
                                val body = response.body ?: return@use
                                val bis = body.byteStream()
                                val buffer = ByteArray(512 * 1024) // PRO Buffer
                                var bytesRead: Int
                                var currentPos = 0L
                                
                                while (bis.read(buffer).also { bytesRead = it } != -1) {
                                    coroutineContext.ensureActive()
                                    // Direct write to channel at offset
                                    channel.write(java.nio.ByteBuffer.wrap(buffer, 0, bytesRead), currentPos)
                                    currentPos += bytesRead
                                    
                                    progressMap[0] = currentPos
                                    val currentTotal = totalDownloaded.addAndGet(bytesRead.toLong())
                                    download.update(currentTotal, size, false)
                                    notifier.onProgressChange(download)
                                    
                                    // If we finish the first segment, let the workers handle the rest
                                    if (currentPos >= segmentSize) break
                                }
                            }
                        } catch (e: Exception) {
                            sizeDeferred.completeExceptionally(e)
                        }
                    }

                    // Wait for Size (usually < 300ms)
                    val size = try {
                        sizeDeferred.await()
                    } catch (e: Exception) {
                        throw e
                    }

                    // Workers for Dynamic Parallelism
                    val nextSegmentIndex = java.util.concurrent.atomic.AtomicInteger(1)
                    val totalSegments = (size / segmentSize).toInt() + (if (size % segmentSize > 0L) 1 else 0)
                    download.totalSegments = totalSegments
                    download.downloadedSegments = 1 // Leader segment
                    
                    (1 until threadCount).map { workerId ->
                        launch(Dispatchers.IO) {
                            while (isActive) {
                                val segIdx = nextSegmentIndex.getAndIncrement()
                                if (segIdx >= totalSegments) break
                                
                                val start = segIdx * segmentSize
                                val end = if (segIdx == totalSegments - 1) size - 1 else (segIdx + 1) * segmentSize - 1
                                
                                var attempt = 0
                                var success = false
                                while (attempt < 5 && !success && isActive) {
                                    try {
                                        val request = Request.Builder()
                                            .url(video.videoUrl)
                                            .headers(headers)
                                            .addHeader("Range", "bytes=$start-$end")
                                            .build()

                                        client.newCall(request).execute().use { response ->
                                            if (response.code != 206 && response.code != 200) throw IOException("Code: ${response.code}")
                                            val body = response.body ?: throw IOException("Empty body")
                                            val bis = body.byteStream()
                                            val buffer = ByteArray(512 * 1024) // PRO Buffer
                                            var bytesRead: Int
                                            var workerPos = start
                                            while (bis.read(buffer).also { bytesRead = it } != -1) {
                                                coroutineContext.ensureActive()
                                                channel.write(java.nio.ByteBuffer.wrap(buffer, 0, bytesRead), workerPos)
                                                workerPos += bytesRead
                                                
                                                val currentTotal = totalDownloaded.addAndGet(bytesRead.toLong())
                                                download.update(currentTotal, size, false)
                                                notifier.onProgressChange(download)
                                            }
                                            synchronized(download) {
                                                download.downloadedSegments++
                                            }
                                            success = true
                                        }
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                        attempt++
                                        if (attempt < 5) delay(200L * attempt)
                                    }
                                }
                            }
                        }
                    }
                }
                channel.force(true)
            }
            pfd.fileDescriptor.sync()
        }

        val file = tmpDir.findFile("$filename.tmp")?.apply {
            renameTo("$filename.mkv")
        }
        return file ?: throw Exception("Downloaded file not found")
    }

    private suspend fun torrentDownload(
        download: Download,
        tmpDir: UniFile,
        filename: String,
    ): UniFile {
        val video = download.video!!
        TorrentServerService.start()
        TorrentServerService.wait(10)
        val currentTorrent = TorrentServerApi.addTorrent(video.videoUrl, video.quality, "", "", false)
        var index = 0
        if (video.videoUrl.contains("index=")) {
            index = try {
                video.videoUrl.substringAfter("index=")
                    .substringBefore("&").toInt()
            } catch (_: Exception) {
                0
            }
        }
        val torrentUrl = TorrentServerUtils.getTorrentPlayLink(currentTorrent, index)
        video.videoUrl = torrentUrl
        return ffmpegDownload(download, tmpDir, filename)
    }

    // ffmpeg is always on safe mode
    private suspend fun ffmpegDownload(
        download: Download,
        tmpDir: UniFile,
        filename: String,
    ): UniFile {
        val video = download.video!!

        // always delete tmp file
        tmpDir.findFile("$filename.tmp")?.delete()
        val videoFile = tmpDir.createFile("$filename.tmp")!!

        val ffmpegFilename = { videoFile.uri.toFFmpegString(context) }

        val headers = (video.headers ?: download.source.headers).toMutableList()
        if (headers.none { it.first.equals("User-Agent", ignoreCase = true) }) {
            // Use a standard browser UA for initial mirror handshake to avoid 403
            headers.add("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }
        if (headers.none { it.first.equals("X-Requested-With", ignoreCase = true) }) {
            headers.add("X-Requested-With" to "com.android.chrome")
        }
        
        val headerOptions = headers.joinToString("", "-headers '", "'") {
            "${it.first}: ${it.second}\r\n"
        }
        val originHeader = headers.find { it.first.equals("origin", ignoreCase = true) }?.second
            ?: headers.find { it.first.equals("referer", ignoreCase = true) }?.second?.let {
                val uri = Uri.parse(it)
                "${uri.scheme}://${uri.host}"
            }
        val extendedHeaderOptions = if (originHeader != null) {
            headerOptions.replace("'", "${if (headerOptions.endsWith("\r\n'")) "" else "\r\n"}Origin: $originHeader\r\n'")
        } else {
            headerOptions
        }

        val ffmpegOptions = getFFmpegOptions(video, extendedHeaderOptions, ffmpegFilename())
        val ffprobeCommand = { file: String, ffprobeHeaders: String? ->
            FFmpegKitConfig.parseArguments(
                "${ffprobeHeaders?.plus(" ") ?: ""}-v quiet -show_entries " +
                    "format=duration -of default=noprint_wrappers=1:nokey=1 " +
                    "-analyzeduration 1000000 -probesize 1000000 -timeout 5000000 \"$file\"",
            )
        }

        var duration = 0L

        val logCallback = LogCallback { log ->
            if (log.level <= Level.AV_LOG_WARNING) {
                log.message?.let {
                    logcat(LogPriority.ERROR) { it }
                }
            }
        }

        val statCallback = StatisticsCallback { s ->
            val outTime = (s.time / 1000.0).toLong()

            if (duration != 0L && outTime > 0) {
                download.progress = (100 * outTime / duration).toInt()
            }
            download.updateSpeed(s.size)
        }

        val useExternal = preferences.useExternalDownloader().get() == download.changeDownloader
        val inputDuration = if (useExternal) 0F else getDuration(ffprobeCommand(video.videoUrl, headerOptions)) ?: 0F
        duration = inputDuration.toLong()

        return suspendCancellableCoroutine { continuation ->
            val session = FFmpegKit.executeAsync(
                ffmpegOptions,
                { s ->
                    val file = tmpDir.findFile("$filename.tmp")
                    val downloadedDuration = (s.duration / 1000.0).toLong()
                    val isSuccess = ReturnCode.isSuccess(s.returnCode)
                    
                    // Final Verification (The Anti-Trap)
                    var corruptionError: String? = null
                    
                    if (file != null) {
                        val fileSizeMB = file.length() / (1024 * 1024)
                        val isMovie = download.anime.title.contains("Movie", ignoreCase = true) || 
                                     download.episode.name.contains("Movie", ignoreCase = true) ||
                                     download.episode.name.contains("Full", ignoreCase = true)
                        
                        // Check 1: Duration verification (Crucial for HLS)
                        if (duration > 60) {
                            val tolerance = duration / 20
                            if (downloadedDuration < (duration - tolerance)) {
                                corruptionError = "Download truncated: Expected ${duration}s, got ${downloadedDuration}s"
                            }
                        } else if (isMovie && downloadedDuration < 3600) {
                            corruptionError = "Movie truncated: Only ${downloadedDuration}s downloaded"
                        }
                        
                        // Check 2: Size verification
                        val minSize = if (isMovie) 100 else 10
                        if (fileSizeMB < minSize) {
                            corruptionError = "File too small (${fileSizeMB}MB). Link expired or connection trash."
                        }
                    } else if (isSuccess) {
                        corruptionError = "Downloaded file not found"
                    }

                    if (isSuccess && corruptionError == null) {
                        file?.renameTo("$filename.mkv")
                        if (continuation.isActive) continuation.resume(file!!)
                    } else {
                        file?.delete()
                        val finalError = corruptionError ?: "FFmpeg Error Code: ${s.returnCode}"
                        if (continuation.isActive) continuation.resumeWithException(Exception(finalError))
                    }
                },
                logCallback,
                statCallback,
            )

            continuation.invokeOnCancellation {
                session.cancel()
            }
        }
    }

    private fun getFFmpegOptions(video: Video, headerOptions: String, ffmpegFilename: String): String {
        fun formatInputs(tracks: List<Track>) = tracks.joinToString(" ", postfix = " ") {
            buildList {
                if (it.url.startsWith("http")) {
                    add(headerOptions)
                }
                add("-i")
                add("\"${it.url}\"")
            }.joinToString(" ")
        }

        fun formatMaps(tracks: List<Track>, type: String, offset: Int = 0) = tracks.indices.joinToString(" ") {
            "-map ${it + 1 + offset}:$type"
        }

        fun formatMetadata(tracks: List<Track>, type: String) = tracks.mapIndexed { i, track ->
            "-metadata:s:$type:$i \"title=${track.lang}\""
        }.joinToString(" ")

        val subtitleInputs = formatInputs(video.subtitleTracks)
        val subtitleMaps = formatMaps(video.subtitleTracks, "s")
        val subtitleMetadata = formatMetadata(video.subtitleTracks, "s")

        val audioInputs = formatInputs(video.audioTracks)
        val audioMaps = formatMaps(video.audioTracks, "a", video.subtitleTracks.size)
        val audioMetadata = formatMetadata(video.audioTracks, "a")

        val videoInput = buildList {
            if (video.videoUrl.startsWith("http")) {
                add(headerOptions)
            }
            add("-i")
            add("\"${video.videoUrl}\"")
        }.joinToString(" ")

        val command = listOf(
            "-reconnect 1 -reconnect_at_eof 1 -reconnect_streamed 1 -reconnect_delay_max 30 -rw_timeout 15000000",
            "-reconnect_on_network_error 1 -reconnect_on_http_error 1",
            "-err_detect explode -thread_queue_size 4096",
            videoInput, subtitleInputs, audioInputs,
            "-map 0:v", audioMaps, "-map 0:a?", subtitleMaps, "-map 0:s? -map 0:t?",
            "-f matroska -c:a copy -c:v copy -c:s copy",
            subtitleMetadata, audioMetadata,
            "\"$ffmpegFilename\" -y",
        )
            .filter(String::isNotBlank)
            .joinToString(" ")

        return command
    }

    private fun getDuration(ffprobeCommand: Array<String>): Float? {
        val session = FFprobeSession.create(ffprobeCommand)
        FFmpegKitConfig.ffprobeExecute(session)
        return session.allLogsAsString.trim().toFloatOrNull()
    }

    /**
     * Returns the observable which downloads the video with an external downloader.
     *
     * @param video the video to download.
     * @param source the source of the video.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the video.
     */
    private suspend fun downloadVideoExternal(
        download: Download,
        tmpDir: UniFile,
        filename: String,
    ): UniFile {
        val video = download.video!!
        val source = download.source
        try {
            val file = tmpDir.createFile("${filename}_tmp.mkv")!!
            withUIContext {
                context.copyToClipboard("Episode download location", tmpDir.filePath!!.substringBeforeLast("_tmp"))
            }

            // TODO: support other file formats!!
            // start download with intent
            val pm = context.packageManager
            val pkgName = preferences.externalDownloaderSelection().get()
            val intent: Intent
            if (pkgName.isNotEmpty()) {
                intent = pm.getLaunchIntentForPackage(pkgName) ?: throw Exception(
                    "Launch intent not found",
                )
                when {
                    // 1DM
                    pkgName.startsWith("idm.internet.download.manager") -> {
                        val headers = (video.headers ?: download.source.headers).toMap()
                        val bundle = Bundle()
                        for ((key, value) in headers) {
                            bundle.putString(key, value)
                        }

                        intent.apply {
                            component = ComponentName(
                                pkgName,
                                "idm.internet.download.manager.Downloader",
                            )
                            action = Intent.ACTION_VIEW
                            data = Uri.parse(video.videoUrl)

                            putExtra("extra_filename", "$filename.mkv")
                            putExtra("extra_headers", bundle)
                            // HiAnime and others require these for stable speeds/access
                            headers["Referer"]?.let { putExtra("extra_referer", it) }
                            headers["User-Agent"]?.let { putExtra("extra_user_agent", it) }
                        }
                        
                        // Mark as finished in AniZen to avoid background task conflicts
                        download.status = Download.State.DOWNLOADED
                        removeFromQueue(download)
                        if (areAllDownloadsFinished()) {
                            stop()
                        }
                        // Cleanup placeholder files
                        file.delete()
                        tmpDir.delete()
                    }
                    // ADM
                    pkgName.startsWith("com.dv.adm") -> {
                        val headers = (video.headers ?: source.headers).toList()
                        val bundle = Bundle()
                        headers.forEach { a ->
                            bundle.putString(
                                a.first,
                                a.second.replace("http", "h_ttp"),
                            )
                        }

                        intent.apply {
                            component = ComponentName(pkgName, "$pkgName.AEditor")
                            action = Intent.ACTION_VIEW
                            putExtra(
                                "com.dv.get.ACTION_LIST_ADD",
                                "${Uri.parse(video.videoUrl)}<info>$filename.mkv",
                            )
                            putExtra(
                                "com.dv.get.ACTION_LIST_PATH",
                                tmpDir.filePath!!.substringBeforeLast("_"),
                            )
                            putExtra("android.media.intent.extra.HTTP_HEADERS", bundle)
                        }
                        // Mark as finished in AniZen to avoid background task conflicts
                        download.status = Download.State.DOWNLOADED
                        removeFromQueue(download)
                        if (areAllDownloadsFinished()) {
                            stop()
                        }
                        // Cleanup placeholder files
                        file.delete()
                        tmpDir.delete()
                    }
                }
            } else {
                intent = Intent(Intent.ACTION_VIEW).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setDataAndType(Uri.parse(video.videoUrl), "video/*")
                    putExtra("extra_filename", filename)
                }
            }
            context.startActivity(intent)
            return file
        } catch (e: Exception) {
            tmpDir.findFile("${filename}_tmp.mkv")?.delete()
            throw e
        }
    }

    /**
     * Checks if the download was successful.
     *
     * @param download the download to check.
     * @param animeDir the anime directory of the download.
     * @param tmpDir the directory where the download is currently stored.
     * @param dirname the real (non temporary) directory name of the download.
     */
    private suspend fun ensureSuccessfulAnimeDownload(
        download: Download,
        animeDir: UniFile,
        tmpDir: UniFile,
        dirname: String,
    ) {
        // Ensure that the episode folder has the full video
        val downloadedVideo = tmpDir.listFiles().orEmpty().filterNot { it.extension == ".tmp" }

        download.status = if (downloadedVideo.size == 1) {
            // Only rename the directory if it's downloaded
            val filename = DiskUtil.buildValidFilename("${download.anime.title} - ${download.episode.name}")
            tmpDir.findFile("${filename}_tmp.mkv")?.delete()
            tmpDir.renameTo(dirname)

            cache.addEpisode(dirname, animeDir, download.anime)

            DiskUtil.createNoMediaFile(tmpDir, context)
            Download.State.DOWNLOADED
        } else {
            throw Exception("Unable to finalize download")
        }
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Download.State.DOWNLOADING.value }
    }

    private fun addAllToQueue(downloads: List<Download>) {
        _queueState.update {
            downloads.forEach { download ->
                download.status = Download.State.QUEUE
            }
            store.addAll(downloads)
            it + downloads
        }
    }

    private fun removeFromQueue(download: Download) {
        _queueState.update {
            store.remove(download)
            if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                download.status = Download.State.NOT_DOWNLOADED
            }
            it - download
        }
    }

    private inline fun removeFromQueueIf(predicate: (Download) -> Boolean) {
        _queueState.update { queue ->
            val downloads = queue.filter { predicate(it) }
            store.removeAll(downloads)
            downloads.forEach { download ->
                if (download.status == Download.State.DOWNLOADING ||
                    download.status == Download.State.QUEUE
                ) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            queue - downloads.toSet()
        }
    }

    fun removeFromQueue(episodes: List<Episode>) {
        val episodeIds = episodes.map { it.id }
        removeFromQueueIf { it.episode.id in episodeIds }
    }

    fun removeFromQueue(anime: Anime) {
        removeFromQueueIf { it.anime.id == anime.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { download ->
                if (download.status == Download.State.DOWNLOADING ||
                    download.status == Download.State.QUEUE
                ) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            store.clear()
            emptyList()
        }
    }

    fun updateQueue(downloads: List<Download>) {
        if (queueState == downloads) return

        if (downloads.isEmpty()) {
            clearQueue()
            stop()
            return
        }

        val wasRunning = isRunning

        pause()
        internalClearQueue()
        addAllToQueue(downloads)

        if (wasRunning) {
            start()
        }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
        const val EPISODES_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 500
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 500
    }
}

// Arbitrary minimum required space to start a download: 200 MB
private const val MIN_DISK_SPACE = 200L * 1024 * 1024
