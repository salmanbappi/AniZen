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
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.VideoType
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.torrentServer.service.TorrentServerService
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.toFFmpegString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.logcat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * This class is the one in charge of downloading episodes.
 */
class Downloader(
    private val context: Context,
    private val provider: DownloadProvider,
    private val cache: DownloadCache,
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkHelper: eu.kanade.tachiyomi.network.NetworkHelper = Injekt.get(),
) {

    private val preferences: DownloadPreferences = Injekt.get()
    private val store = DownloadStore(context)
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val ffmpegSemaphore = Semaphore(5)
    private val memorySemaphore = Semaphore(12) 

    private val notifier by lazy { DownloadNotifier(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloaderJob: Job? = null

    init {
        launchIO {
            val downloads = store.get()
            _queueState.update { downloads }
        }
    }

    fun start() {
        if (downloaderJob != null || queueState.value.isEmpty()) return

        downloaderJob = scope.launch {
            val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            
            queueState.collectLatest { queue ->
                val pending = queue.filter { it.status == Download.State.QUEUE }
                if (pending.isEmpty() && areAllDownloadsFinished()) {
                    stop()
                }

                pending.take(preferences.concurrentDownloads().get()).forEach { download ->
                    downloadScope.launch {
                        downloadEpisode(download)
                    }
                }
            }
        }
    }

    fun stop() {
        downloaderJob?.cancel()
        downloaderJob = null
        queueState.value.filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.QUEUE }
        notifier.dismissProgress()
    }

    fun pause() {
        stop()
    }

    private suspend fun downloadEpisode(download: Download) {
        val animeDir = provider.findAnimeDir(download.anime, download.source) ?: return
        val dirname = provider.getEpisodeDirName(download.episode)
        val tmpDir = animeDir.createDirectory(dirname + TMP_DIR_SUFFIX)!!

        download.status = Download.State.DOWNLOADING
        notifier.onProgressChange(download)

        try {
            val video = download.video ?: EpisodeLoader.getBestVideo(download.source, download.episode)
            download.video = video

            val filename = DiskUtil.buildValidFilename("${download.anime.title} - ${download.episode.name}")
            
            // 1DM+ Sniffing Logic
            val url = video.videoUrl
            var isHls = video.type == VideoType.HLS
            var isDash = video.type == VideoType.DASH
            
            if (video.type == VideoType.VIDEO && !preferences.alwaysUseInternalDownloader().get()) {
                try {
                    val client = networkHelper.client
                    val sniffReq = Request.Builder().url(url).header("Range", "bytes=0-511").headers(video.headers ?: Headers.headersOf()).build()
                    client.newCall(sniffReq).execute().use { res ->
                        val contentType = res.header("Content-Type")?.lowercase() ?: ""
                        val peekText = res.body?.source()?.peek()?.readUtf8(10) ?: ""
                        if (contentType.contains("mpegurl") || peekText.contains("#EXTM3U")) isHls = true
                        else if (contentType.contains("dash+xml") || peekText.contains("<MPD")) isDash = true
                    }
                } catch (e: Exception) {
                    Log.d("AniZen", "Sniffing failed: ${e.message}")
                }
            }

            if (preferences.alwaysUseInternalDownloader().get()) {
                isHls = false
                isDash = false
            }

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

            ensureSuccessfulAnimeDownload(download, animeDir, tmpDir, dirname)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            download.status = Download.State.ERROR
            notifier.onError(e.message, download.episode.name, download.anime.title, download.anime.id)
        }
    }

    private suspend fun internalDownload(download: Download, tmpDir: UniFile, filename: String): UniFile {
        Log.d("AniZen", "Downloader: Entering internalDownload for $filename")
        val video = download.video!!
        val videoFile = tmpDir.findFile("$filename.tmp") ?: tmpDir.createFile("$filename.tmp")!!
        val client = networkHelper.downloadClient
        
        val response = client.newCall(Request.Builder().url(video.videoUrl).headers(video.headers ?: Headers.headersOf()).build()).await()
        if (!response.isSuccessful) throw IOException("Download failed: ${response.code}")
        
        val totalSize = response.body?.contentLength() ?: -1L
        download.totalSize = totalSize
        
        response.body?.source()?.use { source ->
            context.contentResolver.openFileDescriptor(videoFile.uri, "rw")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    while (source.read(buffer).also { bytesRead = it } != -1) {
                        ensureActive()
                        channel.write(ByteBuffer.wrap(buffer, 0, bytesRead))
                        totalRead += bytesRead
                        download.update(totalRead, totalSize, false)
                        notifier.onProgressChange(download)
                    }
                }
            }
        }
        
        videoFile.renameTo("$filename.mkv")
        return videoFile
    }

    private suspend fun nativeHlsDownload(download: Download, tmpDir: UniFile, filename: String): UniFile {
        Log.d("AniZen", "Downloader: Entering nativeHlsDownload (Direct-Stream) for $filename")
        val video = download.video!!
        val client = networkHelper.downloadClient
        val headers = video.headers ?: Headers.headersOf()
        
        val playlistRes = client.newCall(Request.Builder().url(video.videoUrl).headers(headers).build()).await()
        val playlistBody = playlistRes.body?.string() ?: throw IOException("Empty HLS")
        val baseUrl = video.videoUrl.substringBeforeLast("/") + "/"
        val segments = playlistBody.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { if (it.startsWith("http")) it else baseUrl + it }

        download.totalSegments = segments.size
        val videoFile = tmpDir.createFile("$filename.tmp")!!
        val nextWriteIdx = AtomicInteger(0)
        val downloadedBytes = AtomicLong(0)
        val segmentCache = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()

        context.contentResolver.openFileDescriptor(videoFile.uri, "rw")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                coroutineScope {
                    segments.forEachIndexed { index, segUrl ->
                        launch {
                            memorySemaphore.withPermit {
                                try {
                                    val res = client.newCall(Request.Builder().url(segUrl).headers(headers).build()).execute()
                                    val data = res.body?.bytes() ?: throw IOException("Empty segment")
                                    segmentCache[index] = data
                                    
                                    synchronized(channel) {
                                        while (segmentCache.containsKey(nextWriteIdx.get())) {
                                            val writeData = segmentCache.remove(nextWriteIdx.get())!!
                                            channel.write(ByteBuffer.wrap(writeData))
                                            downloadedBytes.addAndGet(writeData.size.toLong())
                                            nextWriteIdx.incrementAndGet()
                                            download.downloadedSegments = nextWriteIdx.get()
                                            download.update(downloadedBytes.get(), -1, false)
                                            notifier.onProgressChange(download)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("AniZen", "Segment $index failed")
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
        // Torrent implementation...
        return tmpDir.findFile("$filename.mkv")!!
    }

    private suspend fun ensureSuccessfulAnimeDownload(download: Download, animeDir: UniFile, tmpDir: UniFile, dirname: String) {
        tmpDir.renameTo(dirname)
        cache.addEpisode(dirname, animeDir, download.anime)
        download.status = Download.State.DOWNLOADED
    }

    fun areAllDownloadsFinished() = queueState.value.none { it.status == Download.State.DOWNLOADING || it.status == Download.State.QUEUE }

    fun clearQueue() {
        _queueState.update { emptyList() }
        store.clear()
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
    }
}
