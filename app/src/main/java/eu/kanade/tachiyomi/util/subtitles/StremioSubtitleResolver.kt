package eu.kanade.tachiyomi.util.subtitles

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object StremioSubtitleResolver {

    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { Injekt.get<NetworkHelper>().client }

    /**
     * Detects if a URL is a Stremio subtitle JSON endpoint.
     */
    fun isStremioUrl(url: String): Boolean {
        return url.contains("/subtitles/") && url.endsWith(".json")
    }

    /**
     * Resolves a Stremio JSON URL into a list of direct subtitle tracks.
     */
    suspend fun resolve(track: Track, headers: Headers? = null): List<Track> {
        if (!isStremioUrl(track.url)) return listOf(track)

        return try {
            val requestBuilder = Request.Builder().url(track.url)
            if (headers != null) {
                requestBuilder.headers(headers)
            } else {
                requestBuilder.header("User-Agent", Injekt.get<NetworkHelper>().defaultUserAgentProvider())
            }
            val request = requestBuilder.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return listOf(track)
                val body = response.body?.string() ?: return listOf(track)

                val data = json.decodeFromString<StremioResponse>(body)
                if (data.subtitles.isEmpty()) return listOf(track)

                data.subtitles.map { sub ->
                    Track(
                        url = sub.url,
                        lang = if (sub.lang.isNotBlank()) sub.lang else track.lang,
                    )
                }
            }
        } catch (e: Exception) {
            listOf(track)
        }
    }

    @Serializable
    private data class StremioResponse(
        val subtitles: List<StremioSub> = emptyList(),
    )

    @Serializable
    private data class StremioSub(
        val id: String? = null,
        val url: String,
        val lang: String = "",
    )
}
