package eu.kanade.tachiyomi.data.filler

import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.Request
import org.jsoup.Jsoup
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeFillerListFetcher(
    private val networkHelper: NetworkHelper = Injekt.get(),
) {
    private val baseUrl = "https://www.animefillerlist.com"
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Set<Float>>()

    suspend fun getFillerEpisodes(animeTitle: String): Set<Float> = withIOContext {
        val titleClean = animeTitle.trim()
        if (titleClean.isBlank()) return@withIOContext emptySet()
        if (cache.containsKey(titleClean)) {
            return@withIOContext cache[titleClean] ?: emptySet()
        }

        try {
            var showUrl: String? = null

            // Clean title by removing non-alphanumeric characters (except spaces)
            val alphanumericTitle = titleClean.lowercase().replace(Regex("[^a-z0-9\\s]"), "").trim()

            // 1. Try Direct URL first
            val slug = alphanumericTitle.replace(Regex("\\s+"), "-")
            val directUrl = "$baseUrl/shows/$slug"
            val directRequest = Request.Builder().url(directUrl).header("User-Agent", "Mozilla/5.0").build()
            networkHelper.client.newCall(directRequest).execute().use { response ->
                if (response.isSuccessful) {
                    showUrl = directUrl
                }
            }

            // 2. Fallback to Search
            if (showUrl == null) {
                showUrl = performSearch(alphanumericTitle)
            }

            // 3. Fallback to Normalized Search (e.g., Shippuuden -> Shippuden)
            if (showUrl == null) {
                val normalizedTitle = alphanumericTitle
                    .replace("ou", "o")
                    .replace("uu", "u")
                    .replace("oo", "o")
                    .replace("aa", "a")
                    .replace("ii", "i")
                if (normalizedTitle != alphanumericTitle) {
                    showUrl = performSearch(normalizedTitle)
                }
            }

            if (showUrl.isNullOrBlank()) {
                throw Exception("Unable to extract metadata of series and episode")
            }

            // 3. Fetch episodes from the show page
            val showRequest = Request.Builder()
                .url(showUrl!!)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val fillerEpisodes = mutableSetOf<Float>()
            networkHelper.client.newCall(showRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Unable to extract metadata of series and episode")
                }
                val body = response.body.string()
                val doc = Jsoup.parse(body)

                val rows = doc.select("tr.filler, tr.mixed")
                for (row in rows) {
                    val numElement = row.selectFirst("td.Number")
                    val epNumText = numElement?.text()?.trim()
                    val epNum = epNumText?.toFloatOrNull()
                    if (epNum != null) {
                        fillerEpisodes.add(epNum)
                    }
                }
            }

            cache[titleClean] = fillerEpisodes
            return@withIOContext fillerEpisodes
        } catch (e: Exception) {
            cache[titleClean] = emptySet() // Cache empty so we don't spam network
            throw Exception("Unable to extract metadata of series and episode", e)
        }
    }

    private fun performSearch(query: String): String? {
        val searchUrl = "$baseUrl/search/node/${query.replace(Regex("\\s+"), "%20")}"
        val searchRequest = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        return networkHelper.client.newCall(searchRequest).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body.string()
            val doc = Jsoup.parse(body)
            // Look for actual search results first
            val firstResult = doc.selectFirst("li.search-result h3.title a")
            firstResult?.attr("href")
        }
    }
}
