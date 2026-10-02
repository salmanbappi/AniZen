package eu.kanade.tachiyomi.torrentServer

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.torrentServer.model.Torrent
import eu.kanade.tachiyomi.torrentServer.model.TorrentRequest
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.InputStream

object TorrentServerApi {
    private val network: NetworkHelper by injectLazy()
    private val hostUrl = TorrentServerUtils.hostUrl

    // Torrent metadata can take minutes to arrive from the swarm; the plain client's
    // timeouts abort these calls long before that (see NetworkHelper.torrentClient).
    // echo()/shutdown() stay on the regular client — they are polled in tight loops while
    // the server starts up and must fail fast.
    private val client get() = network.torrentClient

    @Suppress("TooGenericExceptionCaught")
    fun echo(): String {
        return try {
            network.client.newCall(GET("$hostUrl/echo")).execute().body.string()
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { e.toString() }
            ""
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun shutdown(): String {
        return try {
            network.client.newCall(GET("$hostUrl/shutdown")).execute().body.string()
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { e.toString() }
            ""
        }
    }

    // / Torrents
    fun addTorrent(
        link: String,
        title: String,
        poster: String = "",
        data: String = "",
        save: Boolean,
    ): Torrent {
        val req =
            TorrentRequest(
                "add",
                link = link,
                title = title,
                poster = poster,
                data = data,
                saveToDb = save,
            ).toString()
        val resp =
            client.newCall(
                POST("$hostUrl/torrents", body = req.toRequestBody("application/json".toMediaTypeOrNull())),
            ).execute()
        return Json.decodeFromString(Torrent.serializer(), resp.body.string())
    }

    fun getTorrent(hash: String): Torrent {
        val req = TorrentRequest("get", hash).toString()
        val resp =
            client.newCall(
                POST("$hostUrl/torrents", body = req.toRequestBody("application/json".toMediaTypeOrNull())),
            ).execute()
        return Json.decodeFromString(Torrent.serializer(), resp.body.string())
    }

    fun remTorrent(hash: String) {
        val req = TorrentRequest("rem", hash).toString()
        client.newCall(
            POST("$hostUrl/torrents", body = req.toRequestBody("application/json".toMediaTypeOrNull())),
        ).execute()
    }

    fun listTorrent(): List<Torrent> {
        val req = TorrentRequest("list").toString()
        val resp =
            client.newCall(
                POST("$hostUrl/torrents", body = req.toRequestBody("application/json".toMediaTypeOrNull())),
            ).execute()
        return Json.decodeFromString<List<Torrent>>(resp.body.string())
    }

    fun uploadTorrent(
        file: InputStream,
        title: String,
        poster: String,
        data: String,
        save: Boolean,
    ): Torrent {
        val fileBytes = file.readBytes()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("title", title)
            .addFormDataPart("poster", poster)
            .addFormDataPart("data", data)
            .addFormDataPart("save", save.toString())
            .addFormDataPart(
                "file1",
                "filename",
                fileBytes.toRequestBody("application/x-bittorrent".toMediaTypeOrNull()),
            )
            .build()
        val request = POST("$hostUrl/torrent/upload", body = body)
        val response = client.newCall(request).execute()
        return Json.decodeFromString(Torrent.serializer(), response.body.string())
    }
}
