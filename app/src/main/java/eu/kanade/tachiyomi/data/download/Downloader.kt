package eu.kanade.tachiyomi.data.download

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import androidx.annotation.RequiresApi
import com.hippo.unifile.UniFile
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.util.storage.toFFmpegString
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.torrentServer.service.TorrentServerService
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import tachiyomi.core.common.util.system.logcat
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.subtitles.StremioSubtitleResolver
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.activeNetworkState
import okhttp3.Headers
import okhttp3.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Pro-Level Downloader matching 1DM+ Architecture.
 * Features: FilesDir Sandbox, Startup Truncation, Micro-Chunk Queueing, Jittered Exponential Backoff.
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

    private val memorySemaphore = Semaphore(12)
    private val ffmpegMutex = kotlinx.coroutines.sync.Mutex()
    private val notifier by lazy { DownloadNotifier(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloaderJob: Job? = null
    private val activeDownloads = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    
    private val _isRunningFlow = MutableStateFlow(false)
    val isRunningFlow = _isRunningFlow.asStateFlow()

    val isRunning: Boolean
        get() = _isRunningFlow.value

    val isLocalPhase: Boolean
        get() = activeDownloads.keys.isNotEmpty() && activeDownloads.keys.all { id ->
            val download = queueState.value.find { it.episode.id == id }
            download?.status == Download.State.MERGING || 
            download?.status == Download.State.DECRYPTING || 
            download?.status == Download.State.FINALIZING
        }

    init {
        launchIO {
            sourceManager.isInitialized.first { it }
            val downloads = store.restore()
            addAllToQueue(downloads)
            sweepOrphanedFiles(downloads) // Fire the janitor on startup
        }
    }

    private fun isLocalFile(file: UniFile): Boolean {
        return file.uri.scheme == "file" || file.filePath != null
    }

    private fun getLocalFile(file: UniFile): File? {
        return when {
            file.uri.scheme == "file" -> File(file.uri.path!!)
            file.filePath != null -> File(file.filePath!!)
            else -> null
        }
    }

    private fun calculateDynamicConcurrency(host: String): Int {
        if (host.contains("animepahe") || host.contains("sibnet") || host.contains("video.sibnet")) return 1 // Adaptive: Hosters failing with multi-threading
        
        val userThreads = preferences.downloadThreads().get().coerceAtLeast(1)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        return if (activityManager?.isLowRamDevice == true) userThreads.coerceIn(1, 4) else userThreads.coerceIn(1, 64)
    }

    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) return false
        
        // Resume paused or interrupted downloads by marking them as QUEUE
        _queueState.update { 
            it.forEach { download ->
                if (download.status == Download.State.PAUSED ||
                    download.status == Download.State.DOWNLOADING ||
                    download.status == Download.State.MERGING ||
                    download.status == Download.State.DECRYPTING ||
                    download.status == Download.State.FINALIZING) {
                    download.status = Download.State.QUEUE
                }
            }
            it
        }

        _isRunningFlow.value = true
        DownloadJob.start(context)
        downloaderJob = scope.launch {
            // Dynamic Queue Processing
            while (isRunning) {
                val maxConcurrency = preferences.concurrentDownloads().get().coerceAtLeast(1)
                
                // Clean up completed jobs
                activeDownloads.entries.removeIf { !it.value.isActive }

                if (activeDownloads.size >= maxConcurrency) {
                    delay(500)
                    continue
                }

                val download = queueState.value.firstOrNull { 
                    it.status == Download.State.QUEUE && !activeDownloads.containsKey(it.episode.id)
                } 
                
                if (download == null) {
                    if (activeDownloads.isEmpty()) break
                    delay(500)
                    continue
                }

                if (isNetworkConstraintFailed()) {
                    stop(getNetworkConstraintErrorString())
                    break
                }

                download.status = Download.State.DOWNLOADING
                notifyProgress(download)
                
                val job = launch {
                    try {
                        downloadEpisode(download)
                    } catch (e: Exception) {
                        if (e is CancellationException || e.cause is CancellationException) {
                            logcat(LogPriority.INFO) { "Individual download cancelled: ${download.episode.name}" }
                        } else {
                            logcat(LogPriority.ERROR, e)
                            download.status = Download.State.ERROR
                            notifyProgress(download)
                            notifier.onError(e.message)
                        }
                    } finally {
                        activeDownloads.remove(download.episode.id)
                    }
                }
                activeDownloads[download.episode.id] = job
                
                delay(100) // Cooling period to prevent CPU spikes on rapid failures
            }

            _isRunningFlow.value = false
            if (queueState.value.none { it.status == Download.State.QUEUE || it.status == Download.State.DOWNLOADING }) {
                notifier.onComplete()
            } else {
                notifier.onPaused()
            }
            DownloadJob.stop(context)
        }
        return true
    }

    fun stop(reason: String? = null) {
        if (reason != null && isLocalPhase) {
            logcat(LogPriority.INFO) { "Network offline, but keeping downloader running for local phase (merging/decrypting/finalizing)" }
            return
        }
        _isRunningFlow.value = false
        downloaderJob?.cancel()
        downloaderJob = null
        activeDownloads.clear()
        
        val hasMoreToDownload = queueState.value.any { 
            it.status == Download.State.QUEUE || 
            it.status == Download.State.DOWNLOADING ||
            it.status == Download.State.MERGING ||
            it.status == Download.State.DECRYPTING ||
            it.status == Download.State.FINALIZING
        }

        _queueState.update {
            it.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || 
                    download.status == Download.State.QUEUE ||
                    download.status == Download.State.MERGING ||
                    download.status == Download.State.DECRYPTING ||
                    download.status == Download.State.FINALIZING) {
                    download.interruptedState = download.status
                    download.status = Download.State.PAUSED
                    notifier.dismissProgress(download)
                }
            }
            it
        }

        if (reason != null) notifier.onWarning(reason)
        else if (hasMoreToDownload) notifier.onPaused()
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
        activeDownloads.clear()
        _queueState.update {
            it.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || 
                    download.status == Download.State.QUEUE ||
                    download.status == Download.State.MERGING ||
                    download.status == Download.State.DECRYPTING ||
                    download.status == Download.State.FINALIZING) {
                    download.interruptedState = download.status
                    download.status = Download.State.PAUSED
                    notifier.dismissProgress(download)
                }
            }
            it
        }
        notifier.onPaused()
    }

    fun dismissAll() {
        notifier.dismissAll()
    }

    fun clearQueue() {
        _isRunningFlow.value = false
        downloaderJob?.cancel()
        downloaderJob = null
        activeDownloads.clear()
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
        stop()
    }

    fun updateQueue(downloads: List<Download>) {
        val wasRunning = isRunning

        if (downloads.isEmpty()) {
            clearQueue()
            stop()
            return
        }

        if (wasRunning) {
            pause()
        }

        _queueState.value = downloads
        store.addAll(downloads)

        if (wasRunning) {
            start()
        }
    }

    fun queueEpisodes(anime: Anime, episodes: List<Episode>, autoStart: Boolean, alt: Boolean = false, video: Video? = null) {
        val source = sourceManager.get(anime.source) as? HttpSource ?: return
        val downloads = episodes.map { Download(source, anime, it, alt, video) }
        addAllToQueue(downloads)
        if (autoStart) start()
    }

    fun addAllToQueue(downloads: List<Download>) {
        _queueState.update { current ->
            val new = current.toMutableList()
            downloads.forEach { download ->
                val existing = new.find { it.episode.id == download.episode.id }
                if (existing != null) {
                    if (existing.status == Download.State.PAUSED || existing.status == Download.State.ERROR) {
                        existing.status = Download.State.QUEUE
                    }
                } else {
                    download.status = Download.State.QUEUE
                    new.add(download)
                }
            }
            store.addAll(new)
            new
        }
        start()
    }

    fun removeFromQueue(anime: Anime) {
        val activeIds = activeDownloads.keys.toList()
        queueState.value.filter { it.anime.id == anime.id }.forEach {
            if (it.episode.id in activeIds) {
                activeDownloads[it.episode.id]?.cancel()
                activeDownloads.remove(it.episode.id)
            }
            notifier.dismissProgress(it)
        }
        _queueState.update { current ->
            val new = current.filterNot { it.anime.id == anime.id }
            store.removeAll(current.filter { it.anime.id == anime.id })
            new
        }
        if (_queueState.value.isEmpty()) stop()
    }

    fun removeFromQueue(episodes: List<Episode>) {
        val episodeIds = episodes.map { it.id }
        queueState.value.filter { it.episode.id in episodeIds }.forEach {
            if (it.episode.id in activeDownloads.keys) {
                activeDownloads[it.episode.id]?.cancel()
                activeDownloads.remove(it.episode.id)
            }
            notifier.dismissProgress(it)
        }
        _queueState.update { current ->
            val new = current.filterNot { it.episode.id in episodeIds }
            store.removeAll(current.filter { it.episode.id in episodeIds })
            new
        }
        if (_queueState.value.isEmpty()) stop()
    }

    private fun notifyProgress(download: Download) {
        _queueState.update { it }
        notifier.onProgressChange(download)
    }

    private fun isNetworkConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun <T> retry(
        times: Int = 5,
        initialDelay: Long = 1000,
        maxDelay: Long = 15000,
        factor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        repeat(times - 1) { attempt ->
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                
                // Fast-Fail: Check internet before each retry
                if (!isNetworkConnected()) {
                    throw IOException("No internet connection")
                }
                
                return block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                
                // FATAL ERROR CHECK: Do not retry dead/forbidden links
                if (e is HttpException) {
                    val code = e.code
                    if (code == 401 || code == 403 || code == 404 || code == 410) {
                        logcat(LogPriority.ERROR) { "Fatal HTTP $code. Aborting retry." }
                        throw e 
                    }
                }
                
                // Exponential Backoff with Jitter
                val jitter = Random.nextLong(0, 500)
                val backoff = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
                delay(backoff + jitter)
                currentDelay = backoff
                logcat(LogPriority.WARN) { "Retry attempt ${attempt + 1} failed for network reason, backing off..." }
            }
        }
        return block()
    }

    private fun sweepOrphanedFiles(activeDownloads: List<Download>) {
        launchIO {
            try {
                val sandboxRoot = context.getExternalFilesDir("downloads") ?: return@launchIO
                if (!sandboxRoot.exists()) return@launchIO
                
                // Map the valid, active download directory names
                val expectedDirs = activeDownloads.map { 
                    provider.getEpisodeDirName(it.episode.name, it.episode.scanlator) 
                }.toSet()

                // Sweep the sandbox directory
                sandboxRoot.listFiles()?.forEach { file ->
                    if (file.isDirectory && file.name !in expectedDirs) {
                        logcat(LogPriority.INFO) { "Janitor Protocol: Deleting orphaned sandbox directory: ${file.name}" }
                        file.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to sweep orphaned files" }
            }
        }
    }

    private fun detectEngineType(video: Video): String {
        return when {
            video.videoUrl.startsWith("magnet") || video.videoUrl.endsWith(".torrent") -> "Torrent"
            video.videoUrl.contains(".m3u8", ignoreCase = true) ||
            // Hanime's signed HLS endpoint is extensionless: /hls/{id}/{token}
            video.videoUrl.contains("/hls/", ignoreCase = true) ||
            video.videoUrl.contains("/oppai/") ||
            video.videoUrl.contains("/proxy/oppai/") -> "HLS"
            video.videoUrl.contains(".mpd") || 
            (video.videoUrl.contains("/playback/") && !video.videoUrl.contains(".mp4")) || 
            video.audioTracks.isNotEmpty() -> "DASH"
            else -> "Normal"
        }
    }

    private suspend fun downloadEpisode(download: Download) {
        val previousState = download.interruptedState ?: download.status
        download.interruptedState = null

        val animeDir = provider.getAnimeDir(download.anime.ogTitle, download.source)
        val episodeDirname = provider.getEpisodeDirName(download.episode.name, download.episode.scanlator)
        
        // Sandbox Storage: Protected from OS Cache cleanup
        val sandboxDir = File(context.getExternalFilesDir("downloads"), episodeDirname)
        if (!sandboxDir.exists() && !sandboxDir.mkdirs()) {
            throw IOException("Failed to create sandbox directory: ${sandboxDir.absolutePath}")
        }

        // Prepare Destination Directory (Atomic _tmp approach) early to allow direct merging
        val tmpEpisodeDirname = episodeDirname + TMP_DIR_SUFFIX
        var destDir = animeDir.findFile(tmpEpisodeDirname)
        if (destDir == null) {
            destDir = animeDir.createDirectory(tmpEpisodeDirname)!!
        }

        val videoFilename = DiskUtil.buildValidFilename(download.episode.name)

        notifier.onProgressChange(download)
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            
            val finalExt = if (download.video?.videoUrl?.contains(".mp4") == true) "mp4" else "mkv"
            val mergedFile = File(sandboxDir, "$videoFilename.tmp")

            // RECOVERY: Handle interrupted FINALIZING state
            if (previousState == Download.State.FINALIZING && mergedFile.exists()) {
                finalizeDownload(download, UniFile.fromFile(mergedFile)!!, animeDir, episodeDirname)
                return
            }
            
            // RECOVERY: Handle interrupted MERGING state
            // If .part files exist in sandbox, the destination file is likely corrupted/incomplete
            val hasSandboxParts = sandboxDir.listFiles()?.any { it.name.contains(".part") } == true
            if (previousState == Download.State.MERGING && hasSandboxParts) {
                logcat(LogPriority.WARN) { "Recovery: Interrupted merge detected for ${download.episode.name}. Cleaning destination." }
                destDir.findFile("$videoFilename.$finalExt")?.delete()
                destDir.findFile("$videoFilename.ts")?.delete()
            }

            download.status = Download.State.DOWNLOADING
            notifyProgress(download)
            val video = retry {
                download.video?.takeIf { it.videoUrl.isRemote() } ?: run {
                    val hosters = EpisodeLoader.getHosters(
                        download.episode,
                        download.anime,
                        download.source as AnimeSource,
                        allowDownloaded = false,
                    )
                    val defaultSelector = eu.kanade.tachiyomi.ui.player.utils.DefaultStreamPreferenceStore(
                        Injekt.get<eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences>()
                    ).getEffectiveSelector(download.anime.id)
                    if (defaultSelector.isNotBlank()) {
                        HosterLoader.resolveDefaultStream(download.source as AnimeSource, hosters, defaultSelector)
                    } else {
                        null
                    } ?: HosterLoader.getBestVideo(download.source as AnimeSource, hosters)
                } ?: throw Exception(context.stringResource(MR.strings.video_list_empty_error))
            }.also { download.video = it }

            var downloadHttpServer: HttpServer? = null
            try {
                if (video.usesHttpServer() && download.source is AnimeHttpSource) {
                    val httpSource = download.source as AnimeHttpSource
                    val port = httpSource.createHttpServer()?.let { server ->
                        downloadHttpServer = server
                        server.start()
                        server.listeningPort
                    } ?: 0
                    if (port > 0) {
                        val rewritten = video.copyHttpServer(port)
                        download.video = rewritten
                    }
                }

                val effectiveVideo = download.video ?: video

                // Recompute after every resolution. A restored/retried download may retain an
                // engine selected for an old URL (for example, Normal before a Hanime HLS URL).
                download.engineType = detectEngineType(effectiveVideo)

                // Check again for cancellation after slow network call
                kotlinx.coroutines.currentCoroutineContext().ensureActive()

                // Download soft subtitles EARLY and make them NON-FATAL
                try {
                    downloadSubtitles(effectiveVideo, sandboxDir, videoFilename)
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Subtitles failed but continuing download: ${e.message}" }
                }

                if (download.changeDownloader) {
                    val success = externalDownload(download, animeDir, episodeDirname)
                    if (success) return else throw Exception("Could not open external downloader")
                }

                val videoFile = when (download.engineType) {
                    "Torrent" -> torrentDownload(download, sandboxDir, videoFilename)
                    "HLS" -> nativeHlsDownload(download, sandboxDir, videoFilename)
                    "DASH" -> UniFile.fromFile(nativeDashMuxDownload(download, sandboxDir, videoFilename))!!
                    else -> internalDownload(download, sandboxDir, videoFilename)
                }

                if (videoFile.length() <= 0L) {
                    videoFile.delete()
                    throw IOException("Downloaded video is empty")
                }

                finalizeDownload(download, videoFile, animeDir, episodeDirname)
            } finally {
                downloadHttpServer?.stop()
            }
            
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Download failed" }
            if (e !is CancellationException) {
                download.status = Download.State.ERROR
                notifyProgress(download)
                notifier.onError(e.message)
            }
        }
    }

    private fun checkFreeSpace(dir: File, requiredSize: Long) {
        val stats = StatFs(dir.absolutePath)
        val available = stats.availableBlocksLong * stats.blockSizeLong
        if (available < requiredSize + MIN_DISK_SPACE) {
            throw IOException(context.stringResource(MR.strings.download_insufficient_space))
        }
    }

    private suspend fun downloadSubtitles(video: Video, sandboxDir: File, videoFilename: String) {
        if (video.subtitleTracks.isEmpty()) return

        val client = networkHelper.client
        val headers = getHeaders(video)
        coroutineScope {
            video.subtitleTracks.forEach { originalTrack ->
                launch {
                    val resolvedTracks = StremioSubtitleResolver.resolve(originalTrack, headers)
                    resolvedTracks.forEach { track ->
                        val cleanUrl = track.url.substringBefore("?")
                        val subExt = when {
                            cleanUrl.endsWith(".vtt", ignoreCase = true) -> "vtt"
                            cleanUrl.endsWith(".ass", ignoreCase = true) -> "ass"
                            else -> "srt"
                        }
                        val filename = "${videoFilename}.${track.lang}.$subExt"
                        val subFile = File(sandboxDir, filename)
                        if (subFile.exists() && subFile.length() > 0) return@forEach

                        if (track.url.isBlank()) return@forEach

                        if (track.url.startsWith("file://")) {
                            try {
                                val sourceFile = File(track.url.removePrefix("file://"))
                                if (sourceFile.exists()) {
                                    sourceFile.inputStream().use { input ->
                                        java.io.FileOutputStream(subFile).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR, e) { "Failed to copy local subtitle: ${track.url}" }
                            }
                            return@forEach
                        }

                        retry(times = 3) {
                            val req = Request.Builder().url(track.url).headers(headers).build()
                            client.newCall(req).execute().use { res ->
                                if (!res.isSuccessful) throw IOException("Failed to download subtitle: ${res.code}")
                                res.body?.byteStream()?.use { input ->
                                    java.io.FileOutputStream(subFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun finalizeDownload(download: Download, videoFile: UniFile, publicDir: UniFile, filename: String) {
        download.status = Download.State.FINALIZING
        download.progress = 0
        notifyProgress(download)

        val videoFilename = DiskUtil.buildValidFilename(download.episode.name)
        val finalExt = if (download.video?.videoUrl?.contains(".mp4") == true) "mp4" else "mkv"
        val finalName = "$videoFilename.$finalExt"

        // Create temporary episode directory
        val tmpFilename = filename + TMP_DIR_SUFFIX
        var destDir = publicDir.findFile(tmpFilename)
        if (destDir == null) {
            destDir = publicDir.createDirectory(tmpFilename)!!
        }

        // Check if the file is already in the destination (e.g. direct merge)
        var destFile = destDir.findFile(finalName)
        
        val isAlreadyAtDestination = destFile != null && destFile.uri == videoFile.uri
        
        if (!isAlreadyAtDestination) {
            // CRITICAL: Prevent file bloating
            if (destFile != null) destFile.delete()
            destDir.findFile("$videoFilename.tmp")?.delete()

            // FAST-PATH: Instant Rename if both are on local storage
            val localSource = getLocalFile(videoFile)
            val localDestDir = getLocalFile(destDir)
            
            if (localSource != null && localDestDir != null) {
                val localDestFile = File(localDestDir, finalName)
                if (localSource.renameTo(localDestFile)) {
                    logcat(LogPriority.INFO) { "Finalize: Instant rename success: ${localSource.name} -> ${localDestFile.name}" }
                } else {
                    // Fallback to byte copy if rename fails
                    copyUniFile(videoFile, destDir.createFile(finalName)!!, download)
                }
            } else {
                // SAF Path: Direct byte-by-byte copy
                copyUniFile(videoFile, destDir.createFile(finalName)!!, download)
            }
        }

        // Pro-Active: Move soft subtitles to the destination directory
        val sandboxDir = getLocalFile(videoFile)?.parentFile ?: File(context.getExternalFilesDir("downloads"), filename)
        val baseName = videoFilename
        sandboxDir.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension.startsWith(baseName) && file.name != videoFile.name && 
                !file.name.endsWith(".part") && !file.name.endsWith(".tmp")) {
                val subFile = destDir.createFile(file.name)
                if (subFile != null) {
                    file.inputStream().use { input ->
                        subFile.openOutputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
        
        // Finalize: Rename directory to final name
        val finalDir = publicDir.findFile(filename)
        finalDir?.delete() // Cleanup if somehow exists
        destDir.renameTo(filename)
        
        if (isLocalFile(videoFile)) {
            getLocalFile(videoFile)?.parentFile?.deleteRecursively()
        }

        download.status = Download.State.DOWNLOADED
        notifyProgress(download)
        
        _queueState.update { it - download }
        store.remove(download)
        notifier.dismissProgress(download)

        cache.addEpisode(filename, publicDir, download.anime)
    }

    private suspend fun copyUniFile(source: UniFile, dest: UniFile, download: Download) {
        source.openInputStream().use { input ->
            dest.openOutputStream().use { output ->
                val buffer = ByteArray(8 * 1024 * 1024)
                var bytesCopied = 0L
                val totalBytes = source.length()
                var read: Int
                var lastUpdate = System.currentTimeMillis()
                
                while (input.read(buffer).also { read = it } != -1) {
                    coroutineContext.ensureActive()
                    output.write(buffer, 0, read)
                    bytesCopied += read
                    
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 1000 || bytesCopied == totalBytes) {
                        download.progress = ((bytesCopied.toDouble() / totalBytes.coerceAtLeast(1L)) * 100).toInt()
                        notifier.onProgressChange(download)
                        store.update(download)
                        lastUpdate = now
                    }
                }
            }
        }
    }

    private fun getHeaders(video: Video): Headers {
        val builder = (video.headers ?: Headers.headersOf()).newBuilder()
        if (builder.get("User-Agent") == null) {
            builder.add("User-Agent", networkHelper.defaultUserAgentProvider())
        }
        // PRO-LEVEL: Strip internal security headers that break cross-origin CDN requests
        builder.removeAll("Sec-Fetch-Dest")
        builder.removeAll("Sec-Fetch-Mode")
        builder.removeAll("Sec-Fetch-Site")
        builder.removeAll("Sec-Fetch-User")
        builder.removeAll("X-Requested-With")
        return builder.build()
    }

    private suspend fun internalDownload(download: Download, sandboxDir: File, filename: String): UniFile {
        val video = download.video!!
        
        // Scheme Validation: this engine fetches over OkHttp, so only http(s) is usable. A local
        // URI here means the video resolved to an existing download instead of the source's stream.
        if (!video.videoUrl.startsWith("http", ignoreCase = true)) {
            throw IllegalArgumentException(
                "Cannot download from non-HTTP URL (scheme: ${video.videoUrl.substringBefore(":")})",
            )
        }

        val client = networkHelper.downloadClient
        val host = Uri.parse(video.videoUrl).host ?: ""
        val threadCount = calculateDynamicConcurrency(host)
        val headers = getHeaders(video)
        
        // Instant Startup: Use cached size if available, otherwise probe in parallel
        var size = download.totalSize
        if (size <= 0) {
            try {
                client.newCall(Request.Builder().url(video.videoUrl).headers(headers).head().build()).execute().use { res ->
                    size = if (res.isSuccessful) res.header("Content-Length")?.toLongOrNull() ?: -1L else -1L
                }
            } catch (e: Exception) {
                logcat(LogPriority.DEBUG) { "HEAD request failed: ${e.message}" }
            }
            
            // Pro-Active: Fallback to partial GET if HEAD failed (Sibnet/sensitive hoster support)
            if (size <= 0) {
                try {
                    client.newCall(Request.Builder().url(video.videoUrl).headers(headers).header("Range", "bytes=0-0").build()).execute().use { res ->
                        val contentRange = res.header("Content-Range")
                        size = if (contentRange != null) {
                            contentRange.substringAfterLast("/").toLongOrNull() ?: -1L
                        } else {
                            // If server returned 200 OK instead of 206 Partial Content
                            res.header("Content-Length")?.toLong() ?: -1L
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.DEBUG) { "Fallback GET failed: ${e.message}" }
                }
            }
            
            download.totalSize = size
        }

        if (size > 0) checkFreeSpace(sandboxDir, size)
        
        download.activeThreads = threadCount

        val finalExt = if (download.video?.videoUrl?.contains(".mp4") == true) "mp4" else "mkv"
        val finalFile = File(sandboxDir, "$filename.tmp")
        val downloadedBytes = LongAdder()

        if (size > 0 && threadCount > 1) {
            download.partProgress.clear()
            val partSize = size / threadCount
            coroutineScope {
                (0 until threadCount).map { i ->
                    async {
                        val partFile = File(sandboxDir, "$filename.part$i")
                        var localDownloaded = partFile.length()
                        downloadedBytes.add(localDownloaded)
                        
                        val partTotalSize = if (i == threadCount - 1) size - (i * partSize) else partSize
                        download.partProgress[i] = (localDownloaded.toDouble() / partTotalSize.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)

                        retry(times = 5) {
                            val start = i * partSize + localDownloaded
                            val end = if (i == threadCount - 1) size - 1 else (i + 1) * partSize - 1
                            
                            // Server-Side Safety: Skip if part is already finished
                            if (start > end) {
                                download.partProgress[i] = 1f
                                return@retry
                            }

                            val req = Request.Builder().url(video.videoUrl).headers(headers)
                                .header("Range", "bytes=$start-$end").build()
                            client.newCall(req).execute().use { res ->
                                if (!res.isSuccessful) throw IOException("Unexpected code $res")
                                val source = res.body?.source() ?: throw IOException("Empty body")
                                java.io.FileOutputStream(partFile, true).use { out ->
                                    val buffer = BufferPool.obtain()
                                    try {
                                        var read: Int
                                        var lastUpdate = System.currentTimeMillis()
                                        while (source.read(buffer).also { read = it } != -1) {
                                            coroutineContext.ensureActive()
                                            if (download.status == Download.State.PAUSED) throw CancellationException()
                                            out.write(buffer, 0, read)
                                            localDownloaded += read
                                            downloadedBytes.add(read.toLong())

                                            download.partProgress[i] = (localDownloaded.toDouble() / partTotalSize.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)

                                            val now = System.currentTimeMillis()
                                            if (now - lastUpdate > 500) {
                                                download.update(downloadedBytes.sum(), size, false)
                                                notifier.onProgressChange(download)
                                                store.update(download)
                                                lastUpdate = now
                                            }
                                        }
                                    } finally {
                                        BufferPool.recycle(buffer)
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            download.status = Download.State.MERGING
            download.progress = 0
            notifyProgress(download)

            java.io.FileOutputStream(finalFile).use { outStream ->
                val outChannel = outStream.channel
                val partFiles = (0 until threadCount).map { File(sandboxDir, "$filename.part$it") }
                mergeChannels(partFiles, outChannel, download)
            }
            return UniFile.fromFile(finalFile)!!
        } else {
            // Robust Single-Threaded/Unknown Size Downloader
            download.partProgress.clear()
            retry {
                val start = if (finalFile.exists()) finalFile.length() else 0L
                if (size > 0) download.partProgress[0] = (start.toFloat() / size).coerceIn(0f, 1f)
                
                val reqBuilder = Request.Builder().url(video.videoUrl).headers(headers)
                if (start > 0) reqBuilder.header("Range", "bytes=$start-")
                
                client.newCall(reqBuilder.build()).execute().use { res ->
                    if (!res.isSuccessful) throw IOException("Unexpected code $res")

                    // Handle 200 OK when 206 was requested (server doesn't support Range)
                    val isResuming = start > 0 && res.code == 206
                    val append = isResuming
                    val actualStart = if (isResuming) start else 0L
                    
                    val source = res.body?.source() ?: throw IOException("Empty body")
                    java.io.FileOutputStream(finalFile, append).use { out ->
                        val buffer = BufferPool.obtain()
                        try {
                            var read: Int
                            var totalRead = actualStart
                            var lastUpdate = System.currentTimeMillis()
                            while (source.read(buffer).also { read = it } != -1) {
                                coroutineContext.ensureActive()
                                if (download.status == Download.State.PAUSED) throw CancellationException()
                                
                                out.write(buffer, 0, read)
                                totalRead += read
                                
                                if (size > 0) download.partProgress[0] = (totalRead.toFloat() / size).coerceIn(0f, 1f)

                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 500) {
                                    download.update(totalRead, size, false)
                                    notifier.onProgressChange(download)
                                    lastUpdate = now
                                }
                            }
                        } finally {
                            BufferPool.recycle(buffer)
                        }
                    }
                }
            }
            return UniFile.fromFile(finalFile)!!
        }
    }

    private suspend fun nativeHlsDownload(download: Download, sandboxDir: File, filename: String): UniFile {
        val video = download.video!!
        val client = networkHelper.downloadClient
        val headers = getHeaders(video)

        var playlistUrl = video.videoUrl
        var lines: List<String>

        // RECURSIVE RESOLUTION: Handle Master Playlists by picking the first variant
        while (true) {
            val playlistRes = client.newCall(Request.Builder().url(playlistUrl).headers(headers).build()).execute()
            if (!playlistRes.isSuccessful) throw IOException("Failed to fetch playlist: ${playlistRes.code}")
            lines = playlistRes.body?.string()?.lines() ?: emptyList()
            if (lines.none { it.trimStart().startsWith("#EXTM3U") }) {
                throw IOException("Hanime returned an invalid HLS playlist")
            }

            val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
            if (isMaster) {
                val baseUrl = playlistUrl.substringBeforeLast("/") + "/"
                val subUrl = lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    ?: throw IOException("No variant playlist found in master playlist")

                playlistUrl = if (subUrl.startsWith("http")) subUrl else baseUrl + subUrl
                logcat(LogPriority.INFO) { "HLS Engine: Resolved master playlist to variant: $playlistUrl" }
                continue
            }
            break
        }

        val baseUrl = playlistUrl.substringBeforeLast("/") + "/"
        val segments = mutableListOf<String>()
        var encryptionKeyUrl: String? = null
        var mediaSequence = 0

        // Extract correct Media Sequence and AES Key
        for (line in lines) {
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = line.substringAfter(":").toIntOrNull() ?: 0
            } else if (line.startsWith("#EXT-X-KEY:METHOD=AES-128")) {
                val match = Regex("URI=\"([^\"]+)\"").find(line)
                encryptionKeyUrl = match?.groupValues?.get(1)
                if (encryptionKeyUrl != null && !encryptionKeyUrl.startsWith("http")) {
                    encryptionKeyUrl = baseUrl + encryptionKeyUrl
                }
            } else if (!line.startsWith("#") && line.isNotBlank()) {
                segments.add(if (line.startsWith("http")) line else baseUrl + line)
            }
        }

        if (segments.isEmpty()) throw IOException("No segments found in HLS playlist")
        download.totalSegments = segments.size
        
        var secretKey: javax.crypto.spec.SecretKeySpec? = null
        if (encryptionKeyUrl != null) {
            val keyRes = client.newCall(Request.Builder().url(encryptionKeyUrl).headers(headers).build()).execute()
            val keyBytes = keyRes.body?.bytes() ?: throw IOException("Failed to fetch AES key")
            secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        }

        val downloadedCount = java.util.concurrent.atomic.LongAdder()
        val downloadedBytes = java.util.concurrent.atomic.LongAdder()
        val segmentQueue = segments.mapIndexed { index, url -> index to url }.toMutableList()
        var lastUpdate = System.currentTimeMillis()
        
        val host = Uri.parse(video.videoUrl).host ?: ""
        val threadCount = calculateDynamicConcurrency(host)
        download.activeThreads = threadCount

        coroutineScope {
            repeat(threadCount) {
                launch {
                    while (isActive) {
                        if (download.status == Download.State.PAUSED) break
                        val seg = synchronized(segmentQueue) { if (segmentQueue.isNotEmpty()) segmentQueue.removeAt(0) else null } ?: break
                        val segmentFile = File(sandboxDir, "seg_${seg.first}.part")

                        if (segmentFile.exists() && segmentFile.length() > 0) {
                            downloadedCount.increment()
                            downloadedBytes.add(segmentFile.length())
                            download.segmentProgress[seg.first] = true
                            continue
                        }

                        retry(times = 5) {
                            client.newCall(Request.Builder().url(seg.second).headers(headers).build()).execute().use { res ->
                                if (!res.isSuccessful) throw IOException("Segment failed: ${res.code}")
                                var data = res.body?.bytes() ?: throw IOException("Empty segment")

                                coroutineContext.ensureActive()

                                // THREAD-SAFE AES DECRYPTION WITH CORRECT SEQUENCE IV
                                if (secretKey != null) {
                                    val seqNum = mediaSequence + seg.first
                                    val ivBytes = java.nio.ByteBuffer.allocate(16).putLong(8, seqNum.toLong()).array()
                                    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.IvParameterSpec(ivBytes))
                                    data = cipher.doFinal(data)
                                }

                                java.io.FileOutputStream(segmentFile).use { it.write(data) }
                                downloadedCount.increment()
                                downloadedBytes.add(data.size.toLong())

                                val currentCount = downloadedCount.sum().toInt()
                                download.downloadedSegments = currentCount

                                // NEW: Mark this exact segment as complete for the UI's secondary progress bar
                                download.segmentProgress[seg.first] = true

                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 1000 || currentCount == segments.size) {
                                    download.update(downloadedBytes.sum(), -1, false)
                                    store.update(download)
                                    notifier.onProgressChange(download)
                                    lastUpdate = now
                                }
                            }
                        }
                    }
                }
            }
        }

    download.status = if (secretKey != null) Download.State.DECRYPTING else Download.State.MERGING
    download.progress = 0
    notifyProgress(download)

    val finalFile = File(sandboxDir, "$filename.ts")
    val totalMergeSize = segments.indices.sumOf { File(sandboxDir, "seg_$it.part").length() }
    checkFreeSpace(sandboxDir, totalMergeSize)

    java.io.FileOutputStream(finalFile).use { outStream ->
        val outChannel = outStream.channel
        val partFiles = segments.indices.map { File(sandboxDir, "seg_$it.part") }
        mergeChannels(partFiles, outChannel, download, totalMergeSize)
    }
    return UniFile.fromFile(finalFile)!!
}

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }

    private suspend fun externalDownload(download: Download, animeDir: UniFile, episodeDirname: String): Boolean {
        val video = download.video ?: return false
        val url = video.videoUrl
        val packageName = preferences.externalDownloaderSelection().get()

        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val animeTitle = download.anime.title
            val episodeName = download.episode.name
            val filename = DiskUtil.buildValidFilename("$animeTitle - $episodeName") + ".mp4"

            // Preserve the external downloader's per-episode target directory. The download cache
            // now ignores this directory until it actually contains a video file.
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
            
            val pm = context.packageManager
            if (packageName.isNotBlank() && packageName != "None" && isPackageInstalled(packageName)) {
                intent.setPackage(packageName)
                // Attempt to find the specific downloader activity to bypass the 'Open With' dialog
                val resolveInfo = pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                if (resolveInfo.isNotEmpty()) {
                    // Optimized for 1DM+: Look for Editor or Add activity first to avoid browser-only components
                    val bestMatch = resolveInfo.find { it.activityInfo.name.contains("Editor", ignoreCase = true) }
                                     ?: resolveInfo.find { it.activityInfo.name.contains("Add", ignoreCase = true) }
                                     ?: resolveInfo.find { it.activityInfo.name.contains("Download", ignoreCase = true) }
                                     ?: resolveInfo.first()
                    intent.component = ComponentName(bestMatch.activityInfo.packageName, bestMatch.activityInfo.name)
                }
            }
            
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback to chooser if direct launch fails or component is invalid
                intent.component = null
                val chooser = Intent.createChooser(intent, "Download with...")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
            
            // A successful intent handoff is not a completed Anizen download. The external app has
            // only accepted the request, so do not show a downloaded checkmark or cache an empty dir.
            download.status = Download.State.NOT_DOWNLOADED
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

    private suspend fun mergeChannels(
        partFiles: List<File>,
        outChannel: java.nio.channels.WritableByteChannel,
        download: Download,
        totalSizeOverride: Long = -1L
    ) {
        val totalMergeSize = if (totalSizeOverride > 0) totalSizeOverride else partFiles.sumOf { it.length() }
        var mergedBytes = 0L
        var lastUpdate = System.currentTimeMillis()

        partFiles.forEach { partFile ->
            if (partFile.exists()) {
                java.io.FileInputStream(partFile).use { inStream ->
                    val inChannel = inStream.channel
                    val size = inChannel.size()
                    var remaining = size
                    var position = 0L
                    
                    while (remaining > 0) {
                        coroutineContext.ensureActive()
                        val toTransfer = Math.min(remaining, 4L * 1024 * 1024)
                        
                        // SAF COMPATIBILITY: SAF OutputStreams wrapped in Channels might not support transferTo
                        // We attempt transferTo first, then fallback to a manually managed buffer to avoid IPC overhead
                        try {
                            val transferred = inChannel.transferTo(position, toTransfer, outChannel)
                            if (transferred <= 0) {
                                // Fallback: Manual Buffered Copy
                                val buffer = java.nio.ByteBuffer.allocateDirect(1024 * 1024)
                                inChannel.position(position)
                                while (inChannel.read(buffer) > 0) {
                                    buffer.flip()
                                    outChannel.write(buffer)
                                    buffer.clear()
                                }
                                break
                            }
                            position += transferred
                            remaining -= transferred
                            mergedBytes += transferred
                        } catch (e: Exception) {
                            // Fallback on any Channel exception (e.g. UnsupportedOperation)
                            val buffer = java.nio.ByteBuffer.allocateDirect(1024 * 1024)
                            inChannel.position(position)
                            while (inChannel.read(buffer) > 0) {
                                buffer.flip()
                                outChannel.write(buffer)
                                buffer.clear()
                            }
                            mergedBytes += (size - position)
                            break
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500 && totalMergeSize > 0) {
                            download.progress = ((mergedBytes.toDouble() / totalMergeSize) * 100).toInt()
                            notifier.onProgressChange(download)
                            lastUpdate = now
                        }
                    }
                }
                partFile.delete()
            }
        }
    }

    private suspend fun ffmpegDownload(
        download: Download,
        sandboxDir: java.io.File,
        filename: String,
    ): java.io.File {
        val video = download.video!!
        // PRO-LEVEL: Use sandboxDir for temp file to allow instant rename to final sandbox output
        val tmpFile = java.io.File(sandboxDir, "$filename.ffmpeg.tmp")
        val uniFile = UniFile.fromFile(tmpFile) ?: throw IOException("Failed to create temporary file for FFmpeg")
        val ffmpegFilename = uniFile.toFFmpegString(context)

        // PRE-FLIGHT: DASH/FFmpeg muxing requires space for [Raw Audio + Raw Video + Muxed Output]
        // We check for ~1.5x the total size as a safety margin for the muxing operation
        if (download.totalSize > 0) {
            checkFreeSpace(sandboxDir, (download.totalSize * 1.5).toLong())
        }

        val headers = video.headers ?: download.source.headers
        val headerOptions = headers.joinToString("", "-headers '", "'") {
            "${it.first}: ${it.second}\r\n"
        }

        val ffmpegOptions = getFFmpegOptions(video, headerOptions, ffmpegFilename)

        // Initial UI State
        download.status = Download.State.DOWNLOADING
        download.activeThreads = 0
        notifier.onProgressChange(download)
        store.update(download)

        val logCallback = LogCallback { log ->
            if (log.level <= Level.AV_LOG_WARNING) {
                logcat(LogPriority.ERROR) { "FFmpeg: ${log.message}" }
            }
        }

        var lastUpdate = System.currentTimeMillis()
        val statCallback = StatisticsCallback { s ->
            val now = System.currentTimeMillis()
            val outTime = (s.time / 1000.0).toLong()
            
            // Estimation: If we have duration and bitrate, estimate final size
            if (download.totalSize <= 0 && download.totalDuration > 0 && s.bitrate > 0) {
                download.totalSize = (download.totalDuration * s.bitrate / 8).toLong()
            }

            // Sync with Normal design: report current bytes read
            download.update(s.size, download.totalSize, false)
            
            if (download.totalDuration > 0) {
                download.progress = (100 * outTime / download.totalDuration).toInt().coerceIn(0, 100)
            }
            
            if (now - lastUpdate > 500L) {
                lastUpdate = now
                notifier.onProgressChange(download)
                store.update(download)
            }
        }

        return suspendCancellableCoroutine { continuation ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                ffmpegOptions,
                {
                    if (it.returnCode.isValueSuccess) {
                        val finalFile = java.io.File(sandboxDir, "$filename.mkv")
                        // INSTANT: renameTo works because both are in sandboxDir
                        if (!tmpFile.renameTo(finalFile)) {
                            // Backup: Copy if rename fails (unlikely)
                            tmpFile.copyTo(finalFile, overwrite = true)
                            tmpFile.delete()
                        }
                        continuation.resume(finalFile)
                    } else {
                        if (it.returnCode.isValueCancel) {
                            continuation.cancel()
                        } else {
                            continuation.resumeWithException(Exception("FFmpeg failed: ${it.returnCode}"))
                        }
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

    private fun getFFmpegOptions(video: Video, headerOptions: String, ffmpegFilename: String): Array<String> {
        fun formatInputs(tracks: List<Track>) = tracks.joinToString(" ", postfix = " ") {
            buildList {
                if (it.url.startsWith("http")) add(headerOptions)
                add("-i")
                add("\"${it.url}\"")
            }.joinToString(" ")
        }

        val audioInputs = formatInputs(video.audioTracks)
        val audioMaps = video.audioTracks.indices.joinToString(" ") { "-map ${it + 1}:a" }
        val audioMetadata = video.audioTracks.mapIndexed { i, t -> "-metadata:s:a:$i \"title=${t.lang}\"" }.joinToString(" ")

        val command = listOf(
            if (video.videoUrl.startsWith("http")) headerOptions else "",
            "-i \"${video.videoUrl}\"", audioInputs,
            "-map 0:v", audioMaps, "-map 0:a?",
            "-f matroska -c:a copy -c:v copy",
            audioMetadata,
            "\"$ffmpegFilename\" -y"
        ).filter { it.isNotBlank() }.joinToString(" ")

        return FFmpegKitConfig.parseArguments(command)
    }

    private suspend fun nativeDashMuxDownload(download: Download, sandboxDir: java.io.File, filename: String): java.io.File = ffmpegDownload(download, sandboxDir, filename)
    private suspend fun torrentDownload(download: Download, sandboxDir: File, filename: String, destDir: UniFile? = null): UniFile {
        retry {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            TorrentServerService.start()
            TorrentServerService.wait(10)
        }
        val currentTorrent = retry {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            TorrentServerApi.addTorrent(
                link = download.video!!.videoUrl,
                title = download.video!!.quality,
                poster = "",
                data = "",
                save = false,
            )
        }
        var index = 0
        if (download.video!!.videoUrl.contains("index=")) {
            index = try {
                download.video!!.videoUrl.substringAfter("index=")
                    .substringBefore("&").toInt()
            } catch (_: Exception) {
                0
            }
        }
        val torrentUrl = TorrentServerUtils.getTorrentPlayLink(currentTorrent, index)
        download.video!!.videoUrl = torrentUrl
        return internalDownload(download, sandboxDir, filename)
    }

    private fun isNetworkConstraintFailed(): Boolean {
        val state = context.activeNetworkState()
        if (!state.isOnline) return true
        val requireWifi = preferences.downloadOnlyOverWifi().get()
        return requireWifi && !state.isWifi
    }

    private fun getNetworkConstraintErrorString(): String {
        val state = context.activeNetworkState()
        return if (!state.isOnline) {
            context.stringResource(MR.strings.download_notifier_no_network)
        } else {
            context.stringResource(MR.strings.download_notifier_text_only_wifi)
        }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val WARNING_NOTIF_TIMEOUT_MS = 30_000L
    }
}

private const val MIN_DISK_SPACE = 200L * 1024 * 1024

/**
 * Schemes the download engines can actually fetch. `content://` and `file://` URIs point at a
 * local copy (produced by [DownloadManager.buildVideo] for already-downloaded episodes) and are
 * never valid download inputs.
 */
internal fun String.isRemote(): Boolean {
    return startsWith("http", ignoreCase = true) ||
        startsWith("magnet:", ignoreCase = true) ||
        endsWith(".torrent", ignoreCase = true)
}

object BufferPool {
    private val pool = java.util.concurrent.ArrayBlockingQueue<ByteArray>(128)
    fun obtain(): ByteArray = pool.poll() ?: ByteArray(256 * 1024)
    fun recycle(buffer: ByteArray) { pool.offer(buffer) }
}
