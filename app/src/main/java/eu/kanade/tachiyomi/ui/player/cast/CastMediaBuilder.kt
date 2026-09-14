package eu.kanade.tachiyomi.ui.player.cast

import android.content.Intent
import android.net.Uri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.common.images.WebImage
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.util.LocalHttpServerHolder
import eu.kanade.tachiyomi.util.LocalHttpServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.Headers
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder

class CastMediaBuilder(
    private val viewModel: PlayerViewModel,
    private val activity: PlayerActivity,
) {

    private val player by lazy { activity.player }
    private val prefserver: LocalHttpServerHolder by injectLazy()
    private val port = prefserver.port().get()

    suspend fun buildMediaInfo(video: Video): MediaInfo = withContext(Dispatchers.IO) {
        var videoUrl = video.videoUrl
        logcat(LogPriority.DEBUG) { "Video URL: $videoUrl" }

        videoUrl = when {
            videoUrl.startsWith("content://") -> getLocalServerUrl(videoUrl)
            videoUrl.startsWith(
                "magnet",
            ) ||
                videoUrl.endsWith(".torrent") -> torrentLinkHandler(videoUrl, video.quality)
            else -> {
                // The Cast default receiver cannot send custom HTTP headers.
                // If the video source requires headers (Referer, UA, cookies, etc.),
                // proxy the stream through the local HTTP server.
                val headers = video.headers
                if (headers != null && headers.size > 0) {
                    getProxyUrl(videoUrl, headers)
                } else {
                    videoUrl
                }
            }
        }

        val contentType = when {
            video.videoUrl.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            video.videoUrl.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
            video.videoUrl.contains(".mkv", ignoreCase = true) -> "video/x-matroska"
            else -> "video/mp4"
        }

        MediaInfo.Builder(videoUrl)
            .setContentType(contentType)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .addMetadata(video)
            .addTracks(video)
            .setStreamDuration((player.duration ?: 0).toLong() * 1000)
            .build()
    }

    private fun torrentLinkHandler(videoUrl: String, quality: String): String {
        var index = 0

        if (videoUrl.startsWith("content://")) {
            val videoInputStream = activity.applicationContext.contentResolver.openInputStream(Uri.parse(videoUrl))
                ?: throw IllegalStateException("Unable to open InputStream for content: $videoUrl")
            val torrent = TorrentServerApi.uploadTorrent(videoInputStream, quality, "", "", false)
            return TorrentServerUtils.getTorrentPlayLink(torrent, 0)
        }

        if (videoUrl.startsWith("magnet") && videoUrl.contains("index=")) {
            index = try {
                videoUrl.substringAfter("index=").toInt()
            } catch (e: NumberFormatException) {
                0
            }
        }

        val currentTorrent = TorrentServerApi.addTorrent(videoUrl, quality, "", "", false)
        logcat(LogPriority.DEBUG) { "Torrent URL: $videoUrl" }
        return TorrentServerUtils.getTorrentPlayLink(currentTorrent, index)
    }

    private fun MediaInfo.Builder.addMetadata(video: Video): MediaInfo.Builder {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, viewModel.currentAnime.value?.title ?: "")
            putString(MediaMetadata.KEY_SUBTITLE, viewModel.currentEpisode.value?.name ?: "")
            viewModel.currentAnime.value?.thumbnailUrl?.let { url ->
                addImage(WebImage(Uri.parse(url)))
            }
        }
        return setMetadata(metadata)
    }

    private fun MediaInfo.Builder.addTracks(video: Video): MediaInfo.Builder {
        val subtitleTracks = video.subtitleTracks.mapIndexed { trackIndex, sub ->
            logcat(LogPriority.DEBUG) { "Subtitle URL: ${sub.url}" }
            MediaTrack.Builder(trackIndex.toLong(), MediaTrack.TYPE_TEXT)
                .setContentId(sub.url)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setName(sub.lang)
                .build()
        }

        val audioTracks = video.audioTracks.mapIndexed { trackIndex, audio ->
            MediaTrack.Builder((subtitleTracks.size + trackIndex).toLong(), MediaTrack.TYPE_AUDIO)
                .setContentId(audio.url)
                .setName(audio.lang)
                .setContentType("application/x-mpegURL")
                .build()
        }

        return setMediaTracks(subtitleTracks + audioTracks)
    }

    private fun getLocalServerUrl(contentUri: String): String {
        val context = activity.applicationContext
        context.startService(Intent(context, LocalHttpServerService::class.java))
        val ip = getLocalIpAddress()
        val encodedUri = URLEncoder.encode(contentUri, "UTF-8")
        return "http://$ip:$port/file?uri=$encodedUri"
    }

    private fun getProxyUrl(videoUrl: String, headers: Headers): String {
        val context = activity.applicationContext
        context.startService(Intent(context, LocalHttpServerService::class.java))
        val ip = getLocalIpAddress()
        val encodedUrl = URLEncoder.encode(videoUrl, "UTF-8")
        // Serialize headers as newline-separated "Key: Value" pairs
        val headerString = headers.toMultimap()
            .flatMap { (key, values) -> values.map { "$key: $it" } }
            .joinToString("\n")
        val encodedHeaders = URLEncoder.encode(headerString, "UTF-8")
        return "http://$ip:$port/proxy?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            val ipAddresses = mutableListOf<Pair<String, String>>()
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        if (ip != null) {
                            ipAddresses.add(name to ip)
                        }
                    }
                }
            }

            val wifiIp = ipAddresses.find { (name, _) ->
                name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")
            }?.second
            if (wifiIp != null) return wifiIp

            val nonMobileIp = ipAddresses.find { (name, _) ->
                !name.startsWith("rmnet") &&
                    !name.startsWith("ccmni") &&
                    !name.startsWith("pdp") &&
                    !name.startsWith("tun") &&
                    !name.startsWith("tap") &&
                    !name.startsWith("p2p")
            }?.second
            if (nonMobileIp != null) return nonMobileIp

            if (ipAddresses.isNotEmpty()) {
                return ipAddresses.first().second
            }
        } catch (ex: Exception) {
            logcat(LogPriority.DEBUG) { "Error getting local IP address" }
        }
        return "127.0.0.1"
    }
}
