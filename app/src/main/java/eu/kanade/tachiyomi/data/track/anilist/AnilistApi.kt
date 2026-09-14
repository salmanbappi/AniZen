package eu.kanade.tachiyomi.data.track.anilist

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.animesource.model.Credit
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.anilist.dto.ALAddAnimeResult
import eu.kanade.tachiyomi.data.track.anilist.dto.ALCurrentUserResult
import eu.kanade.tachiyomi.data.track.anilist.dto.ALOAuth
import eu.kanade.tachiyomi.data.track.anilist.dto.ALSearchResult
import eu.kanade.tachiyomi.data.track.anilist.dto.ALUserListEntryQueryResult
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.lang.htmlDecode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes
import tachiyomi.domain.track.model.Track as DomainAnimeTrack

class AnilistApi(val client: OkHttpClient, interceptor: AnilistInterceptor) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder()
        .addInterceptor(interceptor)
        .rateLimit(permits = 85, period = 1.minutes)
        .build()

    suspend fun addLibAnime(track: Track): Track {
        return withIOContext {
            val query = """
            |mutation AddAnime(${'$'}animeId: Int, ${'$'}progress: Int, ${'$'}status: MediaListStatus) {
                |SaveMediaListEntry (mediaId: ${'$'}animeId, progress: ${'$'}progress, status: ${'$'}status) {
                |   id
                |   status
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("animeId", track.remote_id)
                    put("progress", track.last_episode_seen.toInt())
                    put("status", track.toApiStatus())
                }
            }
            with(json) {
                authClient.newCall(
                    POST(
                        API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<ALAddAnimeResult>()
                    .let {
                        track
                    }
            }
        }
    }

    suspend fun updateLibAnime(track: Track): Track {
        return withIOContext {
            val query = """
            |mutation UpdateAnime(
                |${'$'}listId: Int, ${'$'}progress: Int, ${'$'}status: MediaListStatus,
                |${'$'}score: Int, ${'$'}startedAt: FuzzyDateInput, ${'$'}completedAt: FuzzyDateInput
            |) {
                |SaveMediaListEntry(
                    |id: ${'$'}listId, progress: ${'$'}progress, status: ${'$'}status,
                    |scoreRaw: ${'$'}score, startedAt: ${'$'}startedAt, completedAt: ${'$'}completedAt
                |) {
                    |id
                    |status
                    |progress
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("listId", track.library_id)
                    put("progress", track.last_episode_seen.toInt())
                    put("status", track.toApiStatus())
                    put("score", track.score.toInt())
                    put("startedAt", createDate(track.started_watching_date))
                    put("completedAt", createDate(track.finished_watching_date))
                }
            }
            authClient.newCall(POST(API_URL, body = payload.toString().toRequestBody(jsonMime)))
                .awaitSuccess()
            track
        }
    }

    suspend fun deleteLibAnime(track: DomainAnimeTrack) {
        return withIOContext {
            val query = """
            |mutation DeleteAnime(${'$'}listId: Int) {
                |DeleteMediaListEntry(id: ${'$'}listId) {
                    |deleted
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("listId", track.libraryId)
                }
            }
            authClient.newCall(POST(API_URL, body = payload.toString().toRequestBody(jsonMime)))
                .awaitSuccess()
        }
    }

    suspend fun getAnimeMetadata(track: DomainAnimeTrack): eu.kanade.tachiyomi.data.track.model.TrackAnimeMetadata {
        return withIOContext {
            val query = """
            |query (${'$'}animeId: Int!) {
                |Media (id: ${'$'}animeId, type: ANIME) {
                    |id
                    |title {
                        |userPreferred
                    |}
                    |coverImage {
                        |large
                    |}
                    |description
                    |genres
                    |studios(isMain: true) {
                        |nodes {
                            |name
                        |}
                    |}
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("animeId", track.remoteId)
                }
            }
            val responseBody = authClient.newCall(
                POST(
                    API_URL,
                    body = payload.toString().toRequestBody(jsonMime),
                ),
            )
                .awaitSuccess()
                .body.string()

            val jsonElement = json.parseToJsonElement(responseBody)
            val media = jsonElement.jsonObject["data"]?.jsonObject?.get("Media")?.jsonObject
                ?: throw Exception("Media not found in AniList response")

            val title = media["title"]?.jsonObject?.get("userPreferred")?.jsonPrimitive?.content ?: ""
            val coverImage = media["coverImage"]?.jsonObject?.get("large")?.jsonPrimitive?.content ?: ""
            val description = media["description"]?.jsonPrimitive?.contentOrNull
            val genres = media["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            val studios = media["studios"]?.jsonObject?.get("nodes")?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString(", ")
                ?.ifEmpty { null }

            eu.kanade.tachiyomi.data.track.model.TrackAnimeMetadata(
                remoteId = track.remoteId,
                title = title,
                thumbnailUrl = coverImage,
                description = description?.htmlDecode(),
                author = studios,
                artist = null,
                genres = genres,
            )
        }
    }

    suspend fun searchAnime(search: String): List<TrackSearch> {
        return withIOContext {
            val query = """
            |query Search(${'$'}query: String) {
                |Page (perPage: 50) {
                    |media(search: ${'$'}query, type: ANIME) {
                        |id
                        |title {
                            |userPreferred
                        |}
                        |coverImage {
                            |large
                        |}
                        |format
                        |status
                        |episodes
                        |description
                        |startDate {
                            |year
                            |month
                            |day
                        |}
                        |averageScore
                    |}
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("query", search)
                }
            }
            with(json) {
                authClient.newCall(
                    POST(
                        API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<ALSearchResult>()
                    .data.page.media
                    .map { it.toALAnime().toTrack() }
            }
        }
    }

    suspend fun findLibAnime(track: Track, userid: Int): Track? {
        return withIOContext {
            val query = """
            |query (${'$'}id: Int!, ${'$'}anime_id: Int!) {
                |Page {
                    |mediaList(userId: ${'$'}id, type: ANIME, mediaId: ${'$'}anime_id) {
                        |id
                        |status
                        |scoreRaw: score(format: POINT_100)
                        |progress
                        |startedAt {
                            |year
                            |month
                            |day
                        |}
                        |completedAt {
                            |year
                            |month
                            |day
                        |}
                        |media {
                            |id
                            |title {
                                |userPreferred
                            |}
                            |coverImage {
                                |large
                            |}
                            |format
                            |status
                            |episodes
                            |description
                            |startDate {
                                |year
                                |month
                                |day
                            |}
                        |}
                    |}
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("id", userid)
                    put("anime_id", track.remote_id)
                }
            }
            with(json) {
                authClient.newCall(
                    POST(
                        API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<ALUserListEntryQueryResult>()
                    .data.page.mediaList
                    .map { it.toALUserAnime() }
                    .firstOrNull()
                    ?.toTrack()
            }
        }
    }

    suspend fun getLibAnime(track: Track, userId: Int): Track {
        return findLibAnime(track, userId) ?: throw Exception("Could not find anime")
    }

    suspend fun getUserAnimeList(userId: Int): List<eu.kanade.tachiyomi.data.track.anilist.dto.ALUserListItem> {
        return withIOContext {
            val list = mutableListOf<eu.kanade.tachiyomi.data.track.anilist.dto.ALUserListItem>()
            var page = 1
            var hasNextPage = true
            while (hasNextPage) {
                val query = """
                |query (${'$'}id: Int!, ${'$'}page: Int!) {
                    |Page(page: ${'$'}page, perPage: 50) {
                        |pageInfo {
                            |hasNextPage
                        |}
                        |mediaList(userId: ${'$'}id, type: ANIME) {
                            |id
                            |status
                            |scoreRaw: score(format: POINT_100)
                            |progress
                            |startedAt {
                                |year
                                |month
                                |day
                            |}
                            |completedAt {
                                |year
                                |month
                                |day
                            |}
                            |media {
                                |id
                                |title {
                                    |userPreferred
                                    |romaji
                                    |english
                                    |native
                                |}
                                |coverImage {
                                    |large
                                |}
                                |format
                                |status
                                |episodes
                                |description
                                |startDate {
                                    |year
                                    |month
                                    |day
                                |}
                                |averageScore
                            |}
                        |}
                    |}
                |}
                |
                """.trimMargin()
                val payload = buildJsonObject {
                    put("query", query)
                    putJsonObject("variables") {
                        put("id", userId)
                        put("page", page)
                    }
                }
                val response = with(json) {
                    authClient.newCall(
                        POST(
                            API_URL,
                            body = payload.toString().toRequestBody(jsonMime),
                        ),
                    )
                        .awaitSuccess()
                        .parseAs<eu.kanade.tachiyomi.data.track.anilist.dto.ALUserListEntryQueryResult>()
                }
                val mediaList = response.data.page.mediaList
                list.addAll(mediaList)
                hasNextPage = response.data.page.pageInfo?.hasNextPage ?: false
                page++
            }
            list
        }
    }

    suspend fun getRelations(mediaId: Int): List<eu.kanade.tachiyomi.data.track.anilist.dto.ALRelationEdge> {
        return withIOContext {
            val query = """
            |query (${'$'}mediaId: Int!) {
                |Media(id: ${'$'}mediaId, type: ANIME) {
                    |relations {
                        |edges {
                            |relationType(version: 2)
                            |node {
                                |id
                                |title {
                                    |userPreferred
                                    |romaji
                                    |english
                                    |native
                                |}
                                |coverImage {
                                    |large
                                |}
                            |}
                        |}
                    |}
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("mediaId", mediaId)
                }
            }
            with(json) {
                client.newCall(
                    POST(
                        API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<eu.kanade.tachiyomi.data.track.anilist.dto.ALRelationResult>()
                    .data.Media?.relations?.edges?.filter {
                        it.relationType == "PREQUEL" || it.relationType == "SEQUEL"
                    } ?: emptyList()
            }
        }
    }

    fun createOAuth(token: String): ALOAuth {
        return ALOAuth(token, "Bearer", System.currentTimeMillis() + 31536000000, 31536000000)
    }

    suspend fun getCurrentUser(): Pair<Int, String> {
        return withIOContext {
            val query = """
            |query User {
                |Viewer {
                    |id
                    |mediaListOptions {
                        |scoreFormat
                    |}
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
            }
            with(json) {
                authClient.newCall(
                    POST(
                        API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<ALCurrentUserResult>()
                    .let {
                        val viewer = it.data.viewer
                        Pair(viewer.id, viewer.mediaListOptions.scoreFormat)
                    }
            }
        }
    }

    suspend fun fetchCastById(mediaId: Long): List<Credit>? {
        return withIOContext {
            try {
                val query = """
                |query GetCast(${"$"}mediaId: Int) {
                |    Media (id: ${"$"}mediaId, type: ANIME) {
                |        id
                |        characters {
                |            edges {
                |                role
                |                node {
                |                    id
                |                    name { userPreferred }
                |                    image { large medium }
                |                }
                |                voiceActors {
                |                    id
                |                    name { userPreferred }
                |                    language
                |                    image { large medium }
                |                }
                |            }
                |        }
                |        staff {
                |            edges {
                |                role
                |                node {
                |                    id
                |                    name { userPreferred }
                |                    image { large medium }
                |                }
                |            }
                |        }
                |    }
                |}
                |
                """.trimMargin()

                val payload = buildJsonObject {
                    put("query", query)
                    putJsonObject("variables") {
                        put("mediaId", mediaId)
                    }
                }

                val response = authClient.newCall(
                    POST(
                        API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                ).execute()

                if (!response.isSuccessful) return@withIOContext null
                val bodyStr = response.body.string()
                val parsed = json.parseToJsonElement(bodyStr).jsonObject
                val media = parsed["data"]?.jsonObject?.get("Media")?.jsonObject ?: return@withIOContext null

                val credits = mutableListOf<Credit>()

                // Parse characters
                val characters = media["characters"]?.jsonObject?.get("edges")?.jsonArray
                characters?.forEach { edgeEl ->
                    val edge = edgeEl.jsonObject
                    val node = edge["node"]?.jsonObject
                    val charName = node?.get("name")?.jsonObject?.get("userPreferred")?.jsonPrimitive?.contentOrNull
                    val charImage = node?.get("image")?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNull
                    val charId = node?.get("id")?.jsonPrimitive?.contentOrNull
                    val charUrl = charId?.let { "https://anilist.co/character/$it" }

                    val vas = edge["voiceActors"]?.jsonArray
                    val vaNames = mutableListOf<String>()
                    var vaImage: String? = null
                    vas?.forEach { vaEl ->
                        val va = vaEl.jsonObject
                        val vaName = va["name"]?.jsonObject?.get("userPreferred")?.jsonPrimitive?.contentOrNull
                        val vaLang = va["language"]?.jsonPrimitive?.contentOrNull
                        if (!vaName.isNullOrBlank()) {
                            vaNames.add(if (!vaLang.isNullOrBlank()) "$vaName ($vaLang)" else vaName)
                        }
                        if (vaImage == null) {
                            vaImage = va["image"]?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNull
                        }
                    }
                    val roleText = vaNames.joinToString(", ")
                    var finalImage: String? = charImage ?: vaImage
                    if (!finalImage.isNullOrBlank() && finalImage.startsWith("//")) {
                        finalImage = "https:$finalImage"
                    }
                    if (!charName.isNullOrBlank()) {
                        credits.add(
                            Credit(
                                name = charName,
                                role = roleText.ifBlank { null },
                                character = charName,
                                image_url = finalImage,
                                url = charUrl,
                            ),
                        )
                    }
                }

                // Parse staff
                val staff = media["staff"]?.jsonObject?.get("edges")?.jsonArray
                staff?.forEach { stEl ->
                    val sedge = stEl.jsonObject
                    val srole = sedge["role"]?.jsonPrimitive?.contentOrNull
                    val snode = sedge["node"]?.jsonObject
                    val sname = snode?.get("name")?.jsonObject?.get("userPreferred")?.jsonPrimitive?.contentOrNull
                    var sImage = snode?.get("image")?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNull
                    val staffId = snode?.get("id")?.jsonPrimitive?.contentOrNull
                    val staffUrl = staffId?.let { "https://anilist.co/staff/$it" }
                    if (!sImage.isNullOrBlank() && sImage.startsWith("//")) sImage = "https:$sImage"
                    if (!sname.isNullOrBlank()) {
                        credits.add(
                            Credit(
                                name = sname,
                                role = srole,
                                image_url = sImage,
                                url = staffUrl,
                            ),
                        )
                    }
                }

                credits.ifEmpty { null }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun createDate(dateValue: Long): JsonObject {
        if (dateValue == 0L) {
            return buildJsonObject {
                put("year", JsonNull)
                put("month", JsonNull)
                put("day", JsonNull)
            }
        }

        val dateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(dateValue), ZoneId.systemDefault())
        return buildJsonObject {
            put("year", dateTime.year)
            put("month", dateTime.monthValue)
            put("day", dateTime.dayOfMonth)
        }
    }

    suspend fun getCharacterDescription(charId: Long): String? {
        return withIOContext {
            try {
                val query = """
                |query GetChar(${"$"}id: Int) {
                |    Character (id: ${"$"}id) {
                |        description
                |    }
                |}
                """.trimMargin()
                val payload = buildJsonObject {
                    put("query", query)
                    putJsonObject("variables") {
                        put("id", charId.toInt())
                    }
                }
                val response = authClient.newCall(
                    POST(
                        API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                ).execute()
                if (!response.isSuccessful) return@withIOContext null
                val body = response.body.string()
                val parsed = json.parseToJsonElement(body).jsonObject
                parsed["data"]?.jsonObject?.get("Character")?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun getStaffDescription(staffId: Long): String? {
        return withIOContext {
            try {
                val query = """
                |query GetStaff(${"$"}id: Int) {
                |    Staff (id: ${"$"}id) {
                |        description
                |    }
                |}
                """.trimMargin()
                val payload = buildJsonObject {
                    put("query", query)
                    putJsonObject("variables") {
                        put("id", staffId.toInt())
                    }
                }
                val response = authClient.newCall(
                    POST(
                        API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                ).execute()
                if (!response.isSuccessful) return@withIOContext null
                val body = response.body.string()
                val parsed = json.parseToJsonElement(body).jsonObject
                parsed["data"]?.jsonObject?.get("Staff")?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        private const val CLIENT_ID = "36266"
        private const val API_URL = "https://graphql.anilist.co/"
        private const val BASE_URL = "https://anilist.co/api/v2/"
        private const val BASE_ANIME_URL = "https://anilist.co/anime/"

        fun animeUrl(mediaId: Long): String {
            return BASE_ANIME_URL + mediaId
        }

        fun authUrl(): Uri = "${BASE_URL}oauth/authorize".toUri().buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "token")
            .build()
    }
}
