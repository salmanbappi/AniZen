// ANZ -->
package eu.kanade.tachiyomi.data.anilist

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AniListRepository {

    private val networkHelper: NetworkHelper = Injekt.get()
    private val json: Json = Injekt.get()

    private var cachedTrending: List<AniListMedia>? = null
    private var cachedPopular: List<AniListMedia>? = null

    suspend fun getTrending(): List<AniListMedia> {
        cachedTrending?.let { return it }
        return withIOContext {
            try {
                val items = fetchMediaList(TRENDING_QUERY)
                cachedTrending = items
                items
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun getPopular(): List<AniListMedia> {
        cachedPopular?.let { return it }
        return withIOContext {
            try {
                val items = fetchMediaList(POPULAR_QUERY)
                cachedPopular = items
                items
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private suspend fun fetchMediaList(query: String): List<AniListMedia> {
        val payload = buildJsonObject {
            put("query", query)
            putJsonObject("variables") {
                put("page", 1)
                put("perPage", 10)
            }
        }

        val request = POST(API_URL, body = payload.toString().toRequestBody(jsonMime))
        val response = networkHelper.client.newCall(request).awaitSuccess()
        val responseBody = response.body.string()

        val parsed = json.decodeFromString(ALMediaResponse.serializer(), responseBody)
        return parsed.data?.page?.media.orEmpty()
    }

    companion object {
        private const val API_URL = "https://graphql.anilist.co/"

        private const val MEDIA_FIELDS = """
            id
            title { userPreferred english romaji native }
            bannerImage
            coverImage { extraLarge large medium color }
            description(asHtml: false)
            season
            seasonYear
            averageScore
            genres
            episodes
            status
            format
            trailer { id site }
        """

        private const val TRENDING_QUERY = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    media(sort: TRENDING_DESC, type: ANIME, isAdult: false) {
                        $MEDIA_FIELDS
                    }
                }
            }
        """

        private const val POPULAR_QUERY = """
            query (${'$'}page: Int, ${'$'}perPage: Int) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    media(sort: POPULARITY_DESC, type: ANIME, isAdult: false) {
                        $MEDIA_FIELDS
                    }
                }
            }
        """
    }
}

@Serializable
private data class ALMediaResponse(
    val data: ALMediaData? = null,
)

@Serializable
private data class ALMediaData(
    @SerialName("Page") val page: ALMediaPage? = null,
)

@Serializable
private data class ALMediaPage(
    val media: List<AniListMedia> = emptyList(),
)
// ANZ <--
