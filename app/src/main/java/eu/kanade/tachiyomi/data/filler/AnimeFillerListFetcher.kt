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

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val NON_ALPHANUMERIC_WHITESPACE = Regex("""[^a-z0-9\s]""")
        private val WHITESPACE = Regex("""\s+""")
    }

    suspend fun getFillerEpisodes(animeTitle: String): Set<Float> = withIOContext {
        val titleClean = animeTitle.trim()
        if (titleClean.isBlank()) return@withIOContext emptySet()
        if (cache.containsKey(titleClean)) {
            return@withIOContext cache[titleClean] ?: emptySet()
        }

        try {
            var showUrl: String? = null

            // Clean title by removing non-alphanumeric characters (except spaces)
            val alphanumericTitle = titleClean.lowercase().replace(NON_ALPHANUMERIC_WHITESPACE, "").trim()

            // 1. Try Direct URL first
            val slug = alphanumericTitle.replace(WHITESPACE, "-")
            val directUrl = "$baseUrl/shows/$slug"
            val directRequest = Request.Builder().url(directUrl).header("User-Agent", USER_AGENT).build()
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
                .header("User-Agent", USER_AGENT)
                .build()

            val fillerEpisodes = mutableSetOf<Float>()
            networkHelper.client.newCall(showRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Unable to extract metadata of series and episode")
                }
                val doc = Jsoup.parse(response.body.byteStream(), "UTF-8", baseUrl)

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
        val searchUrl = "$baseUrl/search/node/${query.replace(WHITESPACE, "%20")}"
        val searchRequest = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        return networkHelper.client.newCall(searchRequest).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val doc = Jsoup.parse(response.body.byteStream(), "UTF-8", baseUrl)
            // Look for actual search results first
            val firstResult = doc.selectFirst("li.search-result h3.title a")
            firstResult?.attr("href")
        }
    }
}
