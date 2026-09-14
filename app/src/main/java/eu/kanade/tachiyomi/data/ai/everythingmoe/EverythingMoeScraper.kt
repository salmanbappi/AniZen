package eu.kanade.tachiyomi.data.ai.everythingmoe

import android.content.Context
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import logcat.LogPriority
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class EverythingMoeScraper(
    private val context: Context,
    private val networkHelper: NetworkHelper = Injekt.get(),
    private val json: Json = Injekt.get(),
) {
    private val cacheMutex = Mutex()
    private val siteMemoryCache = ConcurrentHashMap<String, EverythingMoeSite>()
    private val slugLookup = ConcurrentHashMap<String, String>() // normalized slug -> exact case slug
    private var lastCacheFetch: Long = 0L

    private val cacheFile: File by lazy {
        File(context.cacheDir, "everythingmoe_cache.json")
    }

    companion object {
        private const val BASE_URL = "https://everythingmoe.com"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    init {
        loadDiskCache()
    }

    private fun loadDiskCache() {
        try {
            if (cacheFile.exists()) {
                val content = cacheFile.readText()
                val cached = json.decodeFromString(EverythingMoeCache.serializer(), content)
                if (cached.sites.isNotEmpty()) {
                    cached.sites.filter { it.tags.isNotEmpty() || it.url.isNotBlank() }.forEach { site ->
                        val lowerSlug = site.slug.lowercase()
                        siteMemoryCache[lowerSlug] = site
                        slugLookup[lowerSlug] = site.slug
                    }
                    lastCacheFetch = cached.lastFetchedTimestamp
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Failed to load EverythingMoe disk cache: ${e.message}" }
        }
    }

    private fun saveDiskCache() {
        try {
            val cacheObj = EverythingMoeCache(
                lastFetchedTimestamp = lastCacheFetch,
                sites = siteMemoryCache.values.filter { it.tags.isNotEmpty() || it.url.isNotBlank() }.toList(),
            )
            cacheFile.writeText(json.encodeToString(EverythingMoeCache.serializer(), cacheObj))
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Failed to save EverythingMoe disk cache: ${e.message}" }
        }
    }

    private fun extractDomain(url: String): String {
        return try {
            val clean = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }
            val uri = URI(clean)
            val host = uri.host ?: ""
            host.removePrefix("www.").lowercase().trim()
        } catch (e: Exception) {
            url.substringAfter("://").substringBefore("/").removePrefix("www.").lowercase().trim()
        }
    }

    private fun unpackAlts(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val out = mutableListOf<String>()
        val parts = value.split("#")
        for (rawPart in parts) {
            var part = rawPart.trim()
            if (part.isEmpty()) continue
            if (part.contains("<<")) {
                part = part.substringAfter("<<").trim()
            }
            if (part.startsWith("http://") || part.startsWith("https://")) {
                out.add(part)
            }
        }
        return out
    }

    private fun unescapeHtml(text: String): String {
        var res = text
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
        val decimalRegex = """&#(\d+);""".toRegex()
        res = decimalRegex.replace(res) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null) code.toChar().toString() else m.value
        }
        return res.trim()
    }

    private fun extractJsonBlob(text: String, startIdx: Int): String? {
        val idx = text.indexOf('{', startIdx)
        if (idx == -1) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in idx until text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                if (inString) escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return text.substring(idx, i + 1)
                    }
                }
            }
        }
        return null
    }

    suspend fun getSiteBySlug(rawSlug: String): EverythingMoeSite? = withIOContext {
        val lowerSlug = rawSlug.lowercase()
        val exactSlug = slugLookup[lowerSlug] ?: rawSlug

        val cached = siteMemoryCache[lowerSlug]
        if (cached != null && (cached.tags.isNotEmpty() || cached.url.isNotBlank())) {
            return@withIOContext cached
        }

        try {
            val request = Request.Builder()
                .url("$BASE_URL/s/$exactSlug")
                .header("User-Agent", USER_AGENT)
                .build()

            val timedClient = networkHelper.client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

            timedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withIOContext null
                val html = response.body.string()
                val site = parseSitePage(exactSlug, html)
                if (site != null) {
                    siteMemoryCache[lowerSlug] = site
                    slugLookup[lowerSlug] = site.slug
                    saveDiskCache()
                    return@withIOContext site
                }
                null
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) { "Failed to fetch EverythingMoe site page for $rawSlug: ${e.message}" }
            null
        }
    }

    private fun parseSitePage(slug: String, html: String): EverythingMoeSite? {
        val marker = "var siteData = "
        val idx = html.indexOf(marker)
        if (idx == -1) return null
        val start = idx + marker.length
        val rawJsonCandidate = extractJsonBlob(html, start) ?: return null

        val jsonObject: JsonObject = try {
            json.parseToJsonElement(rawJsonCandidate).jsonObject
        } catch (e: Exception) {
            return null
        }

        val title = jsonObject["title"]?.jsonPrimitive?.content ?: slug
        val link = jsonObject["link"]?.jsonPrimitive?.content ?: ""
        val icon = jsonObject["icon"]?.jsonPrimitive?.content ?: ""
        val filterRaw = jsonObject["filter"]?.jsonPrimitive?.content ?: ""
        val tags = filterRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val expandObj = jsonObject["expand"]?.let { if (it is JsonObject) it else null }
        val expandAltlink = expandObj?.get("altlink")?.jsonPrimitive?.content
        val expandPos = expandObj?.get("positive")?.jsonPrimitive?.content
        val expandNeg = expandObj?.get("negative")?.jsonPrimitive?.content
        val expandInfo = expandObj?.get("info")?.jsonPrimitive?.content

        val pros = expandPos?.split("#")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val cons = expandNeg?.split("#")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val info = expandInfo?.trim()?.ifBlank { null }

        val altLink = jsonObject["ex-altlink"]?.jsonPrimitive?.content
        val altLink2 = jsonObject["ex-altlink2"]?.jsonPrimitive?.content
        val extraLink = jsonObject["extra-link"]?.jsonPrimitive?.content

        val allMirrors = (unpackAlts(expandAltlink) + unpackAlts(altLink2) + unpackAlts(altLink)).distinct()
        val extraLinks = unpackAlts(extraLink).distinct()

        val rank = jsonObject["rank"]?.jsonPrimitive?.content ?: ""
        val category = jsonObject["type"]?.jsonPrimitive?.content ?: ""

        val deadPrimitive = jsonObject["DEAD"]?.jsonPrimitive
        val isDead = deadPrimitive != null && deadPrimitive.content.isNotBlank() && deadPrimitive.content != "false"
        val deadReason = if (isDead) deadPrimitive?.content else null

        val reviewsList = mutableListOf<EverythingMoeReview>()
        val rawReviews = jsonObject["reviews"]?.let {
            if (it is JsonArray) it else null
        }
        if (rawReviews != null) {
            for (element in rawReviews) {
                if (element is JsonObject) {
                    val rName = element["name"]?.jsonPrimitive?.content
                    val rRawReview = element["review"]?.jsonPrimitive?.content
                    val rReview = rRawReview?.let { unescapeHtml(it) }
                    val rTime = element["time"]?.jsonPrimitive?.longOrNull
                    val rVote = element["vote"]?.jsonPrimitive?.intOrNull
                    val rType = element["type"]?.jsonPrimitive?.content
                    reviewsList.add(
                        EverythingMoeReview(
                            name = rName,
                            review = rReview,
                            time = rTime,
                            vote = rVote,
                            type = rType,
                        ),
                    )
                }
            }
        }

        val metaDescRegex = """<meta\s+name="description"\s+content="([^"]+)"""".toRegex(RegexOption.IGNORE_CASE)
        val description = metaDescRegex.find(html)?.groupValues?.getOrNull(1)?.trim()

        return EverythingMoeSite(
            slug = slug,
            name = title,
            url = link,
            icon = icon,
            tags = tags,
            mirrors = allMirrors,
            extraLinks = extraLinks,
            pros = pros,
            cons = cons,
            info = info,
            rank = rank,
            category = category,
            description = description,
            isDead = isDead,
            deadReason = deadReason,
            reviewCount = reviewsList.size,
            reviewVoteSum = reviewsList.sumOf { it.vote ?: 0 },
            reviews = reviewsList,
        )
    }

    suspend fun refreshDirectoryIfNeeded(): Unit = withIOContext {
        val now = System.currentTimeMillis()
        if (slugLookup.isNotEmpty() && (now - lastCacheFetch < CACHE_TTL_MS)) {
            return@withIOContext
        }

        cacheMutex.withLock {
            if (slugLookup.isNotEmpty() && (now - lastCacheFetch < CACHE_TTL_MS)) {
                return@withLock
            }

            try {
                val timedClient = networkHelper.client.newBuilder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()

                // 1. Fetch Master Database Cache (/data/cache/main.json) - 912 sites + section arrays instantly
                val mainCacheReq = Request.Builder()
                    .url("$BASE_URL/data/cache/main.json")
                    .header("User-Agent", USER_AGENT)
                    .build()

                timedClient.newCall(mainCacheReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val mainJsonStr = response.body.string()
                        val rootObj = json.parseToJsonElement(mainJsonStr).jsonObject
                        for ((slug, el) in rootObj) {
                            if (el is JsonObject) {
                                val lowerSlug = slug.lowercase()
                                slugLookup[lowerSlug] = slug

                                val altName = el["altname"]?.jsonPrimitive?.content
                                val title = altName ?: slug.replace("-", " ").replaceFirstChar { it.uppercase() }

                                val posRaw = (el["positive"] ?: el["ex-positive"])?.jsonPrimitive?.content
                                val negRaw = (el["negative"] ?: el["ex-negative"])?.jsonPrimitive?.content
                                val infoRaw = (el["info"] ?: el["ex-info"] ?: el["note"])?.jsonPrimitive?.content

                                val pros = posRaw?.split("#")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                                val cons = negRaw?.split("#")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                                val info = infoRaw?.trim()?.ifBlank { null }

                                val altLink = el["altlink"]?.jsonPrimitive?.content
                                val altLinkEx = el["ex-altlink"]?.jsonPrimitive?.content
                                val altLinkEx2 = el["ex-altlink2"]?.jsonPrimitive?.content
                                val extraLink = (el["extra-link"] ?: el["extralink"])?.jsonPrimitive?.content

                                val mirrors = (unpackAlts(altLink) + unpackAlts(altLinkEx) + unpackAlts(altLinkEx2)).distinct()
                                val extraLinks = unpackAlts(extraLink).distinct()

                                val existing = siteMemoryCache[lowerSlug]
                                siteMemoryCache[lowerSlug] = existing?.copy(
                                    pros = if (existing.pros.isNotEmpty()) existing.pros else pros,
                                    cons = if (existing.cons.isNotEmpty()) existing.cons else cons,
                                    info = existing.info ?: info,
                                    mirrors = if (existing.mirrors.isNotEmpty()) existing.mirrors else mirrors,
                                    extraLinks = if (existing.extraLinks.isNotEmpty()) existing.extraLinks else extraLinks,
                                ) ?: EverythingMoeSite(
                                    slug = slug,
                                    name = title,
                                    mirrors = mirrors,
                                    extraLinks = extraLinks,
                                    pros = pros,
                                    cons = cons,
                                    info = info,
                                )
                            } else if (slug.startsWith("section") && el is JsonArray) {
                                for (item in el) {
                                    if (item !is JsonObject) continue
                                    val sid = item["id"]?.jsonPrimitive?.content ?: item["slug"]?.jsonPrimitive?.content ?: continue
                                    val link = item["link"]?.jsonPrimitive?.content ?: ""
                                    val title = item["title"]?.jsonPrimitive?.content ?: ""
                                    val icon = item["icon"]?.jsonPrimitive?.content ?: ""
                                    val lowerSlug = sid.lowercase()
                                    val existing = siteMemoryCache[lowerSlug]
                                    if (existing != null) {
                                        siteMemoryCache[lowerSlug] = existing.copy(
                                            url = existing.url.ifBlank { link },
                                            name = if (title.isNotBlank()) title else existing.name,
                                            icon = existing.icon.ifBlank { icon },
                                        )
                                    } else {
                                        siteMemoryCache[lowerSlug] = EverythingMoeSite(
                                            slug = sid,
                                            name = title.ifBlank { sid },
                                            url = link,
                                            icon = icon,
                                        )
                                    }
                                    slugLookup[lowerSlug] = sid
                                }
                            }
                        }
                    }
                }

                // 2. Fetch Directory HTML for full list of active slugs, data-link URLs, and display titles
                val dirReq = Request.Builder()
                    .url(BASE_URL)
                    .header("User-Agent", USER_AGENT)
                    .build()

                timedClient.newCall(dirReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val html = response.body.string()
                        val cardRegex = """<a[^>]+href="/s/([a-zA-Z0-9_-]+)"[^>]*data-link="([^"]+)"[^>]*>(.*?)</a>""".toRegex(RegexOption.DOT_MATCHES_ALL)
                        for (match in cardRegex.findAll(html)) {
                            val exactSlug = match.groupValues[1]
                            val directUrl = match.groupValues[2].trim()
                            val inner = match.groupValues[3]
                            val title = inner.replace("""<[^>]+>""".toRegex(), " ").replace("""\s+""".toRegex(), " ").trim()
                            val lowerSlug = exactSlug.lowercase()
                            slugLookup[lowerSlug] = exactSlug

                            val existing = siteMemoryCache[lowerSlug]
                            if (existing != null) {
                                siteMemoryCache[lowerSlug] = existing.copy(
                                    url = directUrl.ifBlank { existing.url },
                                    name = if (title.isNotBlank()) title else existing.name,
                                )
                            } else {
                                siteMemoryCache[lowerSlug] = EverythingMoeSite(
                                    slug = exactSlug,
                                    name = title.ifBlank { exactSlug },
                                    url = directUrl,
                                )
                            }
                        }

                        val slugRegex = """href="/s/([a-zA-Z0-9_-]+)"""".toRegex()
                        val slugs = slugRegex.findAll(html).map { it.groupValues[1] }.distinct().toList()
                        for (exactSlug in slugs) {
                            val lowerSlug = exactSlug.lowercase()
                            slugLookup[lowerSlug] = exactSlug
                        }
                    }
                }

                lastCacheFetch = now
                saveDiskCache()
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Failed to refresh EverythingMoe directory: ${e.message}" }
            }
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("""\b(en|dub|sub|hd|all|app|tv|to|is|me|org|com|net|co)\b""", RegexOption.IGNORE_CASE), "")
            .replace("[^a-zA-Z0-9]".toRegex(), "")
            .lowercase()
            .trim()
    }

    suspend fun matchSource(sourceName: String, baseUrl: String?): EverythingMoeSite? {
        return matchExtensionOrSource(null, sourceName, baseUrl, null)
    }

    suspend fun matchExtensionOrSource(
        extensionTitle: String?,
        sourceName: String,
        baseUrl: String?,
        pkgName: String? = null,
    ): EverythingMoeSite? = withIOContext {
        refreshDirectoryIfNeeded()

        val sourceDomain = baseUrl?.let { extractDomain(it) }?.ifBlank { null }

        // Tier 1: Direct Domain & Mirror Match
        if (sourceDomain != null) {
            for (site in siteMemoryCache.values) {
                if (site.url.isNotBlank() && extractDomain(site.url) == sourceDomain) {
                    return@withIOContext site
                }
                if (site.mirrors.any { extractDomain(it) == sourceDomain }) {
                    return@withIOContext site
                }
            }
        }

        // Tier 2: Candidate Slugs from Extension Title, Source Name, and Package Name
        val slugCandidates = mutableListOf<String>()
        if (!extensionTitle.isNullOrBlank()) {
            slugCandidates.add(extensionTitle.lowercase().replace("[^a-z0-9]".toRegex(), ""))
            slugCandidates.add(extensionTitle.lowercase().replace(" ", "-").replace("[^a-z0-9-]".toRegex(), ""))
            slugCandidates.add(cleanTitle(extensionTitle))
        }
        if (sourceName.isNotBlank()) {
            slugCandidates.add(sourceName.lowercase().replace("[^a-z0-9]".toRegex(), ""))
            slugCandidates.add(sourceName.lowercase().replace(" ", "-").replace("[^a-z0-9-]".toRegex(), ""))
            slugCandidates.add(cleanTitle(sourceName))
        }
        if (!pkgName.isNullOrBlank()) {
            slugCandidates.add(pkgName.substringAfterLast(".").lowercase().replace("[^a-z0-9]".toRegex(), ""))
        }

        for (cand in slugCandidates.distinct().filter { it.isNotBlank() }) {
            if (slugLookup.containsKey(cand)) {
                val site = getSiteBySlug(cand)
                if (site != null && (site.tags.isNotEmpty() || site.url.isNotBlank())) {
                    return@withIOContext site
                }
            }
        }

        // Tier 3: Normalized Title and Source Name Matching against Cached Sites
        val normalizedCandidates = listOfNotNull(extensionTitle, sourceName).map { cleanTitle(it) }.filter { it.length >= 3 }
        for (site in siteMemoryCache.values) {
            val siteClean = cleanTitle(site.name)
            val slugClean = cleanTitle(site.slug)
            for (cand in normalizedCandidates) {
                if (siteClean == cand || slugClean == cand) {
                    return@withIOContext site
                }
                if (cand.length >= 4 && (siteClean.contains(cand) || cand.contains(siteClean))) {
                    return@withIOContext site
                }
            }
        }

        // Tier 4: Fallback Substring Search in Directory Slugs
        for (cand in normalizedCandidates.filter { it.length >= 4 }) {
            for ((lowerSlug, _) in slugLookup) {
                if (lowerSlug.contains(cand) || cand.contains(lowerSlug)) {
                    val site = getSiteBySlug(lowerSlug)
                    if (site != null && (site.tags.isNotEmpty() || site.url.isNotBlank())) {
                        return@withIOContext site
                    }
                }
            }
        }

        null
    }

    suspend fun getIntelligenceContext(
        query: String,
        installedExtensions: List<Extension.Installed>,
    ): String = withIOContext {
        refreshDirectoryIfNeeded()
        val sb = StringBuilder()

        // 1. Installed extensions matching with Title + Source + BaseUrl + PkgName fallbacks
        val installedMatched = mutableListOf<Triple<String?, String, EverythingMoeSite>>()
        for (ext in installedExtensions) {
            var matchedForExt = false
            for (source in ext.sources) {
                val baseUrl = (source as? AnimeHttpSource)?.baseUrl
                val site = matchExtensionOrSource(ext.name, source.name, baseUrl, ext.pkgName)
                if (site != null && (site.tags.isNotEmpty() || site.url.isNotBlank())) {
                    matchedForExt = true
                    installedMatched.add(Triple(ext.name, baseUrl ?: site.url, site))
                }
            }
            // Fallback: If sources didn't match, attempt matching directly on the extension title
            if (!matchedForExt) {
                val site = matchExtensionOrSource(ext.name, ext.name, null, ext.pkgName)
                if (site != null && (site.tags.isNotEmpty() || site.url.isNotBlank())) {
                    installedMatched.add(Triple(ext.name, site.url, site))
                }
            }
        }

        if (installedMatched.isNotEmpty()) {
            sb.append("### INSTALLED EXTENSIONS COMMUNITY INTELLIGENCE (From EverythingMoe):\n")
            for ((extName, baseUrl, site) in installedMatched) {
                formatSiteEntry(sb, extName, site.name, baseUrl, site)
            }
        }

        // 2. Queried/Mentioned Sources from Directory (Not installed)
        val queryWords = query.lowercase().split("[^a-zA-Z0-9_-]".toRegex()).filter { it.length >= 3 }
        val mentionedSites = mutableListOf<EverythingMoeSite>()
        for (word in queryWords) {
            if (word in listOf("the", "and", "for", "with", "from", "anime", "extension", "extensions", "source", "sources", "stream", "working", "check", "what", "which", "down", "dead", "mirror", "mirrors", "link", "links")) continue
            val site = matchExtensionOrSource(word, word, null, null)
            if (site != null && (site.tags.isNotEmpty() || site.url.isNotBlank()) && mentionedSites.none { it.slug.equals(site.slug, ignoreCase = true) }) {
                val alreadyInstalled = installedMatched.any { it.third.slug.equals(site.slug, ignoreCase = true) }
                if (!alreadyInstalled) {
                    mentionedSites.add(site)
                }
            }
        }

        if (mentionedSites.isNotEmpty()) {
            sb.append("\n### COMMUNITY DIRECTORY INTELLIGENCE (Queried / Mentioned Sites - Not Installed):\n")
            for (site in mentionedSites) {
                formatSiteEntry(sb, null, site.name, site.url, site)
            }
        }

        sb.toString().trim()
    }

    private fun formatSiteEntry(
        sb: StringBuilder,
        extName: String?,
        sourceName: String,
        baseUrl: String?,
        site: EverythingMoeSite,
    ) {
        val statusStr = if (site.isDead) "🔴 DEAD (${site.deadReason ?: "Discontinued"})" else "🟢 ALIVE"
        val rankStr = if (site.rank.isNotBlank()) "Rank: ${site.rank}" else "Unranked"
        val tagsStr = if (site.tags.isNotEmpty()) site.tags.joinToString(", ") else "None listed"
        val ratingStr = if (site.reviewCount > 0) "+${site.reviewVoteSum} (${site.reviewCount} reviews)" else "No reviews"

        val totalReviews = site.reviews.size
        val sentimentStr = if (totalReviews > 0) {
            val pos = site.reviews.count { it.type == "1" || (it.vote ?: 0) > 0 }
            val mixed = site.reviews.count { it.type == "0" || (it.vote ?: 0) == 0 }
            val neg = site.reviews.count { it.type == "-1" || (it.vote ?: 0) < 0 }
            val posPct = (pos * 100) / totalReviews
            val mixedPct = (mixed * 100) / totalReviews
            val negPct = (neg * 100) / totalReviews
            " | **Sentiment**: 🟢 $posPct% Pos ($pos) / 🟡 $mixedPct% Mixed ($mixed) / 🔴 $negPct% Neg ($neg)"
        } else {
            ""
        }

        val titlePrefix = if (extName != null && extName != sourceName) "$extName ($sourceName)" else sourceName
        sb.append("- **$titlePrefix** (`${baseUrl ?: site.url}`):\n")
        sb.append("  * **Status**: $statusStr | **$rankStr** | **Community Rating**: $ratingStr$sentimentStr\n")
        sb.append("  * **Supported Features/Tags**: $tagsStr\n")

        if (site.mirrors.isNotEmpty()) {
            val mirrorsDisplay = site.mirrors.joinToString(", ")
            sb.append("  * **Active Mirrors**: $mirrorsDisplay\n")
        }

        if (site.pros.isNotEmpty()) {
            sb.append("  * **Pros**: ${site.pros.joinToString(" • ")}\n")
        }

        if (site.cons.isNotEmpty()) {
            sb.append("  * **Cons**: ${site.cons.joinToString(" • ")}\n")
        }

        if (!site.info.isNullOrBlank()) {
            sb.append("  * **Community Note**: ${site.info}\n")
        }

        val validReviews = site.reviews.filter { !it.review.isNullOrBlank() && (it.review?.length ?: 0) >= 20 }
        if (validReviews.isNotEmpty()) {
            val topVoted = validReviews.sortedByDescending { it.vote ?: 0 }.take(3)
            val mostRecent = validReviews.filterNot { it in topVoted }.sortedByDescending { it.time ?: 0L }.take(2)

            if (topVoted.isNotEmpty()) {
                sb.append("  * **Top Community Feedback (Helpful)**:\n")
                topVoted.forEach { r ->
                    val clean = (r.review ?: "").replace("\n", " ").take(160)
                    val author = r.name ?: "User"
                    sb.append("    - [$author] (+${r.vote ?: 0} votes): \"$clean\"\n")
                }
            }

            if (mostRecent.isNotEmpty()) {
                sb.append("  * **Recent Status Signals (Live Health)**:\n")
                mostRecent.forEach { r ->
                    val clean = (r.review ?: "").replace("\n", " ").take(160)
                    val author = r.name ?: "User"
                    val dateStr = if (r.time != null && r.time > 0) {
                        try {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                            sdf.format(java.util.Date(r.time * 1000L))
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    }

                    val timeTag = if (dateStr != null) " ($dateStr)" else ""
                    sb.append("    - [$author]$timeTag: \"$clean\"\n")
                }
            }
        }
    }
}
