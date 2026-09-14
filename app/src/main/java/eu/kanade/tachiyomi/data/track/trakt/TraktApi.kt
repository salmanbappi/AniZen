package eu.kanade.tachiyomi.data.track.trakt

import androidx.core.net.toUri
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.track.ImportStatusFilter
import eu.kanade.tachiyomi.data.track.ImportableEntry
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktOAuth
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktSearchResult
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktSyncMovie
import eu.kanade.tachiyomi.data.track.trakt.dto.TraktSyncRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class TraktApi(private val client: OkHttpClient, private val interceptor: TraktInterceptor) {

    private val baseUrl = "https://api.trakt.tv"
    private val json = Json { ignoreUnknownKeys = true }
    private val userAgent = "AniZen v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"

    private val publicClient by lazy {
        client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    }

    companion object {
        fun authUrl(): android.net.Uri =
            "https://trakt.tv/oauth/authorize".toUri().buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", Trakt.CLIENT_ID)
                .appendQueryParameter("redirect_uri", Trakt.REDIRECT_URI)
                // Request sync scope so obtained token has permissions to write ratings/history.
                .appendQueryParameter("scope", Trakt.SCOPES)
                .build()
    }

    private val authClient by lazy {
        client.newBuilder()
            .addInterceptor(interceptor)
            .build()
    }

    private fun Request.Builder.applyTraktHeaders(includeContentType: Boolean = true): Request.Builder {
        if (includeContentType) {
            header("Content-Type", "application/json")
        }
        return header("Accept", "application/json")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", Trakt.CLIENT_ID)
            .header("User-Agent", userAgent)
    }

    fun buildSearchRequest(query: String): Request {
        // Request extended data so overview and images are included in search results.
        return Request.Builder()
            .url(
                "$baseUrl/search/movie,show?extended=full,images&limit=20&query=${java.net.URLEncoder.encode(
                    query,
                    "utf-8",
                )}",
            )
            .applyTraktHeaders(includeContentType = false)
            .get()
            .build()
    }

    fun search(query: String): List<TraktSearchResult> {
        val request = buildSearchRequest(query)
        // Use a client without cookies for public search requests to avoid Cloudflare/session cookie issues.
        val response = publicClient.newCall(request).execute()
        val body = response.body.string()
        return json.decodeFromString(body)
    }

    /**
     * Sync a single episode as watched for a show.
     * Uses the /sync/history endpoint with a "shows" payload containing seasons/episodes.
     * season fallback: 1 when caller doesn't provide season info.
     */
    fun updateShowEpisodeProgress(traktId: Long, season: Int? = null, episode: Int): Boolean {
        // If caller provides a season, send it directly. Otherwise attempt to determine the season
        // by fetching the show's seasons (with episodes) and mapping the episode index to the
        // correct season based on cumulative episode counts.
        // Determine season number and episode index within that season.
        var seasonNumber: Int
        var episodeNumberInSeason: Int
        if (season != null) {
            seasonNumber = season
            episodeNumberInSeason = episode
        } else {
            try {
                val seasonsReq = Request.Builder()
                    .url("$baseUrl/shows/$traktId/seasons?extended=episodes")
                    .applyTraktHeaders(includeContentType = false)
                    .get()
                    .build()
                val seasonsResp = authClient.newCall(seasonsReq).execute()
                val seasonsBodyRaw = seasonsResp.body.string()

                val root = run {
                    // Ensure we work with a String for logging/parsing to avoid platform-type issues.
                    val seasonsBody = seasonsBodyRaw
                    try {
                        json.parseToJsonElement(seasonsBody).jsonArray
                    } catch (_: Exception) {
                        JsonArray(emptyList())
                    }
                }

                var seasonMatchByNumber: Int? = null
                root.forEach { seasonEl ->
                    try {
                        val seasonObj = seasonEl.jsonObject
                        val seasonNum = seasonObj["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                        if (seasonNum == 0) return@forEach // skip specials
                        val episodesArr = seasonObj["episodes"]?.jsonArray
                        if (episodesArr != null) {
                            val hasMatch = episodesArr.any { epEl ->
                                try {
                                    epEl.jsonObject["number"]?.jsonPrimitive?.intOrNull == episode
                                } catch (_: Exception) {
                                    false
                                }
                            }
                            if (hasMatch) {
                                seasonMatchByNumber = seasonNum
                            }
                        }
                    } catch (_: Exception) {
                        // ignore and continue
                    }
                }
                if (seasonMatchByNumber != null) {
                    seasonNumber = seasonMatchByNumber
                    episodeNumberInSeason = episode
                } else {
                    var cumulative = 0
                    var foundSeasonLocal: Int? = null
                    var epInSeasonLocal = episode
                    root.forEach { seasonEl ->
                        try {
                            val seasonObj = seasonEl.jsonObject
                            val seasonNum = seasonObj["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                            val episodesArr = seasonObj["episodes"]?.jsonArray
                            val count = seasonObj["episode_count"]?.jsonPrimitive?.intOrNull ?: episodesArr?.size ?: 0
                            if (seasonNum == 0 || count <= 0) return@forEach
                            val start = cumulative + 1
                            val end = cumulative + count
                            if (episode in start..end) {
                                foundSeasonLocal = seasonNum
                                epInSeasonLocal = episode - cumulative
                                return@forEach
                            }
                            cumulative += count
                        } catch (_: Exception) {
                            // ignore and continue
                        }
                    }
                    seasonNumber = foundSeasonLocal ?: 1
                    episodeNumberInSeason = epInSeasonLocal
                }
            } catch (_: Exception) {
                seasonNumber = 1
                episodeNumberInSeason = episode
            }
        }

        val payload = """
            {
                "shows": [
                    {
                        "ids": { "trakt": $traktId },
                        "seasons": [
                            {
                                "number": $seasonNumber,
                                "episodes": [
                                    { "number": $episodeNumberInSeason }
                                ]
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()
        val request = Request.Builder()
            .url("$baseUrl/sync/history")
            .applyTraktHeaders()
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val response = authClient.newCall(request).execute()
        return response.isSuccessful
    }

    fun updateMovieWatched(syncMovie: TraktSyncMovie): Boolean {
        val syncRequest = TraktSyncRequest(movies = listOf(syncMovie))
        val requestBody = json.encodeToString(
            TraktSyncRequest.serializer(),
            syncRequest,
        )
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/sync/history")
            .applyTraktHeaders()
            .post(requestBody)
            .build()
        val response = authClient.newCall(request).execute()
        return response.isSuccessful
    }

    /**
     * Send rating(s) to Trakt.
     * Accepts optional lists of shows and movies with rating (1-10).
     * Uses POST /sync/ratings with body: { "movies":[{ "ids":{...}, "rating": n }], "shows":[...] }
     */
    fun sendRatings(
        movieRatings: List<Pair<Long, Int>> = emptyList(),
        showRatings: List<Pair<Long, Int>> = emptyList(),
    ): Boolean {
        // Build JSON payload manually to avoid changing DTOs.
        if (movieRatings.isEmpty() && showRatings.isEmpty()) return true
        val moviesJson = movieRatings.joinToString(separator = ",") { (id, rating) ->
            """{ "ids": { "trakt": $id }, "rating": $rating }"""
        }
        val showsJson = showRatings.joinToString(separator = ",") { (id, rating) ->
            """{ "ids": { "trakt": $id }, "rating": $rating }"""
        }
        val parts = mutableListOf<String>()
        if (movieRatings.isNotEmpty()) parts.add("\"movies\": [ $moviesJson ]")
        if (showRatings.isNotEmpty()) parts.add("\"shows\": [ $showsJson ]")
        val payload = "{ ${parts.joinToString(", ")} }"
        val request = Request.Builder()
            .url("$baseUrl/sync/ratings")
            .applyTraktHeaders()
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val response = authClient.newCall(request).execute()
        return response.isSuccessful
    }

    fun loginOAuth(code: String, clientId: String, clientSecret: String, redirectUri: String): TraktOAuth? {
        val bodyJson = """
            {
                "code":"$code",
                "client_id":"$clientId",
                "client_secret":"$clientSecret",
                "redirect_uri":"$redirectUri",
                "grant_type":"authorization_code"
            }
        """.trimIndent()
        val request = Request.Builder()
            .url("$baseUrl/oauth/token")
            .applyTraktHeaders()
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        val body = response.body.string()
        return try {
            json.decodeFromString<TraktOAuth>(body)
        } catch (_: Exception) {
            null
        }
    }

    fun refreshOAuth(refreshToken: String, clientId: String, clientSecret: String): TraktOAuth? {
        val bodyJson = """
            {
                "refresh_token":"$refreshToken",
                "client_id":"$clientId",
                "client_secret":"$clientSecret",
                "grant_type":"refresh_token"
            }
        """.trimIndent()
        val request = Request.Builder()
            .url("$baseUrl/oauth/token")
            .applyTraktHeaders()
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        val body = response.body.string()
        return try {
            json.decodeFromString<TraktOAuth>(body)
        } catch (_: Exception) {
            null
        }
    }

    fun getCurrentUser(): String? {
        val request = Request.Builder()
            .url("$baseUrl/users/me")
            .applyTraktHeaders(includeContentType = false)
            .get()
            .build()
        val response = authClient.newCall(request).execute()
        val body = response.body.string()
        return try {
            val parsed = json.parseToJsonElement(body).jsonObject
            parsed["username"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetch watched shows from the authenticated user's account.
     * Parses each item and returns a list of TraktLibraryItem with traktId and title.
     */
    fun getUserShows(): List<eu.kanade.tachiyomi.data.track.trakt.dto.TraktLibraryItem> {
        val request = Request.Builder()
            .url("$baseUrl/sync/watched/shows")
            .applyTraktHeaders(includeContentType = false)
            .get()
            .build()
        val response = authClient.newCall(request).execute()
        val body = response.body.string()
        return try {
            val root = json.parseToJsonElement(body).jsonArray
            root.mapNotNull { elem ->
                try {
                    val show = elem.jsonObject["show"]?.jsonObject ?: return@mapNotNull null
                    val ids = show["ids"]?.jsonObject
                    val traktId = ids?.get("trakt")?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                    val title = show["title"]?.jsonPrimitive?.contentOrNull
                    eu.kanade.tachiyomi.data.track.trakt.dto.TraktLibraryItem(
                        traktId = traktId,
                        title = title,
                        progress = 0,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Fetch watched movies from the authenticated user's account.
     */
    fun getUserMovies(): List<eu.kanade.tachiyomi.data.track.trakt.dto.TraktLibraryItem> {
        val request = Request.Builder()
            .url("$baseUrl/sync/watched/movies")
            .applyTraktHeaders(includeContentType = false)
            .get()
            .build()
        val response = authClient.newCall(request).execute()
        val body = response.body.string()
        return try {
            val root = json.parseToJsonElement(body).jsonArray
            root.mapNotNull { elem ->
                try {
                    val movie = elem.jsonObject["movie"]?.jsonObject ?: return@mapNotNull null
                    val ids = movie["ids"]?.jsonObject
                    val traktId = ids?.get("trakt")?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                    val title = movie["title"]?.jsonPrimitive?.contentOrNull
                    eu.kanade.tachiyomi.data.track.trakt.dto.TraktLibraryItem(
                        traktId = traktId,
                        title = title,
                        progress = 0,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractPosterUrl(posterEl: JsonElement?): String {
        val raw = when (posterEl) {
            null -> null
            is JsonObject -> posterEl["full"]?.jsonPrimitive?.contentOrNull
                ?: posterEl["medium"]?.jsonPrimitive?.contentOrNull
                ?: posterEl["thumb"]?.jsonPrimitive?.contentOrNull
                ?: posterEl.entries.firstOrNull()?.value?.let { extractPosterUrl(it) }
            is JsonArray -> posterEl.firstOrNull()?.jsonPrimitive?.contentOrNull
            else -> posterEl.jsonPrimitive.contentOrNull
        }?.trim().takeUnless { it.isNullOrBlank() } ?: return ""

        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> raw
            else -> "https://$raw"
        }
    }

    private fun getPosterUrl(traktId: Long, isMovie: Boolean): String {
        val path = if (isMovie) "movies" else "shows"
        val request = Request.Builder()
            .url("$baseUrl/$path/$traktId?extended=full,images")
            .applyTraktHeaders(includeContentType = false)
            .get()
            .build()
        return try {
            val response = publicClient.newCall(request).execute()
            val body = response.body.string()
            val obj = json.parseToJsonElement(body).jsonObject
            extractPosterUrl(obj["images"]?.jsonObject?.get("poster"))
        } catch (_: Exception) {
            ""
        }
    }

    fun getImportableList(): List<ImportableEntry> {
        val entries = mutableListOf<ImportableEntry>()

        // Watched shows
        try {
            val request = Request.Builder()
                .url("$baseUrl/sync/watched/shows?extended=full,images")
                .applyTraktHeaders(includeContentType = false)
                .get()
                .build()
            val response = authClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body.string()
                val root = json.parseToJsonElement(body).jsonArray
                root.forEach { elem ->
                    val show = elem.jsonObject["show"]?.jsonObject ?: return@forEach
                    val ids = show["ids"]?.jsonObject ?: return@forEach
                    val traktId = ids["trakt"]?.jsonPrimitive?.longOrNull ?: return@forEach
                    val slug = ids["slug"]?.jsonPrimitive?.contentOrNull ?: ""
                    val title = show["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    var coverUrl = extractPosterUrl(show["images"]?.jsonObject?.get("poster"))
                    if (coverUrl.isBlank()) {
                        coverUrl = getPosterUrl(traktId, isMovie = false)
                    }
                    val totalEpisodes = show["aired_episodes"]?.jsonPrimitive?.longOrNull ?: 0L
                    val seasons = elem.jsonObject["seasons"]?.jsonArray
                    var watchedEpisodes = 0
                    seasons?.forEach { s ->
                        val eps = s.jsonObject["episodes"]?.jsonArray
                        watchedEpisodes += eps?.size ?: 0
                    }
                    val isCompleted = totalEpisodes > 0 && watchedEpisodes >= totalEpisodes
                    val statusFilter = if (isCompleted) ImportStatusFilter.COMPLETED else ImportStatusFilter.WATCHING

                    entries.add(
                        ImportableEntry(
                            remoteId = traktId,
                            title = title,
                            coverUrl = coverUrl,
                            totalEpisodes = totalEpisodes,
                            episodesSeen = watchedEpisodes,
                            score = 0.0,
                            status = if (isCompleted) Trakt.COMPLETED else Trakt.WATCHING,
                            statusFilter = statusFilter,
                            startDate = 0L,
                            finishDate = 0L,
                            trackingUrl = "https://trakt.tv/shows/$slug",
                        ),
                    )
                }
            }
        } catch (_: Exception) {}

        // Watched movies
        try {
            val request = Request.Builder()
                .url("$baseUrl/sync/watched/movies?extended=full,images")
                .applyTraktHeaders(includeContentType = false)
                .get()
                .build()
            val response = authClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body.string()
                val root = json.parseToJsonElement(body).jsonArray
                root.forEach { elem ->
                    val movie = elem.jsonObject["movie"]?.jsonObject ?: return@forEach
                    val ids = movie["ids"]?.jsonObject ?: return@forEach
                    val traktId = ids["trakt"]?.jsonPrimitive?.longOrNull ?: return@forEach
                    val slug = ids["slug"]?.jsonPrimitive?.contentOrNull ?: ""
                    val title = movie["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    var coverUrl = extractPosterUrl(movie["images"]?.jsonObject?.get("poster"))
                    if (coverUrl.isBlank()) {
                        coverUrl = getPosterUrl(traktId, isMovie = true)
                    }

                    entries.add(
                        ImportableEntry(
                            remoteId = traktId,
                            title = title,
                            coverUrl = coverUrl,
                            totalEpisodes = 1L,
                            episodesSeen = 1,
                            score = 0.0,
                            status = Trakt.COMPLETED,
                            statusFilter = ImportStatusFilter.COMPLETED,
                            startDate = 0L,
                            finishDate = 0L,
                            trackingUrl = "https://trakt.tv/movies/$slug",
                        ),
                    )
                }
            }
        } catch (_: Exception) {}

        // Watchlist shows
        try {
            val request = Request.Builder()
                .url("$baseUrl/sync/watchlist/shows?extended=full,images")
                .applyTraktHeaders(includeContentType = false)
                .get()
                .build()
            val response = authClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body.string()
                val root = json.parseToJsonElement(body).jsonArray
                root.forEach { elem ->
                    val show = elem.jsonObject["show"]?.jsonObject ?: return@forEach
                    val ids = show["ids"]?.jsonObject ?: return@forEach
                    val traktId = ids["trakt"]?.jsonPrimitive?.longOrNull ?: return@forEach
                    if (entries.none { it.remoteId == traktId }) {
                        val slug = ids["slug"]?.jsonPrimitive?.contentOrNull ?: ""
                        val title = show["title"]?.jsonPrimitive?.contentOrNull ?: ""
                        var coverUrl = extractPosterUrl(show["images"]?.jsonObject?.get("poster"))
                        if (coverUrl.isBlank()) {
                            coverUrl = getPosterUrl(traktId, isMovie = false)
                        }
                        val totalEpisodes = show["aired_episodes"]?.jsonPrimitive?.longOrNull ?: 0L

                        entries.add(
                            ImportableEntry(
                                remoteId = traktId,
                                title = title,
                                coverUrl = coverUrl,
                                totalEpisodes = totalEpisodes,
                                episodesSeen = 0,
                                score = 0.0,
                                status = Trakt.PLAN_TO_WATCH,
                                statusFilter = ImportStatusFilter.PLAN_TO_WATCH,
                                startDate = 0L,
                                finishDate = 0L,
                                trackingUrl = "https://trakt.tv/shows/$slug",
                            ),
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // Watchlist movies
        try {
            val request = Request.Builder()
                .url("$baseUrl/sync/watchlist/movies?extended=full,images")
                .applyTraktHeaders(includeContentType = false)
                .get()
                .build()
            val response = authClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body.string()
                val root = json.parseToJsonElement(body).jsonArray
                root.forEach { elem ->
                    val movie = elem.jsonObject["movie"]?.jsonObject ?: return@forEach
                    val ids = movie["ids"]?.jsonObject ?: return@forEach
                    val traktId = ids["trakt"]?.jsonPrimitive?.longOrNull ?: return@forEach
                    if (entries.none { it.remoteId == traktId }) {
                        val slug = ids["slug"]?.jsonPrimitive?.contentOrNull ?: ""
                        val title = movie["title"]?.jsonPrimitive?.contentOrNull ?: ""
                        var coverUrl = extractPosterUrl(movie["images"]?.jsonObject?.get("poster"))
                        if (coverUrl.isBlank()) {
                            coverUrl = getPosterUrl(traktId, isMovie = true)
                        }

                        entries.add(
                            ImportableEntry(
                                remoteId = traktId,
                                title = title,
                                coverUrl = coverUrl,
                                totalEpisodes = 1L,
                                episodesSeen = 0,
                                score = 0.0,
                                status = Trakt.PLAN_TO_WATCH,
                                statusFilter = ImportStatusFilter.PLAN_TO_WATCH,
                                startDate = 0L,
                                finishDate = 0L,
                                trackingUrl = "https://trakt.tv/movies/$slug",
                            ),
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        return entries
    }

    /**
     * Remove a show's history entry for the authenticated user (best-effort).
     * Returns true if request succeeded.
     */
    fun removeShowHistory(traktId: Long): Boolean {
        val payload = """
            {
                "shows": [
                    {
                        "ids": { "trakt": $traktId }
                    }
                ]
            }
        """.trimIndent()
        val request = Request.Builder()
            .url("$baseUrl/sync/history/remove")
            .applyTraktHeaders()
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val response = authClient.newCall(request).execute()
        return response.isSuccessful
    }

    /**
     * Remove a movie's history entry for the authenticated user (best-effort).
     */
    fun removeMovieHistory(traktId: Long): Boolean {
        val payload = """
            {
                "movies": [
                    {
                        "ids": { "trakt": $traktId }
                    }
                ]
            }
        """.trimIndent()
        val request = Request.Builder()
            .url("$baseUrl/sync/history/remove")
            .applyTraktHeaders()
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val response = authClient.newCall(request).execute()
        return response.isSuccessful
    }

    /**
     * Return total episode count for a show by summing episodes across seasons.
     * Uses the seasons endpoint with embedded episodes. Uses a no-cookie client for public requests.
     */
    fun getShowEpisodeCount(traktId: Long): Long {
        val request = Request.Builder()
            .url("$baseUrl/shows/$traktId/seasons?extended=episodes")
            .applyTraktHeaders(includeContentType = false)
            .get()
            .build()
        val response = publicClient.newCall(request).execute()
        val body = response.body.string()
        return try {
            val root = json.parseToJsonElement(body).jsonArray
            var total = 0L
            root.forEach { seasonEl ->
                try {
                    val seasonObj = seasonEl.jsonObject
                    val seasonNum = seasonObj["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                    // Prefer explicit episode_count when available, fallback to embedded episodes array size.
                    val episodes = seasonObj["episodes"]?.jsonArray
                    val count = seasonObj["episode_count"]?.jsonPrimitive?.intOrNull ?: episodes?.size ?: 0
                    // Skip specials (season 0)
                    if (seasonNum == 0) {
                        return@forEach
                    }
                    if (count > 0) {
                        total += count.toLong()
                    }
                } catch (_: Exception) {
                }
            }
            total
        } catch (_: Exception) {
            0L
        }
    }

    fun getShowSeasons(traktId: Long): List<Pair<Int, Int>> {
        val request = Request.Builder()
            .url("$baseUrl/shows/$traktId/seasons?extended=episodes")
            .applyTraktHeaders(includeContentType = false)
            .get()
            .build()
        val response = publicClient.newCall(request).execute()
        val body = response.body.string()
        return try {
            val root = json.parseToJsonElement(body).jsonArray
            root.mapNotNull { seasonEl ->
                try {
                    val seasonObj = seasonEl.jsonObject
                    val seasonNum = seasonObj["number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                    if (seasonNum == 0) return@mapNotNull null // skip specials
                    val episodes = seasonObj["episodes"]?.jsonArray
                    val count = seasonObj["episode_count"]?.jsonPrimitive?.intOrNull ?: episodes?.size ?: 0
                    seasonNum to count
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getTmdbId(traktId: Long, mediaType: String): Long? {
        val path = if (mediaType == "movie") "movies" else "shows"
        val request = Request.Builder()
            .url("$baseUrl/$path/$traktId")
            .applyTraktHeaders(includeContentType = false)
            .get()
            .build()
        return try {
            val response = publicClient.newCall(request).execute()
            val body = response.body.string()
            val obj = json.parseToJsonElement(body).jsonObject
            val ids = obj["ids"]?.jsonObject
            ids?.get("tmdb")?.jsonPrimitive?.longOrNull
        } catch (_: Exception) {
            null
        }
    }
}
