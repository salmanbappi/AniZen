package eu.kanade.tachiyomi.util.subtitles

import android.content.Context
import android.os.Environment
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object SubtitleDownloader {

    private val networkHelper by lazy { Injekt.get<NetworkHelper>() }

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

    suspend fun downloadSubtitleTrack(
        anime: Anime,
        episode: Episode,
        track: Track,
        video: Video?,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val headers = getHeaders(video)
            val resolvedTracks = StremioSubtitleResolver.resolve(track, headers)
            val actualTrack = resolvedTracks.firstOrNull() ?: track

            val ext = getCleanExtension(actualTrack.url)
            val safeAnimeTitle = DiskUtil.buildValidFilename(anime.title)
            val safeEpisodeName = DiskUtil.buildValidFilename(episode.name)
            val safeLang = DiskUtil.buildValidFilename(actualTrack.lang.ifBlank { "sub" })
            val filename = "${safeEpisodeName}.${safeLang}.$ext"

            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AniZen/Subtitles/$safeAnimeTitle",
            )
            if (!publicDir.exists()) {
                publicDir.mkdirs()
            }

            val targetFile = File(publicDir, filename)

            if (actualTrack.url.startsWith("file://")) {
                val sourceFile = File(actualTrack.url.removePrefix("file://"))
                if (sourceFile.exists()) {
                    sourceFile.inputStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
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
                    FileOutputStream(targetFile).use { output ->
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
