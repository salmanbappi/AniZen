package eu.kanade.tachiyomi.util.subtitles

import android.os.Environment
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException

object SubtitleDownloader {

    private val networkHelper by lazy { Injekt.get<NetworkHelper>() }
    private val storageManager by lazy { Injekt.get<StorageManager>() }

    fun getCleanExtension(url: String): String {
        val cleanUrl = url.substringBefore("?")
        return when {
            cleanUrl.endsWith(".vtt", ignoreCase = true) -> "vtt"
            cleanUrl.endsWith(".ass", ignoreCase = true) -> "ass"
            else -> "srt"
        }
    }

    private fun getHeaders(video: Video?): Headers {
        val builder = (video?.headers ?: Headers.headersOf()).newBuilder()
        if (builder.get("User-Agent") == null) {
            builder.add("User-Agent", networkHelper.defaultUserAgentProvider())
        }
        builder.removeAll("Sec-Fetch-Dest")
        builder.removeAll("Sec-Fetch-Mode")
        builder.removeAll("Sec-Fetch-Site")
        builder.removeAll("Sec-Fetch-User")
        builder.removeAll("X-Requested-With")
        return builder.build()
    }

    private fun getDestinationDir(sourceName: String, animeTitle: String): UniFile {
        val safeSource = DiskUtil.buildValidFilename(sourceName.ifBlank { "Unknown" })
        val safeAnime = DiskUtil.buildValidFilename(animeTitle)

        val baseDownloads = storageManager.getDownloadsDirectory()
        if (baseDownloads != null && baseDownloads.exists()) {
            val subDir = baseDownloads.createDirectory("subtitles") ?: baseDownloads
            val sourceDir = subDir.createDirectory(safeSource) ?: subDir
            val animeDir = sourceDir.createDirectory(safeAnime) ?: sourceDir
            return animeDir
        }

        // Fallback to public device downloads directory
        val fallback = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AniZen/subtitles/$safeSource/$safeAnime",
        )
        if (!fallback.exists()) {
            fallback.mkdirs()
        }
        return UniFile.fromFile(fallback) ?: throw IOException("Cannot create destination directory")
    }

    fun getSubtitleFilename(episode: Episode, track: Track): String {
        val ext = getCleanExtension(track.url)
        val safeEpisodeName = DiskUtil.buildValidFilename(episode.name)
        val safeLang = DiskUtil.buildValidFilename(track.lang.ifBlank { "sub" })
        return "${safeEpisodeName}.${safeLang}.$ext"
    }

    fun isSubtitleDownloaded(
        anime: Anime,
        episode: Episode,
        source: AnimeSource?,
        track: Track,
    ): Boolean {
        return try {
            val sourceName = source?.name ?: "Unknown"
            val destDir = getDestinationDir(sourceName, anime.title)
            val filename = getSubtitleFilename(episode, track)
            val file = destDir.findFile(filename)
            file != null && file.exists() && file.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    suspend fun downloadSubtitleTrack(
        anime: Anime,
        episode: Episode,
        source: AnimeSource?,
        track: Track,
        video: Video?,
    ): Result<UniFile> = withContext(Dispatchers.IO) {
        try {
            val headers = getHeaders(video)
            val resolvedTracks = StremioSubtitleResolver.resolve(track, headers)
            val actualTrack = resolvedTracks.firstOrNull() ?: track

            val filename = getSubtitleFilename(episode, actualTrack)
            val sourceName = source?.name ?: "Unknown"
            val destDir = getDestinationDir(sourceName, anime.title)
            val targetFile = destDir.createFile(filename)
                ?: throw IOException("Cannot create subtitle file: $filename")

            if (actualTrack.url.startsWith("file://")) {
                val sourceFile = File(actualTrack.url.removePrefix("file://"))
                if (sourceFile.exists()) {
                    sourceFile.inputStream().use { input ->
                        targetFile.openOutputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    return@withContext Result.success(targetFile)
                }
            }

            val client = networkHelper.client
            val req = Request.Builder().url(actualTrack.url).headers(headers).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                val body = res.body ?: throw IOException("Empty response body")
                body.byteStream().use { input ->
                    targetFile.openOutputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to download subtitle track: ${track.url}" }
            Result.failure(e)
        }
    }
}
